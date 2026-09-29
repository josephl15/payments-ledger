package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.AccountType;
import dev.joseph.ledger.repository.AccountRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place where the accounts of a money movement are fetched for update. Every deposit and transfer calls
 * {@link #lock} first, before it reads or changes anything, and works only with the accounts it returns.
 *
 * <p>INTENTIONALLY NAIVE UNTIL PHASE 4. Today {@code lock} is a plain read that takes no database lock, so two
 * simultaneous transfers can both read the same balance and one update overwrites the other (a "lost update").
 * Phase 4 replaces only the body of this method with {@code SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE},
 * and a concurrency test that fails today is then expected to pass. The call shape (a set of ids in, the managed
 * customer accounts out, called first in the transaction) is final so no other class changes.
 *
 * <p>Only CUSTOMER accounts are returned, so a system account can never be locked through here (they have no cached
 * balance to protect). Asking for a system account id, or an id that does not exist, gives a 404.
 *
 * <p>{@code Propagation.MANDATORY} means "this method must be called inside a transaction that is already open, and
 * it throws if there is none". A locking read outside a transaction would release its lock immediately, so making
 * that mistake fail loudly is safer than allowing it.
 */
@Service
public class AccountLockService {

    private final AccountRepository accounts;

    public AccountLockService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Returns the requested CUSTOMER accounts by id; throws {@link ResourceNotFoundException} if any is missing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lock(Set<UUID> customerAccountIds) {
        // Naive body: no lock is taken here. See the class comment.
        Map<UUID, Account> found = new LinkedHashMap<>();
        for (Account account : accounts.findByIdInAndTypeOrderById(customerAccountIds, AccountType.CUSTOMER)) {
            found.put(account.getId(), account);
        }
        if (found.size() != customerAccountIds.size()) {
            throw new ResourceNotFoundException("Account not found");
        }
        return found;
    }
}
