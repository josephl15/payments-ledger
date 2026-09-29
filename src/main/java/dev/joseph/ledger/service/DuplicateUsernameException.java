package dev.joseph.ledger.service;

/** Registration with a username that is already taken. Becomes HTTP 409. */
public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException() {
        super("Username is already taken");
    }
}
