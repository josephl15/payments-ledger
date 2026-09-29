# CV evidence log

Rules for this file (project brief section 1.3):

- Only facts that can be verified from the repository or from a command that was actually run.
- Test counts come from `build/test-results/test/*.xml` after `./gradlew cleanTest test`, never estimated.
- No figure appears without the command or file that produced it.
- Anything not done is listed as not done.

## Phase 1: Setup and toolchain (brief phase 0)

### Run details

Date: 2026-09-29
Commit tested: b0d1d20

Commands run for this entry (Git Bash, Windows 11, with `JAVA_HOME` set to the JDK 21 install and the Docker CLI on PATH):

- `java -version`
- `docker version`
- `docker compose version`
- `./gradlew --version`
- `cmd.exe //c ver`
- `git rev-parse --short HEAD`
- `./gradlew cleanTest test --console=plain` (output saved to docs/evidence/phase-1-test-output.txt, exit code 0)
- the Compose smoke: `docker compose config -q && docker compose up --build -d --wait`, then a health request, a `flyway_schema_history` query through `psql`, `id -u` in the app container, `docker compose logs app`, `docker compose ps` and `docker compose down -v` (output saved to docs/evidence/phase-1-compose-smoke.txt, assertion exit code 0)

### Environment

- OS: Microsoft Windows [Version 10.0.26200.9457]
- Java: openjdk version "21.0.12.1" 2026-08-18 LTS (Temurin-21.0.12.1+1)
- Gradle: 8.14.5 (from `./gradlew --version`)
- Docker: client 29.8.1; server Docker Desktop 4.93.0, Engine 29.8.1, API version 1.56 (minimum 1.40)
- Docker Compose: v5.5.1
- Testcontainers 1.21.4 (from the Spring Boot 3.5.16 dependency management, build.gradle.kts declares no version), PostgreSQL image `postgres:16.15-alpine`

### Test results

Integration tests (*IntegrationTest): 10 run, 0 skipped, 0 failed, 0 errors
Unit tests (*Test): 1 run, 0 skipped, 0 failed, 0 errors
Total: 11 tests, 0 skipped, 0 failed, 0 errors

Source: docs/evidence/phase-1-test-output.txt and build/test-results/test/*.xml after ./gradlew cleanTest test

Classes: `ToolchainSmokeIntegrationTest` (10 tests, integration) and `PostgresImageTagConsistencyTest` (1 test, unit). Testcontainers reported: "Found Docker environment with local Npipe socket (npipe:////./pipe/docker_engine)".

### Compose smoke

`up=0 health={"status":"UP"} flyway=1|t uid=10001`

Source: docs/evidence/phase-1-compose-smoke.txt. Both containers (app and `postgres:16.15-alpine`) reported healthy in `docker compose ps`, with ports published on 127.0.0.1 only, and `docker compose down -v` removed them afterwards.

### Completed

- SETUP-01: `docker compose up --build -d --wait` gave two healthy containers; `/actuator/health` returned `{"status":"UP"}`; Flyway version 1 was recorded as successful in the Compose database; the app ran as uid 10001 (docs/evidence/phase-1-compose-smoke.txt).
- SETUP-02: the app boots on PostgreSQL 16.15 with Flyway V1, `ddl-auto=validate` and `open-in-view=false`, asserted by `ToolchainSmokeIntegrationTest` (docs/evidence/phase-1-test-output.txt).
- SETUP-03: integration tests run against a real PostgreSQL container started by Testcontainers (`runsAgainstPostgres16` passes); `noH2OnClasspath` passes; a real lock wait fails with SQLSTATE 55P03 (`lockTimeoutFiresOnRealLockWait` passes).
- SETUP-04 (file part only): `.github/workflows/ci.yml` is committed and its structure and zero-test guard were checked locally. It has not been run on GitHub (see Not completed).
- SETUP-05: docs/LEARNING_NOTES.md.
- SETUP-06: docs/DECISIONS.md (entry 1 records the Spring Boot 3.5.16 end-of-life choice) and the README honest scope statement.
- Testcontainers 1.21.4 connected to Docker Engine 29.8.1 directly, without the `docker-java.properties` `api.version` fallback (the file does not exist in the repository).
- `PostgresImageTagConsistencyTest` fails the build if the PostgreSQL image tag in docker-compose.yml differs from the one used by the tests (checked failing with `postgres:16.14-alpine` and passing again after reverting, recorded in Plan 01-02).

### Not completed

CI workflow committed, not yet observed running.

- No GitHub remote exists yet, so no CI run, no green check and no CI test count exist.
- The deliberate red-then-green CI proof is Plan 01-04 and needs the repository to be created on GitHub first.
- The Actions-side behaviour of the workflow (the multi-line artifact path and the `::notice` annotation) is unverified.

### Bugs caught

- Found by a run during Plan 01-02: the image-tag drift test was silently skipped by Gradle's up-to-date check when only `docker-compose.yml` changed, so the guard would have missed local drift. Fixed by declaring `docker-compose.yml` as a test input in build.gradle.kts, commit ab99a29.
