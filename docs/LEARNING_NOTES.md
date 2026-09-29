# Learning notes

Plain-language notes on how this project works, written for someone who knows Python and is new to Java and Spring. Each phase appends to this file. Every command below was run during Phase 1 (on Windows 11 with Git Bash); nothing here is copied from a tutorial without being tried.

## Project layout

```
build.gradle.kts            Build script (Gradle, Kotlin syntax): dependencies, Java 21, test settings
settings.gradle.kts         Gradle project name
gradlew, gradlew.bat        Gradle wrapper scripts (Linux/Git Bash and Windows): run the pinned Gradle 8.14.5
gradle/wrapper/             Wrapper jar and properties (which Gradle version to download)
.gitattributes              Line-ending rules (gradlew must stay LF)
.gitignore                  Files git must not track (build output, the local secrets env file)
Dockerfile                  Multi-stage image: build the jar, then run it on a JRE as a non-root user
docker-compose.yml          The app plus PostgreSQL 16 for local use
.env.example                Documented overrides for the Compose dev credentials
.dockerignore               Files kept out of the Docker build context
.github/workflows/ci.yml    GitHub Actions workflow: build and run all tests on every push and pull request
src/main/java/...           Application code
src/main/resources/         application.yml (settings) and db/migration (Flyway SQL files)
src/test/java/...           Tests
src/test/resources/         application-test.yml (settings that apply only in tests)
docs/                       These notes, DECISIONS.md, CV_EVIDENCE.md and raw evidence output
```

Java code lives under the base package `dev.joseph.ledger`. A package is Java's folder-plus-namespace: the folder path `dev/joseph/ledger` matches the `package dev.joseph.ledger;` line at the top of each file. Git does not track empty folders, so each sub-package holds a `package-info.java` file (a comment-only file) that says what it will contain.

| Package | Will hold | Filled in |
|---------|-----------|-----------|
| `api` | REST controllers, request/response records, error handler | Phase 3 |
| `domain` | JPA entities and enums (rows as Java objects) | Phase 2 |
| `service` | Business rules and transaction boundaries | Phase 3 onwards |
| `repository` | Database access, including the locking queries | Phase 3, locking in Phase 4 |
| `security` | JWT filter and Spring Security setup | Phase 6 |
| `config` | Bean wiring and typed configuration | Phase 3, extended in Phase 6 |

Dependency direction: `api` and `security` depend on `service`; `service` depends on `repository`; `repository` depends on `domain`. Never the other way round, so business rules never import HTTP classes.

In Phase 1 only `LedgerServiceApplication.java` (the entry point) exists; the six sub-packages are empty apart from their `package-info.java`.

## What Spring Boot auto-configures here

`LedgerServiceApplication` carries `@SpringBootApplication`. That one annotation means three things: this class is a configuration class; Spring scans the package `dev.joseph.ledger` and below for components it should manage; and auto-configuration is switched on. Auto-configuration means: Spring Boot looks at which library jars are on the classpath and, for each one it recognises, creates the standard objects for it, reading its settings from `application.yml`. You write no setup code; you add a jar and set properties.

What that produces in this project:

- **DataSource and HikariCP pool** from `spring.datasource.*`. A DataSource hands out database connections; HikariCP keeps a pool of them open so each request does not pay to connect. Pool size is 10 in the app and 20 in tests. `connection-init-sql` runs `SET lock_timeout = '5s'` on every new connection.
- **Flyway** runs the SQL files in `classpath:db/migration` at startup, before JPA starts.
- **JPA / Hibernate** (the EntityManagerFactory) with `ddl-auto=validate`: Hibernate checks that entity classes match the tables Flyway made and never changes the schema. There are no entities in Phase 1, so it has nothing to compare yet.
- **Embedded Tomcat on port 8080** with Spring MVC, so the app is a normal jar started with `java -jar`.
- **RFC 7807 problem details** for framework errors such as a 404, returned as `application/problem+json` (`spring.mvc.problemdetails.enabled`).
- **Actuator** `/actuator/health`, which includes a database check; details are hidden, so the body is just `{"status":"UP"}`. Only `health` is exposed.

