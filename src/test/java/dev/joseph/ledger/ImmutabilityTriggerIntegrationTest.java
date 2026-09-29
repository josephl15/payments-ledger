package dev.joseph.ledger;

import static dev.joseph.ledger.SqlErrors.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Invariant 4: ledger entries and audit rows can never be changed or removed. Raw SQL is used on purpose (not the
 * entities) because the point is that the DATABASE refuses, whatever code sent the statement.
 *
 * <p>A row-level trigger only fires when a row matches, so UPDATE and DELETE tests target a row that exists. A
 * row-level trigger does not fire for TRUNCATE at all, which is why V3 adds separate BEFORE TRUNCATE triggers;
 * the TRUNCATE tests are what would catch their removal.
 */
class ImmutabilityTriggerIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String APPEND_ONLY = "forbidden: the table is append-only";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    LedgerTestData data;
    UUID account;
    UUID transaction;
    long entryId;
    long auditId;

    @BeforeEach
    void setUp() {
        data = new LedgerTestData(jdbc);
        UUID user = data.newUser();
        account = data.newAccount(user, 500);
        transaction = data.balancedDeposit(user, account, 500);
        entryId = jdbc.queryForObject(
                "SELECT id FROM ledger_entries WHERE account_id = ?", Long.class, account);
        auditId = data.newAuditRow(user);
    }

    /** Runs each statement on its own autocommit connection and asserts the trigger refused it. */
    private void assertAllRefused(List<String> statements) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String sql : statements) {
                Throwable failure = catchThrowable(() -> st.execute(sql));
                assertThat(failure).as(sql).isNotNull();
                SQLException se = SqlErrors.sqlException(failure);
                assertThat(se.getSQLState()).as(sql).isEqualTo("P0001");
                assertThat(se.getMessage()).as(sql).contains(APPEND_ONLY);
            }
        }
    }

    private void assertUntouched() {
        assertThat(jdbc.queryForObject("SELECT amount_minor FROM ledger_entries WHERE id = ?", Long.class, entryId))
                .isEqualTo(500L);
        assertThat(data.sumForTransaction(transaction)).isZero();
        assertThat(jdbc.queryForObject("SELECT action FROM audit_log WHERE id = ?", String.class, auditId))
                .isEqualTo("TEST_ACTION");
    }

    @Test
    void updateOfALedgerEntryIsRejected() {
        assertRejected(
                () -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1 WHERE id = ?", entryId), "P0001", null);
        assertUntouched();
    }

    @Test
    void updateRejectionNamesTheOperationAndTable() {
        Throwable failure = catchThrowable(
                () -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1 WHERE id = ?", entryId));
        assertThat(SqlErrors.sqlException(failure).getMessage())
                .contains("UPDATE on ledger_entries is forbidden: the table is append-only");
    }

    @Test
    void deleteOfALedgerEntryIsRejected() {
        assertRejected(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId), "P0001", null);
        assertUntouched();
    }

    @Test
    void updateOfAnAuditRowIsRejected() {
        assertRejected(
                () -> jdbc.update("UPDATE audit_log SET action = 'TAMPERED' WHERE id = ?", auditId), "P0001", null);
        assertUntouched();
    }

    @Test
    void deleteOfAnAuditRowIsRejected() {
        assertRejected(() -> jdbc.update("DELETE FROM audit_log WHERE id = ?", auditId), "P0001", null);
        assertUntouched();
    }

    @Test
    void truncateOfTheLedgerAndAuditTablesIsRejected() throws SQLException {
        assertAllRefused(List.of("TRUNCATE ledger_entries", "TRUNCATE audit_log"));
        assertUntouched();
    }

    @Test
    void truncateCascadeFromEveryParentTableIsRejected() throws SQLException {
        // CASCADE reaches ledger_entries and audit_log from their parents, and the truncate trigger still fires there.
        assertAllRefused(List.of(
                "TRUNCATE ledger_entries CASCADE",
                "TRUNCATE ledger_transactions CASCADE",
                "TRUNCATE accounts CASCADE",
                "TRUNCATE users CASCADE"));
        assertUntouched();
    }

    @Test
    void truncateOfSeveralTablesAtOnceIsRejected() throws SQLException {
        assertAllRefused(List.of(
                "TRUNCATE users, accounts, ledger_transactions, ledger_entries, audit_log, idempotency_keys"));
        assertUntouched();
    }

    @Test
    void ordinaryInsertsStillWork() {
        UUID user = data.newUser();
        long newEntry = data.newEntry(transaction, data.newAccount(user, 0), 7);
        assertThat(newEntry).isGreaterThan(entryId);
    }
}
