package dev.joseph.ledger.domain;

/** How an audited action ended. Stored as text in audit_log.outcome. */
public enum AuditOutcome {
    SUCCESS,
    DENIED,
    FAILED
}
