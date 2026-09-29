# Double-Entry Payments Ledger Service

A Java 21 / Spring Boot backend service that records money movement between accounts using double-entry bookkeeping, exposed through a REST API and designed to stay correct under concurrent requests, client retries and partial failures. The ledger of entries is the source of truth; each customer account also stores a cached balance for fast reads, changed only in the same database transaction as the entries it reflects and checked by reconciliation.

## Status

In progress: Phases 1 to 4 are built (setup and toolchain; schema, triggers and domain model; accounts, deposits and transfers; concurrency control and reconciliation). The app boots against PostgreSQL 16, integration tests run on a real PostgreSQL through Testcontainers, and `docker compose up` gives a healthy stack (app plus database). CI workflow committed, not yet observed running.

What works now: open, list and view GBP accounts; simulated deposits; transfers between customer accounts, each written as balanced double-entry lines (sum zero) with the cached balance updated in the same database transaction; no overdraft (422); errors as RFC 7807 problem+json. Concurrent requests are safe: every deposit and transfer locks the accounts it touches with one ordered `SELECT ... ORDER BY id FOR UPDATE`, which prevents lost updates and deadlocks. This is shown by a test that fires 1,000 random transfers from 16 threads at a real PostgreSQL and then checks that money was neither created nor destroyed and that every cached balance equals its entries, using a `ReconciliationService` (no HTTP endpoint yet). The test was first committed failing against the earlier non-locking version (docs/evidence/phase-4-red-naive-lock.txt), then passed after the fix. What does not exist yet: idempotency keys, login (the caller is identified by an unauthenticated `X-Acting-User-Id` header stub, so nothing is secured), reversals and history. See docs/architecture.md for the data model and docs/INTERVIEW_PREP.md for a walkthrough.

## Run it

You need Docker Desktop running. From a clone of this repository (no clone URL yet; the repository has not been published):

```bash
docker compose up
```

Then open http://localhost:8080/actuator/health, which returns `{"status":"UP"}`. Stop and remove everything, including the database volume, with:

```bash
docker compose down -v
```

The first run builds the image and pulls base images, which takes a couple of minutes. The Phase 1 verification used `docker compose up --build -d --wait` (detached, returns when both services are healthy); see docs/LEARNING_NOTES.md.

## Run the tests

You need JDK 21 and Docker Desktop running.

```bash
./gradlew cleanTest test
```

Details, the Windows PowerShell form and troubleshooting are in docs/LEARNING_NOTES.md.

## Honest scope statement

- This is a learning project and not production-grade.
- Single currency: GBP only, with amounts held as whole minor units (pence).
- Deposits are simulated, and there are no real payment rails, no open banking and no connection to any bank or card network.
- It is built on Spring Boot 3.5.16, the final 3.x release, whose open-source support ended on 2026-06-30, so the framework receives no free security patches. This was chosen deliberately; see docs/DECISIONS.md, entry 1.
- The database credentials in `docker-compose.yml` (`ledger` / `ledger`) are local-development defaults only, and the ports are bound to `127.0.0.1`.
- CI workflow committed, not yet observed running: no remote repository exists yet, so no CI result is claimed.

## Known limitations and next steps

- Concurrency is tested against one PostgreSQL instance through the service layer, not over HTTP and not across several application instances; pessimistic locking limits throughput on a single very busy account (compared with optimistic locking and SERIALIZABLE in docs/DECISIONS.md, entry 23); reconciliation has no endpoint and is asserted in tests only over the rows each test created; the append-only triggers stop application bugs and casual SQL but not a privileged database administrator (the table owner can disable them); the rule that each transaction sums to zero is enforced by the service and checked by reconciliation, not by the database; authentication is not added until Phase 6, so nothing built before then is secured.
- Upgrade from Spring Boot 3.5 to 4.1.x is a possible next step.
- Possible extensions outside the current scope: multi-currency and FX; real payment rails or open banking; Kafka, the outbox pattern or microservices; a frontend; rate limiting, Prometheus metrics and tracing; interest, fees, scheduled payments and overdrafts.

## Documentation

- [docs/LEARNING_NOTES.md](docs/LEARNING_NOTES.md): project layout, what Spring Boot auto-configures, how to run the app and tests.
- [docs/architecture.md](docs/architecture.md): the data model diagram and why the ledger entries are the source of truth.
- [docs/INTERVIEW_PREP.md](docs/INTERVIEW_PREP.md): concept explanations, trade-offs and likely interview questions, plus a walkthrough of the files that matter.
- [docs/DECISIONS.md](docs/DECISIONS.md): every non-obvious decision with options, choice and trade-off.
- [docs/CV_EVIDENCE.md](docs/CV_EVIDENCE.md): verified facts and test counts per phase, each backed by saved command output.
