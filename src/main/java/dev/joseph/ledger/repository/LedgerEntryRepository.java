package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.LedgerEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Extends Repository, not JpaRepository: there is no delete or update method to call. The methods that are
 * declared again here have the same signatures as CrudRepository, which is enough for Spring Data to implement them.
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    <S extends LedgerEntry> S save(S entry);

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    Optional<LedgerEntry> findById(Long id);

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    List<LedgerEntry> findByAccountIdOrderByIdDesc(UUID accountId);
}
