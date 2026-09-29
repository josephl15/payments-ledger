package dev.joseph.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One money movement: a group of ledger entries that sum to zero. Never updated after it is written, so it is
 * mapped {@code @Immutable}. A REVERSAL transaction points at the transaction it reverses.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private TransactionType type;

    @Column(name = "reference", length = 255, updatable = false)
    private String reference;

    @Column(name = "reverses_transaction_id", updatable = false)
    private UUID reversesTransactionId;

    @Column(name = "created_by_user_id", nullable = false, updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {}

    public LedgerTransaction(
            TransactionType type, String reference, UUID reversesTransactionId, UUID createdByUserId, Instant createdAt) {
        this.type = type;
        this.reference = reference;
        this.reversesTransactionId = reversesTransactionId;
        this.createdByUserId = createdByUserId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public String getReference() {
        return reference;
    }

    public UUID getReversesTransactionId() {
        return reversesTransactionId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
