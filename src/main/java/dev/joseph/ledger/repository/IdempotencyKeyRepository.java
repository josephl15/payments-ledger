package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.IdempotencyKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Extends Repository (not JpaRepository) so only the methods listed here exist. */
public interface IdempotencyKeyRepository extends Repository<IdempotencyKey, Long> {

    <S extends IdempotencyKey> S save(S key);

    Optional<IdempotencyKey> findByUserIdAndIdemKey(UUID userId, String idemKey);
}
