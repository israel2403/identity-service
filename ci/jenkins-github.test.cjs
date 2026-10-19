const test = require('node:test');
const assert = require('node:assert/strict');
const run = require('./jenkins-github.cjs');

const env = {
  BUILD_SHA: 'a'.repeat(40), JENKINS_URL: 'http://jenkins-controller:8080',
  JENKINS_USER: 'test-user', JENKINS_API_TOKEN: 'test-value', JENKINS_BUILD_NUMBER: '42',
  JENKINS_BROWSER_URL: 'http://127.0.0.1:8080',
};
function harness() {
  const outputs = {}, logs = [], summary = [];
  const core = {
    setOutput: (key, value) => { outputs[key] = value; },
    info: value => logs.push(value),
    summary: Object.fromEntries(['addHeading', 'addRaw', 'addLink', 'addTable', 'write']
      .map(method => [method, function (...args) { summary.push({ method, args }); return this; }])),
  };
  return { core, outputs, logs, summary };
}
function response(data, status = 200, location) {
  return { ok: status >= 200 && status < 300, status,
    headers: { get: () => location }, json: async () => data };
}

test('submits exact SHA and polls only the private origin for a browser queue URL', async () => {
  const h = harness(), calls = [];
  await run({ core: h.core, env, mode: 'start', sleep: async () => {}, fetch: async (url, options) => {
    calls.push({ url, options });
    return url.includes('buildWithParameters')
      ? response(null, 201, 'http://127.0.0.1:8080/queue/item/9/')
      : response({ executable: { number: 42 } });
  } });
  assert.equal(h.outputs.build_number, '42');
  assert.equal(new URLSearchParams(calls[0].options.body).get('GIT_COMMIT'), env.BUILD_SHA);
  assert.ok(calls.every(call => call.url.startsWith(env.JENKINS_URL + '/')));
  assert.ok(calls.every(call => call.options.redirect === 'manual'));
});

test('rejects cancelled queue items', async () => {
  await assert.rejects(run({ core: harness().core, env, mode: 'start', sleep: async () => {},
    fetch: async url => url.includes('buildWithParameters')
      ? response(null, 201, '/queue/item/9/') : response({ cancelled: true }),
  }), /queue item was cancelled/);
});

function watchFetch(snapshots, { testsStatus = 200, sha = env.BUILD_SHA } = {}) {
  let index = 0;
  return async url => {
    if (url.includes('/testReport/')) return response({ passCount: 6, failCount: 0, skipCount: 0 }, testsStatus);
    const current = snapshots[Math.min(index, snapshots.length - 1)];
    if (url.endsWith('/wfapi/describe')) {
      index++;
      return response({ stages: current.stages });
    }
    return response({ building: current.building, result: current.result,
      actions: [{ parameters: [{ name: 'GIT_COMMIT', value: sha }] }] });
  };
}
const stage = status => ({ id: '1', name: 'Unit tests', status, durationMillis: 1250 });

test('logs stage transitions once and publishes stage timings and test counts', async () => {
  const h = harness();
  await run({ core: h.core, env, mode: 'watch', sleep: async () => {}, fetch: watchFetch([
    { building: true, stages: [stage('IN_PROGRESS')] },
    { building: true, stages: [stage('IN_PROGRESS')] },
    { building: false, result: 'SUCCESS', stages: [stage('SUCCESS')] },
  ]) });
  assert.equal(h.logs.filter(line => line.includes('IN_PROGRESS')).length, 1);
  assert.equal(h.logs.filter(line => line.includes('SUCCESS')).length, 1);
  assert.ok(h.summary.some(item => item.method === 'addTable' && item.args[0][1].join(' ') === 'Unit tests SUCCESS 1.3'));
  assert.ok(h.summary.some(item => item.method === 'addRaw' && item.args[0].includes('6 passed')));
});

test('publishes failed stage results even when compilation produced no test report', async () => {
  const h = harness();
  await assert.rejects(run({ core: h.core, env, mode: 'watch', fetch: watchFetch([
    { building: false, result: 'FAILURE', stages: [stage('FAILED')] },
  ], { testsStatus: 404 }) }), /Jenkins final result: FAILURE/);
  assert.ok(h.summary.some(item => item.method === 'addTable'));
  assert.ok(h.summary.some(item => item.method === 'write'));
});

test('a post-build failure fails GitHub even when all stages succeeded', async () => {
  await assert.rejects(run({ core: harness().core, env, mode: 'watch', fetch: watchFetch([
    { building: false, result: 'FAILURE', stages: [stage('SUCCESS')] },
  ]) }), /Jenkins final result: FAILURE/);
});

test('rejects a build belonging to a different requested commit', async () => {
  await assert.rejects(run({ core: harness().core, env, mode: 'watch', fetch: watchFetch([
    { building: false, result: 'SUCCESS', stages: [] },
  ], { sha: 'b'.repeat(40) }) }), /does not match the GitHub commit/);
});

test('rejects missing credentials and HTTP authorization failures', async () => {
  await assert.rejects(run({ core: harness().core, env: { ...env, JENKINS_API_TOKEN: '' }, mode: 'start' }), /Missing Jenkins credentials/);
  await assert.rejects(run({ core: harness().core, env, mode: 'watch', fetch: async () => response(null, 403) }), /HTTP 403/);
});

test('bounds queue waiting by the configured deadline', async () => {
  let clock = 0;
  await assert.rejects(run({ core: harness().core, env, mode: 'start', now: () => clock,
    sleep: async () => { clock += 96 * 60 * 1000; },
    fetch: async url => url.includes('buildWithParameters')
      ? response(null, 201, '/queue/item/9/') : response({}),
  }), /Timed out waiting for Jenkins/);
});
