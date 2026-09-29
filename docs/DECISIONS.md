# Decisions

Every non-obvious decision is recorded here with the options considered, the choice and the trade-off. Later phases append new entries; an entry is not rewritten when a decision changes, a new entry supersedes it. Entries 1 to 11 are from Phase 1 (setup and toolchain), entries 12 to 17 from Phase 2 (schema, triggers and domain model), entries 18 to 22 from Phase 3 (accounts, deposits and transfers), entries 23 to 26 from Phase 4 (locking, reconciliation and the concurrency tests), entries 27 and 28 from Phase 5 (idempotency).

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

## 12. Immutability by triggers, including TRUNCATE, not by privileges alone

**Phase:** 2 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Ledger entries and the audit log must never change (invariant 4). Something has to stop an UPDATE or DELETE that does not go through the Java code.

**Options**
- Trigger functions that raise an error on UPDATE, DELETE and TRUNCATE.
- `REVOKE UPDATE, DELETE, TRUNCATE` from the application database role.
- Both.

**Choice**
Triggers only, in V3: a row-level `BEFORE UPDATE OR DELETE` trigger and a statement-level `BEFORE TRUNCATE` trigger on each of `ledger_entries` and `audit_log`. A row-level trigger does not fire for TRUNCATE, so without the second trigger `TRUNCATE ledger_entries` would empty the ledger; the tests would catch that. The trigger raises SQLSTATE `P0001`, and tests assert the SQLSTATE and message through JDBC (Spring does not translate it to a specific exception class). Java adds a second layer: the entities are `@Immutable` and the repositories extend `Repository`, not `JpaRepository`, so there is no delete method to call.

**Trade-off**
Compose runs the application as the database owner, so the owner can still run `ALTER TABLE ... DISABLE TRIGGER` or drop the table. The triggers stop application bugs and casual SQL, not a privileged DBA, and the README says so. Revoking the privileges from a separate low-privilege application role is the production hardening step and is deferred because it needs a second database role and migration user.

**Revisit if**
The service is ever run against a shared or production database: then add the separate application role and REVOKE.

## 13. VARCHAR plus CHECK for currency and enumerations, not CHAR(3) or native enums

**Phase:** 2 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
`ddl-auto=validate` compares the entity mapping with the real column types.

**Options**
- `CHAR(3)` for currency and native PostgreSQL `ENUM` types.
- `VARCHAR(3)` with `CHECK (currency ~ '^[A-Z]{3}$')`, and `VARCHAR` with a `CHECK ... IN (...)` mapped with `@Enumerated(EnumType.STRING)`.

**Choice**
`VARCHAR` plus `CHECK`. A `CHAR(3)` column is reported by PostgreSQL as `bpchar`, and Hibernate validation fails with `found [bpchar (Types#CHAR)], but expecting [varchar(255) (Types#VARCHAR)]`. Native enums need extra mapping. A `CHECK` gives the same protection, and a new value is added with a normal migration. Timestamps are `TIMESTAMPTZ` mapped to `Instant`.

**Trade-off**
Enum names live in two places (the Java enum and the CHECK), so adding a value needs a migration and a code change; the round-trip tests catch a mismatch. Validate is lenient in some cases (it accepts a primitive `long` on a nullable column and an `Instant` on a plain `timestamp`), so the round-trip tests, not validate, are what prove the mappings.

**Revisit if**
The list of enum values starts changing often.

## 14. Identity ids for entries, keys and audit rows; UUIDs for users, accounts and transactions

**Phase:** 2 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Every table needs a primary key, and Hibernate must be able to insert without surprises.

**Options**
- UUIDs everywhere.
- `BIGINT GENERATED ALWAYS AS IDENTITY` for high-volume append-only tables, UUIDs for the rest.

**Choice**
UUIDs (`GenerationType.UUID`) for `users`, `accounts` and `ledger_transactions`, because ids are exposed in the API and should not be guessable or countable. Identity columns for `ledger_entries`, `idempotency_keys` and `audit_log`. With an assigned id, Spring Data `save()` first runs a SELECT to decide between insert and update; with an identity column it just inserts. `GENERATED ALWAYS` means the application can never supply an id, and a test checks that. The mapping must be `GenerationType.IDENTITY`; the default `AUTO` fails validation with `missing sequence [ledger_entries_SEQ]`.

**Trade-off**
Two id styles in one schema. Identity ids also give a cheap "newest first" order for history (`ORDER BY id DESC`), though under heavy concurrency identity order can differ slightly from commit order.

