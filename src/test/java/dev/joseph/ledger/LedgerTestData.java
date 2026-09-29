package dev.joseph.ledger;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test fixtures written in plain SQL (bind parameters only), so schema tests do not depend on the entities.
 *
 * <p><b>Test isolation strategy.</b> The ledger and audit tables cannot be emptied between tests (DELETE and
 * TRUNCATE are blocked by triggers, by design), and the database is shared by every test class in the JVM. So
 * every test creates its own users, accounts and transactions with unique ids and asserts only on that data,
 * never on whole-table counts. Each factory method below returns the new id so the test can scope its queries
 * ({@code WHERE transaction_id = ?}, {@code WHERE account_id = ?}).
 */
final class LedgerTestData {

    /** The seeded EXTERNAL_FUNDING system account (V4). */
    static final UUID EXTERNAL_FUNDING = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** The seeded EXTERNAL_PAYOUTS system account (V4). */
    static final UUID EXTERNAL_PAYOUTS = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final JdbcTemplate jdbc;

    LedgerTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A new USER with a unique username. */
    UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, 'not-a-real-hash', 'USER')",
                id,
                "user-" + id);
        return id;
    }

    /** A new ACTIVE GBP CUSTOMER account with the given cached balance. */
    UUID newAccount(UUID ownerUserId, long balanceMinor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO accounts (id, owner_user_id, type, name, currency, status, balance_minor) "
                        + "VALUES (?, ?, 'CUSTOMER', ?, 'GBP', 'ACTIVE', ?)",
                id,
                ownerUserId,
                "account-" + id,
                balanceMinor);
        return id;
    }

    /** A new transaction row of the given type (DEPOSIT or TRANSFER); use {@link #newReversal} for REVERSAL. */
    UUID newTransaction(UUID createdByUserId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ledger_transactions (id, type, created_by_user_id) VALUES (?, ?, ?)",
                id,
                type,
                createdByUserId);
        return id;
    }

    /** A new REVERSAL transaction pointing at {@code reversesTransactionId}. */
    UUID newReversal(UUID createdByUserId, UUID reversesTransactionId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ledger_transactions (id, type, reverses_transaction_id, created_by_user_id) "
                        + "VALUES (?, 'REVERSAL', ?, ?)",
                id,
                reversesTransactionId,
                createdByUserId);
        return id;
    }

    /** Inserts one ledger entry (GBP) and returns its generated id. */
    long newEntry(UUID transactionId, UUID accountId, long amountMinor) {
        return jdbc.queryForObject(
                "INSERT INTO ledger_entries (transaction_id, account_id, amount_minor, currency) "
                        + "VALUES (?, ?, ?, 'GBP') RETURNING id",
                Long.class,
                transactionId,
                accountId,
                amountMinor);
    }

    /**
     * A balanced deposit: a DEPOSIT transaction with -amount on EXTERNAL_FUNDING and +amount on {@code account}.
     * The cached balance of {@code account} is NOT touched (that is the job of the Phase 3 service).
     */
    UUID balancedDeposit(UUID createdByUserId, UUID accountId, long amountMinor) {
        UUID tx = newTransaction(createdByUserId, "DEPOSIT");
        newEntry(tx, EXTERNAL_FUNDING, -amountMinor);
        newEntry(tx, accountId, amountMinor);
        return tx;
    }

    /** One audit row for {@code userId}; returns its generated id. */
    long newAuditRow(UUID userId) {
        return jdbc.queryForObject(
                "INSERT INTO audit_log (user_id, action, resource_type, outcome, details) "
                        + "VALUES (?, 'TEST_ACTION', 'TEST', 'SUCCESS', '{\"k\": 1}'::jsonb) RETURNING id",
                Long.class,
                userId);
    }

    /** Sum of the signed entry amounts of one transaction (0 for a balanced transaction). */
    long sumForTransaction(UUID transactionId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE transaction_id = ?",
                Long.class,
                transactionId);
    }

    /** Number of entries posted to one account. */
    long entryCountForAccount(UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?", Long.class, accountId);
    }
}
