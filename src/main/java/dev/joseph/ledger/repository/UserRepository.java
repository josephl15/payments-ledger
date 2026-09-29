package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Extends Repository (not JpaRepository) so only the methods listed here exist. */
public interface UserRepository extends Repository<User, UUID> {

    <S extends User> S save(S user);

    Optional<User> findById(UUID id);

    Optional<User> findByUsername(String username);

    boolean existsById(UUID id);
}
