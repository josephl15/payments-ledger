package dev.joseph.ledger.service;

/** The request itself is wrong regardless of database state (amount out of range, same account twice). HTTP 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