**Revisit if**
Entry ids ever need to be exposed publicly.

## 15. System accounts have fixed ids and no cached balance

**Phase:** 2 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Every deposit needs a counter-side account representing the outside world. If it kept a cached balance, every deposit would update and lock the same row.

**Choice**
Two SYSTEM accounts, `EXTERNAL_FUNDING` and `EXTERNAL_PAYOUTS`, are inserted by migration V4 with fixed ids (`...0001`, `...0002`), mirrored by `SystemAccountIds` constants that a test checks against the database. `balance_minor` is NULL for them (enforced by `ck_accounts_balance_shape`), they have no owner (`ck_accounts_owner_shape`), and they are never locked or updated. Their balance, when needed, is derived by summing entries. `EXTERNAL_PAYOUTS` is reserved for withdrawals, a deferred requirement, and has no consumer yet.

**Trade-off**
Their balance is a sum over all their entries, so it is slower to read; that is acceptable because nothing reads it on a hot path. Boxed `Long` is required for the nullable balance in Java.

**Revisit if**
Withdrawals are dropped for good (then remove `EXTERNAL_PAYOUTS` in a new migration).

## 16. Test isolation: unique data per test, because the ledger cannot be cleaned

**Phase:** 2 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
The immutability triggers block DELETE and TRUNCATE, so the usual "empty the tables after each test" is impossible, and all test classes share one PostgreSQL container.

**Options**
- (a) Weaken or drop the triggers in tests, then clean up.
- (b) Every test creates its own users, accounts and transactions with unique ids and asserts only on that data.
- (c) A fresh database per test class: Flyway `clean()` then `migrate()` with `spring.flyway.clean-disabled=false` in that test only, or `CREATE DATABASE` inside the same container with a sibling base class.

**Choice**
(b) is the default, shown by `LedgerTestData` and `TestIsolationIntegrationTest` (two tests of the same shape that pass in any order). Never assert on whole-table counts. The production triggers are never weakened. Tests that must inject bad data use `ReplicaRole.asReplica`, which runs one transaction with `SET LOCAL session_replication_role = replica` (triggers and foreign keys off, CHECK constraints still on, reverts automatically) and cleans up in a `finally`. Option (c) was verified in research but not built here; it is for later whole-ledger checks that need a pristine database.

**Trade-off**
The database accumulates test data during a run, so no test can assume the ledger is empty. Replica mode needs a superuser, which the Testcontainers user is. Flyway `clean()` wipes the shared database, so it is only safe while test classes run one at a time, which is Gradle's default.

**Revisit if**
Test parallelism is turned on, or a whole-ledger sum check is needed (use (c)).

## 17. The zero-sum rule is enforced by the service, not the database

**Phase:** 2 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Invariant 1 says every transaction's entries sum to zero. A deferrable constraint trigger could enforce that in the database.

**Choice**
Not built (a stretch item). The posting service (Phase 3) creates balanced entries and reconciliation (Phase 4) verifies every transaction sums to zero. The database does enforce the parts that are simple constraints: non-zero amounts, non-negative customer balances, at-most-once reversal, at-most-once idempotency key. One rule was added beyond the brief: `ck_ledger_tx_reversal_shape`, so exactly REVERSAL transactions carry a `reverses_transaction_id` and none points at itself.

**Trade-off**
A bug in the service could write an unbalanced transaction and the database would accept it; reconciliation would detect it afterwards.

**Revisit if**
There is time for the stretch trigger.

## 18. AccountLockService: final call shape, deliberately naive body until Phase 4

**Phase:** 3 | **Date:** 2026-09-29 | **Status:** Accepted, body replaced in Phase 4

**Context**
Ordered row locking is the centre of the project and is built and proven in Phase 4 with a concurrency test. Phase 3 needs deposits and transfers to work now, and Phase 4's change should be one method body, not a refactor of every caller.

**Options**
- Build the locking now.
- Leave locking out and let the services load accounts directly (Phase 4 then rewrites every service).
- Create `AccountLockService.lock(Set<UUID>)` now in its final shape with a plain, non-locking read as its body.

**Choice**
The third. Every service already calls `lock` first, never loads an account before it, checks ownership with `existsByIdAndOwnerUserId` (no entity loaded) and works only with the accounts `lock` returns. The class comment says "intentionally naive until Phase 4". It returns CUSTOMER accounts only; a missing id or a system account id is a 404. Phase 4 replaces the body with `SELECT ... WHERE id IN (...) AND type = 'CUSTOMER' ORDER BY id FOR UPDATE`, and the concurrency test that is expected to fail today should then pass.

