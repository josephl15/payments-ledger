package dev.joseph.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One line of the ledger: a signed amount in pence posted to one account (negative = debit, positive = credit).
 * Entries are the source of truth for every balance. They are append-only: {@code @Immutable} stops Hibernate
 * emitting an UPDATE, every column is {@code updatable = false}, the repository has no delete method, and the
 * database triggers reject UPDATE, DELETE and TRUNCATE from anything else.
 */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    // IDENTITY (not plain @GeneratedValue): the column is GENERATED ALWAYS AS IDENTITY, and the default AUTO
    // strategy would look for a sequence named ledger_entries_SEQ and fail schema validation.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {}

    public LedgerEntry(UUID transactionId, UUID accountId, long amountMinor, String currency, Instant createdAt) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
