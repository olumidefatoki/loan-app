-- Foundation schema for the MFB digital lending platform.
-- Domain tables for later epics (customers, loans, repayments, and so on) are intentionally absent.

CREATE TABLE branches (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(255) NOT NULL,
    address_line  VARCHAR(500),
    city          VARCHAR(120),
    state         VARCHAR(120),
    country_code  CHAR(2)      NOT NULL DEFAULT 'NG',
    phone_number  VARCHAR(32),
    email         VARCHAR(255),
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(128),
    updated_by    VARCHAR(128),
    CONSTRAINT uq_branches_code UNIQUE (code),
    CONSTRAINT ck_branches_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_branches_country CHECK (country_code = UPPER(country_code))
);

CREATE TABLE system_configurations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key   VARCHAR(150) NOT NULL,
    config_value TEXT         NOT NULL,
    description  VARCHAR(500),
    value_type   VARCHAR(32)  NOT NULL DEFAULT 'STRING',
    sensitive    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(128),
    CONSTRAINT uq_system_configurations_key UNIQUE (config_key),
    CONSTRAINT ck_system_configurations_key CHECK (char_length(btrim(config_key)) > 0),
    CONSTRAINT ck_system_configurations_value_type CHECK (value_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'JSON'))
);

CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id  VARCHAR(64),
    actor           VARCHAR(128),
    action          VARCHAR(64)  NOT NULL,
    entity_type     VARCHAR(128) NOT NULL,
    entity_id       VARCHAR(128),
    before_state    JSONB,
    after_state     JSONB,
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_audit_logs_action CHECK (char_length(btrim(action)) > 0),
    CONSTRAINT ck_audit_logs_entity_type CHECK (char_length(btrim(entity_type)) > 0)
);

CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_logs_correlation_id ON audit_logs (correlation_id);

CREATE TABLE integration_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(128) NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    event_type      VARCHAR(128) NOT NULL,
    payload         JSONB        NOT NULL,
    headers         JSONB,
    destination     VARCHAR(255),
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    last_error      VARCHAR(2000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_integration_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_integration_outbox_attempts CHECK (attempt_count >= 0)
);

CREATE INDEX idx_integration_outbox_dispatch ON integration_outbox (status, available_at);

CREATE TABLE integration_inbox (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id    VARCHAR(128) NOT NULL,
    source        VARCHAR(128) NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    payload       JSONB        NOT NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED',
    processed_at  TIMESTAMPTZ,
    last_error    VARCHAR(2000),
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_integration_inbox_message UNIQUE (message_id),
    CONSTRAINT ck_integration_inbox_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'))
);

CREATE INDEX idx_integration_inbox_status ON integration_inbox (status, received_at);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_branches_set_updated_at
    BEFORE UPDATE ON branches
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_system_configurations_set_updated_at
    BEFORE UPDATE ON system_configurations
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO system_configurations (config_key, config_value, description, value_type, sensitive)
VALUES (
    'platform.name',
    'MFB Digital Lending Platform',
    'Human-readable platform name',
    'STRING',
    FALSE
);

COMMENT ON TABLE branches IS 'Bank branches. Reference data owned by the platform foundation.';
COMMENT ON TABLE system_configurations IS 'Non-domain platform settings. Sensitive values must stay in the secret store, not in this table.';
COMMENT ON TABLE audit_logs IS 'Append-only record of security-relevant and business actions.';
COMMENT ON TABLE integration_outbox IS 'Transactional outbox for integration events published after the local transaction commits.';
COMMENT ON TABLE integration_inbox IS 'Idempotent inbox of integration messages already accepted by this service.';
