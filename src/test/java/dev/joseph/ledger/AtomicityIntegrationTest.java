package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import dev.joseph.ledger.service.AccountService;
import dev.joseph.ledger.service.ActingUser;
import dev.joseph.ledger.service.DepositCommand;
import dev.joseph.ledger.service.DepositService;
import dev.joseph.ledger.service.LedgerPostingService;
import dev.joseph.ledger.service.TransferCommand;
import dev.joseph.ledger.service.TransferService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * Invariant 6, atomicity: a failure part-way through a deposit or transfer leaves NOTHING behind.
 *
 * <p>How the failure is forced: the posting service is wrapped in a Mockito spy (Spring's {@code @MockitoSpyBean}).
 * The stub runs the REAL post (transaction row, entries, balance changes), then calls {@code flush()} so the
 * INSERTs and UPDATEs have really been sent to PostgreSQL, checks from inside the transaction that the rows are
 * there, and only then throws. Throwing before any flush would prove nothing, because Hibernate would not have
 * written anything yet. After the exception the test reads the database from a separate connection and expects
 * every trace to be gone. That can only be true because the service method's {@code @Transactional} rolled the
 * flushed rows back.
 *
 * <p>Non-vacuity, checked by hand when this test was written (recorded in docs/CV_EVIDENCE.md), two experiments:
 * (A) with only {@code @Transactional} removed from the service methods, both tests fail with
 * IllegalTransactionStateException, because the helpers are MANDATORY and refuse to run without a transaction;
 * (B) with {@code @Transactional} removed and MANDATORY loosened to the default on the helpers, each repository
 * call ran and committed on its own and both tests still failed: in the deposit test the balance change was lost
 * (the database balance was still 0 after post() where 700 was expected, because the account entity was no longer
 * managed), and in the transfer test the earlier deposit's balance was lost the same way, so the transfer was
 * refused for insufficient funds instead of reaching its crash. MoneyMovementIntegrationTest.postingOutsideATransactionFailsLoudly keeps
 * the MANDATORY guard under test permanently.
 */
class AtomicityIntegrationTest extends AbstractPostgresIntegrationTest {

    /** The deliberate failure. A named type so the test can tell it apart from any real error. */
    static class SimulatedCrash extends RuntimeException {
        SimulatedCrash() {
            super("simulated crash after flush");
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountService accountService;

    @Autowired
    DepositService depositService;

    @Autowired
    TransferService transferService;

    @MockitoSpyBean
    LedgerPostingService postingService;

    @PersistenceContext
    EntityManager entityManager;

    private final AtomicLong entriesSeenInsideTransaction = new AtomicLong(-1);
    private final AtomicLong balanceSeenInsideTransaction = new AtomicLong(-1);

    /** Make the next post() do its real work, flush it to the database, look at it, and then crash. */
    private void crashAfterFlushOf(UUID accountToInspect) {
        doAnswer(invocation -> {
                    invocation.callRealMethod(); // the real work: transaction row, entries, balance changes
                    entityManager.flush();
                    entriesSeenInsideTransaction.set(((Number) entityManager
                                    .createNativeQuery("SELECT count(*) FROM ledger_entries WHERE account_id = ?1")
                                    .setParameter(1, accountToInspect)
                                    .getSingleResult())
                            .longValue());
                    balanceSeenInsideTransaction.set(((Number) entityManager
                                    .createNativeQuery("SELECT balance_minor FROM accounts WHERE id = ?1")
                                    .setParameter(1, accountToInspect)
                                    .getSingleResult())
                            .longValue());
                    throw new SimulatedCrash();
                })
                .when(spyBehindTheTransactionProxy())
                .post(any(), any(), any(), any(), any(), any());
    }

    /**
     * The injected field is Spring's transactional proxy wrapped around the Mockito spy. Stubbing must be done on the
     * spy itself; stubbing through the proxy would call post() for real, outside any transaction, and fail on
     * MANDATORY. The proxy stays in front of the spy at run time, so the service still runs inside the transaction.
     */
    private LedgerPostingService spyBehindTheTransactionProxy() {
        return AopTestUtils.getTargetObject(postingService);
    }

    private long balance(UUID account) {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account);
    }

    private long entryCount(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?", Long.class, account);
    }

    private long transactionCount(ActingUser actor) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_transactions WHERE created_by_user_id = ?", Long.class, actor.userId());
    }

    @Test
    void transferThatFailsAfterTheFlushLeavesNoEntriesAndNoBalanceChange() {
        ActingUser actor = new ActingUser(new LedgerTestData(jdbc).newUser());
        UUID from = accountService.open(actor, "from").getId();
        UUID to = accountService.open(actor, "to").getId();
        depositService.deposit(actor, new DepositCommand(from, 1_000, "GBP", null));
        // Committed state before the doomed transfer: 1 deposit transaction, 1 entry on `from`, balance 1,000.
        assertThat(transactionCount(actor)).isEqualTo(1);
        assertThat(entryCount(from)).isEqualTo(1);

        crashAfterFlushOf(from);
        assertThatThrownBy(() -> transferService.transfer(actor, new TransferCommand(from, to, 400, "GBP", null)))
                .isInstanceOf(SimulatedCrash.class);

        // Proof the failure really came AFTER the writes reached the database: inside the transaction the new entry
        // and the debited balance were visible.
        assertThat(entriesSeenInsideTransaction.get()).isEqualTo(2);
        assertThat(balanceSeenInsideTransaction.get()).isEqualTo(600L);

        // ...and afterwards, from another connection, nothing is left.
        assertThat(balance(from)).isEqualTo(1_000L);
        assertThat(balance(to)).isZero();
        assertThat(entryCount(from)).isEqualTo(1);
        assertThat(entryCount(to)).isZero();
        assertThat(transactionCount(actor)).isEqualTo(1);
    }

    @Test
    void depositThatFailsAfterTheFlushLeavesNoEntriesAndNoBalanceChange() {
        ActingUser actor = new ActingUser(new LedgerTestData(jdbc).newUser());
        UUID account = accountService.open(actor, "account").getId();

        crashAfterFlushOf(account);
        assertThatThrownBy(() -> depositService.deposit(actor, new DepositCommand(account, 700, "GBP", null)))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(entriesSeenInsideTransaction.get()).isEqualTo(1);
        assertThat(balanceSeenInsideTransaction.get()).isEqualTo(700L);

        assertThat(balance(account)).isZero();
        assertThat(entryCount(account)).isZero();
        assertThat(transactionCount(actor)).isZero();
    }
}
