-- Baseline marker: proves Flyway ran against this database. It creates no ledger tables.
-- This file is final once committed (Flyway checksums applied migrations); Phase 2 starts at V2.
COMMENT ON SCHEMA public IS 'Payments ledger schema, managed by Flyway';
