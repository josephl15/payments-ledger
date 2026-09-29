# Interview prep

Each entry: what it is in plain words, why it is built this way here, the trade-off, and questions an interviewer is likely to ask with short model answers. Later phases append to this file. Everything below describes code that exists in the repository; the pointers name the file to open.

## Walkthrough: the four files that matter for money movement (Phase 3)

Open them in this order; together they are the whole story of a transfer.

1. `src/main/java/dev/joseph/ledger/service/TransferService.java` is the business rule for a transfer. One `@Transactional` method: cheap checks first (amount, currency, from is not to), then "does the caller own the paying account" (a yes/no query that loads nothing), then `lockService.lock(...)`, then the checks that need the locked rows (ACTIVE, currency, enough money), then `postingService.post(...)`. `DepositService` is the same shape with the outside world as the other side.
2. `src/main/java/dev/joseph/ledger/service/LedgerPostingService.java` is the only code that writes ledger entries or changes a cached balance. It refuses anything that does not sum to zero, has a zero line or mixes currencies, inserts the transaction row and the entries, then applies each customer line to the account entity. It must be called inside a transaction (`Propagation.MANDATORY`).
3. `src/main/java/dev/joseph/ledger/service/AccountLockService.java` is the one place accounts are fetched for update. It shipped in Phase 3 with a plain-read body on purpose; in Phase 4 the body became one `SELECT ... ORDER BY id FOR UPDATE` and nothing else changed (see the Phase 4 walkthrough below).
4. `src/test/java/dev/joseph/ledger/AtomicityIntegrationTest.java` is the proof that a failure half-way leaves nothing behind: it lets the real posting run and flush, crashes afterwards, and checks the database is unchanged.

The HTTP edge is thin: `TransferController` maps JSON to a `TransferCommand` and back, and `ApiExceptionHandler` turns exceptions into RFC 7807 `problem+json` (400 bad input, 404 not found or not yours, 422 insufficient funds or unusable account).

---

## 1. Double-entry bookkeeping versus a single balance column

**Plain words.** Instead of one number per account that gets overwritten, every money movement is recorded as at least two signed entries that add up to zero: a £30 transfer is `-3000` on the payer and `+3000` on the payee. The balance is what you get by adding up an account's entries. This project also stores a cached balance on the account for fast reads, but it is only ever changed in the same database transaction as the entries, and the entries win if the two disagree.

**Why here.** History cannot be silently rewritten (the entries table is append-only, enforced by triggers), every pound has a visible source and destination, and "the whole ledger sums to zero" is a cheap check for bugs. Deposits come from a system account, `EXTERNAL_FUNDING`, so even money entering the system balances (`-X` on funding, `+X` on the customer).

**Trade-off.** More rows and more code than `UPDATE accounts SET balance = balance - ?`; reads that need the true balance must add up entries (hence the cached copy, which then has to be kept consistent and checked).

**Likely questions**
- *Why not just a balance column?* A bare balance has no history and no way to tell whether it is right. With entries you can recompute it, audit it, and prove nothing was created or destroyed.
- *What keeps the cached balance honest?* It changes only inside `LedgerPostingService.post`, in the same transaction as the entries, and a database `CHECK` stops it going negative. Reconciliation (Phase 4) recomputes it from entries and reports any difference.
- *How do you correct a mistake?* You never edit or delete an entry; you post a new reversing transaction (planned, not built yet).

## 2. Integers in pence, never floating point

**Plain words.** Money is a `long` count of pence (`amountMinor`). £12.50 is `1250`. `double` cannot represent 0.1 exactly, so sums drift; integers are exact.

**Why here.** The database column is `BIGINT`, the Java field is `long`, and the API takes a JSON integer. Jackson is configured so a decimal (`30.9`), an exponent form (`1e2`) or a string (`"3000"`) is rejected with 400 instead of being silently turned into a whole number, and the request field is a boxed `Long` so a missing amount is `null` (rejected) rather than `0`. Balance arithmetic uses `Math.addExact`, which throws on overflow instead of wrapping around.

**Trade-off.** One currency only (GBP); converting between currencies would need rates and rounding rules. `BigDecimal` is the alternative and is fine, but is slower to reason about and easy to misuse (`new BigDecimal(0.1)`).

**Likely questions**
- *Why not `BigDecimal`?* It works, but pence as `long` is simpler, maps straight to `BIGINT`, and removes rounding decisions. The limit is one currency and one minor unit.
- *What happens at `Long.MAX_VALUE`?* The request is above the configured maximum and gets 400; and balance additions use `addExact`, so overflow throws rather than corrupts.
- *What would you change for multiple currencies?* Store the currency on every entry (already done), never sum across currencies, and add an FX transaction type with explicit rate and rounding.

## 3. Atomic transfers: `@Transactional` and the self-invocation pitfall

**Plain words.** A transfer changes several rows: the transaction row, two entries, two balances. A database transaction makes those all-or-nothing. In Spring, `@Transactional` on a method makes Spring wrap the bean in a proxy that opens the transaction before the method runs, commits when it returns and rolls back if it throws an unchecked exception.

**Why here.** `@Transactional` sits only on the business methods (`TransferService.transfer`, `DepositService.deposit`). The helpers they call (`LedgerPostingService.post`, `AccountLockService.lock`) use `Propagation.MANDATORY`: "join the transaction that is already open, and throw if there is none". So a missing `@Transactional` becomes an immediate error instead of half a transfer committed. `AtomicityIntegrationTest` forces a crash after the real writes were flushed and checks that nothing remains.

