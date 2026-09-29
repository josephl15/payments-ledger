-- Phase 2: the ledger and the audit log are append-only. Any UPDATE, DELETE or TRUNCATE is rejected by the database
-- itself, so SQL run outside the application (or a buggy code path) cannot rewrite history.
--
-- Honest limit: the table owner or a superuser can still disable these triggers (ALTER TABLE ... DISABLE TRIGGER)
-- or drop the table. The triggers stop application bugs and casual SQL, not a privileged DBA. Production hardening
-- would also REVOKE UPDATE, DELETE and TRUNCATE from the application role (see docs/DECISIONS.md).
--
-- A row-level trigger does not fire on TRUNCATE, so each table also gets a BEFORE TRUNCATE statement-level trigger.
-- This file is final once committed; change it with a new V-number.

CREATE FUNCTION forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    -- RAISE EXCEPTION defaults to SQLSTATE P0001 (raise_exception). TG_OP is UPDATE, DELETE or TRUNCATE.
    RAISE EXCEPTION '% on % is forbidden: the table is append-only', TG_OP, TG_TABLE_NAME;
    RETURN NULL; -- never reached; keeps the function well-formed for a trigger
END;
$$;

CREATE TRIGGER trg_ledger_entries_no_update_delete
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
