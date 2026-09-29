# Interview prep

Each entry: what it is in plain words, why it is built this way here, the trade-off, and questions an interviewer is likely to ask with short model answers. Later phases append to this file. Everything below describes code that exists in the repository; the pointers name the file to open.

## Walkthrough: the four files that matter for money movement (Phase 3)

Open them in this order; together they are the whole story of a transfer.

1. `src/main/java/dev/joseph/ledger/service/TransferService.java` is the business rule for a transfer. One `@Transactional` method: cheap checks first (amount, currency, from is not to), then "does the caller own the paying account" (a yes/no query that loads nothing), then `lockService.lock(...)`, then the checks that need the locked rows (ACTIVE, currency, enough money), then `postingService.post(...)`. `DepositService` is the same shape with the outside world as the other side.
2. `src/main/java/dev/joseph/ledger/service/LedgerPostingService.java` is the only code that writes ledger entries or changes a cached balance. It refuses anything that does not sum to zero, has a zero line or mixes currencies, inserts the transaction row and the entries, then applies each customer line to the account entity. It must be called inside a transaction (`Propagation.MANDATORY`).
3. `src/main/java/dev/joseph/ledger/service/AccountLockService.java` is the one place accounts are fetched for update. In Phase 3 its body is a plain read, on purpose; Phase 4 swaps the body for `SELECT ... ORDER BY id FOR UPDATE` and nothing else changes.
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

**Why here.** The check "does the payer have enough money" reads the balance in Java, so the code needs the loaded value anyway, and the flow reads top to bottom: lock, check, change. It also keeps the concurrency story honest: with the row lock in place (Phase 4) read-check-write is safe; with the lock deliberately absent (today's naive `AccountLockService`) it loses updates, and the Phase 4 concurrency test is written to show that failing first and passing after the lock is added. Option (b) would hide that race because the SQL increment is atomic by itself.

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
