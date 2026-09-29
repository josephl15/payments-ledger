package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.Account;
import dev.joseph.ledger.domain.AccountType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

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
     * Loads the given accounts of one type, ordered by id, WITHOUT locking them. Used only by the deliberately naive
     * AccountLockService body in Phase 3; Phase 4 replaces that body with a SELECT ... FOR UPDATE query.
     */
    List<Account> findByIdInAndTypeOrderById(Collection<UUID> ids, AccountType type);
}
