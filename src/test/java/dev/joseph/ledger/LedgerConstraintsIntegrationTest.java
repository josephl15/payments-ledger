package dev.joseph.ledger;

import static dev.joseph.ledger.SqlErrors.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every CHECK, UNIQUE and foreign key rule is exercised with a row that breaks it, and the test asserts the
 * SQLSTATE and the exact constraint name (23514 check, 23505 unique, 23503 foreign key). Each test makes its own
 * users and accounts, so the tests are independent of each other and of the order they run in.
 */
class LedgerConstraintsIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String INSERT_ACCOUNT =
            "INSERT INTO accounts (id, owner_user_id, type, name, currency, status, balance_minor) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    @Autowired
    JdbcTemplate jdbc;

    LedgerTestData data;
    UUID user;

    @BeforeEach
    void setUp() {
        data = new LedgerTestData(jdbc);
        user = data.newUser();
    }

    private void insertAccount(UUID owner, String type, String currency, String status, Long balance) {
        jdbc.update(INSERT_ACCOUNT, UUID.randomUUID(), owner, type, "acc-" + UUID.randomUUID(), currency, status, balance);
    }

    // --- accounts -----------------------------------------------------------------------------------------

    @Test
    void customerBalanceCannotBeNegativeOnInsert() {
        assertRejected(() -> insertAccount(user, "CUSTOMER", "GBP", "ACTIVE", -1L), "23514", "ck_accounts_balance_nonneg");
    }

    @Test
    void customerBalanceCannotBecomeNegativeOnUpdate() {
        UUID account = data.newAccount(user, 100);
        assertRejected(
                () -> jdbc.update("UPDATE accounts SET balance_minor = -1 WHERE id = ?", account),
                "23514",
                "ck_accounts_balance_nonneg");
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account))
                .isEqualTo(100L);
    }

    @Test
    void customerBalanceCanBeZero() {
        insertAccount(user, "CUSTOMER", "GBP", "ACTIVE", 0L);
    }

    @Test
    void customerMustHaveABalance() {
        assertRejected(() -> insertAccount(user, "CUSTOMER", "GBP", "ACTIVE", null), "23514", "ck_accounts_balance_shape");
    }

    @Test
    void systemAccountMustNotHaveABalance() {
        assertRejected(() -> insertAccount(null, "SYSTEM", "GBP", "ACTIVE", 0L), "23514", "ck_accounts_balance_shape");
    }

    @Test
    void seededSystemAccountCannotBeGivenABalance() {
        assertRejected(
                () -> jdbc.update("UPDATE accounts SET balance_minor = 5 WHERE id = ?", LedgerTestData.EXTERNAL_FUNDING),
                "23514",
                "ck_accounts_balance_shape");
    }

    @Test
    void systemAccountMustNotHaveAnOwner() {
        assertRejected(() -> insertAccount(user, "SYSTEM", "GBP", "ACTIVE", null), "23514", "ck_accounts_owner_shape");
    }

    @Test
    void customerAccountMustHaveAnOwner() {
        assertRejected(() -> insertAccount(null, "CUSTOMER", "GBP", "ACTIVE", 0L), "23514", "ck_accounts_owner_shape");
    }

    @Test
    void lowercaseCurrencyIsRejected() {
        assertRejected(() -> insertAccount(user, "CUSTOMER", "gbp", "ACTIVE", 0L), "23514", "ck_accounts_currency");
    }

    @Test
    void currencyLongerThanThreeCharactersIsRejected() {
        // 22001 string_data_right_truncation: the VARCHAR(3) length fires before the CHECK is evaluated.
        assertRejected(() -> insertAccount(user, "CUSTOMER", "GBPP", "ACTIVE", 0L), "22001", null);
    }

    @Test
    void unknownAccountStatusIsRejected() {
        assertRejected(() -> insertAccount(user, "CUSTOMER", "GBP", "FROZEN", 0L), "23514", "ck_accounts_status");
    }

    @Test
    void accountOwnerMustExist() {
        assertRejected(
                () -> insertAccount(UUID.randomUUID(), "CUSTOMER", "GBP", "ACTIVE", 0L), "23503", "fk_accounts_owner");
    }

    // --- users --------------------------------------------------------------------------------------------

    @Test
    void usernameMustBeUnique() {
        UUID other = UUID.randomUUID();
        String username = "dup-" + other;
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, 'h', 'USER')", other, username);
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, 'h', 'USER')",
                        UUID.randomUUID(),
                        username),
                "23505",
                "uq_users_username");
    }

    @Test
    void unknownUserRoleIsRejected() {
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, 'h', 'ROOT')",
                        UUID.randomUUID(),
                        "role-" + UUID.randomUUID()),
                "23514",
                "ck_users_role");
    }

    // --- ledger entries -----------------------------------------------------------------------------------

    @Test
    void zeroAmountEntryIsRejected() {
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");
        assertRejected(() -> data.newEntry(tx, account, 0), "23514", "ck_entries_amount_nonzero");
        assertThat(data.entryCountForAccount(account)).isZero();
    }

    @Test
    void negativeAndPositiveEntriesAreAccepted() {
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");
        data.newEntry(tx, account, 25);
        data.newEntry(tx, LedgerTestData.EXTERNAL_FUNDING, -25);
        assertThat(data.sumForTransaction(tx)).isZero();
    }

    @Test
    void entryCurrencyMustBeThreeCapitalLetters() {
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO ledger_entries (transaction_id, account_id, amount_minor, currency) "
                                + "VALUES (?, ?, 1, 'gbp')",
                        tx,
                        account),
                "23514",
                "ck_entries_currency");
    }

    @Test
    void entryForUnknownAccountIsRejected() {
        UUID tx = data.newTransaction(user, "DEPOSIT");
        assertRejected(() -> data.newEntry(tx, UUID.randomUUID(), 1), "23503", "fk_entries_account");
    }

    @Test
    void entryForUnknownTransactionIsRejected() {
        UUID account = data.newAccount(user, 0);
        assertRejected(() -> data.newEntry(UUID.randomUUID(), account, 1), "23503", "fk_entries_transaction");
    }

    @Test
    void entryIdCannotBeSuppliedByTheApplication() {
        UUID account = data.newAccount(user, 0);
        UUID tx = data.newTransaction(user, "DEPOSIT");
        // GENERATED ALWAYS AS IDENTITY: 428C9 generated_always.
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO ledger_entries (id, transaction_id, account_id, amount_minor, currency) "
                                + "VALUES (?, ?, ?, 1, 'GBP')",
                        -1L,
                        tx,
                        account),
                "428C9",
                null);
    }

    // --- transactions and reversals -----------------------------------------------------------------------

    @Test
    void aTransactionCanBeReversedOnlyOnce() {
        UUID original = data.newTransaction(user, "DEPOSIT");
        data.newReversal(user, original);
        assertRejected(() -> data.newReversal(user, original), "23505", "uq_ledger_tx_reverses");
    }

    @Test
    void manyOrdinaryTransactionsAreAllowed() {
        // UNIQUE (reverses_transaction_id) must not treat the NULLs of ordinary transactions as duplicates.
        data.newTransaction(user, "DEPOSIT");
        data.newTransaction(user, "TRANSFER");
        data.newTransaction(user, "DEPOSIT");
    }

    @Test
    void reversalMustPointAtATransaction() {
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO ledger_transactions (id, type, created_by_user_id) VALUES (?, 'REVERSAL', ?)",
                        UUID.randomUUID(),
                        user),
                "23514",
                "ck_ledger_tx_reversal_shape");
    }

    @Test
    void nonReversalMustNotPointAtATransaction() {
        UUID original = data.newTransaction(user, "DEPOSIT");
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO ledger_transactions (id, type, reverses_transaction_id, created_by_user_id) "
                                + "VALUES (?, 'DEPOSIT', ?, ?)",
                        UUID.randomUUID(),
                        original,
                        user),
                "23514",
                "ck_ledger_tx_reversal_shape");
    }

    @Test
    void reversalCannotPointAtItself() {
        UUID id = UUID.randomUUID();
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO ledger_transactions (id, type, reverses_transaction_id, created_by_user_id) "
                                + "VALUES (?, 'REVERSAL', ?, ?)",
                        id,
                        id,
                        user),
                "23514",
                "ck_ledger_tx_reversal_shape");
    }

    @Test
    void unknownTransactionTypeIsRejected() {
        assertRejected(
                () -> data.newTransaction(user, "WITHDRAWAL"), "23514", "ck_ledger_tx_type");
    }

    // --- idempotency keys and audit log -------------------------------------------------------------------

    private void insertKey(UUID userId, String key) {
        jdbc.update(
                "INSERT INTO idempotency_keys (user_id, idem_key, request_hash, expires_at) "
                        + "VALUES (?, ?, 'hash', now() + interval '1 day')",
                userId,
                key);
    }

    @Test
    void sameUserCannotReuseAnIdempotencyKey() {
        insertKey(user, "key-1");
        assertRejected(() -> insertKey(user, "key-1"), "23505", "uq_idempotency_user_key");
    }

    @Test
    void differentUsersMayUseTheSameIdempotencyKey() {
        insertKey(user, "shared-key");
        insertKey(data.newUser(), "shared-key");
    }

    @Test
    void unknownAuditOutcomeIsRejected() {
        assertRejected(
                () -> jdbc.update(
                        "INSERT INTO audit_log (user_id, action, resource_type, outcome) "
                                + "VALUES (?, 'A', 'T', 'MAYBE')",
                        user),
                "23514",
                "ck_audit_outcome");
    }

    @Test
    void auditRowsMayHaveNoUser() {
        jdbc.update("INSERT INTO audit_log (user_id, action, resource_type, outcome) VALUES (NULL, 'LOGIN', 'USER', 'DENIED')");
    }
}
