package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.DepositService;
import dev.joseph.ledger.service.ReconciliationService;
import dev.joseph.ledger.service.TransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 400 simultaneous transfers alternating A to B and B to A across 16 threads: with locks taken in id order (the real
 * AccountLockService) they must all complete, with no deadlock and no timeout, and the balances must end exactly
 * where the arithmetic says.
 *
 * <p>Can this test fail? Yes: {@link DeadlockMutationIntegrationTest} runs the same scenario against a lock service
 * that takes the locks in alternating order and asserts that PostgreSQL then aborts transactions with a deadlock
 * error.
 */
class DeadlockIntegrationTest extends AbstractPostgresIntegrationTest {

    static final int TRANSFERS = 400;
    static final int THREADS = 16;

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
    @Timeout(120)
    void oppositeTransfersBetweenTwoAccountsAllCompleteWithoutDeadlock() throws Exception {
        PingPong.Result result = PingPong.run(
                jdbc, accountService, depositService, transferService, reconciliation, TRANSFERS, THREADS);
        System.out.println("DEADLOCK TEST OUTCOME (seed=" + PingPong.SEED + ", transfers=" + TRANSFERS + ", threads="
                + THREADS + "): " + result.outcome());

        assertSoftly(softly -> {
            softly.assertThat(result.outcome().successes()).as("all transfers completed").isEqualTo(TRANSFERS);
            softly.assertThat(result.outcome().otherFailures()).as("deadlocks or timeouts").isEmpty();
            softly.assertThat(result.cachedA()).as("balance of A").isEqualTo(result.expectedIfAllSucceedA());
            softly.assertThat(result.cachedB()).as("balance of B").isEqualTo(result.expectedIfAllSucceedB());
            softly.assertThat(result.report().isClean()).as(result.report().summary()).isTrue();
        });
        assertThat(result.outcome().successes()).isPositive();
    }
}