The startup order is visible in the log of a test run (`build/test-results/test/TEST-dev.joseph.ledger.ToolchainSmokeIntegrationTest.xml`). Flyway migrates first, then Hibernate starts:

```
DbMigrate : Migrating schema "public" to version "1 - baseline"
DbMigrate : Successfully applied 1 migration to schema "public", now at version v1 (execution time 00:00.006s)
org.hibernate.Version : HHH000412: Hibernate ORM core version 6.6.53.Final
LocalContainerEntityManagerFactoryBean : Initialized JPA EntityManagerFactory for persistence unit 'default'
```

The app log from the Compose run shows the same Flyway line: `Migrating schema "public" to version "1 - baseline"`.

**Dependency injection** in two sentences: instead of a class creating the objects it needs with `new`, Spring creates them once and hands them in. In `ToolchainSmokeIntegrationTest`, the fields marked `@Autowired` (`JdbcTemplate jdbc`, `DataSource dataSource`, `Environment env`, `TestRestTemplate rest`) are filled in by Spring before each test runs.

**Profiles**: `@ActiveProfiles("test")` on the test base class makes Spring load `application-test.yml` on top of `application.yml`; keys in the test file win. That is how tests get a pool of 20 while the app keeps 10.

Later study pointer: starting Spring Boot with the `--debug` argument prints a report of which auto-configurations applied and why. That was not run in Phase 1.

## How to run the app

Prerequisite: Docker Desktop running. From the repository root, in Git Bash (add the Docker CLI to PATH if `docker` is not found, see Windows notes):

```bash
docker compose up --build -d --wait
curl -fsS http://localhost:8080/actuator/health
docker compose down -v
```

- `docker compose up --build -d --wait` builds the app image, starts PostgreSQL and the app in the background, and returns once both report healthy. This is the exact form run in Phase 1. Plain `docker compose up` starts the same two services in the foreground and streams their logs; that foreground form was not run separately.
- The first run pulls base images and compiles the jar inside Docker, which took about 1.5 minutes cold. Later runs use the cache.
- The health check returns `{"status":"UP"}`.
- `docker compose down -v` stops and removes the containers and also deletes the `pgdata` database volume. Without `-v` the database contents survive between runs.
- The dev credentials default to `ledger` / `ledger`. To change them, copy `.env.example` to `.env` (which git ignores) and edit it.
- Both ports are published as `127.0.0.1:5432` and `127.0.0.1:8080`, loopback only, so the development database with a default password cannot be reached from other machines on your network.
- Tests are skipped inside the image build (`-x test`) because Testcontainers cannot start containers inside `docker build`. Tests run on the host or in CI.

## How to run the tests

Prerequisites: JDK 21 and Docker Desktop running. Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
./gradlew cleanTest test
```

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
.\gradlew.bat cleanTest test
```

`cleanTest` deletes the previous results first. Without it, a second `./gradlew test` with no code changes is marked UP-TO-DATE and skipped, so no test would actually execute. To run one class:

```bash
./gradlew test --tests 'dev.joseph.ledger.ToolchainSmokeIntegrationTest'
```

Results: `build/reports/tests/test/index.html` is the human-readable report; `build/test-results/test/*.xml` are the machine-readable results used to count tests.

Naming rule: a class ending in `IntegrationTest` starts the whole application against a real PostgreSQL; a class ending in `Test` is a plain unit test with no database.

What each test proves (`ToolchainSmokeIntegrationTest`, 10 tests):

| Test | Proves |
|------|--------|
| `runsAgainstPostgres16` | The database is a real PostgreSQL 16 |
| `flywayAppliedBaseline` | Flyway applied migration 1 and the schema comment is present |
| `healthIsUp` | `/actuator/health` returns 200 and `"status":"UP"` |
| `configIsStrict` | `ddl-auto` is `validate` and `open-in-view` is `false` |
| `hikariPoolIsExplicitForTests` | Pool size 20 and connection timeout 5000 ms in tests |
| `lockTimeoutAppliedToPoolConnections` | Pooled connections report `lock_timeout` of 5s |
| `lockTimeoutFiresOnRealLockWait` | A blocked lock wait really fails with SQLSTATE `55P03` |
| `unknownPathReturnsProblemJson` | A 404 comes back as `application/problem+json` |
| `onlyHealthEndpointIsExposed` | `/actuator/env` returns 404 |
| `noH2OnClasspath` | `org.h2.Driver` cannot be loaded, so no embedded database can sneak in |

