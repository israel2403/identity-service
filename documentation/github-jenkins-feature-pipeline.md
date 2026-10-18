# Run feature-branch verification in GitHub through Jenkins

## Goal

Validate `feature/SCRUM-13-bootstrap-infrastructure` before merging it into `main`. GitHub Actions invokes Jenkins; Jenkins checks out the commit supplied by GitHub, starts PostgreSQL using Testcontainers, runs Maven verification, and publishes test reports. GitHub waits for that Jenkins build and reports its final result.

## Cause of the original failure

`identity-service-ci` was configured in JCasC with `branch('*/main')`. That branch had no root-level `Jenkinsfile`. Jenkins could not load the pipeline and failed before reaching an agent or Maven. Changing application code cannot fix that SCM selection: the infrastructure job must select the feature branch containing the pipeline.

## Components

| Component | Responsibility |
| --- | --- |
| `.github/workflows/jenkins-feature.yml` | Starts on feature-branch pushes and waits for the Jenkins result |
| GitHub self-hosted runner `nahuatl-rocky-01` | Calls Jenkins on a private Docker network |
| JCasC `identity-service-ci` | Loads the Jenkinsfile from the feature branch and accepts `GIT_COMMIT` |
| `Jenkinsfile` | Validates and checks out the supplied full SHA, then runs `mvn -B -ntp clean verify` |
| `jenkins-agent` and `jenkins-dind` | Compile with Java 21 and run PostgreSQL/Ryuk containers through TLS Docker |

The GitHub workflow does not run Maven a second time. Its job succeeds only if Jenkins returns `SUCCESS`; failed, unstable, aborted, cancelled, and timed-out runs fail the workflow.

## Infrastructure changes

On `isra@192.168.0.104`, the infrastructure source lives at `/home/isra/Projects/nahuatl-corpus-ci-infrastructure`.

The existing `docker/jenkins-controller/jenkins.yaml` now declares:

```groovy
pipelineJob('identity-service-ci') {
  parameters {
    stringParam('GIT_COMMIT', '', 'Optional full commit SHA supplied by GitHub Actions')
  }
  // Existing cpsScm definition uses:
  // branch('*/feature/SCRUM-13-bootstrap-infrastructure')
  // scriptPath('Jenkinsfile')
}
```

Keep the rest of the existing job definition. The parameter is declared through Job DSL before the first API-triggered build. A manual Jenkins Build with Parameters request may leave it empty to build the configured branch tip.

In `docker/compose.yaml`, the GitHub runner now joins both private networks:

```yaml
  github-runner:
    # Keep all existing image, environment, volumes, and dependencies.
    networks: [github, jenkins]
```

The existing running container was connected to `nahuatl-ci_jenkins` with `docker network connect`, preserving its GitHub registration. Future deployments use the Compose declaration. Recreating this runner still requires the fresh GitHub registration procedure from the Rocky guide; do not recreate it merely to connect the network.

The active Rocky files under `/opt/nahuatl-ci/docker` were updated and the controller rebuilt/recreated with:

```bash
cd /opt/nahuatl-ci/docker
docker compose config --quiet
docker compose up -d --build --no-deps jenkins-controller
```

The Jenkins home volume preserves existing jobs, credentials, and API tokens. The agent reconnects after startup. Future source changes should follow the guide's rsync and deliberate `/opt` copy procedure.

## Authentication and network path

Repository Actions secrets `JENKINS_USER` and `JENKINS_API_TOKEN` authenticate the remote build API. The token is named `identity-service-github-actions` in the Jenkins account. It is stored in GitHub Secrets, never committed or printed. The current lab uses the existing administrator account and administrator-only authorization model; the token therefore has that account's privileges. A later multi-user setup should use a dedicated account with job-specific read/build permissions.

The runner calls `http://jenkins-controller:8080` over the shared Docker network. Jenkins remains bound to Rocky loopback for browser access; no public Jenkins endpoint or inbound GitHub webhook is required. Jenkins API tokens permit authenticated POST requests without disabling CSRF protection.

Jenkins may return queue and build URLs using its configured browser address `127.0.0.1`. The workflow preserves their paths but sends polling requests to the private controller origin. Credentials are sent only to that configured origin, and redirects are not followed.

## Run before merging

