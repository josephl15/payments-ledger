package dev.joseph.ledger;

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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The deadlock scenario: two accounts, A and B, and many simultaneous transfers that alternate direction (A to B, then
 * B to A, then A to B ...). This is the classic way to provoke a lock-order deadlock: if one transfer locks A then B
 * while another locks B then A, each ends up holding one and waiting for the other. Amounts are small compared with
 * the balances, so no transfer is ever refused for insufficient funds and any failure is a locking failure.
 */
final class PingPong {

    static final long SEED = 7_654_321L;
    static final long INITIAL_BALANCE = 1_000_000;

    private PingPong() {}

    record Result(
            TransferLoad.Outcome outcome,
            ReconciliationReport report,
            long cachedA,
            long cachedB,
            long expectedIfAllSucceedA,
            long expectedIfAllSucceedB) {}

    static Result run(
            JdbcTemplate jdbc,
            AccountService accounts,
            DepositService deposits,
            TransferService transfers,
            ReconciliationService reconciliation,
            int transferCount,
            int threads)
            throws Exception {
        ActingUser actor = new ActingUser(new LedgerTestData(jdbc).newUser());
        UUID a = accounts.open(actor, "ping").getId();
        UUID b = accounts.open(actor, "pong").getId();
        deposits.deposit(actor, new DepositCommand(a, INITIAL_BALANCE, "GBP", null));
        deposits.deposit(actor, new DepositCommand(b, INITIAL_BALANCE, "GBP", null));

        Random random = new Random(SEED);
        List<TransferLoad.Spec> specs = new ArrayList<>();
        long expectedA = INITIAL_BALANCE;
        long expectedB = INITIAL_BALANCE;
        for (int i = 0; i < transferCount; i++) {
            long amount = 1 + random.nextInt(100);
            if (i % 2 == 0) {
                specs.add(new TransferLoad.Spec(a, b, amount));
                expectedA -= amount;
                expectedB += amount;
            } else {
                specs.add(new TransferLoad.Spec(b, a, amount));
                expectedB -= amount;
                expectedA += amount;
            }
        }

        TransferLoad.Outcome outcome = TransferLoad.run(transfers, actor, specs, threads, 90);

        Set<UUID> transactionIds = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM ledger_transactions WHERE created_by_user_id = ?", UUID.class, actor.userId()));
        ReconciliationReport report = reconciliation.reconcile(ReconciliationScope.of(Set.of(a, b), transactionIds));
        long cachedA = jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, a);
        long cachedB = jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, b);
        return new Result(outcome, report, cachedA, cachedB, expectedA, expectedB);
    }
}
