package dev.joseph.ledger.service;

import dev.joseph.ledger.config.LedgerProperties;
import dev.joseph.ledger.domain.IdempotencyKey;
import dev.joseph.ledger.repository.IdempotencyKeyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three database steps of the idempotency protocol. {@link IdempotentExecutor} decides when each one runs.
 *
 * <ul>
 *   <li>{@link #begin}: claim the key by INSERTing its row, first thing in the business transaction.</li>
 *   <li>{@link #complete}: write the response onto that row, last thing in the same transaction.</li>
 *   <li>{@link #replay}: for a key that was already used, read the stored response in a NEW transaction.</li>
 * </ul>
 */
@Service
public class IdempotencyService {

    /** The named unique constraint on (user_id, idem_key), created in V2__schema.sql. */
    static final String KEY_CONSTRAINT = "uq_idempotency_user_key";

    /** SQLSTATE for unique_violation. */
    private static final String UNIQUE_VIOLATION = "23505";

    private final IdempotencyKeyRepository keys;
    private final LedgerProperties properties;
    private final Clock clock;

    public IdempotencyService(IdempotencyKeyRepository keys, LedgerProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Claims the key for this request. MANDATORY: it only works inside the executor's transaction, so the key row
     * commits or rolls back together with the money movement.
     *
     * <p>Step 1 removes an EXPIRED row for this user and key, if there is one (an expired row still occupies the
     * unique index, so it would otherwise block reuse for ever). Step 2 inserts the new row and flushes. The
     * database, not Java code, decides who wins: if another transaction has already inserted (or is in the middle
     * of inserting) the same (user, key), this INSERT waits for that transaction to finish and then fails with
     * unique_violation, which is turned into {@link DuplicateIdempotencyKeyException}. There is deliberately no
     * "SELECT to see if it exists" first: between that SELECT and the INSERT another request could slip in.
     *
     * @throws DuplicateIdempotencyKeyException only for a violation of {@code uq_idempotency_user_key}; any other
     *     database error is passed on unchanged and is NOT treated as a replay
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IdempotencyKey begin(UUID userId, String idemKey, String requestHash) {
        Instant now = clock.instant();
        keys.deleteExpired(userId, idemKey, now);
        try {
            return keys.saveAndFlush(new IdempotencyKey(userId, idemKey, requestHash, now, now.plus(properties.idempotencyTtl())));
        } catch (DataIntegrityViolationException e) {
            if (isKeyViolation(e)) {
                throw new DuplicateIdempotencyKeyException(e);
            }
            throw e;
        }
    }

    /** Stores the response on the key row. The UPDATE is sent when the transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(IdempotencyKey key, int responseStatus, String responseBodyJson, UUID transactionId) {
        key.complete(responseStatus, responseBodyJson, transactionId);
    }

    /**
     * Reads the stored response for a key that was already used. It runs in its OWN short read-only transaction and
     * must be called with no transaction open: the transaction that hit the duplicate is unusable (PostgreSQL marks
     * a transaction aborted after any error, and Spring marks it rollback-only), so the read has to happen after
     * that transaction has ended. That is why the caller is the non-transactional {@link IdempotentExecutor}.
     *
     * @return the stored response, or empty if the row is gone or has expired (the executor then starts again)
     * @throws IdempotencyKeyMismatchException if the key was used with a different request
     */
    @Transactional(readOnly = true)
    public Optional<StoredResponse> replay(UUID userId, String idemKey, String requestHash) {
        Optional<IdempotencyKey> found = keys.findByUserIdAndIdemKey(userId, idemKey);
        if (found.isEmpty() || !found.get().getExpiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        IdempotencyKey row = found.get();
        if (!row.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyMismatchException();
        }
        if (row.getResponseStatus() == null) {
            // Cannot happen: the row becomes visible only when its transaction commits, and that transaction also
            // wrote the response. If it ever does, failing loudly beats answering with nothing.
            throw new IllegalStateException("Idempotency key row has no stored response");
        }
        return Optional.of(new StoredResponse(row.getResponseStatus(), row.getResponseBody(), true));
    }

    /**
     * True only for a unique violation (SQLSTATE 23505) of {@code uq_idempotency_user_key}. Hibernate's
     * ConstraintViolationException carries both the SQLSTATE and the constraint name that PostgreSQL reported. Any
     * other 23505 (for example a duplicate username) or another kind of error returns false, so it is never
     * mistaken for "this request was seen before".
     */
    public static boolean isKeyViolation(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return UNIQUE_VIOLATION.equals(cve.getSQLState()) && KEY_CONSTRAINT.equals(cve.getConstraintName());
            }
        }
        return false;
    }
}
