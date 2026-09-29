package dev.joseph.ledger.service;

/** The same Idempotency-Key was sent with a different request (other body, path or method). HTTP 422. */
public class IdempotencyKeyMismatchException extends RuntimeException {

    public IdempotencyKeyMismatchException() {
        super("This Idempotency-Key was already used with a different request");
    }
}
