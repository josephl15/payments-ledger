package dev.joseph.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Record of a client's idempotency key. The row is inserted first, inside the business transaction; the unique
 * constraint {@code uq_idempotency_user_key} on (user_id, idem_key) is what makes a retry safe. The response columns
 * are filled in by {@link #complete} once the work is done.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "idem_key", nullable = false, length = 255, updatable = false)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    // The response is replayed to a retrying client, so it stays a String of JSON text. PostgreSQL JSONB
    // normalises it (key order, spacing) but the content is the same.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected IdempotencyKey() {}

    public IdempotencyKey(UUID userId, String idemKey, String requestHash, Instant createdAt, Instant expiresAt) {
        this.userId = userId;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Stores the outcome so a retry with the same key can be answered without redoing the work. */
    public void complete(int responseStatus, String responseBody, UUID transactionId) {
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.transactionId = transactionId;
    }

    public Long getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
