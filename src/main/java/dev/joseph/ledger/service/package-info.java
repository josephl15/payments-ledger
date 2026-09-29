/**
 * Business rules and transaction boundaries: TransferService, DepositService, ReversalService,
 * IdempotencyService, ReconciliationService and AuditService. This is the only layer that declares
 * {@code @Transactional}, and it never imports api or security classes. First filled in Phase 3 (deposits and
 * transfers), with locking and reconciliation in Phase 4 and idempotency in Phase 5.
 */
package dev.joseph.ledger.service;
