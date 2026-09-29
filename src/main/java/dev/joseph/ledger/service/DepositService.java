package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.AccountStatus;
import dev.joseph.ledger.domain.SystemAccountIds;
import dev.joseph.ledger.domain.TransactionType;
import dev.joseph.ledger.repository.AccountRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A simulated deposit: money arrives from outside the ledger. Two entries, EXTERNAL_FUNDING debited and the
 * customer credited, so the transaction sums to zero. Only the customer account is locked and updated; the funding
 * account is a system account with no cached balance.
 */
@Service
public class DepositService {

    private final AccountRepository accounts;
    private final AccountLockService lockService;
    private final LedgerPostingService postingService;
    private final AmountPolicy amountPolicy;

    public DepositService(
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
     * {@code @Transactional} here is what makes the whole deposit one all-or-nothing unit: the lock, the entries and
     * the balance change commit together or (on any exception) roll back together. It sits on the public business
     * method, called from the controller through Spring's proxy.
     */
    @Transactional
    public PostedTransaction deposit(ActingUser actor, DepositCommand command) {
        amountPolicy.checkAmount(command.amountMinor());
        amountPolicy.checkCurrency(command.currency());

        // Ownership check that loads no entity, so the first time the account is read is inside lock().
        if (!accounts.existsByIdAndOwnerUserId(command.accountId(), actor.userId())) {
            throw new ResourceNotFoundException("Account not found");
        }

        Map<UUID, Account> locked = lockService.lock(Set.of(command.accountId()));
        Account target = locked.get(command.accountId());
        if (target.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotUsableException("Account is not active");
        }
        if (!command.currency().equals(target.getCurrency())) {
            throw new AccountNotUsableException("Currency does not match the account");
        }

        List<EntryLine> lines = List.of(
                new EntryLine(SystemAccountIds.EXTERNAL_FUNDING, -command.amountMinor()),
                new EntryLine(target.getId(), command.amountMinor()));
        return postingService.post(
                TransactionType.DEPOSIT, command.reference(), actor.userId(), command.currency(), lines, locked);
    }
}
