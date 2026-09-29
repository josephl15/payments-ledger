package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Extends Repository (not JpaRepository) so only the methods listed here exist. The locking query arrives in Phase 3. */
public interface AccountRepository extends Repository<Account, UUID> {

    <S extends Account> S save(S account);

    Optional<Account> findById(UUID id);

    List<Account> findByOwnerUserId(UUID ownerUserId);
}
