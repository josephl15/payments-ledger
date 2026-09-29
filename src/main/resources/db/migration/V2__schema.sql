-- Phase 2: ledger schema. Flyway owns the schema; Hibernate only validates it (ddl-auto=validate).
-- This file is final once committed (Flyway checksums applied migrations). Change it with a new V-number.
--
-- Conventions: money is BIGINT minor units (pence); currency is VARCHAR(3) + CHECK (not CHAR(3): Hibernate
-- validate reports bpchar vs varchar); enums are VARCHAR + CHECK (not native PG enums); timestamps are
-- TIMESTAMPTZ; every constraint is named explicitly because later phases discriminate violations by name.

CREATE TABLE users (
    id            UUID         NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_users          PRIMARY KEY (id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_role     CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE accounts (
    id            UUID         NOT NULL,
    owner_user_id UUID,
    type          VARCHAR(16)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    -- Cached balance. NULL for SYSTEM accounts (deliberately no cache, so no hot row); see ck_accounts_balance_shape.
    balance_minor BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_accounts_type     CHECK (type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT ck_accounts_status   CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_accounts_currency CHECK (currency ~ '^[A-Z]{3}$'),
    -- Owner exactly for CUSTOMER accounts.
    CONSTRAINT ck_accounts_owner_shape CHECK (
        (type = 'CUSTOMER' AND owner_user_id IS NOT NULL) OR (type = 'SYSTEM' AND owner_user_id IS NULL)),
    -- Cached balance exactly for CUSTOMER accounts.
    CONSTRAINT ck_accounts_balance_shape CHECK (
        (type = 'CUSTOMER' AND balance_minor IS NOT NULL) OR (type = 'SYSTEM' AND balance_minor IS NULL)),
    -- Invariant 3 backstop. A NULL balance (SYSTEM) makes this comparison unknown, and a CHECK passes on unknown.
    CONSTRAINT ck_accounts_balance_nonneg CHECK (balance_minor >= 0)
);
CREATE INDEX ix_accounts_owner ON accounts (owner_user_id);

CREATE TABLE ledger_transactions (
    id                      UUID         NOT NULL,
    type                    VARCHAR(16)  NOT NULL,
    reference               VARCHAR(255),
    reverses_transaction_id UUID,
    created_by_user_id      UUID         NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ledger_transactions  PRIMARY KEY (id),
    CONSTRAINT fk_ledger_tx_reverses   FOREIGN KEY (reverses_transaction_id) REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_tx_created_by FOREIGN KEY (created_by_user_id)      REFERENCES users (id)               ON DELETE RESTRICT,
    -- A transaction can be reversed at most once (Phase 7 maps a violation of this name to 409).
    -- UNIQUE allows any number of NULLs, so ordinary transactions do not collide.
    CONSTRAINT uq_ledger_tx_reverses   UNIQUE (reverses_transaction_id),
    CONSTRAINT ck_ledger_tx_type       CHECK (type IN ('DEPOSIT', 'TRANSFER', 'REVERSAL')),
    -- Exactly REVERSAL transactions point at the transaction they reverse, and never at themselves.
    CONSTRAINT ck_ledger_tx_reversal_shape CHECK (
        (type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL)
        AND reverses_transaction_id IS DISTINCT FROM id)
);

CREATE TABLE ledger_entries (
    -- GENERATED ALWAYS: the application can never supply an id. Hibernate GenerationType.IDENTITY inserts without the column.
    id             BIGINT       GENERATED ALWAYS AS IDENTITY,
    transaction_id UUID         NOT NULL,
    account_id     UUID         NOT NULL,
    -- Signed minor units: negative = debit, positive = credit.
    amount_minor   BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_entries_transaction FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_entries_account     FOREIGN KEY (account_id)     REFERENCES accounts (id)            ON DELETE RESTRICT,
    CONSTRAINT ck_entries_amount_nonzero CHECK (amount_minor <> 0),
    CONSTRAINT ck_entries_currency       CHECK (currency ~ '^[A-Z]{3}$')
);
-- Entry history for one account, newest first (Phase 7), and the per-account SUM in reconciliation (Phase 4).
CREATE INDEX ix_entries_account_id_desc ON ledger_entries (account_id, id DESC);
-- A transaction with its entries, and the per-transaction sum in reconciliation.
CREATE INDEX ix_entries_transaction ON ledger_entries (transaction_id);

CREATE TABLE idempotency_keys (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY,
    user_id         UUID         NOT NULL,
    idem_key        VARCHAR(255) NOT NULL,
    -- SHA-256 as 64 lowercase hex characters.
    request_hash    VARCHAR(64)  NOT NULL,
    -- NULL until the business work completes; the row is inserted first, inside the business transaction.
    response_status INTEGER,
    response_body   JSONB,
    transaction_id  UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT fk_idem_user        FOREIGN KEY (user_id)        REFERENCES users (id)               ON DELETE RESTRICT,
    CONSTRAINT fk_idem_transaction FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id) ON DELETE RESTRICT,
    -- The at-most-once guarantee (invariant 5). Phase 5 recognises a duplicate by SQLSTATE 23505 plus this name.
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key)
);

CREATE TABLE audit_log (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY,
    -- NULL for events with no authenticated user (for example a rejected login).
    user_id       UUID,
    action        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(64)  NOT NULL,
    resource_id   UUID,
    outcome       VARCHAR(16)  NOT NULL,
    details       JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED'))
);