**The self-invocation pitfall.** The proxy only sees calls that come from outside the bean. If one method of a class calls another `@Transactional` method of the same class (`this.other()`), the call skips the proxy, so its annotation is ignored. That is why the transactional units are separate beans and why the helpers are `MANDATORY`: the mistake fails loudly.

**Trade-off.** Everything in one transaction means row locks (Phase 4) are held until commit, so keep the transaction short and do no network calls inside it. Checked exceptions do not roll back by default; all exceptions here are unchecked for that reason.

**Likely questions**
- *What does `@Transactional` actually do?* Spring creates a proxy around the bean; the proxy begins a transaction, calls the method, then commits or rolls back on an unchecked exception.
- *Why might `@Transactional` silently not work?* Self-invocation, a `private` or non-public method, a call on an object you built with `new`, or throwing a checked exception without `rollbackFor`.
- *How do you know your atomicity test is not vacuous?* If the test throws before any flush, Hibernate has written nothing and rollback proves nothing. This test crashes after `flush()` and it also failed when I removed `@Transactional` (recorded in `docs/CV_EVIDENCE.md`).

## 4. Why the entity's `applyDelta` and not `UPDATE ... SET balance = balance + ?`

**Plain words.** Two ways to change a balance. (a) Load the account, add to the field in Java, let Hibernate write it back. (b) Send `UPDATE accounts SET balance_minor = balance_minor + :d`, which the database applies atomically. This project uses (a): `Account.applyDelta(long)`.

**Why here.** The check "does the payer have enough money" reads the balance in Java, so the code needs the loaded value anyway, and the flow reads top to bottom: lock, check, change. It also keeps the concurrency story honest: with the row lock in place (Phase 4) read-check-write is safe; with the lock absent (the Phase 3 naive `AccountLockService`) it loses updates, and the Phase 4 concurrency test was committed failing first (`docs/evidence/phase-4-red-naive-lock.txt`) and passes after the lock was added. Option (b) would hide that race because the SQL increment is atomic by itself.

**Trade-off.** Correctness now depends on holding the row lock across read and write; a SQL increment would not. The database `CHECK (balance_minor >= 0)` is the backstop either way. Also, the account must be loaded only after the lock is taken, or the entity in memory can be stale (the persistence context caches it). That is why the code never calls `findById` on an account before `lock(...)` and does the ownership check with `existsByIdAndOwnerUserId`, which loads no entity.

**Likely questions**
- *What is a lost update?* Two transactions read balance 100, both write back 100 - 30 = 70 and 100 - 50 = 50; one write vanishes. Locking the row until commit serialises them.
- *Why is the funds check after the lock?* Because checking first and locking second lets another transaction change the balance in between (check-then-act race).
- *Could you use optimistic locking (`@Version`) instead?* Yes; it detects the conflict and retries rather than waiting. Pessimistic locking suits hot accounts with real contention; DECISIONS.md will compare them.

## 5. Why system accounts are not locked or updated

**Plain words.** `EXTERNAL_FUNDING` stands for "the outside world". Every deposit posts `-X` to it. If it had a cached balance and every deposit locked and updated that row, all deposits everywhere would queue behind each other on one row (a "hot row").

**Why here.** System accounts have a `NULL` balance (a `CHECK` enforces that), are never locked, and `applyDelta` on one throws. `AccountLockService` only returns `CUSTOMER` accounts, so a transfer naming a system account gets a 404. The system account's balance, if ever needed, is `SUM` of its entries.

**Trade-off.** Reading a system balance is a sum over many rows (slower, but nothing reads it on a hot path). It goes negative by design: it is money owed to the outside world.

**Likely questions**
- *What is a hot row?* One row that many concurrent transactions must update, so they serialise regardless of how many CPUs or connections you have.
- *How do you know the ledger balances if funding has no balance?* Sum every entry: it must be zero. The scoped version of that check is in `MoneyMovementIntegrationTest.mixedSequenceKeepsEveryInvariant`; whole-ledger reconciliation comes in Phase 4.
- *What would you do for very high deposit volume?* Batch or shard the counter-side (several funding accounts), or write entries only and derive balances asynchronously.

## 6. Why services take an `ActingUser` parameter (a header stub until Phase 6)

**Plain words.** Services never look at a security context; they receive an `ActingUser` record (just a user id). Until Phase 6 a header stub built it; now `CurrentUserProvider` builds it from the verified JWT (entry 20). The services did not change when the stub was replaced, which is the point of the design.

**Why here.** Business rules (who owns which account) stay the same when authentication changes; only the code that builds `ActingUser` changes. It also makes services trivially testable: pass a value, no security setup.

**Likely questions**
- *Why not read `SecurityContextHolder` in the service?* It couples business logic to the web layer and to a thread-local, and makes tests need a security context.
- *Why return 404, not 403, for someone else's account?* It does not reveal that the id exists (entry 23).

---

## Walkthrough: the four files that matter for concurrency (Phase 4)

