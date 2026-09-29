package dev.joseph.ledger.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The read-only, set-based SQL behind reconciliation. Each method is ONE aggregate query over the whole table (or the
 * scoped ids), not a loop that loads accounts one by one: the database adds up the entries, which is both faster and
 * easier to trust.
 *
 * <p>Plain SQL through {@link NamedParameterJdbcTemplate} rather than JPA, because these are reporting queries that
 * return numbers, not entities to modify. Values always go in as bind parameters (never concatenated into the SQL);
 * the only text added to a query is one of the fixed fragments below. A {@code null} id set means "no restriction",
 * an empty set means "nothing in scope" and the query is skipped ({@code IN ()} is not valid SQL).
 *
 * <p>{@code LEFT JOIN} from accounts (or transactions) to entries matters: an account with no entries at all has no
 * matching entry row, so an inner join would silently drop it and a wrong cached balance on it would go unnoticed.
 *
 * <p>Call these inside one transaction (the service makes it REPEATABLE READ) so all of them see the same snapshot.
 */
@Repository
public class ReconciliationRepository {

    /** Cap on the examples returned per check, so a badly broken ledger cannot produce an enormous report. */
    public static final int MAX_EXAMPLES = 100;

    /** A transaction id and the sum of its entries. */
    public record TransactionSum(UUID transactionId, long entrySum) {}

    /** A customer account with its cached balance and the balance derived from its entries. */
    public record AccountBalance(UUID accountId, long cachedBalance, long ledgerBalance) {}

    private final NamedParameterJdbcTemplate jdbc;

    public ReconciliationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What isolation level PostgreSQL is applying to the current transaction (for example "repeatable read"). */
    public String currentIsolationLevel() {
        return jdbc.getJdbcTemplate().queryForObject("SHOW transaction_isolation", String.class);
    }

    public long countTransactions(Set<UUID> transactionIds) {
        if (transactionIds != null && transactionIds.isEmpty()) {
            return 0;
        }
        String where = transactionIds == null ? "" : " WHERE id IN (:ids)";
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions" + where, idsParameter(transactionIds), Long.class);
        return count == null ? 0 : count;
    }

    public long countCustomerAccounts(Set<UUID> accountIds) {
        if (accountIds != null && accountIds.isEmpty()) {
            return 0;
        }
        String where = accountIds == null ? "" : " AND id IN (:ids)";
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM accounts WHERE type = 'CUSTOMER'" + where, idsParameter(accountIds), Long.class);
        return count == null ? 0 : count;
    }

    /** Invariant 1: transactions whose entries do not sum to zero. LEFT JOIN so a transaction with no entries is seen. */
    public List<TransactionSum> findUnbalancedTransactions(Set<UUID> transactionIds) {
        if (transactionIds != null && transactionIds.isEmpty()) {
            return List.of();
        }
        String where = transactionIds == null ? "" : " WHERE t.id IN (:ids)";
        String sql = "SELECT t.id AS id, COALESCE(SUM(e.amount_minor), 0)::bigint AS entry_sum "
                + "FROM ledger_transactions t LEFT JOIN ledger_entries e ON e.transaction_id = t.id"
                + where
                + " GROUP BY t.id HAVING COALESCE(SUM(e.amount_minor), 0) <> 0 ORDER BY t.id LIMIT " + MAX_EXAMPLES;
        return jdbc.query(
                sql,
                idsParameter(transactionIds),
                (rs, row) -> new TransactionSum(rs.getObject("id", UUID.class), rs.getLong("entry_sum")));
    }

    /** Invariant 2: the sum of every entry of the given transactions (all entries when null). Zero when healthy. */
    public long sumOfEntries(Set<UUID> transactionIds) {
        if (transactionIds != null && transactionIds.isEmpty()) {
            return 0;
        }
        String where = transactionIds == null ? "" : " WHERE transaction_id IN (:ids)";
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0)::bigint FROM ledger_entries" + where,
                idsParameter(transactionIds),
                Long.class);
        return sum == null ? 0 : sum;
    }

    /**
     * Customer accounts that are wrong in any way: the cached balance differs from the entries (invariant 4), or
     * either figure is negative (invariant 3). One pass; the service decides which problem each row shows.
     */
    public List<AccountBalance> findSuspectCustomerAccounts(Set<UUID> accountIds) {
        if (accountIds != null && accountIds.isEmpty()) {
            return List.of();
        }
        String where = accountIds == null ? "" : " AND a.id IN (:ids)";
        String sql = "SELECT a.id AS id, a.balance_minor AS cached, COALESCE(SUM(e.amount_minor), 0)::bigint AS ledger "
                + "FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id "
                + "WHERE a.type = 'CUSTOMER'"
                + where
                + " GROUP BY a.id, a.balance_minor "
                + "HAVING a.balance_minor <> COALESCE(SUM(e.amount_minor), 0) "
                + "OR a.balance_minor < 0 OR COALESCE(SUM(e.amount_minor), 0) < 0 "
                + "ORDER BY a.id LIMIT " + MAX_EXAMPLES;
        return jdbc.query(
                sql,
                idsParameter(accountIds),
                (rs, row) -> new AccountBalance(
                        rs.getObject("id", UUID.class), rs.getLong("cached"), rs.getLong("ledger")));
    }

    private static MapSqlParameterSource idsParameter(Set<UUID> ids) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (ids != null) {
            parameters.addValue("ids", ids);
        }
        return parameters;
    }
}
