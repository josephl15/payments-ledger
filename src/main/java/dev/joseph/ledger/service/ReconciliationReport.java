package dev.joseph.ledger.service;

import java.util.List;
import java.util.UUID;

/**
 * The result of one reconciliation run. Every list holds at most {@code ReconciliationRepository.MAX_EXAMPLES}
 * examples, so a badly broken ledger cannot produce a huge report; "clean" depends only on whether a list is empty.
 *
 * @param isolationLevel what PostgreSQL reported for the reading transaction (proves REPEATABLE READ was in effect)
 * @param transactionsChecked how many transactions were in scope
 * @param accountsChecked how many customer accounts were in scope
 * @param unbalancedTransactions transactions whose entries do not sum to zero (invariant 1)
 * @param entrySum the sum of all in-scope entries; zero on a healthy ledger (invariant 2)
 * @param balanceMismatches customer accounts whose cached balance differs from the sum of their entries (invariant 4)
 * @param negativeBalances customer accounts whose cached balance or ledger-derived balance is below zero (invariant 3)
 */
public record ReconciliationReport(
        String isolationLevel,
        long transactionsChecked,
        long accountsChecked,
        List<UnbalancedTransaction> unbalancedTransactions,
        long entrySum,
        List<BalanceMismatch> balanceMismatches,
        List<NegativeBalance> negativeBalances) {

    public record UnbalancedTransaction(UUID transactionId, long entrySum) {}

    public record BalanceMismatch(UUID accountId, long cachedBalance, long ledgerBalance) {}

    public record NegativeBalance(UUID accountId, long cachedBalance, long ledgerBalance) {}

    /** True when every check found nothing wrong. */
    public boolean isClean() {
        return unbalancedTransactions.isEmpty()
                && entrySum == 0
                && balanceMismatches.isEmpty()
                && negativeBalances.isEmpty();
    }

    /** A one-paragraph description for assertion messages and logs (ids and numbers only). */
    public String summary() {
        return "isolation=" + isolationLevel
                + ", transactions=" + transactionsChecked
                + ", accounts=" + accountsChecked
                + ", unbalancedTransactions=" + unbalancedTransactions
                + ", entrySum=" + entrySum
                + ", balanceMismatches=" + balanceMismatches
                + ", negativeBalances=" + negativeBalances;
    }
}
