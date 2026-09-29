# Decisions

Every non-obvious decision is recorded here with the options considered, the choice and the trade-off. Later phases append new entries; an entry is not rewritten when a decision changes, a new entry supersedes it. Entries below are from Phase 1 (setup and toolchain).

## 1. Stay on Spring Boot 3.5.16, which is past open-source end of life

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Spring Boot 3.5.16 was released on 2026-06-25 and is the final 3.x release. Open-source support for the 3.5 line ended on 2026-06-30, so it no longer receives free security patches. The supported line is Spring Boot 4.1.x (Spring Framework 7, Jackson 3, Testcontainers 2, Spring Security 7). The end-of-life dates come from secondary sources (checked 2026-09-29), not from an official support page fetched directly, so treat them as accurate to within a few days.

**Options**
- Spring Boot 3.5.16 (final 3.x).
- Spring Boot 4.1.x (supported line), at the cost of Jackson 3 package names, renamed Testcontainers 2 artifacts, Security 7 changes and a re-check of the JWT library.

**Choice**
Stay on 3.5.16. The project brief says Spring Boot 3; most tutorials, Stack Overflow answers and interview material use the Boot 3 API surface; jjwt 0.13.0 binds to Jackson 2, which Boot 3.5 uses; and one learning curve is enough for a beginner who has to defend every line. The choice was made at the start of Phase 1, not mid-build.

**Trade-off**
No free security patches for the framework, so this is acceptable only for a learning project that handles no real money and is never exposed as a service. The README says so. The upgrade to Spring Boot 4.1.x is listed as a next step (requirement STRETCH-06).

**Revisit if**
The project is ever deployed anywhere reachable, or after the core phases are finished and there is time for the migration.

## 2. Gradle with the Kotlin DSL, wrapper 8.14.5

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
The brief (section 6) asks for the build tool choice to be recorded here.

**Options**
- Gradle with the Kotlin DSL (`build.gradle.kts`).
- Gradle with the Groovy DSL (`build.gradle`).
- Maven (`pom.xml`).

**Choice**
Gradle Kotlin DSL. The wrapper is pinned to Gradle 8.14.5 with a distribution checksum, so every machine and CI downloads the same verified build tool. Gradle 9 exists but is not in Spring Boot 3.5's documented support list, so the documented option was taken.

**Trade-off**
The Kotlin script is type-checked and completes in the IDE, but more online examples are written in Groovy DSL or Maven, so some snippets need translating. Gradle 8.14.5 will need a bump if the project later moves to Boot 4.

**Revisit if**
The project moves to Spring Boot 4.1.x.

## 3. Base package dev.joseph.ledger with the brief's layout

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
The brief (section 6.1) lays out six packages: api, domain, service, repository, security, config. Git does not track empty directories, and renaming a Java package later touches every file.

**Options**
- Base package `dev.joseph.ledger` with all six sub-packages created now.
- A shorter or different base package (for example `com.example.ledger`).
- Create sub-packages only when the first class is needed.

**Choice**
`dev.joseph.ledger`, with the six sub-packages created now. Each holds a `package-info.java` file (a comment-only file) that states what the package will contain and which phase fills it, which also lets git track the directories.

**Trade-off**
Six near-empty files exist for a few phases. Renaming the base package later would be a whole-project change, so it was chosen once, up front.

## 4. Flyway V1 is a baseline marker, so real migrations start at V2

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Phase 1 has no tables, but the app must start with Flyway enabled and Hibernate on `ddl-auto=validate`, and a test should prove that Flyway actually ran.

**Options**
- An empty migrations directory (Flyway then applies nothing and there is nothing to assert).
- A fake entity and table only to give the schema something (invents Phase 2 design).
- A marker migration: `V1__baseline.sql` containing a single `COMMENT ON SCHEMA public`.

**Choice**
The marker migration. It creates no ledger tables, and a test reads `flyway_schema_history` and the schema comment to prove the migration ran.

**Trade-off**
Flyway records checksums of applied migrations, so `V1__baseline.sql` is final once committed and must never be edited. Phase 2's migrations therefore start at `V2`. If a migration needs to change after it has been applied, add a higher-numbered migration instead.