**Trade-off**
Until Phase 4 the service can lose updates under concurrent requests on the same account, and the docs say so. In exchange Phase 3 has no locking code to explain twice, and the red-then-green evidence pair is possible.

## 19. One write path: LedgerPostingService creates entries and changes cached balances

**Phase:** 3 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Two invariants must hold in exactly one place to be believable: every transaction sums to zero, and a cached balance changes only together with its entries.

**Choice**
`LedgerPostingService.post` is the only code that inserts `ledger_transactions` and `ledger_entries` and the only code that calls `Account.applyDelta`. It rejects fewer than two lines, a zero line, a non-zero sum (added with `Math.addExact`), a customer line whose account was not passed in as locked, a currency mismatch, and any result that would leave a customer balance negative. System-account lines are skipped for the balance update (they have no cached balance). It is `Propagation.MANDATORY`. Balance changes go through the entity (`applyDelta`, written by Hibernate at flush) rather than an SQL increment, so that read-check-write really depends on the lock and the Phase 4 test can show a real race.

**Trade-off**
The posting service knows about accounts as well as entries. The alternative (each service updates balances itself) would spread the "balance moves with entries" rule over every operation, including the reversals to come.

## 20. Status codes: what is a 404, a 400 and a 422

**Phase:** 3 | **Date:** 2026-09-29 | **Status:** Accepted

**Choice**
400: the request is wrong by itself (missing, non-integer or non-positive amount, above the configured maximum, currency not GBP, from equals to, unreadable JSON, missing or malformed acting-user header). 404: the account does not exist, belongs to someone else (paying or deposit account), or is a system account; these are deliberately indistinguishable so the API does not reveal which ids exist. 422: the request is well formed but cannot be done: insufficient funds (checked only after the locks), or an account that is CLOSED or in a different currency. Unexpected errors are a generic 500 problem with no detail (the stack trace goes to the log only). The destination of a transfer may belong to anyone; only the paying account must be the caller's.

**Trade-off**
A caller cannot tell "no such account" from "not yours", which makes debugging slightly harder and enumeration harder. Closed and currency-mismatch use 422 rather than 409 because nothing conflicts with existing state; the operation is simply not allowed for that account.

## 21. The acting user is a header stub with a dev-profile fixture

**Phase:** 3 | **Date:** 2026-09-29 | **Status:** Superseded in Phase 6 (stub and fixture deleted; see entry 29)

**Choice**
Controllers read `X-Acting-User-Id`, build an `ActingUser` record (marked `TODO(Phase 6)`) and pass it to the services; no service reads a security context. The user must already exist in `users`. A real users row is created by `DevStubUserSeeder`, active only in the `dev` profile, and by test fixtures; no migration inserts a user, so nothing seeded ever reaches a real database.

**Trade-off**
Anyone can claim to be anyone until Phase 6. That is stated in the README and the class comments, and nothing here should be exposed beyond localhost.

## 22. Atomicity is proven by crashing after the flush, and the proof is itself checked

**Phase:** 3 | **Date:** 2026-09-29 | **Status:** Accepted

**Choice**
`AtomicityIntegrationTest` spies on the posting service, lets the real post run, flushes, checks that the entries and the new balance are visible inside the transaction, then throws. Afterwards, from another connection, the entries, the transaction row and the balances must be as before. To confirm the test is not vacuous, `@Transactional` was removed from the service methods (both tests failed with IllegalTransactionStateException because the helpers are MANDATORY) and then MANDATORY was loosened on the helpers as well (both tests still failed, because the flow no longer ran as one unit). Both edits were reverted.

**Scoping of the sum checks.** The shared test database also contains other test classes' deliberately corrupted and half-cleaned rows (entry 16), so "the ledger sums to zero" is asserted over the transactions created by the test's own user, not over the whole table. A whole-database check belongs to reconciliation in Phase 4.

## 23. Concurrency control: pessimistic row locks under READ COMMITTED, not optimistic locking or SERIALIZABLE

**Phase:** 4 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Two simultaneous transfers on one account each read the balance, check funds and write back; without protection one write silently overwrites the other (a lost update, recorded in docs/evidence/phase-4-red-naive-lock.txt). Something has to make read-check-write on an account effectively one step.

