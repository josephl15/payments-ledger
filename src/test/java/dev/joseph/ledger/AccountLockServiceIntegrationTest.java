package dev.joseph.ledger;

import static dev.joseph.ledger.SqlErrors.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.SystemAccountIds;
import dev.joseph.ledger.service.AccountLockService;
import dev.joseph.ledger.service.ResourceNotFoundException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AccountLockService in isolation, against real PostgreSQL: the statement it sends, that the row lock really exists
 * and really blocks a second transaction, and its not-found behaviour.
 *
 * <p>{@code TransactionTemplate} opens a transaction around a block of test code, like {@code @Transactional} does
 * around a service method, but written out where you can see it. Probes that must be a DIFFERENT database session run
 * on a separate thread: a JdbcTemplate call on the transaction's own thread would reuse the transaction's connection.
 */
class AccountLockServiceIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    AccountLockService lockService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TransactionTemplate inTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private LedgerTestData data() {
        return new LedgerTestData(jdbc);
    }

    @Test
    void theEmittedStatementIsOneSelectForUpdateOrderedByIdOnCustomerAccounts() {
        UUID owner = data().newUser();
        UUID a = data().newAccount(owner, 0);
        UUID b = data().newAccount(owner, 0);

        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level before = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(Level.DEBUG);
        try {
            inTransaction().executeWithoutResult(status -> lockService.lock(Set.of(a, b)));
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(before);
        }

        List<String> statements = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        List<String> lockStatements = statements.stream()
                .filter(sql -> sql.toLowerCase().contains("for update"))
                .toList();
        assertThat(lockStatements).as("all SQL sent: %s", statements).hasSize(1);
        assertThat(statements).as("the lock is the only statement of the call").hasSize(1);
        String sql = lockStatements.get(0).toLowerCase().replaceAll("\\s+", " ");
        assertThat(sql).contains("from accounts");
        assertThat(sql).contains("type = 'customer'");
        assertThat(sql).contains("order by id for update");
    }

    @Test
    void accountsComeBackInPostgresIdOrderNotJavaOrder() {
        UUID owner = data().newUser();
        Set<UUID> ids = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            ids.add(data().newAccount(owner, 0));
        }
        String inList = String.join(",", ids.stream().map(id -> "'" + id + "'").toList());
        List<UUID> postgresOrder =
                jdbc.queryForList("SELECT id FROM accounts WHERE id IN (" + inList + ") ORDER BY id", UUID.class);

        Map<UUID, Account> locked = inTransaction().execute(status -> lockService.lock(ids));

        assertThat(new ArrayList<>(locked.keySet())).isEqualTo(postgresOrder);
    }

    @Test
    void aLockedRowIsReallyLockedFromOtherSessionsUntilTheTransactionEnds() throws Exception {
        // This is the check the naive (plain read) body fails: it takes no lock, so NOWAIT would succeed.
        UUID account = data().newAccount(data().newUser(), 1_000);
        ExecutorService otherSession = Executors.newSingleThreadExecutor();
        try {
            inTransaction().executeWithoutResult(status -> {
                lockService.lock(Set.of(account));
                Future<?> probe = otherSession.submit(() -> jdbc.queryForList(
                        "SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", UUID.class, account));
                // 55P03 = lock_not_available: NOWAIT refuses to wait for a row someone else has locked.
                assertRejected(() -> probe.get(10, TimeUnit.SECONDS), "55P03", null);
            });

            // After commit the lock is gone.
            List<UUID> afterCommit = otherSession
                    .submit(() -> jdbc.queryForList(
                            "SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT", UUID.class, account))
                    .get(10, TimeUnit.SECONDS);
            assertThat(afterCommit).containsExactly(account);
        } finally {
            otherSession.shutdownNow();
        }
    }

    @Test
    @Timeout(60)
    void aSecondTransactionWaitsForTheFirstAndThenSeesItsCommittedBalance() throws Exception {
        UUID account = data().newAccount(data().newUser(), 1_000);
        ExecutorService other = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Long>> waiter = new AtomicReference<>();
        try {
            inTransaction().executeWithoutResult(status -> {
                Account first = lockService.lock(Set.of(account)).get(account);
                waiter.set(other.submit(() -> inTransaction()
                        .execute(s -> lockService.lock(Set.of(account)).get(account).getBalanceMinor())));
                // Wait until PostgreSQL shows a session blocked on a lock: the second transaction is really waiting.
                await().atMost(Duration.ofSeconds(20))
                        .until(() -> jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                                                + "AND datname = current_database()",
                                        Long.class)
                                > 0);
                assertThat(waiter.get().isDone()).as("second transaction must still be waiting").isFalse();
                first.applyDelta(50); // written when this transaction commits, which releases the lock
            });

            // The waiter got the row only after the commit, and read the committed value (1,000 + 50), not a stale 1,000.
            assertThat(waiter.get().get(20, TimeUnit.SECONDS)).isEqualTo(1_050L);
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void aMissingSystemOrEmptyRequestIsNotFound() {
        UUID account = data().newAccount(data().newUser(), 0);

        assertThatThrownBy(() -> inTransaction().executeWithoutResult(
                        status -> lockService.lock(Set.of(account, UUID.randomUUID()))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTransaction().executeWithoutResult(
                        status -> lockService.lock(Set.of(account, SystemAccountIds.EXTERNAL_FUNDING))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTransaction().executeWithoutResult(status -> lockService.lock(Set.of())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void lockingOutsideATransactionFailsLoudly() {
        UUID account = data().newAccount(data().newUser(), 0);

        assertThatThrownBy(() -> lockService.lock(Set.of(account))).isInstanceOf(IllegalTransactionStateException.class);
    }
}
