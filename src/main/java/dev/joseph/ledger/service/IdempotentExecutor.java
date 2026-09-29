package dev.joseph.ledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.joseph.ledger.domain.IdempotencyKey;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes a money-moving request safe to retry, without changing the business services it wraps.
 *
 * <p><b>This class is deliberately NOT {@code @Transactional}.</b> It opens and closes the transaction itself with a
 * {@link TransactionTemplate} (the programmatic form of {@code @Transactional}). That matters because of what
 * happens on a duplicate key:
 * <ol>
 *   <li>the INSERT of the key row fails inside the transaction, so PostgreSQL marks the transaction aborted and
 *       Spring marks it rollback-only. Nothing more can be done in it, not even a SELECT;</li>
 *   <li>the template rolls the transaction back and ENDS it, then the exception reaches the {@code catch} below,
 *       which runs with no transaction open;</li>
 *   <li>only now is the stored response read, in a fresh read-only transaction.</li>
 * </ol>
 * If this method were {@code @Transactional}, the catch block would still be inside the doomed transaction.
 *
 * <p>The happy path is one transaction: claim the key ({@link IdempotencyService#begin}), run the business call
 * (which is {@code @Transactional} and simply JOINS this transaction), store the response
 * ({@link IdempotencyService#complete}), commit. Key row, ledger entries, cached balances and the stored response
 * therefore become visible together or not at all.
 *
 * <p>A request that fails (insufficient funds, unknown account, ...) rolls everything back, including the key row.
 * Failures are therefore not remembered: a retry with the same key runs again. That is safe because nothing was
 * done the first time; the price is that a client never gets a stored "no" and simply gets a fresh "no".
 */
@Service
public class IdempotentExecutor {

    /** The loop only repeats if a key row expires or disappears between the duplicate and the read; twice is plenty. */
    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public IdempotentExecutor(
            IdempotencyService idempotency,
            RequestHasher hasher,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param actor the user the key belongs to (keys are per user, so two users may use the same key text)
     * @param idempotencyKey the raw header value; validated here, so every caller gets the same 400 rules
     * @param method HTTP method, part of the request fingerprint
     * @param path the concrete request path, part of the request fingerprint
     * @param validatedRequest the request DTO after Bean Validation, part of the request fingerprint
     * @param work the business call; runs inside the transaction and must return the status, body and transaction id
     */
    public StoredResponse execute(
            ActingUser actor,
            String idempotencyKey,
            String method,
            String path,
            Object validatedRequest,
            Supplier<IdempotentOutcome> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The replay-after-rollback design only works when this class owns the transaction boundary.
            throw new IllegalStateException("IdempotentExecutor must be called without an open transaction");
        }
        String key = IdempotencyKeyPolicy.requireValid(idempotencyKey);
        String requestHash = hasher.hash(method, path, validatedRequest);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(status -> {
                    IdempotencyKey row = idempotency.begin(actor.userId(), key, requestHash);
                    IdempotentOutcome outcome = work.get();
                    String bodyJson = toJson(outcome.body());
                    idempotency.complete(row, outcome.status(), bodyJson, outcome.transactionId());
                    return new StoredResponse(outcome.status(), bodyJson, false);
                });
            } catch (DuplicateIdempotencyKeyException duplicate) {
                // The transaction above has already been rolled back and is finished. Read in a new one.
                Optional<StoredResponse> stored = idempotency.replay(actor.userId(), key, requestHash);
                if (stored.isPresent()) {
                    return stored.get();
                }
                // The row expired or was reclaimed in between: go round again and try to claim the key.
            }
        }
        throw new IllegalStateException("Could not claim or replay the idempotency key");
    }

    private String toJson(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the response", e);
        }
    }
}
