package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Flyway V2 to V4 really ran: tables, named constraints, indexes, triggers and the seeded system accounts exist. */
class SchemaMigrationIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayAppliedVersionsOneToFourSuccessfully() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).contains("1", "2", "3", "4");
    }

    @Test
    void sixLedgerTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables)
                .contains("users", "accounts", "ledger_transactions", "ledger_entries", "idempotency_keys", "audit_log");
    }

    @Test
    void namedConstraintsExist() {
        List<String> constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE connamespace = 'public'::regnamespace", String.class);
        assertThat(constraints)
                .contains(
                        "uq_idempotency_user_key",
                        "uq_ledger_tx_reverses",
                        "uq_users_username",
                        "ck_entries_amount_nonzero",
                        "ck_entries_currency",
                        "ck_accounts_balance_nonneg",
                        "ck_accounts_balance_shape",
                        "ck_accounts_owner_shape",
                        "ck_accounts_currency",
                        "ck_ledger_tx_reversal_shape");
    }

    @Test
    void historyAndReconciliationIndexesExist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains("ix_entries_account_id_desc", "ix_entries_transaction", "ix_accounts_owner");
    }

    @Test
    void fourImmutabilityTriggersExist() {
        List<String> triggers = jdbc.queryForList(
                "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgname LIKE 'trg\\_%'", String.class);
        assertThat(triggers)
                .containsExactlyInAnyOrder(
                        "trg_ledger_entries_no_update_delete",
                        "trg_ledger_entries_no_truncate",
                        "trg_audit_log_no_update_delete",
                        "trg_audit_log_no_truncate");
    }

    @Test
    void systemAccountsAreSeededWithoutBalanceOrOwner() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id::text AS id, name, type, currency, status, balance_minor, owner_user_id "
                        + "FROM accounts WHERE type = 'SYSTEM' ORDER BY id");
        assertThat(rows).hasSize(2);

        Map<String, Object> funding = rows.get(0);
        assertThat(funding.get("id")).isEqualTo(LedgerTestData.EXTERNAL_FUNDING.toString());
        assertThat(funding.get("name")).isEqualTo("EXTERNAL_FUNDING");

        Map<String, Object> payouts = rows.get(1);
        assertThat(payouts.get("id")).isEqualTo(LedgerTestData.EXTERNAL_PAYOUTS.toString());
        assertThat(payouts.get("name")).isEqualTo("EXTERNAL_PAYOUTS");

        for (Map<String, Object> row : rows) {
            assertThat(row.get("currency")).isEqualTo("GBP");
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("balance_minor")).as("no cached balance on a SYSTEM account").isNull();
            assertThat(row.get("owner_user_id")).as("no owner on a SYSTEM account").isNull();
        }
    }
}
