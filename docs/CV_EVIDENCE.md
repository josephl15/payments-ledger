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

## Phase 2: Schema, triggers and domain model (brief phase 1)

### Run details

Date: 2026-09-29
Commit tested: c6208c6 (working tree clean when the tests ran). Code commits: 5c81349 (migrations and SQL-level tests), 5433b6c (entities, repositories, mapping tests), c6208c6 (isolation strategy tests and corruption helper).

Commands run for this entry (Git Bash, Windows 11, `JAVA_HOME` set to the JDK 21 install and the Docker CLI on PATH):

- `./gradlew cleanTest test --console=plain` (output saved to docs/evidence/phase-2-test-output.txt, exit code 0, BUILD SUCCESSFUL)
- a script that read every `build/test-results/test/*.xml` file and summed the `tests`, `skipped`, `failures` and `errors` attributes per class, grouped by class-name suffix

### Test results

Integration tests (*IntegrationTest): 71 run, 0 skipped, 0 failed, 0 errors
Unit tests (*Test): 3 run, 0 skipped, 0 failed, 0 errors
Total: 74 tests, 0 skipped, 0 failed, 0 errors

Source: docs/evidence/phase-2-test-output.txt and build/test-results/test/*.xml after ./gradlew cleanTest test. The 11 Phase 1 tests (10 in `ToolchainSmokeIntegrationTest`, 1 in `PostgresImageTagConsistencyTest`) are unchanged and still pass; Phase 2 added 63 tests.

Per class (tests from the XML):

| Class | Tests | Category |
|-------|-------|----------|
| `LedgerConstraintsIntegrationTest` | 30 | integration |
| `EntityMappingIntegrationTest` | 11 | integration |
| `ToolchainSmokeIntegrationTest` | 10 | integration (Phase 1) |
| `ImmutabilityTriggerIntegrationTest` | 9 | integration |
| `SchemaMigrationIntegrationTest` | 6 | integration |
| `TestIsolationIntegrationTest` | 5 | integration |
| `ImmutableRepositoryShapeTest` | 2 | unit |
| `PostgresImageTagConsistencyTest` | 1 | unit (Phase 1) |

Trigger tests: `ImmutabilityTriggerIntegrationTest` has 9 tests. They cover UPDATE and DELETE on `ledger_entries` and `audit_log`, `TRUNCATE` of each table, `TRUNCATE ... CASCADE` reaching them from `ledger_entries`, `ledger_transactions`, `accounts` and `users`, and a multi-table `TRUNCATE`. Every rejected statement is asserted to fail with SQLSTATE `P0001` and the message "forbidden: the table is append-only", and the row is asserted unchanged afterwards. A further test in `TestIsolationIntegrationTest` shows the same UPDATE succeeding only inside `SET LOCAL session_replication_role = replica` and being rejected again afterwards.

### Completed

- SCHEMA-01: Flyway V2 creates the six tables; `SchemaMigrationIntegrationTest` checks versions 1 to 4 applied successfully, the tables, the named constraints and the indexes; `LedgerConstraintsIntegrationTest` (30 tests) rejects each broken row with the expected SQLSTATE and constraint name, including a zero amount, a second reversal of one transaction and a duplicate `(user_id, idem_key)`.
- SCHEMA-02: V3 triggers, tested as above.
- SCHEMA-03: V4 seeds `EXTERNAL_FUNDING` and `EXTERNAL_PAYOUTS` with fixed ids, NULL balance and NULL owner; checked in SQL (`SchemaMigrationIntegrationTest`) and through the repository, with `SystemAccountIds` compared to the database ids (`EntityMappingIntegrationTest`).
- SCHEMA-04: customer balance below zero, wrong SYSTEM/CUSTOMER shape and bad currency are each rejected (`LedgerConstraintsIntegrationTest`).
- SCHEMA-05: the application boots with `ddl-auto=validate` against the six entities; each entity round-trips; a modified `@Immutable` entry sends no UPDATE; `ImmutableRepositoryShapeTest` checks that the append-only repositories expose no delete or remove method and that none extends `JpaRepository` or `CrudRepository`.
- SCHEMA-06: strategy recorded in docs/DECISIONS.md (entry 16) and demonstrated by `TestIsolationIntegrationTest`.
- SCHEMA-07: docs/architecture.md (Mermaid ER diagram and "why the ledger entries are the source of truth").

### Not completed

- The Mermaid diagram in docs/architecture.md has not been rendered by any tool here; only its syntax was checked against the Mermaid documentation.
- The two "pristine database" options in DECISIONS.md entry 16 (Flyway clean and migrate, `CREATE DATABASE` in the same container) were verified during research but are not built or tested in the repository.
- Invariant 1 (each transaction sums to zero) is not enforced by the database in this phase; see DECISIONS.md entry 17.
- Nothing has run on GitHub Actions (no remote repository exists), so no CI test count exists.

### Bugs caught

None by the tests in this phase: all new tests passed the first time they ran. One compile error in the test code (an ambiguous `assertThat` on a `TransactionTemplate` result in `EntityMappingIntegrationTest`) was fixed before the commit; it was caught by the compiler, not by a test, and is not counted as a bug in the product code.

## Phase 3: Accounts, deposits and transfers (brief phase 2)

### Run details

Date: 2026-09-29
Commit tested: 0983815 (code and tests; the documentation files were edited but uncommitted when the tests ran, no source file differed from the commit).

Commands run for this entry (Git Bash, Windows 11, `JAVA_HOME` set to the JDK 21 install and the Docker CLI on PATH):

- `./gradlew cleanTest test --console=plain` (output saved to docs/evidence/phase-3-test-output.txt, exit code 0, BUILD SUCCESSFUL)
- a script that read every `build/test-results/test/*.xml` file and summed the `tests`, `skipped`, `failures` and `errors` attributes per class, grouped by class-name suffix
- two manual non-vacuity experiments on the atomicity test (described below), each followed by restoring the files and re-running the test to green

### Test results

Integration tests (*IntegrationTest): 106 run, 0 skipped, 0 failed, 0 errors
Unit tests (*Test): 3 run, 0 skipped, 0 failed, 0 errors
Total: 109 tests, 0 skipped, 0 failed, 0 errors

Source: docs/evidence/phase-3-test-output.txt and build/test-results/test/*.xml after ./gradlew cleanTest test. The 74 Phase 1 and 2 tests are unchanged and still pass; Phase 3 added 35.

Phase 3 test classes (tests from the XML):

| Class | Tests | Category |
|-------|-------|----------|
| `MoneyMovementIntegrationTest` | 19 | integration (service level: deposits, transfers, every rejected input, posting guard, invariants over a mixed sequence) |
| `LedgerApiIntegrationTest` | 13 | integration (MockMvc: status codes, problem+json shape, amount coercion) |
| `AtomicityIntegrationTest` | 2 | integration (deposit and transfer crash after the flush) |
| `DevStubUserSeederIntegrationTest` | 1 | integration |

### Completed

- ACCT-01 to ACCT-03: open, list only the caller's own, and view one account with its balance (`MoneyMovementIntegrationTest`, `LedgerApiIntegrationTest`). Another user's account and an unknown id are both 404.
- ACCT-04: services take an `ActingUser` parameter and read no security context; the stub is `StubActingUser` (header `X-Acting-User-Id`, marked `TODO(Phase 6)`), and a users row comes from `DevStubUserSeeder` (dev profile) or test fixtures, not from a migration.
- MONEY-01, MONEY-02: deposit (debit `EXTERNAL_FUNDING`, credit the customer) and transfer each write two entries that sum to zero, checked per transaction in SQL; cached balances equal the sum of entries.
- MONEY-03: closed account and different-currency account are rejected with 422; a system account or unknown id as destination gives 404.
- MONEY-04: amount 0, negative, above the configured maximum (`ledger.max-amount-minor`, default 100,000,000), `Long.MAX_VALUE`, `30.9`, `1e2`, `100.0`, the string `"3000"`, `null`, `true`, `9223372036854775808` and a missing amount all give 400 problem+json on both endpoints; identical from and to gives 400.
- MONEY-05: insufficient funds gives 422 and writes nothing; the funds check runs after `AccountLockService.lock`, and the exact-balance transfer is allowed.
- MONEY-06: one insert path (`LedgerPostingService.post`); `postingOutsideATransactionFailsLoudly` shows the MANDATORY guard.
- MONEY-07: after every step of a 60-step seeded mixed sequence (including rejected overdrafts) every balance is at least 0 (`mixedSequenceKeepsEveryInvariant`).
- MONEY-08: `AtomicityIntegrationTest` lets the real posting run, flushes, confirms the entries and new balance are visible inside the transaction, throws, and then finds no entries, no transaction row and unchanged balances from another connection.
- MONEY-09: RFC 7807 `application/problem+json` for 400, 404, 422 and the generic 500; validation errors list field names and messages and never echo values.
- `AccountLockService` is in its final call shape with an intentionally naive body (docs/DECISIONS.md, entry 18).

### Non-vacuity of the atomicity test (manual, then reverted)

- Experiment A: `@Transactional` removed from `TransferService.transfer` and `DepositService.deposit`, helpers left MANDATORY: both atomicity tests FAILED (`IllegalTransactionStateException`).
- Experiment B: also changed the helpers from MANDATORY to plain `@Transactional`: both atomicity tests still FAILED (the deposit test saw the database balance stay 0 where 700 was expected; the transfer test was refused for insufficient funds because the earlier deposit's balance change had been lost).
- All four service files were restored and the atomicity tests passed again afterwards. This check is not automated.

### Not completed

- The "global entry sum is zero" success criterion is asserted over the transactions created by each test's own user, not over the whole `ledger_entries` table: other test classes leave deliberately unbalanced rows in the shared database (docs/DECISIONS.md, entries 16 and 22). Whole-ledger reconciliation is Phase 4.
- `AccountLockService.lock` takes no database lock yet, so concurrent requests on one account are not safe (Phase 4). No concurrency test exists.
- No idempotency (Phase 5), no authentication (Phase 6); the acting user header is spoofable.
- No audit log rows, no reversals, no history endpoint (out of the lean scope or later phases).
- The app was not started with Docker Compose in this phase; the API was exercised through MockMvc and the service tests only.
- Nothing has run on GitHub Actions (no remote repository exists).

### Bugs caught

- Found while writing `AtomicityIntegrationTest`: stubbing the injected `LedgerPostingService` field called the real method through the transactional proxy outside a transaction and failed on MANDATORY; the leftover Mockito matchers then made the next test fail misleadingly. Fixed by stubbing the spy behind the proxy (`AopTestUtils.getTargetObject`). This was a test-code error, not a product bug.
- Nothing else failed on the first run of the new product code: the 33 other new tests passed the first time they ran.
