package dev.joseph.ledger.domain;

/** Whether an account can take part in new transactions. Stored as text in accounts.status. */
public enum AccountStatus {
    ACTIVE,
    CLOSED
}