**Options**
- Pessimistic locking: `SELECT ... FOR UPDATE` on the accounts at the start of the transaction, so a competitor waits.
- Optimistic locking: a `@Version` column; a conflicting write fails at commit and the caller retries.
- SERIALIZABLE isolation for the whole transfer: PostgreSQL detects conflicting patterns and aborts one transaction with a serialisation error, and the caller retries.

**Choice**
Pessimistic, at the default READ COMMITTED level. Money accounts are exactly the case with real contention (a payroll account, a shared account), where "wait your turn" is cheaper and simpler than "fail and redo". The funds check then runs on a row nobody else can change, so it is a plain `if`. There is no retry logic to write or get wrong. Deadlock is prevented by locking in one global order (entry 24), not handled after the fact.

**Trade-off**
Transactions on the same account queue, so throughput on one hot account is limited to one transfer at a time; locks are held until commit, so the transaction must stay short. Optimistic locking would allow more parallelism when conflicts are rare but needs retry code and produces user-visible failures under contention. SERIALIZABLE is the most general and needs no explicit locks, but it aborts more work, needs retries everywhere, and hides which rows matter, which is harder to explain. The 5 second `lock_timeout` on every connection (application.yml) turns an unexpectedly long wait into an error rather than a hang.

**Revisit if**
Accounts are rarely contended and throughput matters more than simplicity (then optimistic with bounded retries), or the workload grows beyond one database (then the locking model changes entirely).

## 24. The lock is one native query with ORDER BY id, and it is the first load of the accounts

**Phase:** 4 | **Date:** 2026-09-29 | **Status:** Accepted

**Choice**
`AccountRepository.lockCustomerAccountsOrderedById` is a native query: `SELECT * FROM accounts WHERE id IN (:ids) AND type = 'CUSTOMER' ORDER BY id FOR UPDATE`. One statement locks every requested account, and PostgreSQL takes the row locks in the order it returns the rows, so all transactions lock the same accounts in the same order and cannot deadlock on each other. The ordering is done by the database, never by sorting UUIDs in Java, because Java's `UUID.compareTo` (signed 64-bit halves) and PostgreSQL's `uuid` ordering (unsigned bytes) can disagree; a test compares the returned order with PostgreSQL's own. Native SQL, not JPQL with a lock mode, so the text in the source is what reaches the database; a test captures the emitted statement.

The query must be the first load of these accounts in the transaction: Hibernate caches loaded entities per transaction, and a query that finds an entity already loaded locks the row but returns the old in-memory copy, with a stale balance. The services therefore check ownership with `existsByIdAndOwnerUserId` (a yes/no query that loads nothing) and check funds only on the accounts `lock()` returns. Fewer rows than requested ids (missing, or a system account) gives the existing 404.

**Trade-off**
The "first load" rule is a convention held by the call order and the comments, not enforced by the type system; the concurrency showcase would catch a violation because balances would drift. Native SQL is tied to PostgreSQL. `SELECT *` relies on the entity matching the table, which `ddl-auto=validate` already checks at startup.

## 25. Reconciliation is set-based and runs in one REPEATABLE READ, read-only transaction; tests scope it

**Phase:** 4 | **Date:** 2026-09-29 | **Status:** Accepted

**Choice**
`ReconciliationService` runs aggregate queries (per-transaction sums, the global entry sum, cached versus ledger-derived balance per customer account, negative balances) in one `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`. Under READ COMMITTED each query would see a different moment, so a transfer committing between two of them could make a healthy ledger look broken; REPEATABLE READ gives all of them the same snapshot without blocking writers. The accounts query uses a LEFT JOIN so an account with no entries (but a wrong cached balance) is still checked. The negative check looks at the ledger-derived balance too, because the cached balance cannot be negative (a CHECK stops it). There is no HTTP endpoint (out of the lean scope).

**Scoping, stated honestly.** The shared test database holds other tests' deliberately unbalanced rows (entry 16), so a whole-ledger check cannot be asserted clean in the test suite. `ReconciliationScope.of(accountIds, transactionIds)` restricts the checks to the ids a test created; `wholeLedger()` is what a real run would use, and in the tests its unscoped queries are only checked for running and reporting the isolation level. The scoped tests still detect each kind of injected corruption (using the ReplicaRole helper, then cleaned up).

**Trade-off**
A long reconciliation over a large ledger holds a snapshot open (it delays vacuum) and scans whole tables; for a large system it would run against a replica or per time window. Reports keep at most 100 examples per check.

## 26. The concurrency tests must be able to fail, and the ways they fail are test-scope only

**Phase:** 4 | **Date:** 2026-09-29 | **Status:** Accepted

