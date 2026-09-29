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
export LEDGER_JWT_SECRET="$(openssl rand -base64 48)"   # required since Phase 6: any 32+ character text
docker compose up --build -d --wait
curl -fsS http://localhost:8080/actuator/health
docker compose down -v
```

`LEDGER_JWT_SECRET` has no default: Compose stops with an error if it is unset, and the application refuses to start if it is under 32 characters. Put it in your shell, or in a `.env` file (copy `.env.example`, git ignores `.env`). Remember to have it set for `docker compose down -v` too, because Compose reads the file before it stops anything.

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

## Database triggers, in plain words

A trigger is a small function the database runs automatically when something happens to a table. Here `forbid_mutation()` is attached to `ledger_entries` and `audit_log`; it raises an error whenever a row is updated or deleted, so the statement fails and nothing changes. A row-level trigger only fires once per affected row, so a statement that matches no rows never triggers it, and it does not fire for `TRUNCATE` at all. That is why each table has a second, statement-level `BEFORE TRUNCATE` trigger. The error carries SQLSTATE `P0001` (a `RAISE EXCEPTION`), which is how the tests recognise it. Flyway files are final once applied: to change a trigger, add a new `V` file.

Other database error codes worth knowing from the tests: `23505` unique violation, `23514` check violation, `23503` foreign-key violation, `22001` value too long. PostgreSQL also reports the name of the constraint, which is why every constraint has an explicit name.

## What `ddl-auto=validate` does and does not check

With `validate`, Hibernate compares each entity with the real table at startup and refuses to start if a table, column or column type is missing or different. Flyway creates the schema; Hibernate never changes it. Typical messages: `missing column [amount_minors] in table [ledger_entries]` (typo), `wrong column type ... found [bpchar (Types#CHAR)], but expecting [varchar(255) (Types#VARCHAR)]` (a `CHAR(3)` column), `missing sequence [ledger_entries_SEQ]` (identity column mapped with plain `@GeneratedValue`). Validate is lenient in places: it accepts a primitive `long` for a nullable column (it fails later with a NullPointerException when a NULL row is loaded) and an `Instant` for a `timestamp` without time zone. So each entity has a save-and-load-back test.

## JPA entities and `@Immutable`, briefly

An entity is a plain Java class mapped to a table with annotations (`@Entity`, `@Table`, `@Column`, `@Id`). JPA needs a no-argument constructor, so entities here have a protected one plus a public constructor for application code, and explicit getters (no Lombok). Entities refer to each other by id (a `UUID` field), not by `@ManyToOne` links, to keep the SQL obvious. `@Immutable` tells Hibernate the row never changes, so it never sends an UPDATE for it even if a field is modified in memory. Each column is also `updatable = false`. The database triggers are the layer that stops everything else.

Repositories extend `Repository<T, ID>`, the empty base interface, and declare only the methods wanted (`save`, `findById`, finders). `JpaRepository` would bring `delete`, `deleteAll` and more. A reflection test fails the build if a delete or remove method is ever added to an append-only repository.

## Why tests use unique data instead of cleaning up

Deleting rows from the ledger is blocked by design, so tests cannot empty the tables. Each test makes its own user, account and transaction and only asserts on those (see docs/DECISIONS.md, entry 16). Two small traps: `Instant.now()` can have more precision than PostgreSQL stores (microseconds), so tests truncate with `truncatedTo(ChronoUnit.MICROS)` before comparing; and JSONB normalises key order and spacing, so JSON is compared as data, not as text.

## Phase 3: the Spring pieces used for the first time

- **Constructor injection.** A `@Service` class lists what it needs as constructor parameters; Spring creates each dependency once and passes it in. No `@Autowired` is needed on a single constructor, and there is no field injection, so a class shows its collaborators in one place.
- **`@Transactional`.** Spring wraps the bean in a proxy; the proxy opens a transaction, calls your method and commits, or rolls back if an unchecked exception escapes. It only applies to calls that arrive from another bean (a method calling another method on `this` skips the proxy). `Propagation.MANDATORY` means "there must already be a transaction, otherwise throw". See docs/INTERVIEW_PREP.md, entry 3.
- **`@RestController`, `@RequestBody`, `@Valid`.** The controller method receives the JSON body as a Java `record`; `@Valid` runs the Bean Validation annotations (`@NotNull`, `@Positive`, `@Size`, `@Pattern`) first, and a failure never reaches the method body.
- **`@RestControllerAdvice` and `ProblemDetail`.** One class turns exceptions into `application/problem+json` responses (RFC 7807: `status`, `title`, `detail`, plus extra fields). It extends `ResponseEntityExceptionHandler` so Spring's own errors (unreadable JSON, missing header) use the same format.
- **`@ConfigurationProperties`.** `LedgerProperties` is a record bound from the `ledger:` block of application.yml, so the maximum amount is a setting, not a constant in code. `@EnableConfigurationProperties` registers it as a bean.
- **A `Clock` bean.** Services ask the clock for the time instead of calling `Instant.now()`, so a test could inject a fixed clock.
- **Profiles.** `@Profile("dev")` marks a bean that only exists when `SPRING_PROFILES_ACTIVE=dev` (Phase 3 used it for a dev user seeder; Phase 6 deleted that, users now register through the API).
- **MockMvc.** `@AutoConfigureMockMvc` gives a `MockMvc` that calls the controllers through Spring MVC without a network socket, using the real Jackson, validation and exception handler.
- **`@MockitoSpyBean`.** Wraps a real bean in a Mockito spy so one call can be changed while the rest stays real. The injected field is the transactional proxy around the spy, so stubbing is done on `AopTestUtils.getTargetObject(bean)`; stubbing through the proxy calls the real method outside a transaction.

### Two traps met while building it

- **Jackson turns `30.9` into `30` by default** for a `Long` field, and accepts the string `"3000"`. `application.yml` sets `spring.jackson.deserialization.accept-float-as-int: false` and `spring.jackson.mapper.allow-coercion-of-scalars: false`, and `LedgerApiIntegrationTest` checks that each bad form gets a 400.
- **A broken Mockito stub can make the next test fail misleadingly.** When the first stubbing attempt went through the transactional proxy it threw, and Mockito's half-finished argument matchers stayed on the thread; the following test failed with "entries missing". The cause was in the first test, not the second.

## Phase 4: the pieces used for the first time

- **Native queries with `@Query(nativeQuery = true)`.** The SQL in the annotation is sent to the database as written, with `:ids` replaced by bind parameters (a collection becomes `?, ?, ?`). Used for the `FOR UPDATE` lock because JPQL would leave the SQL to Hibernate; here the text in the source is the text on the wire. A native query that returns whole rows (`SELECT *`) can still return entities.
- **`JdbcTemplate` and `NamedParameterJdbcTemplate`.** Plain SQL without entities, used for reconciliation because it returns numbers, not objects to change. `IN (:ids)` with a `Set` of UUIDs expands to one `?` per id. They join the transaction that Spring opened, so the whole reconciliation sees one snapshot.
- **`@Transactional(readOnly = true, isolation = REPEATABLE_READ)`.** Isolation is a property of the transaction; the default (READ COMMITTED) is left alone for money movement. If a `@Transactional` method calls another method of the same class, the second annotation is ignored (self-invocation), so `reconcile()` carries its own annotation.
- **`TransactionTemplate`.** The programmatic form of `@Transactional`: `template.execute(status -> { ...code... })` opens a transaction, runs the block, commits. Used in the tests to hold a lock open on purpose.
- **`@TestConfiguration`, `@Import` and `@Primary`.** How a test swaps one bean: a small configuration class declares a replacement bean marked `@Primary`, and one test class imports it. Only that class sees the replacement; it also gets its own Spring context (so it starts the application again, against the same database container).
- **Executors, latches and futures.** `ExecutorService` runs tasks on a pool of threads; a `CountDownLatch(1)` used as a starting gate holds them all until released; `Future.get(timeout)` returns a task's result or rethrows its exception, which is how a failure inside a worker thread is noticed.

### Traps to know (only the deadlock-timeout one was actually hit)

- **A JdbcTemplate call on the transaction's own thread reuses the transaction's connection.** A test that wants to prove "another session cannot lock this row" must run its probe on a separate thread, or it would ask the question from inside the lock holder and get a false answer.
- **`deadlock_timeout` makes deadlock tests slow.** PostgreSQL waits 1 second before it resolves each deadlock, so a large run of deliberately deadlocking transfers takes minutes; the mutation test therefore uses 40 transfers.
- **`gradlew test` can say UP-TO-DATE.** Repeating a test run needs `cleanTest`, otherwise Gradle skips the tests and "passes" instantly.
- **Run one concurrency scenario against the broken version first.** The first version of the showcase failed loudly against the naive lock with no exception at all, only wrong numbers; that is the evidence the assertions look at the right things.

### Lost updates and row locks in five lines

Two transfers read the same balance, each computes its own new value, and the second write overwrites the first: a lost update. `@Transactional` does not prevent it (it makes writes all-or-nothing, not one-at-a-time) and the default READ COMMITTED level does not either. `SELECT ... FOR UPDATE` makes the second transaction wait at the read until the first commits, then read the committed value, so read-check-write cannot interleave on one account. To avoid deadlock all transactions lock in the same order, which one `ORDER BY id` statement guarantees. Full explanations and interview questions: docs/INTERVIEW_PREP.md entries 7 to 12; the comparison with optimistic locking and SERIALIZABLE: docs/DECISIONS.md, entry 23.

## Phase 5: the pieces used for the first time

- **`TransactionTemplate`, again, as the star.** Phase 4 used it in tests; here `IdempotentExecutor` uses it in production code because it must decide exactly where the transaction ends. `template.execute(status -> { ... })` runs the block in a transaction and commits, or rolls back if a runtime exception escapes and then rethrows it, so the code after the call runs outside the transaction.
- **`Propagation.MANDATORY` on `begin` and `complete`.** They throw at once if called without a transaction, so the key row can never be written outside the business transaction by mistake (`beginAndCompleteRefuseToRunWithoutATransaction`).
- **`@Modifying @Query` with JPQL.** `deleteExpired` is a bulk `delete ... where` statement written by hand, because Spring Data cannot derive a conditional delete from a method name that returns a count. It runs immediately and does not touch entities already loaded.
- **`saveAndFlush` and identity ids.** `flush` sends pending SQL now. The key table's id is generated by the database, so Hibernate must INSERT right away to learn it, and Spring Data treats the entity as new and does not run a "does it exist" SELECT first. That SELECT would be the check-then-insert race.
- **`ResponseEntity<String>`.** The controller returns the stored JSON text with the stored status, so a fresh and a replayed response come from the same stored value. A `HttpServletRequest` parameter gives the concrete method and path for the fingerprint.
- **`@Import` of a `@TestConfiguration` with `@Primary`.** `AbstractIdempotencyIntegrationTest` swaps the application `Clock` for a `MutableClock` (real time plus a settable offset). All idempotency test classes inherit it, so they share one Spring context.

### The rollback-only pitfall, in plain words

The natural idempotency code looks like this: try to insert the key; if it fails because the key exists, read the stored answer and return it. Inside one `@Transactional` method that fails for two independent reasons. PostgreSQL: after any failed statement the transaction is aborted, and every later statement, even a SELECT, is rejected ("current transaction is aborted, commands ignored until end of transaction block") until it ends. Spring: when a `@Transactional` method throws, the transaction is flagged rollback-only, and if you swallow the exception and return normally, the commit throws `UnexpectedRollbackException` (in the same transaction the flag survives). So the read of the stored answer must run after the failed transaction has ended and in a new one. That is why `IdempotentExecutor` is not `@Transactional`: it starts the transaction with a template, lets it roll back, and reads the answer in the `catch`. See docs/INTERVIEW_PREP.md, entry 16, and docs/DECISIONS.md, entry 27.

### Traps to know (the first was actually hit)

- **Several Spring contexts, one database, 100 connections.** Every distinct test configuration creates its own Spring context, each with its own connection pool. Hikari keeps `maximum-pool-size` connections open by default even when idle, and PostgreSQL in the container allows 100. The new clock configuration added another context, and the first full run failed 13 `LedgerApiIntegrationTest` tests because that context could not open a database connection at startup (a `PSQLException` in `ConnectionFactoryImpl`; the console summary did not print the message text, so "connection slots exhausted" is the diagnosis, supported by the fix working, not a quoted error). Fix in `application-test.yml`: `minimum-idle: 2` and `idle-timeout: 10000`. The pool size of 20 that the concurrency tests need is unchanged.
- **Every existing call to the two endpoints needed a header.** Once `Idempotency-Key` is required, the Phase 3 API tests would get 400 on every deposit and transfer, so their `postJson` helper now sends a fresh key on each call.
- **A test that only checks the count can pass without any overlap.** A 20-thread test finishes in about 0.1 seconds, so it is not certain that duplicates were ever blocked. One test therefore holds the first transaction open and looks in `pg_stat_activity` for the duplicate's INSERT waiting on a transaction id.
- **JSONB is not byte-identical.** Compare replayed and original responses as parsed JSON.

## Phase 6: the pieces used for the first time

- **`spring-boot-starter-security` locks everything by default.** Adding the dependency alone makes every URL demand a login and prints a random password in the log. `SecurityConfig` replaces that with a `SecurityFilterChain` bean (the Security 6 way; `WebSecurityConfigurerAdapter` and `antMatchers` no longer exist). `UserDetailsServiceAutoConfiguration` is excluded in application.yml so the random default user is never created.
- **The filter chain.** Every HTTP request passes through an ordered list of filters before reaching a controller. `JwtAuthenticationFilter` is inserted into that list with `addFilterBefore(...)`. It extends `OncePerRequestFilter`, which guarantees one run per request. It is created by `new` in `SecurityConfig` and is NOT a `@Component`: Spring Boot registers every `Filter` bean as a servlet filter on its own, so the filter would run twice.
- **Security errors are not seen by `ApiExceptionHandler`.** The chain runs before Spring MVC, so 401 and 403 responses are written by hand in `ProblemJsonSecurityHandlers` (an `AuthenticationEntryPoint` for 401 and an `AccessDeniedHandler` for 403), in the same problem+json shape.
- **`SecurityContextHolder`.** A thread-local that holds "who is this request". The filter fills it, `CurrentUserProvider` reads it, and Spring clears it after the request. Services never touch it.
- **`@ConfigurationProperties` on a record, with a secret.** `JwtProperties` is bound from `ledger.jwt.*`. The environment variable `LEDGER_JWT_SECRET` maps onto `ledger.jwt.secret` by Spring's relaxed binding (upper case, underscores for dots), so there is no `${...}` placeholder in application.yml and nothing to accidentally default.
- **Records print all their fields in `toString()`.** A password or token in a record would appear in any log line that prints it. The records that hold secrets override `toString()`.
- **`saveAndFlush` for a unique column.** Registration flushes the INSERT immediately so a duplicate-username violation surfaces inside the `try` and becomes a clean 409; without the flush it would appear later, at commit.
- **`OutputCaptureExtension`.** A JUnit extension that captures what the application writes to the console during a test, used to assert that no password or token is ever logged.
- **`ApplicationContextRunner` and `SpringApplicationBuilder`.** Two ways to start a Spring context inside a test and expect failure: the runner is fast and only loads the classes you list; the builder starts the real application (used to check the real startup error message).

### How to try the API by hand

```bash
export LEDGER_JWT_SECRET="$(openssl rand -base64 48)"    # or any 32+ character text
docker compose up --build -d --wait
curl -s -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' -d '{"username":"alice","password":"correct-horse-battery"}'
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"username":"alice","password":"correct-horse-battery"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
curl -s -X POST localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"Main"}'
docker compose down -v
```

### Traps to know

- **Adding Security breaks tests that assumed open URLs.** Two smoke tests expected 404 for an unknown path and for `/actuator/env`; an unauthenticated caller now gets 401 for every URL that is not explicitly open (including ones that do not exist), so those tests send a token to reach the 404, and a new test pins the 401.
- **Tests use real tokens, not `@WithMockUser`.** `@WithMockUser` skips the filter, so it would never test token parsing, expiry or tampering. `AuthTestClient` registers and logs in through the real endpoints.
- **The shared test clock and token lifetime.** Some idempotency tests move the shared test clock forward 25 hours; the test profile therefore uses a 2 day token lifetime so earlier tokens survive, and the expiry test moves the clock past whatever lifetime is configured.
- **Spring's validation error for a short secret prints the secret.** See docs/INTERVIEW_PREP.md, entry 24.
