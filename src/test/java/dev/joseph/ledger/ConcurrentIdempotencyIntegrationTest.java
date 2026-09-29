package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import dev.joseph.ledger.service.ActingUser;
import dev.joseph.ledger.service.IdempotentExecutor;
import dev.joseph.ledger.service.IdempotentOutcome;
import dev.joseph.ledger.service.PostedTransaction;
import dev.joseph.ledger.service.ReconciliationReport;
import dev.joseph.ledger.service.ReconciliationScope;
import dev.joseph.ledger.service.ReconciliationService;
import dev.joseph.ledger.service.StoredResponse;
import dev.joseph.ledger.service.TransferCommand;
import dev.joseph.ledger.service.TransferService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The idempotency proof: many requests that all carry the same Idempotency-Key, fired at the same instant from
 * separate threads against real PostgreSQL, produce exactly one ledger transaction.
 *
 * <p>Not {@code @Transactional}: each request must run in its own real, committed transaction on its own connection,
 * otherwise the test would wrap everything in one transaction and hide exactly what it is meant to show.
 *
 * <p>Parameters are constants. {@code REQUESTS} equals {@code THREADS}, and the test profile's Hikari pool (20) is at
 * least that, so every thread can hold a connection while it waits for the first request to finish (a duplicate's
 * INSERT blocks inside PostgreSQL until the winner commits). A smaller pool would make the extra requests queue for
 * a connection outside the database and the test would measure the pool instead.
 *
 * <p>Besides the main scenarios there is a control (20 different keys must all run, so the test really does run
 * requests in parallel and would notice if the guard swallowed too much) and a failure scenario.
 */
class ConcurrentIdempotencyIntegrationTest extends AbstractIdempotencyIntegrationTest {

    static final int REQUESTS = 20;
    static final int THREADS = 20;
    static final long INITIAL_BALANCE = 10_000;
    static final long AMOUNT = 100;

    @Autowired
    ReconciliationService reconciliation;

    @Autowired
    IdempotentExecutor executor;

    @Autowired
    TransferService transferService;

    /** What one request came back with. */
    record Reply(int status, boolean replayed, JsonNode body) {}

