package dev.joseph.ledger.service;

/**
 * The thing asked for does not exist, or exists but belongs to someone else (the two are deliberately
 * indistinguishable so the API does not reveal which ids exist). Becomes HTTP 404. Unchecked, so it rolls the
 * transaction back by default and does not clutter method signatures.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