Plus one unit test, `PostgresImageTagConsistencyTest`: it fails the build if the PostgreSQL image tag in `docker-compose.yml` differs from the one the integration tests use, so tests always run on the image that ships.

## One PostgreSQL container for the whole test run (Testcontainers)

Testcontainers is a Java library that starts a throwaway Docker container from inside a test. Here it starts a real `postgres:16.15-alpine`, so tests see real row locks, real constraints and real trigger behaviour, which an embedded database would not give.

`AbstractPostgresIntegrationTest` is the base class of every integration test. Its `static { POSTGRES.start(); }` block starts the container once, the first time any subclass loads, and every test class in that Gradle test JVM shares it. Testcontainers' Ryuk helper container removes it when the JVM exits.

Why not start it per test class: Spring caches the application context between test classes for speed, and that cached context remembers the JDBC URL it was built with. A per-class container would restart on a new random port, so later classes would still point at the old port and fail with connection errors.

`@DynamicPropertySource` solves the second half of the problem. The container's host port is only known after it starts, so it cannot be written in a YAML file. The annotated method registers the live URL, username and password with Spring's environment before the application context is created, overriding `spring.datasource.*`.

Docker Engine 29 compatibility: Testcontainers 1.21.4 connected to Docker Engine 29.8.1 (Docker Desktop 4.93.0) directly over the Windows named pipe. The "client version 1.32 is too old" error did not appear, so the `docker-java.properties` fallback file was not needed and does not exist in the repository.

## Database settings worth knowing

- **`lock_timeout`**: by default PostgreSQL waits forever for a row or table lock held by another transaction. A stuck lock would hang a request and could hide a deadlock-shaped bug. The project sets `lock_timeout` to 5 seconds on every pooled connection, so a stuck wait fails with SQLSTATE `55P03`. `lockTimeoutFiresOnRealLockWait` proves it against a real held lock. It limits lock waits only; a slow query is not stopped by it.
- **`open-in-view: false`**: with open-session-in-view (Spring's default), one persistence context and one database connection stay open for the whole HTTP request, including while JSON is written. Turning it off means the database is only touched inside service methods that declare a transaction.
- **Explicit Hikari pool size**: 10 in the app, 20 in tests. Phase 4 concurrency tests will run many threads at once; the test pool must be at least as large as the number of worker threads, otherwise threads queue for a connection while holding locks and the failures are confusing.
- **`ddl-auto: validate`**: Hibernate can create or alter tables itself (`create`, `update`), but then the schema would change behind Flyway's back. `validate` only compares and reports mismatches. Flyway owns the schema.

## Windows notes

- `JAVA_HOME` may not be set in Git Bash. Export it explicitly before every Gradle command (see "How to run the tests").
- Linux and Git treat the executable bit as part of a file's mode; Windows has no file modes and this repository has `core.filemode=false`, so a plain `git add` records `gradlew` as non-executable. Stage it with `git add --chmod=+x gradlew` and check with `git ls-files --stage gradlew`, which should show mode `100755`. Without it, `./gradlew` fails on Linux CI and inside the Docker build.
- `.gitattributes` keeps `gradlew` on LF line endings. A CRLF copy fails on Linux with a bad-interpreter error. `gradlew.bat` is kept as CRLF.
- The Docker CLI is not on the Git Bash PATH by default. Add it for the session:

```bash
export PATH="/c/Program Files/Docker/Docker/resources/bin:$PATH"
```

## Troubleshooting

- The first test run is slow because Docker pulls the PostgreSQL image; later runs are quick.
- "Could not find a valid Docker environment" means Docker Desktop is not running. Start it and re-run.
- A Flyway checksum error after editing a migration that was already applied: never edit an applied migration. Add a new, higher-numbered file instead. Locally, `docker compose down -v` deletes the Compose database so it can start clean. Migrations after `V1__baseline.sql` start at `V2` (Phase 2).
- Port 5432 or 8080 already in use: something else is listening (often a Compose stack left running). Run `docker compose down -v` first.
