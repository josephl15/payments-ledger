package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.ActingUser;
import dev.joseph.ledger.service.DepositCommand;
import dev.joseph.ledger.service.DepositService;
import dev.joseph.ledger.service.ReconciliationReport;
import dev.joseph.ledger.service.ReconciliationScope;
import dev.joseph.ledger.service.ReconciliationService;
import dev.joseph.ledger.service.TransferCommand;
import dev.joseph.ledger.service.TransferService;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ReconciliationService against real PostgreSQL: it reports a clean bill of health on data written by the real
 * services, and it detects each kind of deliberate corruption (then the corruption is removed again).
 *
 * <p>Every test uses the scoped form ({@link ReconciliationScope#of}) with the ids it created. The database is shared
 * by all test classes and other tests leave deliberately unbalanced rows behind (docs/DECISIONS.md, entry 16), so the
 * whole-ledger form cannot be asserted clean here; a fresh production database is the case it is meant for.
 */
class ReconciliationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    AccountService accountService;

    @Autowired
    DepositService depositService;

    @Autowired
    TransferService transferService;

    private LedgerTestData data() {
        return new LedgerTestData(jdbc);
    }

    private Set<UUID> transactionsCreatedBy(UUID userId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT id FROM ledger_transactions WHERE created_by_user_id = ?", UUID.class, userId));
    }

    /** Removes ledger rows through the trigger-bypassing test helper, then puts the cached balance back to zero. */
    private void removeLedgerRows(Set<UUID> transactionIds, Set<UUID> accountIds) throws Exception {
        ReplicaRole.asReplica(dataSource, c -> {
            for (UUID transactionId : transactionIds) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ledger_entries WHERE transaction_id = ?")) {
                    ps.setObject(1, transactionId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ledger_transactions WHERE id = ?")) {
                    ps.setObject(1, transactionId);
                    ps.executeUpdate();
                }
            }
        });
        for (UUID accountId : accountIds) {
            jdbc.update("UPDATE accounts SET balance_minor = 0 WHERE id = ?", accountId);
        }
    }

    @Test
    void cleanDataWrittenByTheRealServicesReconcilesCleanAtRepeatableRead() {
        ActingUser actor = new ActingUser(data().newUser());
        UUID a = accountService.open(actor, "a").getId();
        UUID b = accountService.open(actor, "b").getId();
        UUID neverUsed = accountService.open(actor, "never used").getId();
        depositService.deposit(actor, new DepositCommand(a, 10_000, "GBP", null));
        transferService.transfer(actor, new TransferCommand(a, b, 2_500, "GBP", null));
        transferService.transfer(actor, new TransferCommand(b, a, 400, "GBP", null));

        ReconciliationReport report =
                reconciliation.reconcile(ReconciliationScope.of(Set.of(a, b, neverUsed), transactionsCreatedBy(actor.userId())));

        assertThat(report.isClean()).as(report.summary()).isTrue();
        assertThat(report.isolationLevel()).isEqualTo("repeatable read");
        assertThat(report.transactionsChecked()).isEqualTo(3);
        assertThat(report.accountsChecked()).isEqualTo(3);
        assertThat(report.entrySum()).isZero();
    }

    @Test
    void anUnbalancedTransactionAndAWrongCachedBalanceAreBothDetected() throws Exception {
        LedgerTestData data = data();
        UUID user = data.newUser();
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");
        Set<UUID> accounts = Set.of(account);
        Set<UUID> transactions = Set.of(tx);
        try {
            data.newEntry(tx, account, 100); // a lone credit: the transaction sums to +100
            jdbc.update("UPDATE accounts SET balance_minor = 500 WHERE id = ?", account); // cache says 500, entries say 100

            ReconciliationReport report = reconciliation.reconcile(ReconciliationScope.of(accounts, transactions));

            assertThat(report.isClean()).isFalse();
            assertThat(report.unbalancedTransactions())
                    .containsExactly(new ReconciliationReport.UnbalancedTransaction(tx, 100));
            assertThat(report.entrySum()).isEqualTo(100);
            assertThat(report.balanceMismatches())
                    .containsExactly(new ReconciliationReport.BalanceMismatch(account, 500, 100));
            assertThat(report.negativeBalances()).isEmpty();
        } finally {
            removeLedgerRows(transactions, accounts);
        }

        assertThat(reconciliation.reconcile(ReconciliationScope.of(accounts, Set.of())).isClean())
                .as("account is back to zero with no entries")
                .isTrue();
    }

    @Test
    void anAccountWithNoEntriesAtAllButANonZeroCachedBalanceIsStillDetected() throws Exception {
        // Guards the LEFT JOIN: with an inner join an account without entries would drop out of the check.
        LedgerTestData data = data();
        UUID account = data.newAccount(data.newUser(), 750);
        try {
            ReconciliationReport report =
                    reconciliation.reconcile(ReconciliationScope.of(Set.of(account), Set.of()));

            assertThat(report.balanceMismatches())
                    .containsExactly(new ReconciliationReport.BalanceMismatch(account, 750, 0));
            assertThat(report.isClean()).isFalse();
        } finally {
            removeLedgerRows(Set.of(), Set.of(account));
        }
    }

    @Test
    void aNegativeBalanceDerivedFromTheEntriesIsDetectedEvenWhenTheCacheIsZero() throws Exception {
        // The cached balance cannot be negative (a CHECK stops it), so a negative balance can only show up in the
        // ledger-derived figure; this is why the negative check looks at the entries too.
        LedgerTestData data = data();
        UUID user = data.newUser();
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "TRANSFER");
        try {
            data.newEntry(tx, account, -50);
            data.newEntry(tx, LedgerTestData.EXTERNAL_PAYOUTS, 50); // balanced transaction, but the account went below zero

            ReconciliationReport report =
                    reconciliation.reconcile(ReconciliationScope.of(Set.of(account), Set.of(tx)));

            assertThat(report.unbalancedTransactions()).isEmpty();
            assertThat(report.entrySum()).isZero();
            assertThat(report.negativeBalances())
                    .containsExactly(new ReconciliationReport.NegativeBalance(account, 0, -50));
            assertThat(report.balanceMismatches())
                    .containsExactly(new ReconciliationReport.BalanceMismatch(account, 0, -50));
            assertThat(report.isClean()).isFalse();
        } finally {
            removeLedgerRows(Set.of(tx), Set.of(account));
        }
    }

    @Test
    void anEmptyScopeChecksNothingAndIsClean() {
        ReconciliationReport report = reconciliation.reconcile(ReconciliationScope.of(Set.of(), Set.of()));

        assertThat(report.isClean()).isTrue();
        assertThat(report.transactionsChecked()).isZero();
        assertThat(report.accountsChecked()).isZero();
    }

    @Test
    void theWholeLedgerFormRunsAtRepeatableReadAndSeesTheSharedDatabase() {
        // Not asserted clean: other test classes leave deliberately unbalanced rows in the shared database.
        // What is checked is that the unscoped queries execute and cover at least the seeded system accounts' entries.
        UUID user = data().newUser();
        UUID account = data().newAccount(user, 0);
        UUID tx = data().balancedDeposit(user, account, 100);
        jdbc.update("UPDATE accounts SET balance_minor = 100 WHERE id = ?", account);

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.isolationLevel()).isEqualTo("repeatable read");
        assertThat(report.transactionsChecked()).isGreaterThanOrEqualTo(1);
        assertThat(report.accountsChecked()).isGreaterThanOrEqualTo(1);
        assertThat(report.unbalancedTransactions()).extracting(t -> t.transactionId()).doesNotContain(tx);
        assertThat(report.balanceMismatches()).extracting(m -> m.accountId()).doesNotContain(account);
        assertThat(List.of(report.unbalancedTransactions(), report.balanceMismatches(), report.negativeBalances()))
                .allSatisfy(examples -> assertThat(examples.size()).isLessThanOrEqualTo(100));
    }
}
