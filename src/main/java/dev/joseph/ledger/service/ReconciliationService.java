package dev.joseph.ledger.service;

import dev.joseph.ledger.repository.ReconciliationRepository;
import dev.joseph.ledger.repository.ReconciliationRepository.AccountBalance;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The safety net behind the ledger: recomputes from the entries (the source of truth) what the rest of the system
 * claims, and reports any difference. It changes nothing. There is no HTTP endpoint for it; tests call it to prove the
 * invariants hold after concurrent load.
 *
 * <p>Checks (invariant numbers from the project brief):
 * <ol>
 *   <li>every transaction's entries sum to zero;</li>
 *   <li>all entries together sum to zero;</li>
 *   <li>no customer balance is negative, judged on the cached balance AND on the balance derived from the entries;</li>
 *   <li>every customer's cached balance equals the sum of that account's entries (accounts with no entries included).</li>
 * </ol>
 *
 * <p><b>Why {@code REPEATABLE_READ}, read-only.</b> These are several separate queries. Under the default READ
 * COMMITTED each query sees whatever has been committed at the moment it starts, so a transfer committing between the
 * "sum of all entries" query and the "cached balances" query could make a healthy ledger look broken (a false alarm).
 * REPEATABLE READ gives the whole transaction one snapshot taken at its first query, so every check sees the same
 * committed state, without blocking anyone writing. {@code readOnly = true} tells Spring and PostgreSQL that nothing
 * is written. This isolation level is only requested here; the money-moving code keeps READ COMMITTED plus row locks.
 */
@Service
public class ReconciliationService {

    private final ReconciliationRepository repository;

    public ReconciliationService(ReconciliationRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks the whole ledger. Suitable for a scheduled job; tests use the scoped form (see {@link ReconciliationScope}).
     * The annotation is repeated here on purpose: this method calls {@code reconcile(scope)} on {@code this}, which
     * skips Spring's proxy, so without its own {@code @Transactional} this call would run with no transaction at all.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        return reconcile(ReconciliationScope.wholeLedger());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile(ReconciliationScope scope) {
        // Reported so a test can prove the transaction really ran at REPEATABLE READ. The snapshot itself is taken
        // by the first data query below and then used by all of them.
        String isolation = repository.currentIsolationLevel();

        List<ReconciliationReport.UnbalancedTransaction> unbalanced = new ArrayList<>();
        for (ReconciliationRepository.TransactionSum row : repository.findUnbalancedTransactions(scope.transactionIds())) {
            unbalanced.add(new ReconciliationReport.UnbalancedTransaction(row.transactionId(), row.entrySum()));
        }
        long entrySum = repository.sumOfEntries(scope.transactionIds());

        List<ReconciliationReport.BalanceMismatch> mismatches = new ArrayList<>();
        List<ReconciliationReport.NegativeBalance> negatives = new ArrayList<>();
        for (AccountBalance row : repository.findSuspectCustomerAccounts(scope.accountIds())) {
            if (row.cachedBalance() != row.ledgerBalance()) {
                mismatches.add(new ReconciliationReport.BalanceMismatch(
                        row.accountId(), row.cachedBalance(), row.ledgerBalance()));
            }
            if (row.cachedBalance() < 0 || row.ledgerBalance() < 0) {
                negatives.add(new ReconciliationReport.NegativeBalance(
                        row.accountId(), row.cachedBalance(), row.ledgerBalance()));
            }
        }

        return new ReconciliationReport(
                isolation,
                repository.countTransactions(scope.transactionIds()),
                repository.countCustomerAccounts(scope.accountIds()),
                unbalanced,
                entrySum,
                mismatches,
                negatives);
    }
}
