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

## Phase 4: Concurrency and reconciliation core

### Run details

Date: 2026-09-29
Commits (in order): `2d38c46` ReconciliationService; `e0e87fa` RED, the concurrency showcase test; `50ac4ee` RED evidence file; `8c4946b` GREEN, ordered `SELECT ... FOR UPDATE`; `18e5790` deadlock test, its mutation partner and the disabled locking-off demonstration. The repeat runs and the full suite below ran on the clean tree at `18e5790` (only documentation files were edited afterwards).

Commands run for this entry (Git Bash, Windows 11, `JAVA_HOME` set to the JDK 21 install, Docker CLI on PATH, real PostgreSQL 16.15 through Testcontainers):

- `./gradlew cleanTest test --tests '*ConcurrentTransferShowcaseIntegrationTest'` twice on the naive lock body (RED; output in docs/evidence/phase-4-red-naive-lock.txt)
- the same test after the fix (GREEN), then the full suite once before committing the fix (122 passing lines)
- the `@Disabled` demonstration run once by hand with the annotation temporarily removed (docs/evidence/phase-4-locking-off-demo.txt), then restored
- six consecutive `./gradlew cleanTest test --tests ...` runs of the five concurrency and locking classes (docs/evidence/phase-4-repeat-runs.txt)
- `./gradlew cleanTest test --console=plain` for the full suite (docs/evidence/phase-4-test-output.txt), plus a script summing `tests`, `skipped`, `failures` and `errors` from every `build/test-results/test/*.xml`

### Showcase parameters

10 customer accounts opened by one user and each funded with a 10,000 pence deposit (total 100,000 pence); 1,000 transfers with amounts 1 to 5,000 pence between random distinct accounts, inputs generated up front from `new Random(20260929)`; 16 worker threads released together by a start latch; Hikari pool 20 in the test profile; each result read with a 60 second timeout; `@Timeout(120)`. Deadlock test: 2 accounts of 1,000,000 pence, 400 alternating A-to-B and B-to-A transfers of 1 to 100 pence (seed 7654321), 16 threads.

### The red-then-green pair

- RED, `e0e87fa` (test only), run against the Phase 3 `AccountLockService` body, which takes no row lock. It failed. Committed run (docs/evidence/phase-4-red-naive-lock.txt): 894 transfers succeeded, 106 were refused for insufficient funds, 0 unexpected exceptions, and the cached balances added up to 148,784 pence when 100,000 existed (48,784 pence created from nothing). All 10 accounts had a cached balance different from the sum of their entries, and one had a negative ledger-derived balance (-66,366) while its cache was positive (an overdraft that got past the funds check). An earlier identical run failed the same way: 960 succeeded, 40 refused, cached total 174,086, 10 of 10 mismatched, 3 accounts negative in the ledger. Every transaction still summed to zero and the entry total was zero, so the ledger itself stayed balanced and only the cache and the funds decisions were wrong. No exception was thrown and the database `CHECK (balance_minor >= 0)` never fired; the symptom was silent drift, not constraint violations.
- GREEN, `8c4946b`: `AccountLockService.lock` became one `SELECT * FROM accounts WHERE id IN (:ids) AND type = 'CUSTOMER' ORDER BY id FOR UPDATE`. The same showcase then passed (for example 829 succeeded, 171 refused, cached total 100,000, reconciliation clean).
- Locking-off demonstration (test-scope naive lock, real code unchanged; docs/evidence/phase-4-locking-off-demo.txt): failed as expected with 872 succeeded, 128 refused, cached total 121,747 instead of 100,000. Kept as `LockingOffDemoIntegrationTest`, `@Disabled` with an explanatory comment.

### Repeat runs

Six consecutive runs of `ConcurrentTransferShowcaseIntegrationTest`, `DeadlockIntegrationTest`, `DeadlockMutationIntegrationTest`, `AccountLockServiceIntegrationTest` and `ReconciliationIntegrationTest`, each preceded by `cleanTest`: 6 of 6 passed, each run 15 tests, 0 skipped, 0 failures, 0 errors. Showcase outcomes over the six runs ranged from 809 to 843 successful transfers (157 to 191 refused for insufficient funds), with zero unexpected failures every time. The deadlock test completed 400 of 400 transfers in every run. Interleavings differ from run to run, so this is repeated evidence, not a proof.

### Deadlock mutation check

