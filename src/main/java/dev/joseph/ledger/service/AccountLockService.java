package dev.joseph.ledger.service;

import dev.joseph.ledger.domain.Account;
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
 * <p><b>What {@code lock} does.</b> One statement, {@code SELECT ... WHERE id IN (...) AND type = 'CUSTOMER' ORDER BY
 * id FOR UPDATE} (see {@link AccountRepository#lockCustomerAccountsOrderedById}). It takes a row lock on every
 * requested account, held until the calling transaction commits or rolls back. A concurrent transaction that wants one
 * of the same accounts waits, then reads the committed balance. That removes the "lost update" (two transfers reading
 * the same balance and one write overwriting the other).
 *
 * <p><b>Why one statement with {@code ORDER BY id}.</b> Locks are taken in the order the rows come back, so all
 * callers lock the same accounts in the same order (the database's id order). Two opposite transfers A to B and B to A
 * then both lock A first; one waits for the other instead of each holding one account and waiting for the other's
 * (deadlock). The ordering is done by PostgreSQL, never by sorting UUIDs in Java, because the two orders differ.
 *
 * <p><b>Why this must be the first load of these accounts in the transaction.</b> Hibernate keeps every entity it has
 * loaded in the persistence context (a per-transaction cache). If an account had been loaded earlier in the same
 * transaction, this query would still lock the row in the database but hand back the OLD in-memory copy, with a stale
 * balance. Callers therefore check ownership with a query that returns yes or no and loads nothing
 * ({@code existsByIdAndOwnerUserId}), and check funds only on the accounts returned here.
 *
 * <p>Only CUSTOMER accounts are returned, so a system account can never be locked through here (they have no cached
 * balance to protect). Asking for a system account id, or an id that does not exist, gives a 404. The transaction
 * runs at the default READ COMMITTED level with the connection's {@code lock_timeout} of 5 seconds: waiting longer
 * than that for a row fails the request instead of hanging it.
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

    /**
     * Locks the requested CUSTOMER accounts in id order and returns them by id (in that same order); throws
     * {@link ResourceNotFoundException} if any is missing, is a system account, or the set is empty.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lock(Set<UUID> customerAccountIds) {
        if (customerAccountIds.isEmpty()) {
            throw new ResourceNotFoundException("Account not found"); // IN () is not valid SQL; nothing to lock
        }
        Map<UUID, Account> found = new LinkedHashMap<>();
        for (Account account : accounts.lockCustomerAccountsOrderedById(customerAccountIds)) {
            found.put(account.getId(), account);
        }
        if (found.size() != customerAccountIds.size()) {
            throw new ResourceNotFoundException("Account not found");
        }
        return found;
    }
}
