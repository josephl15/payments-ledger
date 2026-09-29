/**
 * The HTTP edge: thin REST controllers, request/response records (DTOs) and the global exception handler that
 * turns exceptions into RFC 7807 problem responses. Controllers only validate input and call a service; they hold
 * no business rules and no {@code @Transactional}. First filled in Phase 3 (accounts, deposits, transfers).
 *
 * <p>Dependency direction for the whole project: api and security depend on service; service depends on
 * repository; repository depends on domain. The service layer never imports api or security classes.
 */
package dev.joseph.ledger.api;
