-- Core banking integration. These tables record instructions this platform sends
-- and events it accepts. They do not mirror a vendor schema. integration_outbox
-- and integration_inbox already exist in the foundation migration and are reused.

INSERT INTO permissions (code, description, system_permission) VALUES
    ('INTEGRATION_VIEW', 'View core banking integration health and transactions', TRUE),
    ('INTEGRATION_OPERATE', 'Enquire on and reconcile core banking transactions', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('INTEGRATION_VIEW', 'INTEGRATION_OPERATE')
WHERE r.code IN ('SYSTEM_ADMIN', 'OPERATIONS_OFFICER');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'INTEGRATION_VIEW'
WHERE r.code IN ('AUDITOR', 'CREDIT_OFFICER');

CREATE SEQUENCE core_banking_reference_seq AS BIGINT START WITH 1;

CREATE TABLE core_banking_transactions (
    id                      UUID PRIMARY KEY,
    reference               VARCHAR(14)    NOT NULL,
    idempotency_key         VARCHAR(64)    NOT NULL,
    kind                    VARCHAR(32)    NOT NULL,
    state                   VARCHAR(32)    NOT NULL,
    stage                   VARCHAR(32)    NOT NULL,
    application_id          UUID           REFERENCES loan_applications (id),
    customer_id             UUID           REFERENCES customers (id),
    account_id              UUID,
    related_transaction_id  UUID,
    amount                  NUMERIC(19, 2) NOT NULL,
    applied_amount          NUMERIC(19, 2),
    currency_code           CHAR(3)        NOT NULL DEFAULT 'NGN',
    provider_reference      VARCHAR(128),
    loan_account_reference  VARCHAR(128),
    loan_attempt            INTEGER        NOT NULL DEFAULT 0,
    payment_attempt         INTEGER        NOT NULL DEFAULT 0,
    version                 INTEGER        NOT NULL DEFAULT 0,
    correlation_id          VARCHAR(64),
    last_error              VARCHAR(500),
    actor_id                UUID,
    actor_name              VARCHAR(128)   NOT NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    resolved_at             TIMESTAMPTZ,
    CONSTRAINT uq_core_banking_transactions_reference UNIQUE (reference),
    CONSTRAINT uq_core_banking_transactions_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_core_banking_transactions_related
        FOREIGN KEY (related_transaction_id) REFERENCES core_banking_transactions (id),
    CONSTRAINT ck_core_banking_transactions_reference CHECK (reference ~ '^CB[0-9]{12}$'),
    CONSTRAINT ck_core_banking_transactions_kind CHECK (kind IN ('DISBURSEMENT', 'REPAYMENT', 'REVERSAL')),
    CONSTRAINT ck_core_banking_transactions_state CHECK (state IN (
        'INITIATED', 'PENDING', 'SUBMITTED', 'UNKNOWN', 'SUCCESSFUL', 'FAILED', 'REVERSED', 'RECONCILIATION_REQUIRED')),
    CONSTRAINT ck_core_banking_transactions_stage CHECK (stage IN ('NEW', 'LOAN_REGISTERED', 'PAYMENT_SENT')),
    CONSTRAINT ck_core_banking_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_core_banking_transactions_applied CHECK (applied_amount IS NULL OR applied_amount >= 0),
    CONSTRAINT ck_core_banking_transactions_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_core_banking_transactions_attempts CHECK (loan_attempt >= 0 AND payment_attempt >= 0),
    CONSTRAINT ck_core_banking_transactions_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_core_banking_one_disbursement
    ON core_banking_transactions (application_id)
    WHERE kind = 'DISBURSEMENT'
      AND state IN ('INITIATED', 'PENDING', 'SUBMITTED', 'UNKNOWN', 'SUCCESSFUL', 'RECONCILIATION_REQUIRED');

CREATE INDEX idx_core_banking_transactions_state ON core_banking_transactions (state, created_at DESC);
CREATE INDEX idx_core_banking_transactions_application ON core_banking_transactions (application_id, kind, state);

CREATE TABLE core_banking_transaction_events (
    id              UUID PRIMARY KEY,
    transaction_id  UUID         NOT NULL REFERENCES core_banking_transactions (id),
    from_state      VARCHAR(32),
    to_state        VARCHAR(32)  NOT NULL,
    reason          VARCHAR(500),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_core_banking_transaction_events_to CHECK (to_state IN (
        'INITIATED', 'PENDING', 'SUBMITTED', 'UNKNOWN', 'SUCCESSFUL', 'FAILED', 'REVERSED', 'RECONCILIATION_REQUIRED'))
);

CREATE INDEX idx_core_banking_transaction_events ON core_banking_transaction_events (transaction_id, occurred_at);

CREATE TABLE core_banking_requests (
    id               UUID PRIMARY KEY,
    transaction_id   UUID REFERENCES core_banking_transactions (id),
    operation        VARCHAR(64)  NOT NULL,
    idempotency_key  VARCHAR(64),
    correlation_id   VARCHAR(64),
    payload          JSONB        NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_core_banking_requests_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_core_banking_requests_transaction ON core_banking_requests (transaction_id, created_at);

CREATE TABLE core_banking_responses (
    id                  UUID PRIMARY KEY,
    request_id          UUID         NOT NULL REFERENCES core_banking_requests (id),
    outcome             VARCHAR(32)  NOT NULL,
    provider_reference  VARCHAR(128),
    payload             JSONB,
    latency_ms          INTEGER,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_core_banking_responses_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

CREATE INDEX idx_core_banking_responses_request ON core_banking_responses (request_id);

CREATE TABLE core_banking_webhook_events (
    id               UUID PRIMARY KEY,
    message_id       VARCHAR(128) NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_core_banking_webhook_message UNIQUE (message_id),
    CONSTRAINT ck_core_banking_webhook_status CHECK (status IN ('ACCEPTED', 'DUPLICATE')),
    CONSTRAINT ck_core_banking_webhook_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_core_banking_webhook_received ON core_banking_webhook_events (received_at DESC);

CREATE TABLE reconciliation_batches (
    id              UUID PRIMARY KEY,
    source          VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    mismatch_count  INTEGER     NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT ck_reconciliation_batches_status CHECK (status IN ('OPEN', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_reconciliation_batches_mismatches CHECK (mismatch_count >= 0)
);

CREATE TABLE reconciliation_items (
    id                 UUID PRIMARY KEY,
    batch_id           UUID           NOT NULL REFERENCES reconciliation_batches (id),
    transaction_id     UUID           REFERENCES core_banking_transactions (id),
    external_reference VARCHAR(128),
    expected_amount    NUMERIC(19, 2),
    actual_amount      NUMERIC(19, 2),
    status             VARCHAR(32)    NOT NULL,
    detail             VARCHAR(500),
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_reconciliation_items_status CHECK (status IN ('MATCHED', 'MISMATCH', 'MISSING'))
);

CREATE INDEX idx_reconciliation_items_batch ON reconciliation_items (batch_id);
CREATE INDEX idx_reconciliation_items_transaction ON reconciliation_items (transaction_id);

CREATE TABLE integration_errors (
    id           UUID PRIMARY KEY,
    source       VARCHAR(64)  NOT NULL,
    operation    VARCHAR(64)  NOT NULL,
    reference    VARCHAR(128),
    category     VARCHAR(64)  NOT NULL,
    message      VARCHAR(2000) NOT NULL,
    dead_letter  BOOLEAN      NOT NULL DEFAULT FALSE,
    payload      JSONB,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMPTZ,
    CONSTRAINT ck_integration_errors_payload CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_integration_errors_dead_letter ON integration_errors (dead_letter, resolved_at, created_at);

CREATE TRIGGER trg_core_banking_transactions_set_updated_at
    BEFORE UPDATE ON core_banking_transactions
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE core_banking_transactions IS 'Local financial instructions. A timeout leaves the row UNKNOWN until enquiry resolves it.';
COMMENT ON TABLE core_banking_requests IS 'Outbound adapter calls. Payloads use this platform''s neutral contract, not a vendor schema.';
COMMENT ON TABLE core_banking_responses IS 'Adapter outcomes recorded for the matching request.';
COMMENT ON TABLE core_banking_webhook_events IS 'Accepted core banking notifications after signature and replay checks.';
COMMENT ON TABLE reconciliation_batches IS 'A comparison of local financial rows with adapter posting information.';
COMMENT ON TABLE reconciliation_items IS 'One compared transaction. A mismatch does not invent a corrective posting.';
COMMENT ON TABLE integration_errors IS 'Integration failures. Dead-letter rows are not replayed until enquiry establishes the posting.';
