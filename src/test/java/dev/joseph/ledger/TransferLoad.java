package dev.joseph.ledger;

import dev.joseph.ledger.service.ActingUser;
import dev.joseph.ledger.service.InsufficientFundsException;
import dev.joseph.ledger.service.TransferCommand;
import dev.joseph.ledger.service.TransferService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs a list of transfers from a fixed pool of threads against the real service and real PostgreSQL, and reports
 * how each one ended. Shared by the concurrency tests.
 *
 * <p>The traps this avoids (docs/INTERVIEW_PREP.md, "a concurrency test that cannot fail"):
 * <ul>
 *   <li>a <b>start gate</b>: every worker blocks on a latch until all tasks are submitted, so the first wave really
 *       does start together instead of one thread finishing before the next begins;</li>
 *   <li>every <b>Future is read with a timeout</b>, so an exception inside a worker is seen (an unread Future
 *       swallows it) and a hang fails the test instead of freezing it;</li>
 *   <li>the transfer <b>inputs are fixed up front</b> from a seeded Random by the caller; only the interleaving of
 *       the threads is left to chance;</li>
 *   <li>the caller asserts that at least one transfer succeeded, so "everything was rejected" cannot pass.</li>
 * </ul>
 */
final class TransferLoad {

    private TransferLoad() {}

    /** One transfer to attempt. */
    record Spec(UUID from, UUID to, long amountMinor) {}

    /**
     * How the run ended.
     *
     * @param successes transfers that committed
     * @param insufficientFunds transfers refused with InsufficientFundsException (a legitimate outcome)
     * @param otherFailures anything else, keyed by a short description such as
     *     {@code CannotAcquireLockException [SQLSTATE 40P01]}, with counts
     */
    record Outcome(int successes, int insufficientFunds, Map<String, Integer> otherFailures, long elapsedMillis) {

        int otherFailureCount() {
            return otherFailures.values().stream().mapToInt(Integer::intValue).sum();
        }

        int total() {
            return successes + insufficientFunds + otherFailureCount();
        }

        @Override
        public String toString() {
            return "success=" + successes + ", insufficientFunds=" + insufficientFunds + ", otherFailures="
                    + otherFailures + ", elapsedMillis=" + elapsedMillis;
        }
    }

    static Outcome run(TransferService service, ActingUser actor, List<Spec> specs, int threads, long perTaskTimeoutSeconds)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (Spec spec : specs) {
                Callable<String> task = () -> {
                    startGate.await();
                    try {
                        service.transfer(
                                actor, new TransferCommand(spec.from(), spec.to(), spec.amountMinor(), "GBP", null));
                        return "SUCCESS";
                    } catch (InsufficientFundsException e) {
                        return "INSUFFICIENT_FUNDS";
                    } catch (Throwable t) {
                        return describe(t);
                    }
                };
                futures.add(pool.submit(task));
            }
            long start = System.nanoTime();
            startGate.countDown();

            int successes = 0;
            int insufficient = 0;
            Map<String, Integer> other = new TreeMap<>();
            for (Future<String> future : futures) {
                String result = future.get(perTaskTimeoutSeconds, TimeUnit.SECONDS);
                if (result.equals("SUCCESS")) {
                    successes++;
                } else if (result.equals("INSUFFICIENT_FUNDS")) {
                    insufficient++;
                } else {
                    other.merge(result, 1, Integer::sum);
                }
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return new Outcome(successes, insufficient, other, elapsedMillis);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Exception class plus the SQLSTATE of the deepest SQLException, if any (40P01 = deadlock, 55P03 = lock timeout). */
    private static String describe(Throwable t) {
        String sqlState = "";
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                sqlState = " [SQLSTATE " + sql.getSQLState() + "]";
            }
        }
        return t.getClass().getSimpleName() + sqlState;
    }
}
