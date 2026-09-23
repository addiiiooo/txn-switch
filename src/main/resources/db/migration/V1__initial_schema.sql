-- Initial schema for the authorization switch.
--
-- Tables are plural because `authorization` is a reserved word in standard SQL and
-- Postgres will not accept it unquoted; quoting a table name everywhere for the sake of
-- a singular noun is a poor trade.
--
-- Status columns are VARCHAR + CHECK rather than Postgres enums: adding a value to an
-- enum type is a migration that cannot run inside every transaction, and a CHECK gives
-- the same protection with none of that.

CREATE TABLE authorizations (
    id                  UUID         PRIMARY KEY,
    merchant_id         VARCHAR(64)  NOT NULL,
    merchant_reference  VARCHAR(128),
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency            VARCHAR(3)   NOT NULL,
    status              VARCHAR(16)  NOT NULL
        CHECK (status IN ('AUTHORIZED', 'DECLINED', 'CAPTURED', 'VOIDED', 'EXPIRED')),
    -- Card data retained: the BIN and the last four digits, which is the truncation
    -- PCI DSS permits, plus a keyed fingerprint. The middle digits are never stored.
    card_bin            VARCHAR(6)   NOT NULL,
    card_last4          VARCHAR(4)   NOT NULL,
    card_brand          VARCHAR(16)  NOT NULL,
    card_exp_month      SMALLINT     NOT NULL CHECK (card_exp_month BETWEEN 1 AND 12),
    card_exp_year       SMALLINT     NOT NULL,
    card_fingerprint    VARCHAR(64)  NOT NULL,
    acquirer_name       VARCHAR(64),
    acquirer_reference  VARCHAR(64),
    approval_code       VARCHAR(16),
    decline_code        VARCHAR(32),
    decline_message     VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    captured_at         TIMESTAMPTZ,
    voided_at           TIMESTAMPTZ,
    version             BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_authorizations_merchant ON authorizations (merchant_id, created_at DESC);

-- Partial: the expiry sweeper only ever looks at live holds, so terminal rows should not
-- be in the index it scans.
CREATE INDEX idx_authorizations_due_expiry
    ON authorizations (expires_at)
    WHERE status = 'AUTHORIZED';

CREATE TABLE idempotency_records (
    id                    UUID         PRIMARY KEY,
    merchant_id           VARCHAR(64)  NOT NULL,
    idempotency_key       VARCHAR(255) NOT NULL,
    request_fingerprint   VARCHAR(64)  NOT NULL,
    -- Allocated before anything is sent downstream and used as the acquirer's own
    -- idempotency key. This is what makes a retry after a timeout safe, so it must
    -- survive a failed attempt: records are never deleted on failure, only released.
    authorization_id      UUID         NOT NULL,
    state                 VARCHAR(16)  NOT NULL
        CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    lease_expires_at      TIMESTAMPTZ  NOT NULL,
    attempts              INTEGER      NOT NULL DEFAULT 1,
    downstream_attempted  BOOLEAN      NOT NULL DEFAULT FALSE,
    response_status       INTEGER,
    -- TEXT, not JSONB: a replay must return the exact bytes the first caller got, and
    -- JSONB would normalise key order and whitespace on the way back out.
    response_body         TEXT,
    created_at            TIMESTAMPTZ  NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    -- The single arbitration point of the whole service.
    CONSTRAINT uq_idempotency_merchant_key UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_idempotency_lease ON idempotency_records (state, lease_expires_at);
CREATE INDEX idx_idempotency_expiry ON idempotency_records (expires_at);

CREATE TABLE authorization_events (
    id                BIGSERIAL    PRIMARY KEY,
    authorization_id  UUID         NOT NULL REFERENCES authorizations (id),
    from_status       VARCHAR(16),
    to_status         VARCHAR(16)  NOT NULL,
    detail            VARCHAR(255),
    correlation_id    VARCHAR(64),
    occurred_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_authorization_events_authorization
    ON authorization_events (authorization_id, occurred_at);
