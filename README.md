# Double-Entry Payments Ledger Service

A Java 21 / Spring Boot backend service that records money movement between accounts using double-entry bookkeeping, exposed through a REST API and designed to stay correct under concurrent requests, client retries and partial failures. The ledger of entries is the source of truth; each customer account also stores a cached balance for fast reads, changed only in the same database transaction as the entries it reflects and checked by reconciliation.

## Status

In progress: Phase 1 of 8 (setup and toolchain) is built. The app boots against PostgreSQL 16, integration tests run on a real PostgreSQL through Testcontainers, and `docker compose up` gives a healthy stack (app plus database). CI workflow committed, not yet observed running.

No ledger features exist yet: there are no accounts, transfers or ledger tables. Those arrive in Phases 2 and 3.

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

- No ledger features until Phases 2 and 3; authentication is not added until Phase 6, so nothing built before then is secured.
- Upgrade from Spring Boot 3.5 to 4.1.x is a possible next step.
- Possible extensions outside the current scope: multi-currency and FX; real payment rails or open banking; Kafka, the outbox pattern or microservices; a frontend; rate limiting, Prometheus metrics and tracing; interest, fees, scheduled payments and overdrafts.

## Documentation

- [docs/LEARNING_NOTES.md](docs/LEARNING_NOTES.md): project layout, what Spring Boot auto-configures, how to run the app and tests.
- [docs/DECISIONS.md](docs/DECISIONS.md): every non-obvious decision with options, choice and trade-off.
- [docs/CV_EVIDENCE.md](docs/CV_EVIDENCE.md): verified facts and test counts per phase, each backed by saved command output.
