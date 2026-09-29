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

## 6. The stub acting user and why services take an `ActingUser` parameter

**Plain words.** Real login arrives in Phase 6. Until then the API reads the caller's id from an `X-Acting-User-Id` header (a marked `TODO`, trivially spoofable, deleted in Phase 6). Services never look at a security context; they receive an `ActingUser` record.

**Why here.** Business rules (who owns which account) stay the same when authentication changes; only the code that builds `ActingUser` changes. It also makes services trivially testable: pass a value, no security setup.

**Trade-off.** Nothing before Phase 6 is secured, and the README says so.

**Likely questions**
- *Why not read `SecurityContextHolder` in the service?* It couples business logic to the web layer and to a thread-local, and makes tests need a security context.
- *Why return 404, not 403, for someone else's account?* It does not reveal that the id exists.

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