**Choice**
Process: the showcase test was committed and run against the naive lock body first (RED, commit e0e87fa, evidence in docs/evidence/phase-4-red-naive-lock.txt), and only then was the ordered `FOR UPDATE` committed (GREEN, commit 8c4946b). Design rules taken from the pitfalls list: a start gate so threads really begin together, a seeded Random for the inputs (only the interleaving is left to chance), every Future read with a timeout, the Hikari pool (20) at least the thread count (16), `@Timeout` on each test, and assertions that at least one transfer succeeded and that some were refused, so an all-rejected run cannot pass. Broken lock services (no lock; locks in alternating order) live in `src/test` and are switched on by importing a `@TestConfiguration` into one test class; there is no property, profile or flag in the shipped code that turns locking off. The deadlock test has a mutation partner that must see PostgreSQL abort transactions with SQLSTATE 40P01, and a `@Disabled` locking-off demonstration re-runs the showcase with the naive lock (run once by hand, result recorded).

**Trade-off**
The concurrency tests take a few seconds (the deadlock mutation test about 15 s, because PostgreSQL waits `deadlock_timeout`, 1 s by default, before resolving each deadlock). Thread interleaving is not reproducible, so a passing run is evidence rather than proof; the tests are repeated (docs/CV_EVIDENCE.md gives the count) and the mechanism is separately proven by the lock-service tests (a NOWAIT probe from a second session sees the row locked).

## 27. Idempotency is a unique-constraint guarantee in a non-transactional executor, not a pre-check and not ON CONFLICT

**Phase:** 5 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
A retried or duplicated deposit or transfer must move money once. Duplicates can arrive at the same instant, so whatever detects them must be race-free.

**Options**
- Check then insert: SELECT the key, and insert it if absent. Two requests can both see "absent" (a race), so it is not a guarantee.
- `INSERT ... ON CONFLICT DO NOTHING` and look at the row count: race-free, no exception, so the transaction stays usable and the stored response can be read in it.
- Insert the key row first and let the unique constraint `uq_idempotency_user_key` reject the second one: race-free; the second insert waits for the first transaction to finish, then fails.

