# Double-Entry Payments Ledger Service

A Java 21 / Spring Boot backend service that records money movement between accounts using double-entry bookkeeping, exposed through a REST API and designed to stay correct under concurrent requests, client retries and partial failures. The ledger of entries is the source of truth; each customer account also stores a cached balance for fast reads, changed only in the same database transaction as the entries it reflects and checked by reconciliation.

## Status

In progress: Phases 1 to 6 are built (setup and toolchain; schema, triggers and domain model; accounts, deposits and transfers; concurrency control and reconciliation; idempotency keys; registration, login and ownership checks). The app boots against PostgreSQL 16, integration tests run on a real PostgreSQL through Testcontainers, and `docker compose up` gives a healthy stack (app plus database). CI workflow committed, not yet observed running.

What works now: open, list and view GBP accounts; simulated deposits; transfers between customer accounts, each written as balanced double-entry lines (sum zero) with the cached balance updated in the same database transaction; no overdraft (422); errors as RFC 7807 problem+json. Concurrent requests are safe: every deposit and transfer locks the accounts it touches with one ordered `SELECT ... ORDER BY id FOR UPDATE`, which prevents lost updates and deadlocks. This is shown by a test that fires 1,000 random transfers from 16 threads at a real PostgreSQL and then checks that money was neither created nor destroyed and that every cached balance equals its entries, using a `ReconciliationService` (no HTTP endpoint yet). The test was first committed failing against the earlier non-locking version (docs/evidence/phase-4-red-naive-lock.txt), then passed after the fix. Deposits and transfers require an `Idempotency-Key` header: sending the same key and body again returns the stored response and moves money once, the same key with a different request is 422, and 20 identical requests fired at the same instant create exactly one ledger transaction (the guarantee is a unique database constraint, not a check in Java; the test was also checked by breaking that constraint on purpose, see docs/evidence/phase-5-repeat-runs.txt). Keys expire after 24 hours by default, and a failed first attempt is not remembered, so a retry runs again. Users register and log in (`POST /api/auth/register`, `POST /api/auth/login`): passwords are stored as BCrypt hashes and login returns a signed JWT that must be sent as `Authorization: Bearer <token>` on every other request (401 problem+json without a valid one; only `/api/auth/**` and `/actuator/health` are open). A user can only see accounts they own and can only deposit into or transfer out of their own accounts; another user's account looks exactly like a missing one (404). The signing secret comes only from the `LEDGER_JWT_SECRET` environment variable, has no default, and the app refuses to start without 32 or more characters. What does not exist yet: reversals, transaction history, an audit log and admin endpoints, and token refresh or revocation. See docs/architecture.md for the data model and docs/INTERVIEW_PREP.md for a walkthrough.

## Run it

You need Docker Desktop running. From a clone of this repository (no clone URL yet; the repository has not been published):

```bash
export LEDGER_JWT_SECRET="$(openssl rand -base64 48)"   # required: any text of 32+ characters; never commit it
docker compose up
```

`LEDGER_JWT_SECRET` signs the login tokens and has no default: Compose stops with an error if it is unset. Set it in your shell as above, or copy `.env.example` to `.env` (git-ignored) and replace the placeholder. Then open http://localhost:8080/actuator/health, which returns `{"status":"UP"}`. Stop and remove everything, including the database volume, with:

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
- Authentication has no token refresh or revocation (a token is valid until it expires, 1 hour by default), no rate limiting or lockout on login, and registration reveals whether a username is taken; there is no HTTPS in front of the app. See docs/INTERVIEW_PREP.md, entry 25.
- Idempotency covers deposits and transfers only (reversals are not built); a duplicate request waits inside the database for the first one and holds a connection while it does, so the connection pool must be at least as large as the number of simultaneous duplicates; expired keys are not cleaned up by a job; a replayed body equals the original as JSON but is not byte-identical (stored as JSONB); failed requests are not remembered (docs/DECISIONS.md, entries 27 and 28).
- Upgrade from Spring Boot 3.5 to 4.1.x is a possible next step.
- Possible extensions outside the current scope: multi-currency and FX; real payment rails or open banking; Kafka, the outbox pattern or microservices; a frontend; rate limiting, Prometheus metrics and tracing; interest, fees, scheduled payments and overdrafts.

## Documentation

- [docs/LEARNING_NOTES.md](docs/LEARNING_NOTES.md): project layout, what Spring Boot auto-configures, how to run the app and tests.
- [docs/architecture.md](docs/architecture.md): the data model diagram and why the ledger entries are the source of truth.
- [docs/INTERVIEW_PREP.md](docs/INTERVIEW_PREP.md): concept explanations, trade-offs and likely interview questions, plus a walkthrough of the files that matter.
- [docs/DECISIONS.md](docs/DECISIONS.md): every non-obvious decision with options, choice and trade-off.
- [docs/CV_EVIDENCE.md](docs/CV_EVIDENCE.md): verified facts and test counts per phase, each backed by saved command output.