`DeadlockMutationIntegrationTest` runs the ping-pong scenario against a test-only lock service that locks one account at a time in alternating order (40 transfers, 8 threads). In the six repeat runs PostgreSQL aborted 27 to 31 of the 40 transfers with a deadlock (SQLSTATE 40P01), and in three of the runs 1 to 2 more failed a 5 second lock timeout (55P03); every run still reconciled clean. An earlier run of a larger version (200 transfers, 16 threads) had 173 deadlock aborts and 14 lock timeouts and took 49 seconds, which is why the test was made smaller.

### Test results (full suite, `cleanTest test`, exit code 0)

Integration tests (*IntegrationTest): 122 run, 1 skipped, 0 failed, 0 errors
Unit tests (*Test): 3 run, 0 skipped, 0 failed, 0 errors
Total: 125 tests, 1 skipped (`LockingOffDemoIntegrationTest`, `@Disabled` on purpose), 0 failed, 0 errors

Source: docs/evidence/phase-4-test-output.txt and build/test-results/test/*.xml. The 109 Phase 1 to 3 tests are unchanged and still pass; Phase 4 added 16.

| Class | Tests | What it proves |
|-------|-------|----------------|
| `ReconciliationIntegrationTest` | 6 | clean data from the real services reconciles clean at repeatable read; a lone entry with a wrong cache, an account with no entries but a cached balance, and a ledger-derived negative balance are each detected; empty scope; whole-ledger form runs |
| `AccountLockServiceIntegrationTest` | 6 | the emitted SQL is one `for update` statement `order by id`; rows come back in PostgreSQL id order; a second session gets 55P03 on the locked row; a waiting transaction reads the committed balance (1,050); missing, system and empty requests are 404; locking outside a transaction fails |
| `ConcurrentTransferShowcaseIntegrationTest` | 1 | the 1,000-transfer showcase |
| `DeadlockIntegrationTest` | 1 | 400 opposite transfers all complete, balances exact, reconciliation clean |
| `DeadlockMutationIntegrationTest` | 1 | the same scenario with alternating lock order produces 40P01 aborts |
| `LockingOffDemoIntegrationTest` | 1 (skipped) | the showcase with locking off |

### Completed

- CONC-01: one ordered native `FOR UPDATE` query, ordering done in SQL (comparison with PostgreSQL order in `accountsComeBackInPostgresIdOrderNotJavaOrder`), READ COMMITTED unchanged.
- CONC-02: showcase with start gate, seeded inputs, every Future read with a timeout, pool 20 for 16 threads, asserts successes above zero and that overdraft refusals occurred, then no negative balance, money conserved, reconciliation clean.
- CONC-03: `@Disabled` demonstration using a test-scope override, run by hand once, result recorded above. There is no runtime toggle in shipped code.
- CONC-04: deadlock test, with a mutation partner that proves it can fail.
- CONC-05: six consecutive passing runs recorded.
- CONC-06: docs/LEARNING_NOTES.md (lost update and row locks), docs/INTERVIEW_PREP.md entries 7 to 12, docs/DECISIONS.md entries 23 to 26 (pessimistic versus optimistic versus SERIALIZABLE).
- RECON-01 to RECON-03: `ReconciliationService` (no endpoint), one `REPEATABLE_READ` read-only transaction, asserted at the end of the showcase and deadlock tests.

### Not completed

- Reconciliation is asserted over the ids each test created, not over the whole shared test database, which holds other tests' deliberately unbalanced rows (docs/DECISIONS.md, entry 25). `RECON-04` (admin endpoint) and `RECON-05` (clean after the whole suite) are outside this phase and outside the lean scope.
- Not measured: throughput or latency. Elapsed times printed by the tests (about 2 seconds for 1,000 transfers on a laptop with a local container) are incidental and not a benchmark.
- Optimistic locking and SERIALIZABLE were compared in writing only; neither was built or measured.
- Concurrency through the HTTP layer was not tested; the tests call the services directly so they measure locking, not Tomcat.
- No idempotency (Phase 5), no authentication (Phase 6). Nothing has run on GitHub Actions (no remote repository exists).

### Bugs caught

- None in the shipped code: every new test passed the first time it ran against the fixed code. One test-design problem was found by measuring: the first deadlock mutation test was too large (49 seconds, dominated by PostgreSQL's 1 second deadlock detection) and was cut to 40 transfers.
