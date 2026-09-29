package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.joseph.ledger.service.AccountLockService;
import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.DepositService;
import dev.joseph.ledger.service.ReconciliationService;
import dev.joseph.ledger.service.TransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proof that {@link DeadlockIntegrationTest} is capable of failing. The same ping-pong scenario runs against a
 * test-only AccountLockService that still takes real row locks but in alternating order (see
 * {@link TestLockServices.MixedOrderAccountLockService}); PostgreSQL must then detect deadlocks and abort some
 * transactions with SQLSTATE 40P01. If this test ever stops seeing deadlocks, the "no deadlock" test above would be
 * passing for the wrong reason.
 *
 * <p>Even here the ledger must stay consistent: an aborted transaction rolls back completely, so reconciliation
 * stays clean and the money is conserved.
 *
 * <p>{@code @Import} registers the test-only configuration for this class only; it gets its own Spring context.
 */
@Import(TestLockServices.MixedOrderLockConfig.class)
class DeadlockMutationIntegrationTest extends AbstractPostgresIntegrationTest {

    // Small on purpose: PostgreSQL waits 1 second (deadlock_timeout) before it resolves each deadlock, so a large run is slow.
    static final int TRANSFERS = 40;
    static final int THREADS = 8;

    @Autowired
    AccountLockService lockService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountService accountService;

    @Autowired
    DepositService depositService;

    @Autowired
    TransferService transferService;

    @Autowired
    ReconciliationService reconciliation;

    @Test
    @Timeout(180)
    void lockingInAlternatingOrderProducesDeadlocksAndTheLedgerStillReconciles() throws Exception {
        assertThat(lockService.getClass().getName()).as("the mutant must really be wired in").contains("MixedOrder");

        PingPong.Result result = PingPong.run(
                jdbc, accountService, depositService, transferService, reconciliation, TRANSFERS, THREADS);
        System.out.println("DEADLOCK MUTATION OUTCOME (seed=" + PingPong.SEED + ", transfers=" + TRANSFERS
                + ", threads=" + THREADS + "): " + result.outcome());

        assertThat(result.outcome().otherFailures().keySet())
                .as("failures seen: %s", result.outcome())
                .anyMatch(failure -> failure.contains("40P01"));
        assertThat(result.report().isClean()).as(result.report().summary()).isTrue();
        assertThat(result.cachedA() + result.cachedB()).isEqualTo(2 * PingPong.INITIAL_BALANCE);
    }
}
