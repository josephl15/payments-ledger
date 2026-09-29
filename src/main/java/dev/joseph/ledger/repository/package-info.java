/**
 * Spring Data repositories plus the locking queries ({@code SELECT ... FOR UPDATE} in a fixed order) and the
 * reconciliation SQL. Talks to the database and returns domain objects; contains no business decisions. First
 * filled in Phase 3, with the ordered locking query added in Phase 4.
 */
package dev.joseph.ledger.repository;
