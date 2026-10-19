# Identity service: verification and container publishing

## Execution path

GitHub Actions calls Jenkins job `identity-service-ci` with the triggering commit's full SHA. Jenkins loads this repository's root `Jenkinsfile` from `main`, checks out the requested SHA using a refspec that fetches all repository branches, and verifies the resulting revision before executing CI. Manual Jenkins builds with an empty `GIT_COMMIT` build `main`.

The GitHub workflow remains `.github/workflows/jenkins-feature.yml`, named **Identity service CI**. It accepts branch pushes and manual branch dispatches. Existing branches must contain the new workflow, Maven configuration and CI scripts to use the complete pipeline. Cherry-pick the CI implementation commit, or bring these changes into those branches through your normal Git workflow.

## Pipeline stages

| Stage | Implementation and failure condition |
| --- | --- |
| Checkout | Clean workspace; validate SHA; fetch all heads; verify checkout; provision Java 25 and Trivy from digest-pinned tool images. |
| Code checks | `mvn clean validate`: Checkstyle rejects unused/wildcard imports, missing braces, inconsistent equals/hashCode, empty statements and switch fallthrough. |
| Unit tests | `mvn test`: Surefire runs `*Test` classes and fails if none exist. The current bootstrap test checks argument forwarding; business tests must be added with domain behavior. |
| Integration tests | Failsafe runs `*IT` classes against a real PostgreSQL 17 Testcontainer. Tests verify Flyway migration, SQL connectivity, HTTP health and rejection of unauthenticated protected requests. |
| Coverage/security gates | JaCoCo requires at least 80% aggregate line coverage. CycloneDX produces a runtime dependency SBOM. Trivy rejects detected source secrets and HIGH/CRITICAL runtime dependency vulnerabilities, including those without a known fix. |
| Package | Build the executable `target/identity-service.jar` without rerunning tests. |
| Build image | Build the Java 25 runtime image, attach source/revision labels, run as UID/GID 10001. |
| Image scan | Export the built image to a temporary tar and scan OS and Java dependencies with Trivy. HIGH/CRITICAL findings block publishing. |
| Container smoke test | Start a temporary PostgreSQL instance and the actual application image on a private network. Require HTTP 200/UP at `/actuator/health` and HTTP 401 for an unauthenticated protected request. Remove containers, database volumes and network on exit. |
| Publish image | Log in using the Jenkins credential, push the tested image to Docker Hub, and retain the published reference/digest. Temporary Docker authentication files are removed. |

No deployment is performed. Every successful repository branch build can publish its own image; publishing is reached only after all preceding gates pass. The runtime image is scanned and smoke-tested before the same image is pushed.

## Container identity

Repository: `docker.io/israelhf24/identity-service`.

Tag pattern: `sha-<full-commit-sha>-build-<jenkins-build-number>`.

The pipeline does not overwrite `latest` or release tags. Docker Hub tags are technically mutable unless the registry enforces immutability; use the archived image digest when deploying. The OCI revision label links the image to the source commit.

## Jenkins setup

1. Configure `identity-service-ci` as **Pipeline script from SCM**, using this repository, branch `*/main`, script path `Jenkinsfile`.
2. Keep its `GIT_COMMIT` string parameter so the GitHub caller can submit an exact SHA before the pipeline is loaded.
3. Provide an online agent labeled `nahuatl-maven`, with Git, Maven, Docker CLI and a reachable Docker daemon. Keep the existing Docker TLS and Testcontainers host configuration on the Docker-in-Docker agent.
4. Create a Jenkins **Username with password** credential with ID `dockerhub-identity-service`, username `israelhf24`, and the Docker Hub access token as its password. The token requires permission to push to the image repository. Never add it to Git, Dockerfiles or this document.
5. Keep repository Actions secrets `JENKINS_USER` and `JENKINS_API_TOKEN` for the GitHub-to-Jenkins call; these differ from the Docker Hub credential.
6. Persist the SCM branch change in the infrastructure's JCasC `docker/jenkins-controller/jenkins.yaml` so provisioning cannot restore the original feature branch.

The Jenkins agent can continue running its own Java 21 runtime. CI extracts a separate Java 25 JDK into `.ci-tools/jdk` and sets `JAVA_HOME`/`PATH` for Maven. The tool containers are never executed with the workspace mounted, which also works with a remote Docker-in-Docker daemon. Java and Trivy tool images are pinned by digest; update the digest deliberately when upgrading.

CI uses a Java 25 JDK and runtime. The Java compilation target is declared by `java.version` in the branch's POM; this feature branch preserves its Java 21 target, which Java 25 can compile and execute. The runtime base image uses `eclipse-temurin:25-jre-noble` and `docker build --pull` to receive patched runtime/base packages. Builds therefore are traceable by output digest but are not guaranteed to reproduce byte-for-byte across time.

## Verify a feature before merging it into main

Bring the current CI configuration into the feature branch by merging `main` into that feature branch, resolve conflicts, then push the feature branch. This updates the feature with the pipeline without merging its application changes into `main`. Future branches created from updated `main` inherit the same pipeline automatically.

