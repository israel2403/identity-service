// Report Jenkins stage progress in GitHub; build execution stays in Jenkins.
module.exports = async function run({
  core, mode, env = process.env, fetch = global.fetch,
  sleep = ms => new Promise(resolve => setTimeout(resolve, ms)),
  now = Date.now,
}) {
  const sha = env.BUILD_SHA;
  if (!/^[0-9a-f]{40}$/.test(sha || '')) throw new Error('Invalid commit SHA');
  if (!env.JENKINS_USER || !env.JENKINS_API_TOKEN) throw new Error('Missing Jenkins credentials');
  const base = env.JENKINS_URL;
  const authorization = 'Basic ' + Buffer.from(`${env.JENKINS_USER}:${env.JENKINS_API_TOKEN}`).toString('base64');
  const deadline = now() + 95 * 60 * 1000;
  const path = '/job/identity-service-ci/';
  const request = async (value, options = {}, allowNotFound = false) => {
    // Jenkins returns browser/loopback URLs. Authenticate only to our private origin.
    const parsed = new URL(value, base);
    const response = await fetch(base + parsed.pathname + parsed.search, {
      ...options, redirect: 'manual',
      headers: { ...options.headers, Authorization: authorization },
      signal: AbortSignal.timeout(30000),
    });
    if (!response.ok && !(allowNotFound && response.status === 404)) throw new Error(`Jenkins HTTP ${response.status} for ${parsed.pathname}`);
    return response;
  };
  const json = async value => (await request(value)).json();
  const pause = async () => {
    if (now() > deadline) throw new Error('Timed out waiting for Jenkins');
    await sleep(5000);
  };
  if (mode === 'start') {
    const response = await request(path + 'buildWithParameters', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ GIT_COMMIT: sha }).toString(),
    });
    const queue = response.headers.get('location');
    if (!queue) throw new Error('Jenkins did not return a queue location');
    while (true) {
      await pause();
      const item = await json(queue.replace(/\/$/, '') + '/api/json');
      if (item.cancelled) throw new Error('Jenkins queue item was cancelled');
      if (item.executable) {
        const number = String(item.executable.number);
        if (!/^\d+$/.test(number)) throw new Error('Invalid Jenkins build number');
        core.setOutput('build_number', number);
        core.info(`Jenkins build #${number}; requested commit ${sha}`);
        await core.summary.addHeading('Identity service CI')
          .addRaw(`Jenkins build: #${number}\n\nCommit: ${sha}\n\n`)
          .addRaw('Each following GitHub job reports one real Jenkins stage. Jenkins executes the pipeline.\n')
          .write();
        return;
      }
    }
  }
  if (mode !== 'watch') throw new Error('Unknown Jenkins operation');
  const number = env.JENKINS_BUILD_NUMBER;
  if (!/^\d+$/.test(number || '')) throw new Error('Invalid Jenkins build number');
  const buildPath = path + number;
  const browser = new URL(buildPath + '/', env.JENKINS_BROWSER_URL || base).href;
  const states = new Map();
  while (true) {
    const build = await json(buildPath + '/api/json');
    const submitted = (build.actions || []).flatMap(action => action.parameters || [])
      .find(parameter => parameter.name === 'GIT_COMMIT')?.value;
    if (submitted !== sha) throw new Error('Jenkins build parameter does not match the GitHub commit');
    const pipeline = await json(buildPath + '/wfapi/describe');
    for (const stage of pipeline.stages || []) {
      if (states.get(stage.id) !== stage.status) {
        core.info(`${stage.name}: ${stage.status} (${((stage.durationMillis || 0) / 1000).toFixed(1)}s)`);
        states.set(stage.id, stage.status);
      }
    }
    if (!build.building && build.result) {
      await core.summary.addHeading('Identity service CI results')
        .addRaw(`Jenkins build: **#${number}** · Result: **${build.result}**\n\nCommit: ${sha}\n\n`)
        .addLink('Open this Jenkins build', browser).addRaw(' · ')
        .addLink('Open Jenkins Stage View', new URL(path, env.JENKINS_BROWSER_URL || base).href)
        .addRaw('\n\nThe Jenkins links use your configured local access/SSH tunnel.\n\n')
        .addTable([
          [{ data: 'CI stage', header: true }, { data: 'Result', header: true }, { data: 'Duration (s)', header: true }],
          ...(pipeline.stages || []).map(stage => [stage.name, stage.status, ((stage.durationMillis || 0) / 1000).toFixed(1)]),
        ]);
      // Early failures can finish before a test report exists. Keep the stage table.
      const testsResponse = await request(buildPath + '/testReport/api/json', {}, true);
      if (testsResponse.status === 404) {
        core.summary.addRaw('\nNo test report was produced before this build ended.\n');
      } else {
        const tests = await testsResponse.json();
        core.summary.addRaw(`\nTests: **${tests.passCount} passed**, ${tests.failCount} failed, ${tests.skipCount} skipped.\n`);
      }
      core.summary.addRaw('\nTest details, coverage, security reports and image digest are available in Jenkins under **Test Result** and **Artifacts**.\n');
      await core.summary.write();
      if (build.result !== 'SUCCESS') throw new Error(`Jenkins final result: ${build.result}`);
      return;
    }
    await pause();
  }
};