**Choice**
The third. `IdempotencyService.begin` inserts `(user_id, idem_key, request_hash)` first, inside the business transaction, with `saveAndFlush` and a database-generated id (so no pre-SELECT is issued and the violation is raised at that line). `IdempotentExecutor`, which is not `@Transactional`, wraps the unchanged deposit and transfer services in a `TransactionTemplate`: begin, work (the service joins the transaction), complete, commit. On a duplicate the template has already rolled back and ended; only then does the `catch` call `replay`, a read-only transaction that returns the stored status and JSON, or 422 if the request hash differs. The duplicate is recognised only when the SQLSTATE is 23505 AND the constraint name is `uq_idempotency_user_key` (Hibernate's `ConstraintViolationException` carries both); any other error is rethrown. The executor refuses to run inside an open transaction, and there is no `REQUIRES_NEW`. Alternatives to putting the logic in the services (threading a key through both business methods) were rejected because it would change Phase 3 code and mix two concerns.

**Trade-off**
A duplicate holds a database connection while it waits for the first transaction, so the pool must cover the number of simultaneous duplicates (the tests use a pool of 20 for 20 threads), and the 5 second `lock_timeout` bounds the wait. `ON CONFLICT DO NOTHING` would avoid the aborted transaction; the exception path was chosen because it is the well-known pitfall (docs/LEARNING_NOTES.md) and forces the design to be explicit about transaction boundaries. Endpoints must remember to call the executor.

**Revisit if**
Waiting duplicates hurt (return 409 "in progress" instead), or many more money endpoints appear (move the executor call into a filter or annotation).

## 28. Failed attempts are not stored; the request hash covers method, path and validated DTO; expired keys are reclaimed by delete-then-insert

**Phase:** 5 | **Date:** 2026-09-29 | **Status:** Accepted

**Choice**
- *Failures are not replayed.* The key row lives in the business transaction, so a failed attempt (insufficient funds, unknown account, any exception) rolls the row back and a retry with the same key runs again. Simple and crash-safe; it means a client can get a different answer on retry (the account may have been topped up). Recorded rather than hidden.
- *Fingerprint.* `request_hash` is SHA-256 of `METHOD \n concrete path \n JSON of the validated request DTO with alphabetically sorted properties`, computed with a private Jackson mapper so application-wide JSON settings cannot change stored hashes. It is computed from the DTO, so field order and whitespace in the incoming JSON do not matter, and an absent optional field equals an explicit null. The concrete path (the URL actually called) is included so a future `/transactions/{id}/reversal` with two different ids under one key cannot replay each other. Same key, different fingerprint: 422.
- *Key format.* 1 to 128 characters from letters, digits and `_ . : -`; anything else, or no header, is a 400. The character set keeps odd characters out of logs and keys.
- *Expiry.* `ledger.idempotency-ttl` (default 24h, a `Duration`), `expires_at` set from the injected `Clock`. An expired row still occupies the unique index, so `begin` first deletes an expired row for that (user, key) in the same transaction and then inserts. If two requests do this together, the second delete finds nothing and the unique constraint still selects one winner. `replay` treats an expired or vanished row as absent and the executor tries again (at most three times). Tested with a movable test clock at 23 hours (replayed), 24h01 and 25 hours (reused, including with a different body).
- *Stored response.* A JSONB column written from the JSON text the response was built from. JSONB reorders keys and adds spaces, so a replay is equal to the first response as JSON data, not byte for byte (tests compare parsed JSON; two replays are textually identical because both are read from the same column).

**Trade-off**
No cleanup job, so expired rows stay until their key is reused (a scheduled delete is a small later addition). A 24 hour window means a retry after a longer outage is treated as new. Hashing the DTO means two bodies the server treats identically (for example a null and an omitted `reference`) are the same request, which is the intent.

## 29. Authentication: jjwt with one hand-written filter, stateless, HS256, secret only from the environment

**Phase:** 6 | **Date:** 2026-09-29 | **Status:** Accepted

**Context**
Every request must come from a known user, and the id the services act for must come from something the client cannot choose.

**Options**
- Spring Security session login (cookie): needs CSRF protection and server-side session state.
- `spring-boot-starter-oauth2-resource-server` with Nimbus: less code to write, but more configuration to explain and built for an external token issuer.
- jjwt plus a small `OncePerRequestFilter`: a few dozen lines, every step visible.

**Choice**
The third (as in research/STACK.md). `JwtService` signs HS256 tokens with subject = user id and a `role` claim, and verifies with the injected `Clock`. `JwtAuthenticationFilter` sets the security context or leaves it empty; the authorization rules then produce 401 (`ProblemJsonSecurityHandlers`, as `application/problem+json`). Only `/api/auth/**` and `GET /actuator/health` are open; sessions are stateless; CSRF, form login and basic auth are off. The filter is created inside `SecurityConfig` and is not a `@Component`, so it is not also registered as a plain servlet filter (double execution). `CurrentUserProvider` builds the `ActingUser` the services already took; the header stub, `StubActingUser` and `DevStubUserSeeder` are deleted. The secret is bound from `LEDGER_JWT_SECRET` into `JwtProperties` with no default anywhere; a missing or under-32-character value stops startup, with a message that does not contain the value (Spring's own validation message would). Passwords are BCrypt (cost 10, plain encoder, no prefix), usernames are case-insensitive and stored lower-case, passwords over 72 bytes are refused (BCrypt ignores the rest), and login answers wrong-password and unknown-user identically (401) after the same amount of BCrypt work. Duplicate usernames are 409, decided by the `uq_users_username` unique constraint (the exists-check only gives the friendly path; a concurrency test fires 8 registrations of one name and gets exactly one 201).

**Trade-off**
No revocation or refresh: a token is valid until it expires (default 1 hour), and the role in the token changes only at the next login. The registration response reveals that a username is taken. No rate limiting on login or register. An HMAC secret shared by signer and verifier is fine for one service.

**Revisit if**
Another service must verify tokens (switch to an asymmetric key or a resource server), users need logout, or the API is exposed to the internet (rate limiting, refresh tokens, HTTPS).

## 30. Another user's account is a 404, enforced in the services

**Phase:** 6 | **Date:** 2026-09-29 | **Status:** Accepted (the rule dates from Phase 3, decision 20; Phase 6 makes it real and tests it with real tokens)

**Choice**
A logged-in user asking for someone else's account, or depositing into it, or transferring out of it, gets the same 404 problem+json as for an id that does not exist. A transfer INTO another user's account is allowed. The checks stay in `AccountService`, `DepositService` and `TransferService` (not in the controllers or in URL rules), so they hold for any caller of the services. No URL returns 403 in this build; the 403 handler exists for when role-restricted URLs (an admin area) are added, which the lean scope leaves out.

**Trade-off**
"Not allowed" and "does not exist" look the same to the client, which slightly hinders debugging.
