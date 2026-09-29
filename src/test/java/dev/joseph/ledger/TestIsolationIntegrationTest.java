package dev.joseph.ledger;

import static dev.joseph.ledger.SqlErrors.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Demonstrates the test isolation strategy (see docs/DECISIONS.md).
 *
 * <p>The ledger and audit tables cannot be cleaned between tests: DELETE and TRUNCATE are blocked by triggers, by
 * design, and one database is shared by every test class. So each test creates its own users, accounts and
 * transactions with unique ids and asserts only on that data. The first two tests have the same shape and pass in
 * any order because neither looks at anything it did not create. The last tests show the one sanctioned way to
 * bypass the triggers for a single transaction, and that it cleans up after itself.
 */
class TestIsolationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private void scopedScenario() {
        LedgerTestData data = new LedgerTestData(jdbc);
        UUID user = data.newUser();
        UUID account = data.newAccount(user, 1_000);
        UUID first = data.balancedDeposit(user, account, 1_000);
        UUID second = data.balancedDeposit(user, account, 250);

        // Scoped assertions: only the rows this test created, whatever else is in the shared tables.
        assertThat(data.sumForTransaction(first)).isZero();
        assertThat(data.sumForTransaction(second)).isZero();
        assertThat(data.entryCountForAccount(account)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE account_id = ?",
                        Long.class,
                        account))
                .isEqualTo(1_250L);
    }

    @Test
    void scopedTestOne() {
        scopedScenario();
    }

    @Test
    void scopedTestTwoSameShapeIndependentOfTestOne() {
        scopedScenario();
    }

    @Test
    void replicaRoleSwitchesTheTriggersOffOnlyInsideItsTransaction() throws Exception {
        LedgerTestData data = new LedgerTestData(jdbc);
        UUID user = data.newUser();
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");
        long entryId = data.newEntry(tx, account, 40);

        // Normal mode: the trigger refuses the UPDATE.
        assertRejected(
                () -> jdbc.update("UPDATE ledger_entries SET amount_minor = 41 WHERE id = ?", entryId), "P0001", null);

        // Replica mode: the same UPDATE goes through.
        ReplicaRole.asReplica(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE ledger_entries SET amount_minor = 41 WHERE id = ?")) {
                ps.setLong(1, entryId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        });
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM ledger_entries WHERE id = ?", Long.class, entryId))
                .isEqualTo(41L);

        // Afterwards the setting reverted by itself and the trigger is active again.
        assertThat(jdbc.queryForObject("SHOW session_replication_role", String.class)).isEqualTo("origin");
        assertRejected(
                () -> jdbc.update("UPDATE ledger_entries SET amount_minor = 42 WHERE id = ?", entryId), "P0001", null);

        ReplicaRole.asReplica(dataSource, c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ledger_entries WHERE id = ?")) {
                ps.setLong(1, entryId);
                ps.executeUpdate();
            }
        });
    }

    @Test
    void replicaRoleStillEnforcesCheckConstraints() {
        LedgerTestData data = new LedgerTestData(jdbc);
        UUID user = data.newUser();
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");

        assertRejected(
                () -> ReplicaRole.asReplica(dataSource, c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO ledger_entries (transaction_id, account_id, amount_minor, currency) "
                                    + "VALUES (?, ?, 0, 'GBP')")) {
                        ps.setObject(1, tx);
                        ps.setObject(2, account);
                        ps.executeUpdate();
                    }
                }),
                "23514",
                "ck_entries_amount_nonzero");
    }

    @Test
    void injectedCorruptionIsDetectedAndThenCleanedUp() throws Exception {
        LedgerTestData data = new LedgerTestData(jdbc);
        UUID user = data.newUser();
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");

        try {
            // Inject: a lone +100 entry (unbalanced transaction) and a cached balance that disagrees with the ledger.
            // Neither needs the replica role to INSERT; the zero-sum rule is enforced by the service and checked
            // by reconciliation, not by a database trigger.
            data.newEntry(tx, account, 100);
            jdbc.update("UPDATE accounts SET balance_minor = 500 WHERE id = ?", account);

            // Detect, the way reconciliation will: the transaction does not sum to zero and the cache is wrong.
            assertThat(data.sumForTransaction(tx)).isEqualTo(100L);
            Long ledgerBalance = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE account_id = ?", Long.class, account);
            Long cachedBalance =
                    jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account);
            assertThat(ledgerBalance).isEqualTo(100L);
            assertThat(cachedBalance).isEqualTo(500L);
            assertThat(cachedBalance).isNotEqualTo(ledgerBalance);
        } finally {
            // Clean up so later tests (and any whole-ledger check) never see this corruption. Removing ledger
            // entries is only possible with the triggers off.
            ReplicaRole.asReplica(dataSource, c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM ledger_entries WHERE account_id = ?")) {
                    ps.setObject(1, account);
                    ps.executeUpdate();
                }
            });
            jdbc.update("UPDATE accounts SET balance_minor = 0 WHERE id = ?", account);
        }

        assertThat(data.entryCountForAccount(account)).isZero();
        assertThat(data.sumForTransaction(tx)).isZero();
        assertThat(jdbc.queryForObject("SHOW session_replication_role", String.class)).isEqualTo("origin");
    }
}
