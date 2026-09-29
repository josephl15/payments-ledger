package dev.joseph.ledger.service;

/**
 * Login failed. The same exception and the same message are used whether the username does not exist or the
 * password is wrong, so the response never reveals which usernames are registered. Becomes HTTP 401.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid username or password");
    }
}