    /** Fires all requests together (start gate) and reads every Future with a timeout, so no worker error is lost. */
    private List<Reply> fireTogether(List<Callable<Reply>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Reply>> futures = new ArrayList<>();
            for (Callable<Reply> request : requests) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return request.call();
                }));
            }
            startGate.countDown();
            List<Reply> replies = new ArrayList<>();
            for (Future<Reply> future : futures) {
                replies.add(future.get(30, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Reply> post(String path, UUID user, String key, String body) {
        return () -> {
            var result = send(path, user, key, body);
            String text = result.getResponse().getContentAsString();
            return new Reply(
                    result.getResponse().getStatus(),
                    "true".equals(result.getResponse().getHeader("Idempotent-Replayed")),
                    text.isEmpty() ? null : json.readTree(text));
        };
    }

    private ReconciliationReport reconcile(UUID user, UUID... accounts) {
        Set<UUID> transactionIds = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM ledger_transactions WHERE created_by_user_id = ?", UUID.class, user));
        return reconciliation.reconcile(ReconciliationScope.of(Set.of(accounts), transactionIds));
    }

    @Test
    @Timeout(120)
    void twentyConcurrentIdenticalTransfersCreateExactlyOneTransaction() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, INITIAL_BALANCE);
        UUID to = newAccount(newUser(), 0);
        String key = "concurrent-transfer-" + UUID.randomUUID();
        List<Callable<Reply>> requests = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            requests.add(post("/api/transfers", user, key, transferBody(from, to, AMOUNT)));
        }

        List<Reply> replies = fireTogether(requests);

        long executed = replies.stream().filter(r -> !r.replayed()).count();
        JsonNode first = replies.get(0).body();
        System.out.println("CONCURRENT TRANSFER (requests=" + REQUESTS + ", threads=" + THREADS + "): statuses="
                + replies.stream().map(Reply::status).distinct().toList() + ", executed=" + executed + ", replayed="
                + (REQUESTS - executed) + ", ledgerTransactions=" + transactionCount(user, "TRANSFER"));
        ReconciliationReport report = reconcile(user, from, to);
        assertSoftly(softly -> {
            softly.assertThat(replies).as("every request answered").hasSize(REQUESTS);
            softly.assertThat(replies).as("every caller got 201").allMatch(r -> r.status() == 201);
            softly.assertThat(executed).as("requests that did the work").isEqualTo(1);
            softly.assertThat(replies).as("every caller got the same body").allMatch(r -> r.body().equals(first));
            softly.assertThat(transactionCount(user, "TRANSFER")).as("ledger transactions").isEqualTo(1);
            softly.assertThat(keyRowCount(user, key)).as("key rows").isEqualTo(1);
            softly.assertThat(balance(from)).as("payer balance: one debit").isEqualTo(INITIAL_BALANCE - AMOUNT);
            softly.assertThat(balance(to)).as("payee balance: one credit").isEqualTo(AMOUNT);
            softly.assertThat(report.isClean()).as("reconciliation: " + report.summary()).isTrue();
        });
    }

    @Test
    @Timeout(120)
    void twentyConcurrentIdenticalDepositsCreateExactlyOneTransaction() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "concurrent-deposit-" + UUID.randomUUID();
        List<Callable<Reply>> requests = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            requests.add(post("/api/deposits", user, key, depositBody(account, 5_000)));
        }

        List<Reply> replies = fireTogether(requests);

        long executed = replies.stream().filter(r -> !r.replayed()).count();
        JsonNode first = replies.get(0).body();
        ReconciliationReport report = reconcile(user, account);
        assertSoftly(softly -> {
            softly.assertThat(replies).allMatch(r -> r.status() == 201);
            softly.assertThat(executed).isEqualTo(1);
            softly.assertThat(replies).allMatch(r -> r.body().equals(first));
            softly.assertThat(transactionCount(user, "DEPOSIT")).isEqualTo(1);
            softly.assertThat(balance(account)).as("credited once").isEqualTo(5_000);
            softly.assertThat(report.isClean()).as("reconciliation: " + report.summary()).isTrue();
        });
    }

    @Test
    @Timeout(120)
    void controlTwentyConcurrentTransfersWithDifferentKeysAllExecute() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, INITIAL_BALANCE);
        UUID to = newAccount(newUser(), 0);
        List<Callable<Reply>> requests = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            requests.add(post("/api/transfers", user, "control-" + i + "-" + UUID.randomUUID(), transferBody(from, to, AMOUNT)));
        }

        List<Reply> replies = fireTogether(requests);

        ReconciliationReport report = reconcile(user, from, to);
        assertSoftly(softly -> {
            softly.assertThat(replies).allMatch(r -> r.status() == 201 && !r.replayed());
            softly.assertThat(transactionCount(user, "TRANSFER")).as("one transaction per key").isEqualTo(REQUESTS);
            softly.assertThat(balance(from)).isEqualTo(INITIAL_BALANCE - REQUESTS * AMOUNT);
            softly.assertThat(balance(to)).isEqualTo(REQUESTS * AMOUNT);
            softly.assertThat(report.isClean()).as("reconciliation: " + report.summary()).isTrue();
        });
    }

    @Test
    @Timeout(120)
    void oneKeyWithTwentyDifferentBodiesLetsExactlyOneWinAndRefusesTheRest() throws Exception {
        UUID user = newUser();
        UUID account = newAccount(user, 0);
        String key = "contested-" + UUID.randomUUID();
        List<Callable<Reply>> requests = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            requests.add(post("/api/deposits", user, key, depositBody(account, 1_000 + i)));
        }

        List<Reply> replies = fireTogether(requests);

        long created = replies.stream().filter(r -> r.status() == 201).count();
        long refused = replies.stream().filter(r -> r.status() == 422).count();
        ReconciliationReport report = reconcile(user, account);
        assertSoftly(softly -> {
            softly.assertThat(created).as("winner").isEqualTo(1);
            softly.assertThat(refused).as("losers refused with 422").isEqualTo(REQUESTS - 1);
            softly.assertThat(transactionCount(user, "DEPOSIT")).isEqualTo(1);
            softly.assertThat(balance(account)).as("exactly the winner's amount").isBetween(1_000L, 1_000L + REQUESTS - 1);
            softly.assertThat(report.isClean()).as("reconciliation: " + report.summary()).isTrue();
        });
    }

    @Test
    @Timeout(120)
    void twentyConcurrentIdenticalRequestsThatFailAllFailCleanlyAndLeaveNothingBehind() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, 50);
        UUID to = newAccount(user, 0);
        String key = "concurrent-failure-" + UUID.randomUUID();
        List<Callable<Reply>> requests = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            requests.add(post("/api/transfers", user, key, transferBody(from, to, 9_999)));
        }

        List<Reply> replies = fireTogether(requests);

        ReconciliationReport report = reconcile(user, from, to);
        assertSoftly(softly -> {
            softly.assertThat(replies).as("each one refused, none a 500").allMatch(r -> r.status() == 422 && !r.replayed());
            softly.assertThat(transactionCount(user, "TRANSFER")).isZero();
            softly.assertThat(keyRowCount(user, key)).as("failed attempts leave no key row").isZero();
            softly.assertThat(balance(from)).isEqualTo(50);
            softly.assertThat(report.isClean()).as("reconciliation: " + report.summary()).isTrue();
        });
        assertThat(replies).hasSize(REQUESTS);
    }

    /**
     * The blocking behaviour, made deterministic instead of hoped for. The first request is held open after it has
     * done its work but before it commits. A duplicate arrives meanwhile: PostgreSQL must make its INSERT of the same
     * key wait (we look for that wait in pg_stat_activity), and once the first request commits the duplicate must
     * fail with the unique violation and answer with the first request's stored result.
     */
    @Test
    @Timeout(60)
    void aDuplicateArrivingWhileTheFirstTransactionIsOpenWaitsThenReplaysItsResult() throws Exception {
        UUID user = newUser();
        UUID from = newAccount(user, INITIAL_BALANCE);
        UUID to = newAccount(newUser(), 0);
        String key = "held-open-" + UUID.randomUUID();
        ActingUser actor = new ActingUser(user);
        TransferCommand command = new TransferCommand(from, to, AMOUNT, "GBP", null);
        String requestBody = transferBody(from, to, AMOUNT);
        CountDownLatch workDone = new CountDownLatch(1);
        CountDownLatch commitNow = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<StoredResponse> first = pool.submit(() -> executor.execute(
                    actor, key, "POST", "/api/transfers", requestBody, () -> {
                        PostedTransaction posted = transferService.transfer(actor, command);
                        workDone.countDown();
                        try {
                            commitNow.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new IllegalStateException(e);
                        }
                        return new IdempotentOutcome(
                                201, Map.of("id", posted.transaction().getId().toString()), posted.transaction().getId());
                    }));
            assertThat(workDone.await(10, TimeUnit.SECONDS)).as("first request reached the end of its work").isTrue();

            Future<StoredResponse> duplicate = pool.submit(() -> executor.execute(
                    actor, key, "POST", "/api/transfers", requestBody, () -> {
                        throw new AssertionError("the duplicate must never run the business call");
                    }));
            // Wait until PostgreSQL reports an INSERT into idempotency_keys blocked on another transaction's id.
            await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                            "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                                    + "AND wait_event = 'transactionid' AND query ILIKE 'insert into idempotency_keys%'",
                            Long.class)
                    >= 1);
            assertThat(duplicate.isDone()).as("the duplicate is still waiting").isFalse();

            commitNow.countDown();
            StoredResponse winnerResponse = first.get(30, TimeUnit.SECONDS);
            StoredResponse duplicateResponse = duplicate.get(30, TimeUnit.SECONDS);

            assertThat(winnerResponse.replayed()).isFalse();
            assertThat(duplicateResponse.replayed()).isTrue();
            assertThat(json.readTree(duplicateResponse.bodyJson())).isEqualTo(json.readTree(winnerResponse.bodyJson()));
            assertThat(transactionCount(user, "TRANSFER")).isEqualTo(1);
            assertThat(balance(from)).isEqualTo(INITIAL_BALANCE - AMOUNT);
        } finally {
            commitNow.countDown();
            pool.shutdownNow();
        }
    }
}
