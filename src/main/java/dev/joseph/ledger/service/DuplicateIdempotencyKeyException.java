package dev.joseph.ledger.service;

/**
 * Thrown by {@link IdempotencyService#begin} when the (user, key) pair already has a row: an earlier or concurrent
 * request used this key. It is NOT an error for the client; {@link IdempotentExecutor} catches it after the failed
 * transaction has rolled back and answers with the stored response.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(Throwable cause) {
        super("Idempotency key already used", cause);
    }
}
