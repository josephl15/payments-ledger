-- Phase 2: the two SYSTEM accounts that stand for the outside world, with fixed well-known ids so Java code
-- (SystemAccountIds) can refer to them. They have no cached balance (NULL) and no owner, so they are never locked or
-- updated: deposits post to them as the counter-side, and their balance is only ever derived from the ledger.
-- EXTERNAL_PAYOUTS is reserved for withdrawals (a deferred requirement) and has no consumer yet.
INSERT INTO accounts (id, owner_user_id, type, name, currency, status, balance_minor) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM', 'EXTERNAL_FUNDING', 'GBP', 'ACTIVE', NULL),
    ('00000000-0000-0000-0000-000000000002', NULL, 'SYSTEM', 'EXTERNAL_PAYOUTS', 'GBP', 'ACTIVE', NULL);
