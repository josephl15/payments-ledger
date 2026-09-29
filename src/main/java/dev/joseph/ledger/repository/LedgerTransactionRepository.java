package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.LedgerTransaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Transactions are never deleted or updated, so this repository has neither operation. */
public interface LedgerTransactionRepository extends Repository<LedgerTransaction, UUID> {

    <S extends LedgerTransaction> S save(S transaction);

    Optional<LedgerTransaction> findById(UUID id);

    /** True if some transaction already reverses this one (the database also enforces at most one). */
    boolean existsByReversesTransactionId(UUID reversesTransactionId);
}