1. `src/main/java/dev/joseph/ledger/repository/AccountRepository.java`, the method `lockCustomerAccountsOrderedById`: the one SQL statement that makes concurrent transfers safe, `SELECT * FROM accounts WHERE id IN (:ids) AND type = 'CUSTOMER' ORDER BY id FOR UPDATE`. Read the comment above it; every clause is there for a reason.
2. `src/main/java/dev/joseph/ledger/service/AccountLockService.java` wraps that query: it must run inside a transaction, returns the locked accounts in id order, and turns "fewer rows than asked for" into a 404. `TransferService` and `DepositService` call it first and use only what it returns.
3. `src/main/java/dev/joseph/ledger/service/ReconciliationService.java` (with `ReconciliationRepository`): the safety net. It recomputes from the entries what the rest of the system claims (each transaction sums to zero, the whole ledger sums to zero, each cached balance equals its entries, nothing negative) in one read-only `REPEATABLE_READ` transaction.
4. `src/test/java/dev/joseph/ledger/ConcurrentTransferShowcaseIntegrationTest.java`: 1,000 random transfers from 16 threads over 10 accounts, then the invariants checked directly and through reconciliation. Its partners: `DeadlockIntegrationTest` and `DeadlockMutationIntegrationTest` (opposite transfers, and proof the test can fail), and `AccountLockServiceIntegrationTest` (the emitted SQL, and a second session really being blocked). `TestLockServices` holds the deliberately broken lock services used to make the tests fail on purpose.

