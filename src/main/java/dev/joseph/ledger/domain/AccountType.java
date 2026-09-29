package dev.joseph.ledger.domain;

/**
 * CUSTOMER accounts belong to a user and carry a cached balance that may never go negative. SYSTEM accounts stand
 * for the outside world (EXTERNAL_FUNDING, EXTERNAL_PAYOUTS): no owner and no cached balance.
 */
public enum AccountType {
    CUSTOMER,
    SYSTEM
}