## 5. lock_timeout of 5 seconds set through Hikari connection-init-sql

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
PostgreSQL waits forever for a row or table lock by default (`lock_timeout` is 0). The ledger's locking design in Phase 4 needs a stuck lock to fail rather than hang.

**Options**
- JDBC URL option: `options=-c lock_timeout=5000` (works, but the Testcontainers URL already contains `?loggerLevel=OFF`, so the option must be appended with `&`, and the Compose URL would need the same edit).
- Hikari `connection-init-sql` in `application.yml`: `SET lock_timeout = '5s'` runs on every new pooled connection.
- `ALTER DATABASE ... SET lock_timeout` (persists in the database, but is invisible in the repository and not applied by the test container unless a migration or script does it).

**Choice**
`connection-init-sql`. One mechanism in one file serves tests and Compose, and it is visible in the repository. A test asserts pooled connections report `5s`, and another proves a real blocked lock wait fails with SQLSTATE `55P03`.

**Trade-off**
It limits lock waits only, not long-running statements; `statement_timeout` is not set. Five seconds is generous, and Phase 4 may lower it in `application-test.yml` for tests that contend on purpose.

## 6. Strict runtime defaults from day one

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Some Spring defaults are convenient for tutorials but wrong for a ledger, and changing them later is harder than starting strict.

**Options**
- Keep Spring's defaults and tighten later.
- Set the strict values now and assert them in a test.

**Choice**
Set now, asserted in `ToolchainSmokeIntegrationTest`: `ddl-auto=validate` (Flyway owns the schema, Hibernate only checks); `open-in-view=false` (no persistence context or connection held for the whole HTTP request, and no stale entities handed to later lock queries); RFC 7807 problem details on for framework errors; an explicit Hikari pool (10 for the app, 20 in tests so the Phase 4 concurrency tests never queue for a connection while holding locks); the Actuator exposes `health` only.

**Trade-off**
Some things Spring would do for free need explicit code later (lazy loading outside a transaction fails instead of silently working). That friction is intended.

## 7. Test database: a Testcontainers singleton with @DynamicPropertySource

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
The project's value is correctness under real row locks, isolation levels and database triggers, and each invariant must be proven against real PostgreSQL.

**Options**
- An embedded database such as H2 (rejected: no real row locks, isolation behaviour or trigger semantics; a test asserts H2 is not on the classpath).
- A Testcontainers container per test class through the JUnit lifecycle annotations (the container restarts on a new port per class, but Spring caches the application context with the old JDBC URL, so later classes fail with connection errors).
- `@ServiceConnection` from `spring-boot-testcontainers` (shorter, but hides how the datasource URL reaches Spring).
- One container started in a static initialiser of a shared base class, wired in with `@DynamicPropertySource`.

**Choice**
The static-initialiser singleton with `@DynamicPropertySource`, because it is explicit and shows exactly how the URL reaches Spring. No container reuse across runs (`withReuse` is not used), so each run gets a fresh database and CI behaves like a laptop.

Docker Engine 29 outcome: Testcontainers 1.21.4 connected to Docker Engine 29.8.1 directly, with no "client version 1.32 is too old" error. The `docker-java.properties` fallback (`api.version=1.44`) is a documented contingency and was not needed, so the file is not committed.

**Trade-off**
All integration test classes share one database for the whole test run, so later phases must scope assertions to data each test creates, or reset the schema deliberately.

## 8. One pinned PostgreSQL image tag, enforced by a test; Temurin images pinned to noble

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Tests that pass on one PostgreSQL version say little about the version the Compose stack runs. Separately, the untagged `eclipse-temurin:21-jre` tag now resolves to Ubuntu 26.04.

**Options**
- Separate tags in Compose and tests, kept in step by hand.
- One tag in both places, with an automated check.
- Floating tags (`postgres:16`, `eclipse-temurin:21-jre`).

