/**
 * JPA entities and enums that map the database tables (users, accounts, transactions, entries, ...). Entities are
 * plain classes with explicit getters, no Lombok, and money is always {@code long} minor units (pence). Depends on
 * nothing else in this project. First filled in Phase 2 (schema, triggers and domain model).
 */
package dev.joseph.ledger.domain;
