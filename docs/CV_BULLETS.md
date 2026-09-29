# CV bullets

Every figure below comes from docs/CV_EVIDENCE.md or from the final full test run (docs/evidence/final-test-output.txt). Rules applied (project brief, section 10): a bullet is included only if the feature exists in the repository and is tested; there is no estimated figure; none of the inflated-claim wording banned by the brief (section 10) is used, because nothing here was tested at that scale.

## Read this before using anything below

- **CI has not been observed running.** The GitHub Actions workflow (`.github/workflows/ci.yml`) is committed, and its steps were checked locally, but the repository has not been pushed anywhere, so it has never run on GitHub. Do not write "CI passing", "green build" or a badge until you have pushed the repository and seen a green run yourself. Until then the heading line below is given without GitHub Actions, and the one clause that mentions it is optional and marked.
- **The GitHub link does not exist yet.** Add it after you publish the repository.
- Do not list a feature that is not built: reversals, an audit log, an admin reconciliation endpoint, OpenAPI/Swagger docs and load testing are all absent.

## Heading line

**Double-Entry Payments Ledger API** | Java 21, Spring Boot, Spring Security, PostgreSQL, Docker, Testcontainers | [GitHub link, add after publishing]

(After you have pushed and seen a green Actions run, you may add "GitHub Actions" to the list.)

## Bullets (pick 3 or 4)

- Built a REST API in **Java 21, Spring Boot and PostgreSQL** that records every deposit and transfer as balanced double-entry ledger entries (each transaction sums to zero), with database triggers making the ledger append-only and a cached balance kept in step inside the same transaction.
- Prevented lost updates and deadlocks under concurrent transfers with ordered `SELECT ... FOR UPDATE` row locking; an integration test fires **1,000 random transfers from 16 threads across 10 accounts** against real PostgreSQL and verifies no negative balances, no money created or lost, and every cached balance equal to its ledger entries. The test was first committed failing against a version without the lock.
- Implemented **idempotency keys** backed by a database unique constraint so a retried payment request executes at most once; verified with a test that sends **20 identical concurrent requests and gets exactly 1 ledger transaction**, repeated over 5 consecutive runs.
- Secured the API with **Spring Security, BCrypt and JWT**, with per-user account ownership checks; wrote **188 automated tests (171 integration tests on Testcontainers PostgreSQL, 17 unit tests)**, with the concurrency and locking test classes passing on 6 consecutive runs.

Optional clause, only once CI has been seen running: add "run by a GitHub Actions workflow on every push" to the last bullet.

## One-line version (crowded CV)

Java/Spring Boot payments ledger with double-entry accounting, concurrency-safe transfers and idempotent retries, verified by 188 tests (171 integration, real PostgreSQL via Testcontainers).

## Where each figure comes from

| Figure | Source |
|--------|--------|
| 188 tests, 171 integration, 17 unit, 1 skipped on purpose, 0 failed | docs/evidence/final-test-output.txt and the sum of `build/test-results/test/*.xml` from the final run |
| 1,000 transfers, 16 threads, 10 accounts | docs/CV_EVIDENCE.md, Phase 4, "Showcase parameters" (seed 20260929) |
| Committed failing first without the lock | docs/CV_EVIDENCE.md, Phase 4, "The red-then-green pair"; docs/evidence/phase-4-red-naive-lock.txt |
| 6 consecutive runs of the concurrency and locking classes | docs/CV_EVIDENCE.md, Phase 4, "Repeat runs"; docs/evidence/phase-4-repeat-runs.txt |
| 20 identical concurrent requests, 1 transaction, 5 consecutive runs | docs/CV_EVIDENCE.md, Phase 5; docs/evidence/phase-5-repeat-runs.txt |

The "188" counts the one skipped demonstration as part of the total but not as a pass; if you want a strictly "passing" figure, use 187.

## Skills keywords

List a keyword only if you can answer a follow-up question on it. The study pointers are entries in docs/INTERVIEW_PREP.md.

| Keyword | Status | Study first |
|---------|--------|-------------|
| Java, Spring Boot | Fine to list after a read-through | Walkthroughs in INTERVIEW_PREP.md; LEARNING_NOTES.md |
| PostgreSQL, Flyway | Fine to list after a read-through | entries 8, 12; docs/architecture.md |
| REST API design | Fine to list | Entry 23 (status codes), docs/DECISIONS.md entry 20 |
| Spring Security, JWT, BCrypt | Fine to list after entries 20 to 25 | Entries 20 to 25 |
| Docker, Docker Compose | Fine to list (multi-stage Dockerfile, Compose with health checks) | docs/DECISIONS.md entry 9 |
| JUnit 5, AssertJ, Testcontainers | Fine to list | LEARNING_NOTES.md, Testcontainers section |
| **Concurrency control** | **Learn first** | Entries 7 to 12; be able to draw the race on paper |
| **Idempotency** | **Learn first** | Entries 13 to 18 |
| **SQL transactions and isolation levels** | **Learn first** | Entries 3, 7, 10, 12 |
| **JPA / Hibernate** | **Learn first** (persistence context, entity versus repository) | Entries 4, 11 |
| Double-entry bookkeeping | Fine after entries 1 and 5 | Entries 1, 5 |
| CI/CD (GitHub Actions) | **Do not list yet**: workflow written, never observed running | docs/DECISIONS.md entry 10 |
| OpenAPI / Swagger | **Do not list**: not built | - |
| AWS / Azure | **Do not list**: only a description in the README, nothing deployed | - |

## What to double check before sending

- [ ] Every number in the bullets you keep matches docs/CV_EVIDENCE.md and the table above; re-run `./gradlew cleanTest test` and re-count if the code has changed since.
- [ ] The repository is published and the GitHub link works; the README renders on GitHub (the two Mermaid diagrams especially, which have never been rendered by a tool here).
- [ ] The GitHub Actions run has been seen green. If not, do not mention CI.
- [ ] You can explain, without notes, each bullet you kept: the race in the concurrency bullet, why a unique constraint and not a check in Java, what JWT does.
- [ ] You have read INTERVIEW_PREP.md entry 29 and can say honestly how AI tools were used and how you verified the work.
- [ ] No claim about scale, speed or real money: nothing was load-tested, deposits are simulated, and the framework version has passed its open-source end of life (the README says so).
- [ ] If an application form asks for "in progress", the honest status is: core ledger, concurrency, idempotency and authentication are finished and tested; reversals, audit log and admin reconciliation endpoint were deliberately left out.
