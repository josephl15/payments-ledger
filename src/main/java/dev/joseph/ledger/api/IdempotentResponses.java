package dev.joseph.ledger.api;

import dev.joseph.ledger.service.StoredResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Shared by the money-moving controllers: names the header and turns a {@link StoredResponse} into an HTTP response. */
final class IdempotentResponses {

    /** Request header the client sends; the value is validated by IdempotencyKeyPolicy inside the executor. */
    static final String KEY_HEADER = "Idempotency-Key";

    /** Response header set to "true" when the answer was read back from an earlier request with the same key. */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private IdempotentResponses() {}

    /**
     * The body is the stored JSON text, sent as is. A fresh answer and a replayed one both come from the same stored
     * artefact (status plus JSON), so they cannot drift apart.
     */
    static ResponseEntity<String> toResponse(StoredResponse stored) {
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(stored.status()).contentType(MediaType.APPLICATION_JSON);
        if (stored.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(stored.bodyJson());
    }
}
