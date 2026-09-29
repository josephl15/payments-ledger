package dev.joseph.ledger.service;

/** The paying account holds less than the amount. Becomes HTTP 422. Thrown only after the account rows are locked. */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
