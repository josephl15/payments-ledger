package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.joseph.ledger.config.LedgerProperties;
import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.SystemAccountIds;
import dev.joseph.ledger.domain.TransactionType;
import dev.joseph.ledger.service.AccountNotUsableException;
import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.ActingUser;
import dev.joseph.ledger.service.DepositCommand;
import dev.joseph.ledger.service.DepositService;
import dev.joseph.ledger.service.EntryLine;
import dev.joseph.ledger.service.InsufficientFundsException;
import dev.joseph.ledger.service.InvalidRequestException;
import dev.joseph.ledger.service.LedgerPostingService;
import dev.joseph.ledger.service.PostedTransaction;
import dev.joseph.ledger.service.ResourceNotFoundException;
import dev.joseph.ledger.service.TransferCommand;
import dev.joseph.ledger.service.TransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Deposits and transfers through the service layer against real PostgreSQL: the entries, the cached balances, every
 * rejected input, and the invariants (each transaction sums to zero, cached balance equals the entries, no customer
 * balance below zero).
 *
 * <p>Isolation: the ledger cannot be emptied between tests, so each test creates its own users and accounts and
 * asserts only on those (see docs/DECISIONS.md, entry 16).
 */
class MoneyMovementIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountService accountService;

    @Autowired
    DepositService depositService;

    @Autowired
    TransferService transferService;

    @Autowired
    LedgerPostingService postingService;

    @Autowired
    LedgerProperties properties;

    private LedgerTestData data() {
        return new LedgerTestData(jdbc);
    }

    private ActingUser newActor() {
        return new ActingUser(data().newUser());
    }

    private UUID openAccount(ActingUser actor) {
        return accountService.open(actor, "test account").getId();
    }

    private PostedTransaction deposit(ActingUser actor, UUID account, long amount) {
        return depositService.deposit(actor, new DepositCommand(account, amount, "GBP", "test deposit"));
    }

    private PostedTransaction transfer(ActingUser actor, UUID from, UUID to, long amount) {
        return transferService.transfer(actor, new TransferCommand(from, to, amount, "GBP", "test transfer"));
    }

    private long balance(UUID account) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account);
    }

    private long entrySum(UUID account) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE account_id = ?", Long.class, account);
    }

    private long transactionCountCreatedBy(ActingUser actor) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE created_by_user_id = ?", Long.class, actor.userId());
    }

    private long entryCountFor(UUID account) {
        return data().entryCountForAccount(account);
    }

    // ---------------------------------------------------------------- accounts

    @Test
    void openedAccountIsActiveGbpWithZeroBalanceAndOwnedByTheActor() {
        ActingUser actor = newActor();

        Account account = accountService.open(actor, "Current account");

        assertThat(account.getId()).isNotNull();
        assertThat(account.getOwnerUserId()).isEqualTo(actor.userId());
        assertThat(account.getCurrency()).isEqualTo("GBP");
        assertThat(account.getBalanceMinor()).isZero();
        assertThat(balance(account.getId())).isZero();
    }

    @Test
    void openingAnAccountForAnUnknownUserIsNotFound() {
        assertThatThrownBy(() -> accountService.open(new ActingUser(UUID.randomUUID()), "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listReturnsOnlyTheActorsAccountsAndGetHidesOthers() {
        ActingUser alice = newActor();
        ActingUser bob = newActor();
        UUID aliceAccount = openAccount(alice);
        UUID bobAccount = openAccount(bob);

        assertThat(accountService.list(alice)).extracting(Account::getId).containsExactly(aliceAccount);
        assertThat(accountService.get(alice, aliceAccount).getId()).isEqualTo(aliceAccount);
        assertThatThrownBy(() -> accountService.get(alice, bobAccount)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> accountService.get(alice, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        // A system account is never visible through the customer API either.
        assertThatThrownBy(() -> accountService.get(alice, SystemAccountIds.EXTERNAL_FUNDING))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- deposits

    @Test
    void depositWritesTwoBalancedEntriesAndRaisesTheCachedBalance() {
        ActingUser actor = newActor();
        UUID account = openAccount(actor);

        PostedTransaction posted = deposit(actor, account, 12_500);

        assertThat(posted.transaction().getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(posted.transaction().getCreatedByUserId()).isEqualTo(actor.userId());
        assertThat(data().sumForTransaction(posted.transaction().getId())).isZero();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT account_id, amount_minor FROM ledger_entries WHERE transaction_id = ? ORDER BY amount_minor",
                posted.transaction().getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("account_id")).isEqualTo(SystemAccountIds.EXTERNAL_FUNDING);
        assertThat(((Number) rows.get(0).get("amount_minor")).longValue()).isEqualTo(-12_500L);
        assertThat(rows.get(1).get("account_id")).isEqualTo(account);
        assertThat(((Number) rows.get(1).get("amount_minor")).longValue()).isEqualTo(12_500L);
        assertThat(balance(account)).isEqualTo(12_500L);
    }

    @Test
    void depositLeavesTheSystemAccountWithoutACachedBalance() {
        ActingUser actor = newActor();
        deposit(actor, openAccount(actor), 100);

        assertThat(jdbc.queryForObject(
                        "SELECT balance_minor FROM accounts WHERE id = ?", Long.class, SystemAccountIds.EXTERNAL_FUNDING))
                .isNull();
    }

    @Test
    void depositIntoSomeoneElsesOrUnknownOrSystemAccountIsNotFoundAndWritesNothing() {
        ActingUser alice = newActor();
        ActingUser bob = newActor();
        UUID bobAccount = openAccount(bob);

        assertThatThrownBy(() -> deposit(alice, bobAccount, 100)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> deposit(alice, UUID.randomUUID(), 100)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> deposit(alice, SystemAccountIds.EXTERNAL_FUNDING, 100))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(balance(bobAccount)).isZero();
        assertThat(transactionCountCreatedBy(alice)).isZero();
    }

    @Test
    void depositRejectsBadAmountsAndCurrency() {
        ActingUser actor = newActor();
        UUID account = openAccount(actor);

        assertThatThrownBy(() -> deposit(actor, account, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> deposit(actor, account, -5)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> deposit(actor, account, properties.maxAmountMinor() + 1))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> depositService.deposit(actor, new DepositCommand(account, 100, "USD", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(transactionCountCreatedBy(actor)).isZero();

        // The configured maximum itself is allowed.
        deposit(actor, account, properties.maxAmountMinor());
        assertThat(balance(account)).isEqualTo(properties.maxAmountMinor());
    }

    @Test
    void depositIntoAClosedAccountIsRejected() {
        ActingUser actor = newActor();
        UUID account = openAccount(actor);
        jdbc.update("UPDATE accounts SET status = 'CLOSED' WHERE id = ?", account);

        assertThatThrownBy(() -> deposit(actor, account, 100)).isInstanceOf(AccountNotUsableException.class);
        assertThat(entryCountFor(account)).isZero();
    }

    // ---------------------------------------------------------------- transfers

    @Test
    void transferWritesTwoBalancedEntriesAndMovesTheCachedBalances() {
        ActingUser actor = newActor();
        UUID from = openAccount(actor);
        UUID to = openAccount(newActor());
        deposit(actor, from, 10_000);

        PostedTransaction posted = transfer(actor, from, to, 3_000);

        assertThat(posted.transaction().getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(posted.entries()).hasSize(2);
        assertThat(data().sumForTransaction(posted.transaction().getId())).isZero();
        assertThat(balance(from)).isEqualTo(7_000L);
        assertThat(balance(to)).isEqualTo(3_000L);
        assertThat(entrySum(from)).isEqualTo(7_000L);
        assertThat(entrySum(to)).isEqualTo(3_000L);
    }

    @Test
    void transferOfTheWholeBalanceIsAllowedAndLeavesZero() {
        ActingUser actor = newActor();
        UUID from = openAccount(actor);
        UUID to = openAccount(actor);
        deposit(actor, from, 500);

        transfer(actor, from, to, 500);

        assertThat(balance(from)).isZero();
        assertThat(balance(to)).isEqualTo(500L);
    }

    @Test
    void transferWithInsufficientFundsIsRejectedAndWritesNothing() {
        ActingUser actor = newActor();
        UUID from = openAccount(actor);
        UUID to = openAccount(actor);
        deposit(actor, from, 500);

        assertThatThrownBy(() -> transfer(actor, from, to, 501)).isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(from)).isEqualTo(500L);
        assertThat(balance(to)).isZero();
        assertThat(entryCountFor(from)).isEqualTo(1); // only the deposit entry
        assertThat(entryCountFor(to)).isZero();
    }

    @Test
    void transferFromAnAccountTheActorDoesNotOwnIsNotFound() {
        ActingUser alice = newActor();
        ActingUser bob = newActor();
        UUID aliceAccount = openAccount(alice);
        UUID bobAccount = openAccount(bob);
        deposit(bob, bobAccount, 1_000);

        assertThatThrownBy(() -> transfer(alice, bobAccount, aliceAccount, 100))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(balance(bobAccount)).isEqualTo(1_000L);
        assertThat(balance(aliceAccount)).isZero();
    }

    @Test
    void transferToAnUnknownOrSystemAccountIsNotFound() {
        ActingUser actor = newActor();
        UUID from = openAccount(actor);
        deposit(actor, from, 1_000);

        assertThatThrownBy(() -> transfer(actor, from, UUID.randomUUID(), 100))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> transfer(actor, from, SystemAccountIds.EXTERNAL_FUNDING, 100))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(balance(from)).isEqualTo(1_000L);
    }

    @Test
    void transferBetweenTheSameAccountIsInvalid() {
        ActingUser actor = newActor();
        UUID account = openAccount(actor);
        deposit(actor, account, 1_000);

        assertThatThrownBy(() -> transfer(actor, account, account, 100)).isInstanceOf(InvalidRequestException.class);
        assertThat(balance(account)).isEqualTo(1_000L);
    }

    @Test
    void transferRejectsBadAmountsAndCurrency() {
        ActingUser actor = newActor();
        UUID from = openAccount(actor);
        UUID to = openAccount(actor);
        deposit(actor, from, 1_000);

        assertThatThrownBy(() -> transfer(actor, from, to, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> transfer(actor, from, to, -1)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> transfer(actor, from, to, properties.maxAmountMinor() + 1))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> transfer(actor, from, to, Long.MAX_VALUE)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> transferService.transfer(actor, new TransferCommand(from, to, 100, "EUR", null)))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(balance(from)).isEqualTo(1_000L);
    }

    @Test
    void transferInvolvingAClosedAccountIsRejected() {
        ActingUser actor = newActor();
        UUID from = openAccount(actor);
        UUID to = openAccount(actor);
        deposit(actor, from, 1_000);

        jdbc.update("UPDATE accounts SET status = 'CLOSED' WHERE id = ?", to);
        assertThatThrownBy(() -> transfer(actor, from, to, 100)).isInstanceOf(AccountNotUsableException.class);

        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?", to);
        jdbc.update("UPDATE accounts SET status = 'CLOSED' WHERE id = ?", from);
        assertThatThrownBy(() -> transfer(actor, from, to, 100)).isInstanceOf(AccountNotUsableException.class);

        assertThat(balance(from)).isEqualTo(1_000L);
        assertThat(balance(to)).isZero();
    }

    @Test
    void transferBetweenDifferentCurrencyAccountsIsRejected() {
        ActingUser actor = newActor();
        UUID from = openAccount(actor);
        deposit(actor, from, 1_000);
        // The API only opens GBP accounts, so a foreign-currency account has to be inserted directly.
        UUID usdAccount = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounts (id, owner_user_id, type, name, currency, status, balance_minor) "
                        + "VALUES (?, ?, 'CUSTOMER', 'usd', 'USD', 'ACTIVE', 0)",
                usdAccount,
                actor.userId());

        assertThatThrownBy(() -> transfer(actor, from, usdAccount, 100)).isInstanceOf(AccountNotUsableException.class);

        assertThat(balance(from)).isEqualTo(1_000L);
        assertThat(balance(usdAccount)).isZero();
    }

    // ---------------------------------------------------------------- the posting path

    @Test
    void postingOutsideATransactionFailsLoudly() {
        // MANDATORY propagation: the posting service refuses to run without a transaction that is already open,
        // so a forgotten @Transactional (or a self-invocation that skips the proxy) cannot commit half a transfer.
        List<EntryLine> lines = List.of(
                new EntryLine(SystemAccountIds.EXTERNAL_FUNDING, -1), new EntryLine(SystemAccountIds.EXTERNAL_PAYOUTS, 1));

        assertThatThrownBy(() -> postingService.post(
                        TransactionType.DEPOSIT, null, data().newUser(), "GBP", lines, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------- invariants over a sequence

    @Test
    void mixedSequenceKeepsEveryInvariant() {
        Random random = new Random(20260929L); // fixed seed: the same sequence on every run
        ActingUser actor = newActor();
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            accounts.add(openAccount(actor));
        }
        List<UUID> transactionIds = new ArrayList<>();
        int rejectedForFunds = 0;

        for (int step = 0; step < 60; step++) {
            UUID a = accounts.get(random.nextInt(accounts.size()));
            UUID b = accounts.get(random.nextInt(accounts.size()));
            if (step % 4 == 0) {
                transactionIds.add(deposit(actor, a, 1 + random.nextInt(5_000)).transaction().getId());
            } else if (!a.equals(b)) {
                try {
                    transactionIds.add(transfer(actor, a, b, 1 + random.nextInt(4_000)).transaction().getId());
                } catch (InsufficientFundsException expected) {
                    rejectedForFunds++;
                }
            }
            // Invariant 3 after every step: no customer balance below zero.
            for (UUID account : accounts) {
                assertThat(balance(account)).isGreaterThanOrEqualTo(0L);
            }
        }

        assertThat(transactionIds).isNotEmpty();
        assertThat(rejectedForFunds).as("the sequence should include some rejected overdrafts").isPositive();

        // Invariant 1: every transaction sums to zero.
        for (UUID id : transactionIds) {
            assertThat(data().sumForTransaction(id)).as("transaction %s", id).isZero();
        }
        // Invariant 2, scoped to this test's data (the shared database also holds other tests' deliberately
        // corrupted rows, so a whole-table sum cannot be asserted): all entries of all transactions this actor
        // created sum to zero.
        Long scopedSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(e.amount_minor), 0) FROM ledger_entries e "
                        + "JOIN ledger_transactions t ON t.id = e.transaction_id WHERE t.created_by_user_id = ?",
                Long.class,
                actor.userId());
        assertThat(scopedSum).isZero();
        // Each cached balance equals the sum of its entries, and the customer balances together equal the money
        // that came in from EXTERNAL_FUNDING.
        long customerTotal = 0;
        for (UUID account : accounts) {
            assertThat(balance(account)).isEqualTo(entrySum(account));
            customerTotal += balance(account);
        }
        Long fundingOut = jdbc.queryForObject(
                "SELECT COALESCE(SUM(e.amount_minor), 0) FROM ledger_entries e "
                        + "JOIN ledger_transactions t ON t.id = e.transaction_id "
                        + "WHERE t.created_by_user_id = ? AND e.account_id = ?",
                Long.class,
                actor.userId(),
                SystemAccountIds.EXTERNAL_FUNDING);
        assertThat(customerTotal).isEqualTo(-fundingOut);
    }
}
