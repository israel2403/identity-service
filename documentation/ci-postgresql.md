# Identity service: PostgreSQL and Jenkins verification

## Purpose

This page describes the bootstrap service, its PostgreSQL dependency, and the CI build. It is ready to copy into Confluence as a page with headings, tables, and code blocks. The service currently has no identity domain entities or business endpoints; these checks establish application startup, database connectivity, and HTTP health.

## Build requirements

| Component | Configuration |
| --- | --- |
| Java | JDK 21, matching the Jenkins agent and GitHub runner in the Rocky guide |
| Build | Maven; run `mvn -B -ntp clean verify` |
| Framework | Spring Boot 4.1.1 |
| Test database | `postgres:17-alpine`, created automatically by Testcontainers |
| Container access | Local Docker socket, or the TLS DinD endpoint configured by the Rocky guide |

The Maven parent manages Testcontainers versions. The test dependencies are `spring-boot-testcontainers` and `testcontainers-postgresql`. PostgreSQL is a real container; no H2 replacement or shared CI database is used.

## Changes included

- Target Java 21 so compilation matches the existing Rocky build images.
- Add PostgreSQL Testcontainers integration through a Spring-managed `@Bean` with `@ServiceConnection`.
- Replace the empty context test with a real database query and an HTTP health check on a random application port.
- Require datasource environment variables when running the application normally. Hibernate validates the schema instead of creating it automatically.
- Add the Jenkins pipeline, perform one explicit SCM checkout, build from a clean target directory, and require test reports.

## Test execution

From the identity-service repository, select JDK 21 and run:

```bash
java -version
mvn -B -ntp clean verify
```

Docker must be accessible to the build user. The first execution downloads PostgreSQL and Ryuk images. No datasource secrets are required for tests: `@ServiceConnection` provides the generated container credentials and mapped JDBC address. Spring starts PostgreSQL before database-dependent beans and stops it after application shutdown. Ryuk remains enabled for cleanup.

`IdentityServiceApplicationTests` is discovered by Maven Surefire, so no Failsafe configuration is required. The two tests assert:

1. JDBC executes `SELECT 1` and the server identifies itself as PostgreSQL.
2. The application serves `/actuator/health` over HTTP with status 200 and `UP`.

Surefire reports are written under `target/surefire-reports`. The packaged executable JAR is `target/identity-service-0.0.1-SNAPSHOT.jar`. These checks do not prove authentication flows or domain persistence, which are not implemented yet.

## Running the packaged application

Start a separate PostgreSQL container for application development. Set its database name, username, and password using the PostgreSQL image configuration. Then supply the matching connection settings to the application process:

| Environment variable | Meaning |
| --- | --- |
| `SPRING_DATASOURCE_URL` | JDBC URL, for example `jdbc:postgresql://localhost:5432/identity` when PostgreSQL publishes port 5432 to the application host |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL user |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password, supplied privately |

```bash
java -jar target/identity-service-0.0.1-SNAPSHOT.jar
```

With those variables exported in the same terminal, verify:

```bash
curl --fail http://localhost:8080/actuator/health
```

When the application runs in another container, use the PostgreSQL service hostname on their shared Docker network instead of `localhost`. Testcontainers is test-scoped and does not provision a database for the packaged JAR. No Flyway migration is added because there are no application tables yet; add versioned migrations under `src/main/resources/db/migration` alongside future entities.

## Rocky CI guide connection

- Step 7: Jenkins agent uses Java 21 and Maven; no image change is required.
- Step 14: Docker TLS and mapped-port probe must pass before application testing.
- Step 15: install Job DSL and declare `identity-service-ci` in JCasC. Set its branch to `*/feature/SCRUM-13-bootstrap-infrastructure` to test this branch, or use `*/main` after merging. The script path is `Jenkinsfile` at the repository root.
- Step 16: the GitHub runner can execute the same Maven command.
- Step 17: the new test supplies the required actual PostgreSQL/JDBC verification.

The infrastructure supplies `DOCKER_HOST`, `DOCKER_TLS_VERIFY`, `DOCKER_CERT_PATH`, `TESTCONTAINERS_HOST_OVERRIDE`, and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`. Jenkins uses `jenkins-dind` and GitHub uses `github-dind` for mapped ports. Do not hardcode those hostnames or localhost database ports in application tests. Do not disable Ryuk or add a permanent CI PostgreSQL service.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Docker access denied | Build user's daemon access or TLS certificate mount |
| Database connection refused in CI | DinD host override and dynamically mapped port connectivity |
| Compilation fails | Maven must run under JDK 21; check `mvn -version` |
| Missing datasource URL when running the JAR | Set all three datasource environment variables |
| Jenkins cannot find the Jenkinsfile | Job DSL branch and script path, and pushed commit |
| Empty Jenkins test reports | Maven failed before tests or test discovery changed; inspect console output |

## References

- Spring Boot Testcontainers: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Local infrastructure instructions: `ROCKY-LINUX-CICD-GUIDE.md`, steps 7 and 14–17.

## Verification record — 18 October 2026

| Environment | Command | Result |
| --- | --- | --- |
| Local JDK 21 and Docker | `mvn -B -ntp clean verify` | BUILD SUCCESS; 2 tests, 0 failures/errors/skips; executable JAR created |
| Rocky Jenkins agent and TLS DinD | `mvn -B -ntp clean verify` | BUILD SUCCESS; 2 tests, 0 failures/errors/skips; executable JAR created |

The Rocky build was run directly inside `jenkins-agent` in a separate validation workspace using the service sources. It verifies the agent/daemon/database path; it is not a Jenkins job execution or GitHub Actions execution. Configure the job branch as described above before selecting Build Now.