For `feature/SCRUM-13-bootstrap-infrastructure`, the merge retains its datasource configuration and Java 21 compilation target, adopts the complete CI configuration, and moves its original PostgreSQL tests to `IdentityServicePostgresqlIT`. These tests now run in Failsafe alongside the new migration and security integration tests instead of running as unit tests.

In GitHub Actions, select **Run workflow** and choose the feature branch, or simply push its commit. Jenkins still loads the trusted pipeline definition from `main`, then checks out and validates the exact feature SHA supplied by GitHub. Confirm the requested feature SHA and successful result in the run summary before merging the feature into `main`.

## Reports and artifacts

The pipeline's `post/always` publishes JUnit XML from Surefire and Failsafe, even when a later stage fails. Each test stage rejects an empty suite; early checkout/compilation failures may legitimately have no XML to publish.

Jenkins archives and fingerprints:

- Executable JAR and CycloneDX `target/bom.json`.
- JaCoCo HTML/XML/CSV under `target/site/jacoco/`.
- Unit and integration test XML/text reports.
- Checkstyle XML results.
- Trivy source-secret, dependency and image JSON reports.
- Container smoke-test application logs and health response.
- Published image reference and registry digest.

Open a Jenkins build's **Test Result** view for test counts and failures; use **Artifacts** for coverage and security details. The GitHub run summary includes the Jenkins build number, requested commit, final result, each stage's result and duration, and test counts. Retention keeps 20 builds and artifacts for the latest 10 builds.

The SBOM is archived as a build artifact. It is not attached to the registry image as an attestation, and image signing/provenance attestations are not implemented by this pipeline.

## Local verification

Prerequisites: Java 25, Maven, Docker, network access to Maven Central/container registries and Trivy vulnerability databases.

```sh
mvn -B -ntp clean verify cyclonedx:makeAggregateBom
sh ci/prepare-tools.sh
sh ci/security.sh source
docker build --pull -t israelhf24/identity-service:ci-local .
IMAGE_REF=israelhf24/identity-service:ci-local sh ci/security.sh image
IMAGE_REF=israelhf24/identity-service:ci-local sh ci/smoke-test.sh
```

The smoke test creates an ephemeral database password for its isolated containers. Its shell must not run with tracing enabled, which would expose that password. No fixed database port is required. Flyway migration `V1__initialize_schema.sql` establishes an empty `identity_service` schema; domain tables will be introduced through later migrations.

The Spring Boot 4.1.1 baseline resolved vulnerable Tomcat/Jackson versions during implementation. The POM overrides Tomcat to 11.0.25 and the Jackson 3 BOM to 3.1.7. Remove these overrides when a verified Spring Boot patch manages suitable fixed versions. Security database updates can cause a previously passing build to fail later; review the JSON report and update the affected component rather than suppressing the entire gate.

## Operational limits

Jenkins serializes builds and limits execution to 60 minutes. GitHub waits up to 95 minutes, within a 100-minute job timeout, to allow queueing. Excessive backlogs can still exceed that deadline. Cancelling the GitHub caller does not cancel its submitted Jenkins build.

Only trusted repository writers should be able to execute this pipeline: branch code runs on a persistent CI agent and successful branch builds can use the Docker Hub publishing credential. Fork pull requests are not a trigger. The current application contains only a bootstrap class, so its high coverage is not evidence of business-level identity/authentication coverage.

## References

- [Jenkins test results and artifacts](https://www.jenkins.io/doc/pipeline/tour/tests-and-artifacts/)
- [JaCoCo Maven integration](https://www.jacoco.org/jacoco/trunk/doc/maven.html)
- [Maven Failsafe](https://maven.apache.org/surefire/maven-failsafe-plugin/)
- [Trivy container scanning](https://trivy.dev/docs/latest/guide/target/container_image/)
- [Tomcat 11 security advisories](https://tomcat.apache.org/security-11.html)
- [Jackson 3.1.7 release notes](https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.7)

## Follow individual CI stages

The GitHub workflow is **Identity service CI**, with a single job named **Run CI in Jenkins**. Jenkins executes all ten CI stages. In GitHub, expand **Follow Jenkins stages and publish CI summary** for logged stage transitions. After the job finishes, the run summary contains a stage results/duration table and test counts, including reports for failed builds when available.

For the live graphical stage view, open Jenkins job `identity-service-ci` and select **Full Stage View**, or view the **Stage View** table on its job page. Each column represents a real pipeline stage; select a stage cell to open its logs. The `pipeline-stage-view:2.41` plugin is declared in the infrastructure's `docker/jenkins-controller/plugins.txt` and baked into the controller image. It also provides `/job/identity-service-ci/<build>/wfapi/describe`, which GitHub polls for stage details.

The summary links use the existing browser address `http://127.0.0.1:8080`; keep your usual Jenkins access/tunnel available. GitHub retains one orchestration box because execution remains in Jenkins. The stage graph and live stage logs are in Jenkins; GitHub receives stage transitions and the final table. This workflow performs CI and image publishing, with no deployment stage.

The observer validates that the Jenkins build's submitted `GIT_COMMIT` equals the GitHub SHA before reporting results, and sends credentials only to the private controller origin. A failed Jenkins build still fails GitHub even if all stages succeeded but a post-build action failed. Test the reporting helper locally with `node ci/jenkins-github.test.cjs`.
