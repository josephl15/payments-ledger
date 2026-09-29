package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.LedgerEntry;
import dev.joseph.ledger.domain.LedgerTransaction;
import dev.joseph.ledger.domain.SystemAccountIds;
import dev.joseph.ledger.domain.TransactionType;
import dev.joseph.ledger.repository.LedgerEntryRepository;
import dev.joseph.ledger.repository.LedgerTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONLY code that inserts into ledger_transactions and ledger_entries, and the only code that changes a cached
 * account balance. Deposits and transfers differ only in which lines they hand to {@link #post}. Keeping this in one
 * place is what makes "every transaction sums to zero" true in exactly one method.
 *
 * <p>It is {@code Propagation.MANDATORY}: it must be called from a method that already has a transaction open (the
 * business service methods do). If someone forgets {@code @Transactional}, or calls a {@code @Transactional} method on
 * {@code this} (the proxy is bypassed, see docs/INTERVIEW_PREP.md), this fails immediately with
 * IllegalTransactionStateException instead of silently committing half a transfer.
 */
@Service
public class LedgerPostingService {

    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final Clock clock;

    public LedgerPostingService(LedgerTransactionRepository transactions, LedgerEntryRepository entries, Clock clock) {
        this.transactions = transactions;
        this.entries = entries;
        this.clock = clock;
    }

    /**
     * Writes one balanced transaction and updates the cached balance of every customer account it touches.
     *
     * @param lockedCustomerAccounts the customer accounts the caller obtained from AccountLockService; every
     *     non-system line must point at one of them
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedTransaction post(
            TransactionType type,
            String reference,
            UUID createdByUserId,
            String currency,
            List<EntryLine> lines,
            Map<UUID, Account> lockedCustomerAccounts) {
        requireBalanced(lines);
        requireLockedAccountsInCurrency(lines, currency, lockedCustomerAccounts);

        Instant now = clock.instant();
        // Insert and flush the transaction row first: the entries reference it by foreign key.
        LedgerTransaction transaction =
                transactions.saveAndFlush(new LedgerTransaction(type, reference, null, createdByUserId, now));

        List<LedgerEntry> newEntries = new ArrayList<>();
        for (EntryLine line : lines) {
            newEntries.add(new LedgerEntry(transaction.getId(), line.accountId(), line.amountMinor(), currency, now));
        }
        List<LedgerEntry> savedEntries = entries.saveAll(newEntries);

        // The cached balance moves in the same database transaction as the entries it reflects. The change is made
        // on the entity (read, add, write back) and Hibernate sends the UPDATE at flush/commit.
        for (EntryLine line : lines) {
            if (SystemAccountIds.isSystem(line.accountId())) {
                continue; // system accounts have no cached balance; their balance is derived from entries
            }
            Account account = lockedCustomerAccounts.get(line.accountId());
            account.applyDelta(line.amountMinor());
            if (account.getBalanceMinor() < 0) {
                // The service checks funds before posting, so reaching here is a bug. The database CHECK is the last net.
                throw new IllegalStateException("Posting would make a customer balance negative");
            }
        }
        return new PostedTransaction(transaction, savedEntries);
    }

    private static void requireBalanced(List<EntryLine> lines) {
        if (lines.size() < 2) {
            throw new IllegalArgumentException("A transaction needs at least two entries");
        }
        long sum = 0;
        for (EntryLine line : lines) {
            if (line.amountMinor() == 0) {
                throw new IllegalArgumentException("Entry amounts must be non-zero");
            }
            sum = Math.addExact(sum, line.amountMinor());
        }
        if (sum != 0) {
            throw new IllegalArgumentException("Entries must sum to zero but sum to " + sum);
        }
    }

    private static void requireLockedAccountsInCurrency(
            List<EntryLine> lines, String currency, Map<UUID, Account> lockedCustomerAccounts) {
        for (EntryLine line : lines) {
            if (SystemAccountIds.isSystem(line.accountId())) {
                continue;
            }
            Account account = lockedCustomerAccounts.get(line.accountId());
            if (account == null) {
                throw new IllegalStateException("A customer account was posted to without being locked first");
            }
            if (!currency.equals(account.getCurrency())) {
                throw new IllegalStateException("Every entry of a transaction must use one currency");
            }
        }
    }
}
