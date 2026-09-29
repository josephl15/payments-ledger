package dev.joseph.ledger.domain;

/** Who a user is. Stored as text in users.role (the database CHECK allows exactly these names). */
public enum Role {
    USER,
    ADMIN
}
