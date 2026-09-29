package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.Account;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends Repository (not JpaRepository) so only the methods listed here exist. Spring Data writes the SQL from the
 * method names (for example {@code existsByIdAndOwnerUserId} becomes a query on id and owner_user_id).
 */
public interface AccountRepository extends Repository<Account, UUID> {

    <S extends Account> S save(S account);

    Optional<Account> findById(UUID id);

    List<Account> findByOwnerUserId(UUID ownerUserId);

    /** An owner's accounts, oldest first, for the list endpoint. */
    List<Account> findByOwnerUserIdOrderByCreatedAtAscIdAsc(UUID ownerUserId);

    /**
     * Ownership check that loads NO entity. It answers yes or no with a scalar query, so nothing is put into the
     * persistence context before the accounts are locked and read.
     */
    boolean existsByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    /**
     * Loads the given CUSTOMER accounts and takes a row lock on each, held until the transaction ends. This is the
     * statement that makes concurrent money movement safe; only AccountLockService calls it.
     *
     * <p>Three details are deliberate:
     * <ul>
     *   <li>{@code ORDER BY id}: PostgreSQL takes the row locks in the order it returns the rows. Every caller asking
     *       for the same accounts therefore locks them in the same order, so two transfers between A and B (A to B and
     *       B to A) can never each hold one row and wait for the other (a deadlock). The sort is done by the
     *       database, never in Java: Java's {@code UUID.compareTo} orders differently from PostgreSQL's {@code uuid}
     *       ordering, and only one order may ever be used.</li>
     *   <li>{@code FOR UPDATE}: a second transaction that asks for a locked row waits until the first commits or
     *       rolls back, and then sees the committed row (READ COMMITTED re-reads a locked row it was waiting for).</li>
     *   <li>{@code nativeQuery}: the SQL runs exactly as written here, so what is in the source is what reaches the
     *       database. The {@code type = 'CUSTOMER'} filter keeps system accounts out of the lock path.</li>
     * </ul>
     *
     * <p>{@code ids} must not be empty ({@code IN ()} is not valid SQL); the caller guards that.
     */
    @Query(
            value = "SELECT * FROM accounts WHERE id IN (:ids) AND type = 'CUSTOMER' ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Account> lockCustomerAccountsOrderedById(@Param("ids") Collection<UUID> ids);
}
