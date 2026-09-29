package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.ActingUser;
import dev.joseph.ledger.service.DepositCommand;
import dev.joseph.ledger.service.DepositService;
import dev.joseph.ledger.service.ReconciliationReport;
import dev.joseph.ledger.service.ReconciliationScope;
import dev.joseph.ledger.service.ReconciliationService;
import dev.joseph.ledger.service.TransferService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The concurrency showcase: 1,000 random transfers between 10 funded accounts, fired from 16 threads at once against
 * real PostgreSQL. Afterwards money must not have been created or destroyed, and the cached balances must still equal
 * the sums of the entries.
 *
 * <p>Why this test can fail (it must be able to, or it proves nothing): with the balance read and written without a row
 * lock, two transfers that touch the same account read the same balance and the later write silently overwrites the
 * earlier one (a "lost update"), so the cached balances drift away from the ledger. With
 * {@code AccountLockService} taking {@code SELECT ... FOR UPDATE} in id order, they cannot. The recorded failing run
 * against the naive lock is in docs/evidence/phase-4-red-naive-lock.txt.
 *
 * <p>Parameters are constants, so a run is reproducible in what is asked of the database; only the thread
 * interleaving varies from run to run.
 */
class ConcurrentTransferShowcaseIntegrationTest extends AbstractPostgresIntegrationTest {

    static final long SEED = 20260929L;
    static final int ACCOUNTS = 10;
    static final int TRANSFERS = 1_000;
    static final int THREADS = 16; // the test profile's Hikari pool is 20, so no worker ever waits for a connection
    static final long INITIAL_BALANCE = 10_000; // £100.00 each
    static final int MAX_AMOUNT = 5_000; // amounts 1..5000 pence; large enough that overdraft attempts happen

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
    void thousandConcurrentRandomTransfersKeepEveryInvariant() throws Exception {
        ActingUser actor = new ActingUser(new LedgerTestData(jdbc).newUser());
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID id = accountService.open(actor, "showcase " + i).getId();
            depositService.deposit(actor, new DepositCommand(id, INITIAL_BALANCE, "GBP", null));
            accounts.add(id);
        }
        long totalMoney = ACCOUNTS * INITIAL_BALANCE;

        Random random = new Random(SEED);
        List<TransferLoad.Spec> specs = new ArrayList<>();
        for (int i = 0; i < TRANSFERS; i++) {
            int from = random.nextInt(ACCOUNTS);
            int to = random.nextInt(ACCOUNTS - 1);
            if (to >= from) {
                to++; // any account except `from`, uniformly
            }
            specs.add(new TransferLoad.Spec(accounts.get(from), accounts.get(to), 1 + random.nextInt(MAX_AMOUNT)));
        }

        TransferLoad.Outcome outcome = TransferLoad.run(transferService, actor, specs, THREADS, 60);
        System.out.println("SHOWCASE OUTCOME (seed=" + SEED + ", accounts=" + ACCOUNTS + ", transfers=" + TRANSFERS
                + ", threads=" + THREADS + "): " + outcome);

        Set<UUID> transactionIds = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM ledger_transactions WHERE created_by_user_id = ?", UUID.class, actor.userId()));
        ReconciliationReport report =
                reconciliation.reconcile(ReconciliationScope.of(new HashSet<>(accounts), transactionIds));
        System.out.println("SHOWCASE RECONCILIATION: " + report.summary());

        long cachedTotal = 0;
        long lowestCached = Long.MAX_VALUE;
        for (UUID account : accounts) {
            long cached = jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account);
            cachedTotal += cached;
            lowestCached = Math.min(lowestCached, cached);
        }
        System.out.println("SHOWCASE BALANCES: cachedTotal=" + cachedTotal + " expected=" + totalMoney
                + " lowestCached=" + lowestCached);

        long cachedTotalFinal = cachedTotal;
        long lowestCachedFinal = lowestCached;
        assertSoftly(softly -> {
            // Every submitted transfer ended one of the three ways; nothing was lost or hung.
            softly.assertThat(outcome.total()).as("transfers accounted for").isEqualTo(TRANSFERS);
            // Non-vacuity: the run did real work, and the scenario really produced overdraft refusals.
            softly.assertThat(outcome.successes()).as("successful transfers").isPositive();
            softly.assertThat(outcome.insufficientFunds()).as("insufficient-funds refusals").isPositive();
            softly.assertThat(outcome.otherFailures()).as("unexpected failures").isEmpty();
            // The invariants, read straight from the database.
            softly.assertThat(cachedTotalFinal).as("money is neither created nor destroyed").isEqualTo(totalMoney);
            softly.assertThat(lowestCachedFinal).as("lowest cached balance").isGreaterThanOrEqualTo(0);
            // And through the reconciliation safety net.
            softly.assertThat(report.isClean()).as("reconciliation: " + report.summary()).isTrue();
        });
        assertThat(report.transactionsChecked()).as("scope covers deposits and transfers").isEqualTo(ACCOUNTS + outcome.successes());
    }
}