**Choice**
`postgres:16.15-alpine` in both `docker-compose.yml` and the test base class, with `PostgresImageTagConsistencyTest` failing the build if they differ (checked in both directions in Plan 01-02). The Dockerfile uses `eclipse-temurin:21-jdk-noble` to build and `eclipse-temurin:21-jre-noble` to run, pinning Ubuntu 24.04. `docker-compose.yml` is declared as a Gradle test input so the check re-runs when only that file changes.

**Trade-off**
Bumping PostgreSQL is a deliberate two-place edit (the test then guards it). Pinned tags need a manual bump to pick up new patch releases.

## 9. Compose defaults: dev credentials, loopback ports, health-gated start, non-root user

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
`docker compose up` should work from a fresh clone with no setup, without exposing a database with a default password to the network.

**Options**
- Default credentials through `${VAR:-default}`, with `.env.example` documenting overrides.
- Required variables (`${POSTGRES_PASSWORD:?}`), which forces a `.env` file and breaks the fresh-clone run.
- Publish ports on all interfaces, or only on loopback.

**Choice**
Defaults of `ledger` / `ledger` (local development only), overridable through a gitignored `.env`. Both ports are bound to `127.0.0.1` so the database and app are not reachable from the LAN. The app waits for the database with `depends_on` condition `service_healthy`, because Flyway runs at startup and would crash on a database that is not accepting connections. The app container runs as uid 10001 rather than root (the Compose smoke test asserts it).

**Trade-off**
The password default is public in the repository, which is acceptable only because the port is loopback-bound and the data is disposable. Phase 6 adds the JWT secret to `.env.example` with no usable default.

## 10. CI design: GitHub Actions, Testcontainers on the runner's Docker, inline count guard

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
CI must run the same real-PostgreSQL tests as a laptop and must not go green when no test ran. No GitHub remote exists yet, so the workflow has been checked locally only: CI workflow committed, not yet observed running.

**Options**
- A PostgreSQL `services:` block in the workflow, or Testcontainers using the runner's own Docker.
- A third-party test-report action, or an inline shell step reading `build/test-results/test/*.xml`.
- Action versions pinned to commit SHAs, or to major tags.

**Choice**
Testcontainers on `ubuntu-latest` (which has Docker), with no database service block, so CI runs the same code path as a laptop. An inline step counts tests from the result XML, fails the job if the count is zero (a JUnit Platform misconfiguration otherwise makes Gradle report success with zero tests) and emits a `::notice title=Test count::` annotation. The workflow sets `permissions: contents: read`. Only first-party actions are used, pinned to major tags: `actions/checkout@v7`, `actions/setup-java@v6`, `gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7`. The tags were looked up with `git ls-remote` on 2026-09-29 and no newer major (v8, v7, v7, v8 respectively) existed.

**Trade-off**
A major tag is mutable, so a compromised or buggy release of an action would be picked up automatically; SHA pinning would prevent that at the cost of manual updates. `gradle/actions` v6 moved caching into a component under separate terms: free for public repositories and a preview for private ones. It can be switched off with `cache-disabled: true` on the `setup-gradle` step if those terms are unwanted; this is a conscious choice, not an oversight. The Actions runtime behaviour (the multi-line upload path and the annotation) is unverified until the first real run.

**Revisit if**
The repository becomes private and the caching terms matter, or a security incident involves a first-party action.

## 11. Spring Security is not added in Phase 1

**Phase:** 1 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
The tech stack includes Spring Security with JWT, but no endpoint needs authentication in Phase 1.

**Options**
- Add `spring-boot-starter-security` now and configure a permit-all filter chain.
- Leave it off the classpath until the authentication phase.

**Choice**
Leave it off. Adding the starter would put every endpoint behind authentication by default, including `/actuator/health` and the Compose healthcheck (they would return 401), and would need a throwaway permit-all filter chain that Phase 6 deletes. Phase 6 adds Spring Security together with JWT.

**Trade-off**
The security wiring is not exercised until Phase 6, so any endpoint added in Phases 3 to 5 is unauthenticated until then. That is acceptable only because the stack is local development, the ports are loopback-bound and the phases are built in that order; nothing built before Phase 6 should be treated as secured.
