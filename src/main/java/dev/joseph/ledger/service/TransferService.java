package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.AccountStatus;
import dev.joseph.ledger.domain.TransactionType;
import dev.joseph.ledger.repository.AccountRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves money between two CUSTOMER accounts as one balanced double-entry transaction: the paying account debited,
 * the receiving account credited, both cached balances updated, all in a single database transaction.
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final AccountLockService lockService;
    private final LedgerPostingService postingService;
    private final AmountPolicy amountPolicy;

    public TransferService(
            AccountRepository accounts,
            AccountLockService lockService,
            LedgerPostingService postingService,
            AmountPolicy amountPolicy) {
        this.accounts = accounts;
        this.lockService = lockService;
        this.postingService = postingService;
        this.amountPolicy = amountPolicy;
    }

    /**
     * The order of the steps is part of the design:
     * <ol>
     *   <li>checks that need no database (amount, currency, from != to);</li>
     *   <li>ownership of the paying account, answered without loading any entity;</li>
     *   <li>{@code lockService.lock(...)} FIRST among the account reads, so the balance that is checked below is
     *       the locked one, not a copy loaded earlier;</li>
     *   <li>checks on the locked accounts (ACTIVE, currency), then the funds check;</li>
     *   <li>post the balanced entries and update both cached balances.</li>
     * </ol>
     * Any exception rolls the whole transaction back.
     */
    @Transactional
    public PostedTransaction transfer(ActingUser actor, TransferCommand command) {
        amountPolicy.checkAmount(command.amountMinor());
        amountPolicy.checkCurrency(command.currency());
        if (command.fromAccountId().equals(command.toAccountId())) {
            throw new InvalidRequestException("fromAccountId and toAccountId must be different");
        }
        if (!accounts.existsByIdAndOwnerUserId(command.fromAccountId(), actor.userId())) {
            throw new ResourceNotFoundException("Account not found");
        }

        Set<UUID> ids = new HashSet<>();
        ids.add(command.fromAccountId());
        ids.add(command.toAccountId());
        Map<UUID, Account> locked = lockService.lock(ids);
        Account from = locked.get(command.fromAccountId());
        Account to = locked.get(command.toAccountId());

        for (Account account : List.of(from, to)) {
            if (account.getStatus() != AccountStatus.ACTIVE) {
                throw new AccountNotUsableException("Account is not active");
            }
            if (!command.currency().equals(account.getCurrency())) {
                throw new AccountNotUsableException("Currency does not match the account");
            }
        }

        // Funds are checked only now, on the account as it is after the lock was taken.
        if (from.getBalanceMinor() < command.amountMinor()) {
            throw new InsufficientFundsException("Insufficient funds");
        }

        List<EntryLine> lines =
                List.of(new EntryLine(from.getId(), -command.amountMinor()), new EntryLine(to.getId(), command.amountMinor()));
        return postingService.post(
                TransactionType.TRANSFER, command.reference(), actor.userId(), command.currency(), lines, locked);
    }
}
