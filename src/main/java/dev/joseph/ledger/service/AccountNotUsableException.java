package dev.joseph.ledger.service;

/**
 * The request is well formed and the accounts exist, but the accounts cannot take part in it (CLOSED, or a currency
 * that does not match). Becomes HTTP 422.
 */
public class AccountNotUsableException extends RuntimeException {

    public AccountNotUsableException(String message) {
        super(message);
    }
}
