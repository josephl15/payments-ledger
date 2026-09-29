package dev.joseph.ledger.service;

import java.util.Set;
import java.util.UUID;

/**
 * Which rows a reconciliation run looks at.
 *
 * <p>{@link #wholeLedger()} is what a real reconciliation job uses. The scoped form exists for tests: the test
 * database is shared by every test class and the ledger cannot be emptied, and other tests leave deliberately
 * unbalanced rows behind, so a whole-ledger check cannot be expected to be clean there. A test passes the ids it
 * created and gets a report about only those. A {@code null} set means "no restriction" for that part of the check;
 * an empty set means "nothing in scope".
 *
 * @param accountIds customer accounts to check (balance comparison and negative-balance checks); null = all
 * @param transactionIds transactions to check (per-transaction sum and the global sum); null = all
 */
public record ReconciliationScope(Set<UUID> accountIds, Set<UUID> transactionIds) {

    public static ReconciliationScope wholeLedger() {
        return new ReconciliationScope(null, null);
    }

    public static ReconciliationScope of(Set<UUID> accountIds, Set<UUID> transactionIds) {
        return new ReconciliationScope(Set.copyOf(accountIds), Set.copyOf(transactionIds));
    }
}
