package dev.joseph.ledger.repository;

import dev.joseph.ledger.domain.AuditLogEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** The audit trail is append-only, so this repository can save and read but never delete or update. */
public interface AuditLogRepository extends Repository<AuditLogEntry, Long> {

    <S extends AuditLogEntry> S save(S entry);

    List<AuditLogEntry> findByUserIdOrderByIdDesc(UUID userId);
}