1. Push a commit to `feature/SCRUM-13-bootstrap-infrastructure`.
2. Open GitHub **Actions → Jenkins feature verification**.
3. Open **Jenkins PostgreSQL verification**, then **Invoke Jenkins and wait for verification**.
4. The log identifies the Jenkins build number and requested commit. The workflow summary records the commit, build number, and final result.
5. In Jenkins, open `identity-service-ci` and that build number. Confirm `Verified GitHub commit` matches the GitHub commit, Maven succeeds, and two tests pass.
6. Merge only after the check succeeds. To enforce this, configure a GitHub branch rule/ruleset for `main` requiring the check `Jenkins PostgreSQL verification` after it first appears. Branch protection is not changed by this implementation.

The workflow supports `workflow_dispatch`, but GitHub requires the workflow definition on the default branch to expose manual dispatch. Until it reaches `main`, use pushes. Once a run exists, GitHub's **Re-run jobs** can rerun that commit. A later commit requires a new push/run.

This workflow intentionally accepts only the named trusted feature branch and does not execute fork pull requests on the persistent self-hosted runner. GitHub push checks are associated with their commit and can be viewed from a pull request containing that commit. There is no `pull_request_target` trigger.

## Commit selection and concurrency

Jenkins loads trusted pipeline code from the configured feature branch. Maven builds the exact full SHA passed in `GIT_COMMIT`, with an explicit post-checkout equality check. A later branch push cannot silently change the application commit under test. The Jenkinsfile itself is loaded from the branch tip when the run starts; this is a trusted-branch design, not an isolated executor for unreviewed pipeline code.

GitHub serializes this workflow with `cancel-in-progress: false`; Jenkins also disables concurrent builds. GitHub may replace an older pending run when several pushes arrive, while preserving the running workflow. The caller waits up to 35 minutes, and Jenkins limits the pipeline to 30 minutes. Cancelling the GitHub workflow does not cancel an already queued or running Jenkins build; inspect Jenkins before starting replacement work.

## PostgreSQL and build evidence

The existing `IdentityServiceApplicationTests` starts `postgres:17-alpine` as a Spring-managed Testcontainers service connection, executes real SQL, and verifies HTTP health. No permanent CI database or fixed database port is required. Maven Surefire runs both tests and Jenkins publishes its XML reports. PostgreSQL/Ryuk resources should disappear after the build.

## Troubleshooting

| Failure | Resolution |
| --- | --- |
| Unable to find Jenkinsfile | Verify the JCasC branch and root script path; rebuild the controller after editing its baked-in YAML |
| Workflow remains queued | Confirm `nahuatl-rocky-01` is online with the `nahuatl-ci` label |
| Jenkins hostname cannot resolve | Verify runner membership in `nahuatl-ci_jenkins` and its Compose declaration |
| HTTP 401/403 | Check repository secrets and Jenkins API token; keep CSRF protection enabled |
| HTTP 400 on buildWithParameters | Confirm the JCasC job declares `GIT_COMMIT` |
| Git revision cannot be found | Confirm the full commit was pushed to this repository and is reachable |
| Jenkins FAILURE/UNSTABLE | Open the reported build number and inspect console output/test reports |
| API polling times out | Inspect Jenkins queue, agent status, and pipeline timeout; the Jenkins build may still be running |

## References

- Jenkins remote API: https://www.jenkins.io/doc/book/using/remote-access-api/
- Jenkins API token and CSRF behavior: https://www.jenkins.io/doc/developer/security/misc/
- GitHub workflow triggers: https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow
- Project infrastructure guide: `ROCKY-LINUX-CICD-GUIDE.md`, steps 12, 15, and 17.

## Verified execution — 18 October 2026

The complete GitHub-to-Jenkins path passed:

- GitHub Actions run: https://github.com/israel2403/identity-service/actions/runs/39134032001
- Jenkins job/build: `identity-service-ci` / `#2`, result `SUCCESS`.
- Verified checkout: `5966f34776835ecb61c7674a4b756e92e0d9fe28`.
- Maven: `BUILD SUCCESS`.
- Published Jenkins report: 2 passed, 0 failed, 0 skipped.
- Caller checks also covered failed builds, queue cancellation, submitted SHA, and private-origin polling.

The workflow action is pinned to the official `actions/github-script` v8 commit, which uses Node 24.
