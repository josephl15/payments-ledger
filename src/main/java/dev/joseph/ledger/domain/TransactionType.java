package dev.joseph.ledger.domain;

/** What kind of money movement a ledger transaction is. Stored as text in ledger_transactions.type. */
public enum TransactionType {
    DEPOSIT,
    TRANSFER,
    REVERSAL
}