The story to tell: the showcase was written first and committed failing against the naive lock (cached balances 48,784 pence above the money that existed, every account's cache disagreeing with its entries, no exception anywhere), then the lock was added and it passed, repeatedly. Numbers are in `docs/CV_EVIDENCE.md`.

## 7. The lost update, and why READ COMMITTED does not prevent it

**Plain words.** Transfer A reads the balance 10,000 and will write back 10,000 - 3,000. Transfer B, at the same moment, reads 10,000 and will write back 10,000 - 5,000. Both run; the last write wins, so the balance ends at 5,000 (or 7,000), and one transfer's effect has vanished even though both wrote their ledger entries. That is a lost update. In the ledger the entries are still right but the cached balance is wrong, and the check "do you have enough money" was made against a balance that was already out of date, so an account can be overdrawn.

**Why READ COMMITTED does not stop it.** PostgreSQL's default isolation level only promises that you never see uncommitted data (each statement sees what was committed when it started). It says nothing about two transactions reading the same row and then both writing. The write is atomic, but the decision to write was based on a stale read.

**What was seen here.** With the lock deliberately missing, a run of 1,000 transfers raised the total of the cached balances from 100,000 to 148,784 pence (money out of thin air), left all 10 accounts with a cache different from their entries, and left one account (three in an earlier identical run) with a negative balance in the ledger while its cache was positive, which means it had been overdrawn despite the funds check. Nothing threw an exception, and the database `CHECK (balance_minor >= 0)` never fired, because the wrong numbers were still non-negative. That is why "no errors" is not evidence of correctness.

**Trade-off.** None to accepting the anomaly; the real choice is how to prevent it (next entry).

**Likely questions**
- *Give an example of a race condition in a payments system.* Two transfers reading one balance and each writing back their own result; one disappears. Or two withdrawals both passing the funds check.
- *Does `@Transactional` fix it?* No. A transaction makes the writes all-or-nothing; it does not make two transactions take turns. You need a lock, a version check, or SERIALIZABLE isolation.
- *Why did the database CHECK not catch it?* The bad values were still valid numbers. A constraint catches invalid states, not wrong-but-valid ones; reconciliation exists for those.

## 8. Row locks with `SELECT ... FOR UPDATE`

**Plain words.** `FOR UPDATE` on a SELECT says "I intend to change these rows, so nobody else may change or lock them until my transaction ends". A second transaction that asks for the same row waits. When the first commits, the second continues and, under READ COMMITTED, reads the row as it is now (with the first one's change), not as it was. So read-check-write on an account becomes safe because only one transaction at a time can be between the read and the write.

**Why here.** `AccountLockService.lock` runs it at the start of every deposit and transfer. `AccountLockServiceIntegrationTest` proves the lock is real (a second database session using `FOR UPDATE NOWAIT` on the row is refused with SQLSTATE 55P03 while the first transaction is open) and that a waiting transaction sees the committed balance (1,000 becomes 1,050, not a stale 1,000).

**Trade-off.** Accounts that are locked queue, so a very busy account serialises its transfers, and every lock is held until commit, so transactions must be short and must not call other services over the network. A 5 second `lock_timeout` on every pooled connection makes a stuck wait fail rather than hang.

**Likely questions**
- *What does `FOR UPDATE` do to other readers?* Plain SELECTs are not blocked (PostgreSQL uses multi-version concurrency); other `FOR UPDATE` or UPDATE/DELETE on the same rows wait.
- *When is the lock released?* At commit or rollback of the transaction, never earlier. A lock taken outside a transaction would be released immediately, which is why `lock()` is `Propagation.MANDATORY`.
- *What if someone holds a lock forever?* `lock_timeout` makes the waiter fail after 5 seconds with a lock-timeout error; `NOWAIT` fails immediately; `SKIP LOCKED` skips locked rows (useful for job queues, not here).

## 9. Deadlock, and locking in id order

**Plain words.** Deadlock: transfer 1 locks A and wants B; transfer 2 locks B and wants A; each waits for the other for ever. PostgreSQL notices after `deadlock_timeout` (1 second by default) and aborts one of them with SQLSTATE 40P01. The cure is to make it impossible: if every transaction locks the accounts it needs in the same global order, then both transfers lock A first, one waits at A, and the cycle can never form.

**Why here.** The lock is a single `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`, and PostgreSQL takes the row locks in the order it returns the rows. The ordering is done by the database, not by sorting in Java: Java's `UUID.compareTo` and PostgreSQL's `uuid` ordering can disagree, and a mix of the two orders would bring the deadlock back. `AccountLockServiceIntegrationTest` compares the returned order with PostgreSQL's own. `DeadlockIntegrationTest` fires 400 alternating A-to-B and B-to-A transfers from 16 threads; all complete.

**Proof the test can fail.** `DeadlockMutationIntegrationTest` swaps in a test-only lock service that locks one account at a time in alternating order. In six recorded runs it produced between 27 and 31 deadlock aborts (40P01) out of 40 transfers each time, and the ledger still reconciled (aborted transactions roll back completely).

**Trade-off.** Ordered locking prevents deadlock only if every code path that locks accounts uses the same order. Today there is exactly one path (`AccountLockService`), which is why it is the only place that locks. A future feature that locked accounts elsewhere would need to use it.

**Likely questions**
- *What are the four conditions for deadlock and which do you break?* Mutual exclusion, hold and wait, no pre-emption, circular wait. Ordered locking breaks circular wait.
- *How do you handle a deadlock that still happens?* PostgreSQL aborts one transaction; the application sees a 40P01 error (Spring maps it to `CannotAcquireLockException`) and can retry the whole transaction. Here it cannot occur through the service, so there is no retry code.
- *Why not lock the two accounts with two separate queries?* Then the order depends on the caller (from-then-to), and opposite transfers deadlock. One statement with `ORDER BY` fixes the order for everyone.

## 10. Pessimistic versus optimistic locking versus SERIALIZABLE (and why pessimistic here)

**Plain words.** Three ways to stop the lost update. Pessimistic: lock first, so competitors wait. Optimistic: do not lock; add a version number to the row, and at write time say "update only if the version is still what I read"; if someone else got there first the write fails and you retry. SERIALIZABLE: ask the database to behave as if transactions ran one after another, and it aborts any transaction whose result could differ from some serial order; you retry those.

**Why pessimistic here.** A payments account can be hot (many transfers at once), so under optimistic locking many attempts would fail and retry, which wastes work and needs retry code with limits. With a lock the queue is orderly and the funds check is a plain `if` on a row nobody else can change. The full comparison is in `docs/DECISIONS.md`, entry 23.

**Trade-off.** Pessimistic serialises work on the same account and holds locks during the transaction; optimistic gives higher throughput when conflicts are rare; SERIALIZABLE needs no explicit locks but aborts more and every caller needs retries.

**Likely questions**
- *When would you choose optimistic?* Low contention and long user think time (edit a profile), where holding a lock is unacceptable. Not for money movement on shared accounts.
- *Is `@Version` enough on its own for transfers?* It detects the conflict and prevents the lost update, but you must retry, and the funds check must be redone on the fresh balance.
- *Why not just SERIALIZABLE?* It works, but it is coarse: it can abort transactions that touch different rows, and every request path needs retry handling. Explicit row locks make the contention visible and local.

## 11. Why the locking query must be the first load of the accounts

**Plain words.** Hibernate keeps every entity it has loaded during a transaction in a cache (the persistence context). If code loads account A, then later runs the locking query, the database locks the row and returns the current data, but Hibernate sees "I already have account A" and hands back the OLD object, with the old balance. The lock would exist and the code would still decide on stale data.

**Why here.** The rule is followed by construction: the services check ownership with `existsByIdAndOwnerUserId`, a yes/no query that loads no entity, then call `lock(...)`, and only look at balances on what it returns. Both services have comments saying so. The showcase test would catch a violation: balances would drift.

**Trade-off.** It is a discipline, not something the compiler enforces. A defensive alternative is to `refresh` each entity after locking, at the cost of an extra query.

**Likely questions**
- *What is the persistence context?* Hibernate's per-transaction identity map of loaded entities; it means the same row loaded twice is one Java object, and it is why a later query may not reflect what the database now holds.
- *How would you find such a bug?* A concurrency test with reconciliation afterwards. The failure is a silent numeric drift, not an exception.

## 12. Reconciliation as a safety net

**Plain words.** Every rule above is enforced where the money moves. Reconciliation is an independent check that recomputes the truth from the entries and compares: every transaction's entries sum to zero; all entries together sum to zero; each customer's cached balance equals the sum of their entries; no balance is negative. It reports, it never fixes.

**Why here.** The tests use it to assert the invariants after concurrent load, and the design would let a scheduled job or an admin endpoint run it in production (no endpoint was built). It runs as a few set-based SQL queries (the database adds up the entries) in one read-only `REPEATABLE READ` transaction, so all the checks see the same snapshot and a transfer committing half-way through cannot cause a false alarm. It uses LEFT JOIN so an account with no entries at all is still checked.

**Honest scope.** In the test suite reconciliation is run over the ids each test created, because the shared test database contains other tests' deliberately corrupted rows. `ReconciliationScope.wholeLedger()` is the real-run form. The scoped tests prove it detects a lone entry, a wrong cached balance, a cached balance on an account with no entries, and a ledger-derived negative balance (each injected with a test-only helper and then removed).

**Trade-off.** It scans the ledger, so on a big system it should run off-peak or against a replica; it holds a snapshot open while it runs.

**Likely questions**
- *If the entries are the source of truth, why keep a cached balance?* Reads are fast; summing every entry for every balance request does not scale. Reconciliation is the price of that cache.
- *What would you do when reconciliation finds a mismatch?* Alert, stop trusting the cache for that account, find the transaction that caused it, and correct with a new reversing or adjusting entry, never by editing history.
- *Why REPEATABLE READ here and READ COMMITTED for transfers?* Reconciliation needs one consistent view across several queries; transfers need to see the latest committed value after waiting for a lock, which READ COMMITTED gives and REPEATABLE READ would turn into a serialisation error.

## A concurrency test that cannot fail (what was avoided)

A concurrent test can pass for the wrong reason: a thread pool that never overlaps the work, `Future`s whose exceptions are never read, a run in which every request was rejected, or a pool of database connections smaller than the thread count so requests queue outside the database. This project's tests use a start gate, read every result with a timeout, assert that some transfers succeeded and some were refused, size the connection pool above the thread count, use a seeded Random for the inputs, and were each made to fail on purpose (the naive lock for the showcase, the alternating-order lock for the deadlock test). If asked "how do you know the test is meaningful", the answer is the recorded red run.

---

## Walkthrough: the four files that matter for idempotency (Phase 5)

1. `src/main/java/dev/joseph/ledger/service/IdempotentExecutor.java`: the whole protocol in one method. It is deliberately not `@Transactional`; it opens the transaction itself with a `TransactionTemplate`: claim the key, run the business call, store the response, commit. If claiming the key hits the unique constraint, the template has already rolled back and finished, and only then does the `catch` read the stored answer in a new transaction. `DepositService` and `TransferService` were not changed at all; they simply join the executor's transaction.
2. `src/main/java/dev/joseph/ledger/service/IdempotencyService.java`: the three database steps. `begin` deletes an expired row for this user and key, inserts the new row and flushes, and turns a violation of the constraint `uq_idempotency_user_key` (SQLSTATE 23505 plus that exact name) into `DuplicateIdempotencyKeyException`. `complete` writes the response onto the row. `replay` reads it back.
3. `src/main/java/dev/joseph/ledger/service/RequestHasher.java`: the request fingerprint, SHA-256 of `METHOD \n concrete path \n JSON of the validated request with sorted property names`. Same request in any JSON field order gives the same hash; a different amount, account, path or method gives a different one. `RequestHasherTest` shows each case.
4. `src/test/java/dev/joseph/ledger/ConcurrentIdempotencyIntegrationTest.java`: 20 identical requests on 20 threads create one ledger transaction, with a control (20 different keys create 20), a case that holds the first transaction open and looks in `pg_stat_activity` to see the duplicate genuinely waiting, and reconciliation at the end. The controller side is only a lambda in `DepositController` / `TransferController` that says "this is the work to make safe".

The story to tell: idempotency is a decorator around the business transaction, so the money code has no idea it exists. The guarantee comes from a unique index, not from Java code. The test was checked by breaking it on purpose (the key made unique per request): 20 transfers then executed instead of 1. Numbers are in `docs/CV_EVIDENCE.md`.

## 13. What idempotency is and why payments need it

**Plain words.** An operation is idempotent if doing it twice has the same effect as doing it once. On the network a client often cannot tell whether a request failed: the connection may drop after the server has committed the transfer but before the reply arrives. The only safe reaction for the client is to send it again, and without protection that moves the money twice. The client therefore puts a unique `Idempotency-Key` on each payment request; the server remembers the key and, if it sees it again, returns the first answer instead of doing the work again.

**Why here.** Deposits and transfers require the header (missing, blank, longer than 128 characters or characters outside letters, digits and `_ . : -` give a 400). A key belongs to one user, so two users may use the same key text.

**Trade-off.** Clients must generate and keep keys, and the server must store one row per payment. Reads and other methods (GET) are naturally idempotent and need none.

**Likely questions**
- *Difference between idempotent and safe methods?* Safe means no side effects (GET). Idempotent means repeating has no extra effect (PUT, DELETE). POST is neither by default, which is why payments add a key.
- *Who generates the key?* The client, once per intended payment (a UUID is fine), and reuses it for retries of that same payment only.
- *What if the client reuses a key for a different payment?* The server notices the request differs and answers 422 rather than silently returning the wrong payment's result.

## 14. Why a unique constraint and not "check then insert"

**Plain words.** The tempting code is: look up the key; if it is not there, do the work and save the key. Two requests arriving together can both look, both see nothing, and both do the work. The check and the insert are two steps, and anything can happen between them. A unique index makes the database do it as one step: the INSERT either succeeds or it does not, and the database serialises the competing inserts for us.

**Why here.** `begin` inserts the key row first, inside the business transaction, and flushes so that a violation is raised at that exact line, not later at commit. The key id is generated by the database (an identity column), so Spring Data does a plain INSERT and not the "SELECT to see if it exists, then INSERT" it would do for an entity whose id you assigned yourself. The concurrent test proves it and a mutation proves the test: with the row inserted under a random key (no collision possible) 20 identical transfers all executed and the payer lost 2,000 pence instead of 100.

**Trade-off.** The duplicate's INSERT waits inside PostgreSQL until the first transaction finishes, holding a database connection, so many duplicates need a connection pool at least as big as the number of simultaneous requests. The alternative `INSERT ... ON CONFLICT DO NOTHING` also waits, but does not raise an error, so the transaction stays usable; it was not chosen because the exception path shows the rollback-only problem this project wants to understand (docs/DECISIONS.md, entry 27).

**Likely questions**
- *Why not `synchronized` or a lock in Java?* It only works inside one server process. With two application instances only the database is shared.
- *Why not SELECT ... FOR UPDATE on the key?* There is no row to lock the first time; the unique index is exactly what arbitrates rows that do not exist yet.
- *What is a race condition here?* Two requests interleave so that a check they both passed is no longer true by the time they act.

## 15. What happens when two identical requests arrive at the same instant

**Plain words.** Both start a transaction and try to insert the same (user, key) row. PostgreSQL lets one insert proceed and makes the other wait, because it cannot yet know whether the first will commit. The winner does the transfer, stores its response on the key row and commits. The waiting insert now fails with the unique-violation error. The loser rolls back, then reads the winner's stored response in a new transaction and returns it. Both callers get the same answer and there is one ledger transaction.

**Proof.** `aDuplicateArrivingWhileTheFirstTransactionIsOpenWaitsThenReplaysItsResult` holds the first transaction open and asks PostgreSQL (`pg_stat_activity`) whether the duplicate's INSERT is waiting on a transaction id, then lets the first commit and checks the duplicate got the same body. The 20-thread tests then check the count of ledger transactions, key rows and balances, and that reconciliation is clean.

**Trade-off.** If the winner takes longer than the 5 second `lock_timeout` set on every connection (a very slow transfer, or a queue of other transfers on the same accounts), the waiting duplicate would fail with a lock timeout instead of replaying. Acceptable here; the client retries.

**Likely questions**
- *What if the first request crashes half way?* Its transaction rolls back, key row included, and the waiting duplicate's insert then succeeds and it does the work itself.
- *What if the server dies after commit but before replying?* The client retries and gets the stored response; that is the whole point.
- *How do you test something that depends on timing?* Release all threads from a latch together, read every result, and add one deterministic test that holds the winner open so the overlap is certain, not hoped for.

## 16. Why the replay happens after the rollback, in a new transaction

**Plain words.** Once a SQL statement fails in PostgreSQL, the whole transaction is "aborted": every later statement, even a plain SELECT, is refused until it ends. Spring also marks a transaction rollback-only when a transactional method throws, and even if you catch that exception and carry on, the eventual commit throws `UnexpectedRollbackException`. So you cannot catch the duplicate-key error and then read the stored response in the same transaction. The read has to happen after the transaction has ended.

**Why here.** `IdempotentExecutor` is not `@Transactional`. If it were, its own `catch` block would still be running inside the doomed transaction. Instead it uses a `TransactionTemplate`: the template rolls back and finishes, the exception reaches the `catch` with no transaction open, and `IdempotencyService.replay` runs in its own short read-only transaction. The executor refuses to run inside an existing transaction (`theExecutorRefusesToRunInsideAnotherTransaction`) so nobody can break this by accident. `spring.jpa.open-in-view` is false, so the read cannot be served from a session that still remembers the rolled-back attempt. There is no `REQUIRES_NEW`: a nested new transaction needs a second connection while the first is still held, which can exhaust the pool under load.

**Trade-off.** The idempotency logic is a wrapper the controllers must call, not something hidden in the services, so a new money endpoint that forgets to use the executor would not be idempotent. That is visible in the controller and easy to review.

**Likely questions**
- *What is rollback-only?* A flag on the transaction: something inside it failed, so the only allowed outcome is rollback. It is set when a `@Transactional` method throws a runtime exception, even if a caller catches it.
- *What does `Propagation.REQUIRES_NEW` do and why avoid it here?* It suspends the current transaction and starts another on a second connection; while the first stays open the pool can run dry.
- *Why is only a specific constraint treated as a duplicate?* Other unique or foreign key violations are real bugs or different rules (for example one reversal per transaction, later); treating every 23505 as "seen before" would hide them. `begin` checks the SQLSTATE and the constraint name `uq_idempotency_user_key`, and tests show a duplicate username and a missing user are not mistaken for it.

## 17. Failed requests are not replayed

**Plain words.** If the first attempt fails (for example insufficient funds), everything rolls back, and that includes the key row, so nothing is remembered. A retry with the same key runs the request again. Only a request that succeeded is stored.

**Why here.** It falls out of the design: the key row is in the same transaction as the money, so "key stored" and "money moved" cannot disagree. The test `aFailedFirstAttemptIsNotRememberedSoARetryRunsAgain` fails a transfer for lack of funds, tops up the account, and retries with the same key: it succeeds and moves money once. Under concurrency, 20 identical requests that all fail each fail cleanly with 422 and leave no key row.

**Trade-off.** A client never receives a "stored no". A retry costs the server the work again, and the answer can legitimately change (the account was topped up in between). Some payment APIs store failures too and make the opposite trade: repeatable answers, but a client that wants to retry after fixing the cause must use a new key. The other approach (a separate "processing" row committed first) is riskier: a crash leaves a stuck key.

**Likely questions**
- *Should a 500 be stored?* Not here; nothing was committed, so retrying is safe.
- *Should a validation error (400) use a key?* No: it is rejected before the executor and stores nothing.

## 18. Expiry and the request fingerprint

**Plain words.** Keys cannot be kept for ever, so each row has an `expires_at` (default 24 hours, setting `ledger.idempotency-ttl`). Once expired, the same key may be used again as a new request. There is a catch: an expired row still sits in the unique index, so a new INSERT with that key would collide with it for ever. `begin` therefore deletes an expired row for that (user, key) in the same transaction, then inserts. Two requests doing this at once are safe: the second delete finds nothing and the unique index still picks one winner. Time comes from an injected `Clock`, and `IdempotencyExpiryIntegrationTest` moves a test clock by 23, 24h01 and 25 hours instead of waiting.

**The fingerprint.** The key alone does not say whether a retry is the same request. The stored `request_hash` is SHA-256 of the HTTP method, the concrete path and the validated request object written as JSON with sorted property names. Hashing the object rather than the raw text means field order and whitespace do not matter; including the method and the actual path means the same key used on `/api/deposits` and `/api/transfers` never replays the other's result (422). This matters even more for reversals later (`/api/transactions/{id}/reversal`): the id has to be part of the hash.

**Trade-off.** A short TTL is cheap but a client retrying after a long outage may double-pay; a long TTL keeps more rows. There is no cleanup job, so expired rows stay until the key is reused.

**Likely questions**
- *How long should keys live?* Longer than the longest time a client could plausibly retry; 24 hours is a common default.
- *Why SHA-256 and not just compare the body?* A fixed 64-character value is easy to store and compare; it is a fingerprint, not a secret.
- *What if someone changes the request format later?* Old hashes no longer match new-format retries; acceptable for a 24 hour window.

## 19. What I would change for production

- Store the response body as plain text if byte-identical replay matters. It is JSONB here, which reorders keys and changes spacing, so tests compare replayed and fresh responses as JSON data (two replays are textually identical to each other).
- Add a scheduled job to delete expired keys, and monitoring of how often replays and 422s happen.
- Return 409 with "in progress" for a duplicate that would have to wait a long time, instead of blocking a connection; or use `ON CONFLICT DO NOTHING` to keep the transaction usable.
- Scope keys per endpoint or tenant if several services share the table; add rate limiting so nobody can fill the table with keys.
- Move the executor call into a shared web filter or annotation so a new endpoint cannot forget it.

**Likely questions**
- *What are the limitations of your approach?* Duplicates hold a connection while they wait; the 5 second lock timeout bounds the wait; failures are not remembered; expired rows are not cleaned up; it is tested on one database, not across several application instances (which the database constraint would still handle correctly).

---

## Walkthrough: the four files that matter for authentication (Phase 6)

Open them in this order; together they are the whole story of a login and of an authenticated request.

1. `src/main/java/dev/joseph/ledger/service/AuthService.java` registers a user (BCrypt hash, unique username, stored lower-case) and checks a password. It does not know about tokens.
2. `src/main/java/dev/joseph/ledger/security/JwtService.java` creates a signed token (`issue`) and checks one (`parse`). Time comes from the injected `Clock`. `parse` returns "empty" for every kind of bad token, so the reason never reaches the client.
3. `src/main/java/dev/joseph/ledger/security/JwtAuthenticationFilter.java` runs on every request: it reads `Authorization: Bearer ...` and, if the token is valid, records the user in the security context. `SecurityConfig.java` (same package) holds the rules: only `/api/auth/**` and `/actuator/health` are open, sessions are stateless, CSRF is off. `CurrentUserProvider.java` turns the context into an `ActingUser` for the controllers.
4. `src/test/java/dev/joseph/ledger/AuthApiIntegrationTest.java` is the proof: real registered users with real tokens, then expired, tampered, unsigned, wrong-key and missing tokens, and one user reaching for another user's account.

---

## 20. How JWT authentication works, step by step

**Plain words.**
1. `POST /api/auth/register` stores the username and a BCrypt hash of the password.
2. `POST /api/auth/login` checks the password and returns a token: three base64 pieces `header.payload.signature`. The payload holds the user id (`sub`), the role, the issue time (`iat`) and the expiry (`exp`). The signature is an HMAC-SHA-256 of the first two pieces, made with a secret key that only the server knows.
3. The client sends `Authorization: Bearer <token>` on every later request.
4. `JwtAuthenticationFilter` verifies the signature and expiry. If valid it stores the user in the security context, and controllers ask `CurrentUserProvider` who is calling. If not valid it stores nothing, and the rule "everything else needs authentication" answers 401.

Anyone can read a token (it is only encoded, not encrypted) but nobody without the secret can change it or make one: change one character and the signature no longer matches.

**Why here.** The server keeps no session, so any number of instances can serve any request. jjwt is used with one small hand-written filter so every step is visible in about 60 lines.

**Trade-off.** A token cannot be taken back before it expires (no logout, no revocation), so the lifetime is short (default 1 hour, `ledger.jwt.ttl`). Putting the role in the token means a role change only shows up after the next login.

**Likely questions**
- *Is a JWT encrypted?* No. It is signed, so it cannot be altered, but its contents are readable; never put secrets in it.
- *What stops someone changing `sub` to another user id?* The signature covers the payload. The test `aTokenWithAChangedSignatureOrPayloadIsRefused` does exactly this and gets 401.
- *What is the `alg: none` attack?* A forged token that claims it needs no signature. jjwt refuses unsigned tokens when a verification key is set, and `aTokenSignedWithAnotherKeyOrNotSignedAtAllIsRefused` checks it.
- *How does the server know a token has expired?* It compares `exp` with its `Clock`; the test moves the clock instead of sleeping.

## 21. Why BCrypt

**Plain words.** A password is never stored, only a hash. BCrypt is a hash designed to be slow (cost 10 means 2^10 rounds) and it adds a random salt to every hash, stored inside the result (`$2a$10$<salt><hash>`, 60 characters). The same password gives a different hash every time, and someone who steals the table has to guess passwords one slow attempt at a time.

**Why here.** It is what the brief asks for and what Spring Security ships. The plain `BCryptPasswordEncoder` is used (not the delegating encoder) so the stored value has no `{bcrypt}` prefix to explain. Tests read the column and check it is BCrypt and not the plaintext, and that two users with the same password have different hashes.

**Trade-off.** Slowness is the feature, but it costs CPU on every login (roughly 100 ms), so a flood of logins is a denial-of-service risk; production would add rate limiting. BCrypt only reads the first 72 bytes of a password, so longer passwords are rejected instead of being silently cut. Newer options (Argon2, scrypt) are harder to attack with GPUs.

**Likely questions**
- *Why not SHA-256?* It is fast, so an attacker can try billions of guesses a second. Password hashing must be slow.
- *What is a salt?* Random data mixed into each hash so equal passwords differ and precomputed tables (rainbow tables) are useless.
- *How do you stop attackers learning which usernames exist?* A wrong password and an unknown username return the identical 401, and an unknown username still does one BCrypt comparison against a dummy hash so it is not measurably faster. Registration does reveal a taken name (409); that is hard to avoid.

## 22. Why stateless sessions and CSRF switched off

**Plain words.** "Stateless" means the server remembers nothing between requests; the token is the proof each time. CSRF (cross-site request forgery) is an attack where a malicious web page makes your browser send a request to another site, and the browser attaches your login cookie automatically. This API uses no cookie: the client has to add the `Authorization` header by hand, which a hostile page in another tab cannot do. With nothing automatic to abuse, CSRF protection has nothing to protect and would only reject legitimate POSTs.

**Why here.** Spring Security turns CSRF protection on by default, so it is disabled explicitly in `SecurityConfig` with a comment. The same file turns off form login and basic auth, and the auto-created default user (`UserDetailsServiceAutoConfiguration`) is excluded in `application.yml`, so there is no second way in.

**Trade-off.** If the token were later kept in a cookie, CSRF protection would have to come back. A token kept in browser-readable storage can be stolen by cross-site scripting (XSS); this API has no browser front end.

**Likely questions**
- *When is CSRF protection needed?* When authentication is sent automatically by the browser (cookies, basic auth).
- *Is disabling CSRF safe?* Only because of the point above; it depends on how the credential is carried.

## 23. 401 versus 403 versus 404

**Plain words.** 401: "I do not know who you are" (no token, bad token, expired token, wrong login). 403: "I know who you are, and you may not do this". 404: "there is nothing here for you". For another user's account this project answers 404, the same answer as for an id that does not exist.

**Why here.** A 403 would confirm that the account exists; with 404 an attacker cannot use the API to discover which account ids are real. The rule lives in the services (`AccountService.get` filters by owner; `DepositService` and `TransferService` run the ownership query first), so it holds whichever controller calls them. `aUserCannotReadListDepositToOrTransferFromAnotherUsersAccount` checks read, list, deposit and transfer, checks the 404 body matches the one for an unknown id, and checks nothing moved. Sending money TO someone else's account is allowed: that is what a payment is.

**Trade-off.** A genuine "you lack permission" looks the same as "does not exist", which makes support a little harder. A 403 handler (`ProblemJsonSecurityHandlers`) is wired, but nothing returns 403 yet because this build has no role-restricted URLs.

**Likely questions**
- *Why not 403?* It leaks existence. Many APIs (GitHub for private repositories) return 404 for the same reason.
- *Does the 404 hide the id space completely?* Ids are random UUIDs, so guessing is impractical anyway; this is a second layer.

## 24. Where the JWT secret lives, and why not in git

**Plain words.** The signing key is the whole security of the tokens: anyone who has it can create a valid token for any user. So it is read only from the environment variable `LEDGER_JWT_SECRET`. There is no default in `application.yml`, in code or in `docker-compose.yml`; `.env.example` (committed) holds only a fake placeholder that is deliberately too short to work, and the real `.env` is git-ignored. Compose refuses to start if the variable is unset (`${LEDGER_JWT_SECRET:?...}`).

**Why here.** If a default existed, every deployment that forgot to set one would run with a key published on GitHub. Instead the app fails at startup if the secret is missing or shorter than 32 characters (`JwtProperties`, `JwtSecretFailFastTest`). The tests use a separate fake key from `application-test.yml`.

**A bug this design caught.** Spring's own validation message for a too-short value prints the rejected value, so a 31-character real secret would have been written to the startup log. The test `theRealApplicationFailsToStartWithAShortSecretAndDoesNotPrintItInTheError` showed it, and the check now lives in the record constructor with a message that does not contain the value. `JwtProperties.toString()` is overridden too, and so are the request records that hold passwords, so a stray log line cannot leak them.

**Trade-off.** Environment variables are visible to anyone who can read the process environment; production would use a secrets manager (AWS Secrets Manager, Azure Key Vault) and rotate the key.

**Likely questions**
- *What if the secret leaks?* Change it: every existing token becomes invalid and everyone logs in again. Supporting two keys at once (a key id in the token header) allows rotation without logging everyone out.
- *Why HS256 and not RS256?* One service both signs and verifies, so a shared secret is enough. If other services had to verify tokens, an asymmetric key pair would avoid sharing the signing key.

## 25. What I would add for production

- Refresh tokens and a short access-token lifetime, with a revocation list or a token version stored on the user so logout and "password changed" really end old tokens.
- Rate limiting and lockout on login and register (BCrypt is deliberately expensive), and a password-strength check beyond length.
- Move the secret to a secrets manager and rotate it (key id in the token header).
- Email verification and password reset, and an audit log of logins and denied access (out of scope here).
- HTTPS in front of the app: a bearer token sent over plain HTTP can be read by anyone on the path.

**Likely questions**
- *What are the weaknesses of your login?* No rate limiting, no revocation before expiry, registration reveals taken usernames, role changes wait for the next login.
- *What happens to a stolen token?* It works until it expires; the short lifetime limits the damage.
