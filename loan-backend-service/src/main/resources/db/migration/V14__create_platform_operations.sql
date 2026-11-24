-- Notifications, tamper-evident audit, compliance cases, complaints, and administration.
-- Audit rows are append-only. A hash chain seals each insert. Passwords, tokens, and raw BVN/NIN values do not belong in the payload.
-- A potential sanctions match cannot be stored or updated as cleared unless an investigation note already exists.
-- Sensitive configuration is applied only after a different administrator approves it.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

INSERT INTO permissions (code, description, system_permission) VALUES
    ('NOTIFICATION_VIEW', 'View notification history', TRUE),
    ('NOTIFICATION_MANAGE', 'Manage notification templates and providers', TRUE),
    ('AUDIT_VIEW', 'View the append-only audit trail', TRUE),
    ('COMPLIANCE_VIEW', 'View compliance alerts, cases, and screening queues', TRUE),
    ('COMPLIANCE_MANAGE', 'Assign compliance cases and record investigation notes', TRUE),
    ('COMPLIANCE_DECIDE', 'Clear or reject a compliance case after review', TRUE),
    ('COMPLAINT_VIEW', 'View customer complaints', TRUE),
    ('COMPLAINT_MANAGE', 'Assign, escalate, and resolve customer complaints', TRUE),
    ('OPERATIONS_VIEW', 'View operational health, calendars, and feature flags', TRUE);

INSERT INTO roles (code, name, description, requires_mfa, unrestricted_branches, system_role) VALUES
    ('COMPLIANCE_OFFICER', 'Compliance officer', 'Reviews sanctions, PEP, and AML cases', TRUE, TRUE, TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'NOTIFICATION_VIEW', 'NOTIFICATION_MANAGE', 'AUDIT_VIEW', 'COMPLIANCE_VIEW', 'COMPLIANCE_MANAGE',
    'COMPLIANCE_DECIDE', 'COMPLAINT_VIEW', 'COMPLAINT_MANAGE', 'OPERATIONS_VIEW')
WHERE r.code = 'SYSTEM_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('COMPLIANCE_VIEW', 'COMPLIANCE_MANAGE', 'COMPLIANCE_DECIDE', 'CUSTOMER_VIEW', 'AUDIT_VIEW')
WHERE r.code = 'COMPLIANCE_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('AUDIT_VIEW', 'COMPLIANCE_VIEW', 'COMPLAINT_VIEW', 'NOTIFICATION_VIEW', 'OPERATIONS_VIEW')
WHERE r.code = 'AUDITOR';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('COMPLAINT_VIEW', 'COMPLAINT_MANAGE', 'NOTIFICATION_VIEW')
WHERE r.code IN ('BRANCH_MANAGER', 'OPERATIONS_OFFICER');

ALTER TABLE audit_logs
    ADD COLUMN actor_user_id UUID,
    ADD COLUMN actor_role VARCHAR(64),
    ADD COLUMN module VARCHAR(64),
    ADD COLUMN outcome VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN chain_seq BIGINT,
    ADD COLUMN previous_hash CHAR(64),
    ADD COLUMN record_hash CHAR(64);

ALTER TABLE audit_logs
    ADD CONSTRAINT ck_audit_logs_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    ADD CONSTRAINT ck_audit_logs_hash CHECK (record_hash IS NULL OR record_hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_audit_logs_previous CHECK (previous_hash IS NULL OR previous_hash ~ '^[0-9a-f]{64}$');

CREATE SEQUENCE audit_chain_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE audit_chain_head (
    singleton    SMALLINT PRIMARY KEY CHECK (singleton = 1),
    record_hash  CHAR(64) NOT NULL
);

CREATE OR REPLACE FUNCTION audit_logs_material(
    p_previous text,
    p_chain_seq bigint,
    p_id uuid,
    p_correlation text,
    p_actor text,
    p_actor_user_id uuid,
    p_actor_role text,
    p_action text,
    p_module text,
    p_entity_type text,
    p_entity_id text,
    p_outcome text,
    p_occurred_at timestamptz,
    p_before jsonb,
    p_after jsonb
) RETURNS text
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT coalesce(p_previous, '') || '|' || coalesce(p_chain_seq::text, '') || '|' || coalesce(p_id::text, '') || '|' ||
        coalesce(p_correlation, '') || '|' || coalesce(p_actor, '') || '|' || coalesce(p_actor_user_id::text, '') || '|' ||
        coalesce(p_actor_role, '') || '|' || coalesce(p_action, '') || '|' || coalesce(p_module, '') || '|' ||
        coalesce(p_entity_type, '') || '|' || coalesce(p_entity_id, '') || '|' || coalesce(p_outcome, '') || '|' ||
        coalesce(to_char(p_occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '') || '|' ||
        coalesce(p_before::text, '') || '|' || coalesce(p_after::text, '')
$$;

DO $$
DECLARE
    rec RECORD;
    previous text := repeat('0', 64);
    seq bigint := 0;
    hashed text;
BEGIN
    FOR rec IN SELECT * FROM audit_logs ORDER BY occurred_at, id LOOP
        seq := seq + 1;
        hashed := encode(digest(audit_logs_material(
            previous, seq, rec.id, rec.correlation_id, rec.actor, rec.actor_user_id, rec.actor_role,
            rec.action, rec.module, rec.entity_type, rec.entity_id, rec.outcome, rec.occurred_at,
            rec.before_state, rec.after_state), 'sha256'), 'hex');
        UPDATE audit_logs
        SET chain_seq = seq, previous_hash = previous, record_hash = hashed
        WHERE id = rec.id;
        previous := hashed;
    END LOOP;
    IF seq = 0 THEN
        PERFORM setval('audit_chain_seq', 1, false);
        INSERT INTO audit_chain_head (singleton, record_hash) VALUES (1, repeat('0', 64));
    ELSE
        PERFORM setval('audit_chain_seq', seq, true);
        INSERT INTO audit_chain_head (singleton, record_hash) VALUES (1, previous);
    END IF;
END $$;

ALTER TABLE audit_logs
    ALTER COLUMN chain_seq SET NOT NULL,
    ALTER COLUMN previous_hash SET NOT NULL,
    ALTER COLUMN record_hash SET NOT NULL;

ALTER TABLE audit_logs
    ADD CONSTRAINT uq_audit_logs_chain_seq UNIQUE (chain_seq);

CREATE INDEX idx_audit_logs_module ON audit_logs (module, occurred_at DESC);
CREATE INDEX idx_audit_logs_actor_user ON audit_logs (actor_user_id, occurred_at DESC);

CREATE OR REPLACE FUNCTION audit_logs_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only';
END;
$$;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION audit_logs_reject_mutation();

CREATE OR REPLACE FUNCTION audit_logs_seal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    previous text;
BEGIN
    PERFORM pg_advisory_xact_lock(81414001);
    SELECT record_hash INTO previous FROM audit_chain_head WHERE singleton = 1 FOR UPDATE;
    IF previous IS NULL THEN
        previous := repeat('0', 64);
    END IF;
    NEW.occurred_at := coalesce(NEW.occurred_at, clock_timestamp());
    NEW.outcome := coalesce(NULLIF(btrim(NEW.outcome), ''), 'SUCCESS');
    NEW.chain_seq := nextval('audit_chain_seq');
    NEW.previous_hash := previous;
    NEW.record_hash := encode(digest(audit_logs_material(
        previous, NEW.chain_seq, NEW.id, NEW.correlation_id, NEW.actor, NEW.actor_user_id, NEW.actor_role,
        NEW.action, NEW.module, NEW.entity_type, NEW.entity_id, NEW.outcome, NEW.occurred_at,
        NEW.before_state, NEW.after_state), 'sha256'), 'hex');
    UPDATE audit_chain_head SET record_hash = NEW.record_hash WHERE singleton = 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_logs_seal
    BEFORE INSERT ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION audit_logs_seal();

CREATE OR REPLACE FUNCTION audit_chain_intact()
RETURNS boolean
LANGUAGE plpgsql
AS $$
DECLARE
    rec RECORD;
    previous text := repeat('0', 64);
    expected text;
    rows bigint := 0;
    head text;
BEGIN
    FOR rec IN SELECT * FROM audit_logs ORDER BY chain_seq LOOP
        rows := rows + 1;
        expected := encode(digest(audit_logs_material(
            previous, rec.chain_seq, rec.id, rec.correlation_id, rec.actor, rec.actor_user_id, rec.actor_role,
            rec.action, rec.module, rec.entity_type, rec.entity_id, rec.outcome, rec.occurred_at,
            rec.before_state, rec.after_state), 'sha256'), 'hex');
        IF rec.previous_hash IS DISTINCT FROM previous OR rec.record_hash IS DISTINCT FROM expected THEN
            RETURN FALSE;
        END IF;
        previous := rec.record_hash;
    END LOOP;
    SELECT record_hash INTO head FROM audit_chain_head WHERE singleton = 1;
    IF rows = 0 THEN
        RETURN head = repeat('0', 64);
    END IF;
    RETURN head = previous;
END;
$$;

COMMENT ON TABLE audit_logs IS
    'Append-only, hash-chained audit trail. Do not store passwords, tokens, raw BVN, raw NIN, or other unnecessary sensitive payloads.';

CREATE TABLE notification_templates (
    id           UUID PRIMARY KEY,
    code         VARCHAR(80)  NOT NULL,
    channel      VARCHAR(16)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    locale       VARCHAR(16)  NOT NULL DEFAULT 'en',
    subject      VARCHAR(255),
    body         VARCHAR(4000) NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    version      INTEGER      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by   VARCHAR(128) NOT NULL,
    updated_by   VARCHAR(128) NOT NULL,
    CONSTRAINT uq_notification_templates_version UNIQUE (event_type, channel, locale, version),
    CONSTRAINT ck_notification_templates_channel CHECK (channel IN ('SMS', 'EMAIL', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_notification_templates_event CHECK (event_type IN (
        'CUSTOMER_REGISTRATION', 'OTP', 'KYC_STATUS', 'APPLICATION_SUBMISSION', 'APPLICATION_APPROVAL',
        'APPLICATION_REJECTION', 'ADDITIONAL_INFORMATION_REQUEST', 'LOAN_OFFER', 'LOAN_DISBURSEMENT',
        'UPCOMING_REPAYMENT', 'OVERDUE_REPAYMENT', 'PAYMENT_CONFIRMATION', 'LOAN_SETTLEMENT', 'COMPLAINT_UPDATE')),
    CONSTRAINT ck_notification_templates_version CHECK (version > 0),
    CONSTRAINT ck_notification_templates_body CHECK (char_length(btrim(body)) > 0)
);

CREATE UNIQUE INDEX uq_notification_templates_active
    ON notification_templates (event_type, channel, locale)
    WHERE active = TRUE;

CREATE TRIGGER trg_notification_templates_set_updated_at
    BEFORE UPDATE ON notification_templates
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO notification_templates (id, code, channel, event_type, locale, subject, body, active, version, created_by, updated_by)
SELECT gen_random_uuid(), e.event_type || '_' || c.channel, c.channel, e.event_type, 'en', e.subject, e.body, TRUE, 1, 'SYSTEM', 'SYSTEM'
FROM (VALUES
    ('CUSTOMER_REGISTRATION', 'Registration received', 'Your registration {{reference}} has been received.'),
    ('OTP', 'Verification code', 'A verification code was sent. It expires shortly.'),
    ('KYC_STATUS', 'Identity check update', 'Your identity check is now {{status}}.'),
    ('APPLICATION_SUBMISSION', 'Application submitted', 'Application {{reference}} was submitted.'),
    ('APPLICATION_APPROVAL', 'Application approved', 'Application {{reference}} was approved.'),
    ('APPLICATION_REJECTION', 'Application decision', 'Application {{reference}} was not approved.'),
    ('ADDITIONAL_INFORMATION_REQUEST', 'Information needed', 'Application {{reference}} needs more information.'),
    ('LOAN_OFFER', 'Loan offer', 'Loan offer {{reference}} is ready for review.'),
    ('LOAN_DISBURSEMENT', 'Loan disbursed', 'Loan {{reference}} has been disbursed.'),
    ('UPCOMING_REPAYMENT', 'Upcoming repayment', 'Loan {{reference}} has {{amount}} due on {{dueDate}}.'),
    ('OVERDUE_REPAYMENT', 'Overdue repayment', 'Loan {{reference}} is overdue. Amount {{amount}}. Days past due {{daysPastDue}}.'),
    ('PAYMENT_CONFIRMATION', 'Payment confirmed', 'Payment {{reference}} was confirmed.'),
    ('LOAN_SETTLEMENT', 'Loan settled', 'Loan {{reference}} is settled.'),
    ('COMPLAINT_UPDATE', 'Complaint update', 'Complaint {{reference}} is now {{status}}.')
) AS e(event_type, subject, body)
CROSS JOIN (VALUES ('SMS'), ('EMAIL'), ('PUSH'), ('IN_APP')) AS c(channel);

CREATE TABLE notifications (
    id                   UUID PRIMARY KEY,
    customer_id          UUID REFERENCES customers (id),
    user_id              UUID REFERENCES system_users (id),
    event_type           VARCHAR(64)  NOT NULL,
    channel              VARCHAR(16)  NOT NULL,
    template_code        VARCHAR(80)  NOT NULL,
    subject              VARCHAR(255),
    body                 VARCHAR(4000) NOT NULL,
    payload              JSONB        NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    dedupe_key           VARCHAR(160) NOT NULL,
    attempt_count        INTEGER      NOT NULL DEFAULT 0,
    max_attempts         INTEGER      NOT NULL,
    next_attempt_at      TIMESTAMPTZ,
    provider_code        VARCHAR(64),
    provider_message_id  VARCHAR(128),
    last_error           VARCHAR(500),
    correlation_id       VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at              TIMESTAMPTZ,
    read_at              TIMESTAMPTZ,
    CONSTRAINT uq_notifications_event UNIQUE (dedupe_key, channel),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('SMS', 'EMAIL', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_notifications_event CHECK (event_type IN (
        'CUSTOMER_REGISTRATION', 'OTP', 'KYC_STATUS', 'APPLICATION_SUBMISSION', 'APPLICATION_APPROVAL',
        'APPLICATION_REJECTION', 'ADDITIONAL_INFORMATION_REQUEST', 'LOAN_OFFER', 'LOAN_DISBURSEMENT',
        'UPCOMING_REPAYMENT', 'OVERDUE_REPAYMENT', 'PAYMENT_CONFIRMATION', 'LOAN_SETTLEMENT', 'COMPLAINT_UPDATE')),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'DISPATCHING', 'SENT', 'FAILED', 'DEAD', 'READ', 'SKIPPED')),
    CONSTRAINT ck_notifications_attempts CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts),
    CONSTRAINT ck_notifications_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_notifications_read CHECK (read_at IS NULL OR status IN ('SENT', 'READ'))
);

CREATE INDEX idx_notifications_due ON notifications (status, next_attempt_at);
CREATE INDEX idx_notifications_customer ON notifications (customer_id, created_at DESC);
CREATE INDEX idx_notifications_user ON notifications (user_id, created_at DESC);

CREATE TABLE notification_delivery_logs (
    id                   UUID PRIMARY KEY,
    notification_id      UUID         NOT NULL REFERENCES notifications (id),
    attempt_number       INTEGER      NOT NULL,
    provider_code        VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    provider_message_id  VARCHAR(128),
    error_message        VARCHAR(500),
    attempted_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_notification_delivery_status CHECK (status IN ('ACCEPTED', 'FAILED', 'DEAD', 'SKIPPED')),
    CONSTRAINT ck_notification_delivery_attempt CHECK (attempt_number > 0)
);

CREATE INDEX idx_notification_delivery_notification ON notification_delivery_logs (notification_id, attempted_at);

CREATE OR REPLACE FUNCTION notification_delivery_logs_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'notification_delivery_logs is append-only';
END;
$$;

CREATE TRIGGER trg_notification_delivery_logs_append_only
    BEFORE UPDATE OR DELETE ON notification_delivery_logs
    FOR EACH ROW
    EXECUTE FUNCTION notification_delivery_logs_reject_mutation();

CREATE TABLE notification_preferences (
    id           UUID PRIMARY KEY,
    customer_id  UUID         NOT NULL REFERENCES customers (id),
    channel      VARCHAR(16)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    enabled      BOOLEAN      NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_notification_preferences UNIQUE (customer_id, channel, event_type),
    CONSTRAINT ck_notification_preferences_channel CHECK (channel IN ('SMS', 'EMAIL', 'PUSH', 'IN_APP')),
    CONSTRAINT ck_notification_preferences_event CHECK (event_type IN (
        '*', 'CUSTOMER_REGISTRATION', 'OTP', 'KYC_STATUS', 'APPLICATION_SUBMISSION', 'APPLICATION_APPROVAL',
        'APPLICATION_REJECTION', 'ADDITIONAL_INFORMATION_REQUEST', 'LOAN_OFFER', 'LOAN_DISBURSEMENT',
        'UPCOMING_REPAYMENT', 'OVERDUE_REPAYMENT', 'PAYMENT_CONFIRMATION', 'LOAN_SETTLEMENT', 'COMPLAINT_UPDATE'))
);

COMMENT ON TABLE notifications IS
    'Customer notification outbox and history. Payload stores template fields only. Phone numbers, email addresses, OTP codes, and identity numbers are not stored.';
COMMENT ON TABLE notification_delivery_logs IS
    'Append-only delivery attempts. Destination addresses and message secrets are not stored.';

CREATE SEQUENCE compliance_case_reference_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE compliance_alerts (
    id                    UUID PRIMARY KEY,
    customer_id           UUID         NOT NULL REFERENCES customers (id),
    screening_result_id   UUID         REFERENCES customer_screening_results (id),
    alert_type            VARCHAR(16)  NOT NULL,
    severity              VARCHAR(16)  NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    summary               VARCHAR(500) NOT NULL,
    risk_classification   VARCHAR(16),
    opened_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_compliance_alerts_type CHECK (alert_type IN ('AML', 'SANCTIONS', 'PEP', 'RISK')),
    CONSTRAINT ck_compliance_alerts_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_compliance_alerts_status CHECK (status IN ('OPEN', 'IN_REVIEW', 'ESCALATED', 'CLEARED', 'REJECTED')),
    CONSTRAINT ck_compliance_alerts_risk CHECK (
        risk_classification IS NULL OR risk_classification IN ('UNASSESSED', 'LOW', 'MEDIUM', 'HIGH', 'UNACCEPTABLE'))
);

CREATE INDEX idx_compliance_alerts_queue ON compliance_alerts (status, opened_at DESC);
CREATE INDEX idx_compliance_alerts_customer ON compliance_alerts (customer_id, opened_at DESC);

CREATE TRIGGER trg_compliance_alerts_set_updated_at
    BEFORE UPDATE ON compliance_alerts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE compliance_cases (
    id                        UUID PRIMARY KEY,
    reference                 VARCHAR(32)  NOT NULL,
    customer_id               UUID         NOT NULL REFERENCES customers (id),
    alert_id                  UUID         REFERENCES compliance_alerts (id),
    case_type                 VARCHAR(16)  NOT NULL,
    status                    VARCHAR(16)  NOT NULL,
    risk_classification       VARCHAR(16),
    assignee_id               UUID         REFERENCES system_users (id),
    summary                   VARCHAR(500) NOT NULL,
    decision                  VARCHAR(16),
    decision_reason           VARCHAR(500),
    opened_by                 UUID         REFERENCES system_users (id),
    decided_by                UUID         REFERENCES system_users (id),
    decided_at                TIMESTAMPTZ,
    escalated_at              TIMESTAMPTZ,
    evidence_retained_until   TIMESTAMPTZ,
    opened_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_compliance_cases_reference UNIQUE (reference),
    CONSTRAINT ck_compliance_cases_type CHECK (case_type IN ('AML', 'SANCTIONS', 'PEP', 'RISK')),
    CONSTRAINT ck_compliance_cases_status CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_REVIEW', 'ESCALATED', 'CLEARED', 'REJECTED')),
    CONSTRAINT ck_compliance_cases_risk CHECK (
        risk_classification IS NULL OR risk_classification IN ('UNASSESSED', 'LOW', 'MEDIUM', 'HIGH', 'UNACCEPTABLE')),
    CONSTRAINT ck_compliance_cases_decision CHECK (
        (status IN ('OPEN', 'ASSIGNED', 'IN_REVIEW', 'ESCALATED') AND decision IS NULL AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('CLEARED', 'REJECTED') AND decision = status AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_reason IS NOT NULL AND evidence_retained_until IS NOT NULL)
    ),
    CONSTRAINT ck_compliance_cases_maker CHECK (decided_by IS NULL OR opened_by IS NULL OR decided_by <> opened_by)
);

CREATE INDEX idx_compliance_cases_queue ON compliance_cases (status, opened_at DESC);
CREATE INDEX idx_compliance_cases_assignee ON compliance_cases (assignee_id, status);

CREATE TRIGGER trg_compliance_cases_set_updated_at
    BEFORE UPDATE ON compliance_cases
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE compliance_case_activities (
    id            UUID PRIMARY KEY,
    case_id       UUID         NOT NULL REFERENCES compliance_cases (id),
    activity_type VARCHAR(32)  NOT NULL,
    note          VARCHAR(2000) NOT NULL,
    actor_id      UUID,
    actor_name    VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_compliance_case_activities_type CHECK (activity_type IN ('NOTE', 'ASSIGNMENT', 'ESCALATION', 'DECISION', 'EVIDENCE')),
    CONSTRAINT ck_compliance_case_activities_note CHECK (char_length(btrim(note)) > 0)
);

CREATE INDEX idx_compliance_case_activities_case ON compliance_case_activities (case_id, created_at);

CREATE OR REPLACE FUNCTION compliance_case_activities_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'compliance_case_activities is append-only';
END;
$$;

CREATE TRIGGER trg_compliance_case_activities_append_only
    BEFORE UPDATE OR DELETE ON compliance_case_activities
    FOR EACH ROW
    EXECUTE FUNCTION compliance_case_activities_reject_mutation();

CREATE OR REPLACE FUNCTION compliance_cases_require_review()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.case_type = 'SANCTIONS' AND NEW.status = 'CLEARED' THEN
        IF NOT EXISTS (
            SELECT 1 FROM compliance_case_activities
            WHERE case_id = NEW.id AND activity_type = 'NOTE'
        ) THEN
            RAISE EXCEPTION 'potential sanctions matches cannot be cleared without an investigation note';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_compliance_cases_require_review
    BEFORE INSERT OR UPDATE OF status ON compliance_cases
    FOR EACH ROW
    EXECUTE FUNCTION compliance_cases_require_review();

COMMENT ON TABLE compliance_cases IS
    'Compliance investigations. Sanctions matches stay open until a reviewer records a note and a different officer decides. Screening does not auto-clear them.';
COMMENT ON TABLE compliance_case_activities IS
    'Append-only investigation notes, assignment, escalation, and decision evidence. Retained with the case.';

CREATE SEQUENCE complaint_reference_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE complaints (
    id             UUID PRIMARY KEY,
    reference      VARCHAR(32)  NOT NULL,
    customer_id    UUID         NOT NULL REFERENCES customers (id),
    branch_id      UUID         REFERENCES branches (id),
    category       VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    subject        VARCHAR(200) NOT NULL,
    description    VARCHAR(2000) NOT NULL,
    assignee_id    UUID         REFERENCES system_users (id),
    sla_due_at     TIMESTAMPTZ  NOT NULL,
    escalated_at   TIMESTAMPTZ,
    resolved_at    TIMESTAMPTZ,
    resolution     VARCHAR(2000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_complaints_reference UNIQUE (reference),
    CONSTRAINT ck_complaints_category CHECK (category IN ('SERVICE', 'LOAN', 'REPAYMENT', 'FEE', 'STAFF', 'PRIVACY', 'OTHER')),
    CONSTRAINT ck_complaints_status CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_complaints_subject CHECK (char_length(btrim(subject)) > 0),
    CONSTRAINT ck_complaints_resolution CHECK (
        (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED') AND resolved_at IS NULL)
        OR (status IN ('RESOLVED', 'CLOSED') AND resolved_at IS NOT NULL AND resolution IS NOT NULL)
    )
);

CREATE INDEX idx_complaints_customer ON complaints (customer_id, created_at DESC);
CREATE INDEX idx_complaints_sla ON complaints (status, sla_due_at);

CREATE TRIGGER trg_complaints_set_updated_at
    BEFORE UPDATE ON complaints
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE complaint_activities (
    id             UUID PRIMARY KEY,
    complaint_id   UUID         NOT NULL REFERENCES complaints (id),
    activity_type  VARCHAR(32)  NOT NULL,
    note           VARCHAR(2000) NOT NULL,
    actor_id       UUID,
    actor_name     VARCHAR(128) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_complaint_activities_type CHECK (activity_type IN (
        'COMMENT', 'CUSTOMER_RESPONSE', 'ASSIGNMENT', 'ESCALATION', 'RESOLUTION')),
    CONSTRAINT ck_complaint_activities_note CHECK (char_length(btrim(note)) > 0)
);

CREATE INDEX idx_complaint_activities_complaint ON complaint_activities (complaint_id, created_at);

CREATE OR REPLACE FUNCTION complaint_activities_reject_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'complaint_activities is append-only';
END;
$$;

CREATE TRIGGER trg_complaint_activities_append_only
    BEFORE UPDATE OR DELETE ON complaint_activities
    FOR EACH ROW
    EXECUTE FUNCTION complaint_activities_reject_mutation();

COMMENT ON TABLE complaints IS
    'Customer complaints. SLA due time is fixed when the complaint is opened. Escalation is recorded and does not delete the history.';

CREATE TABLE configuration_change_requests (
    id                  UUID PRIMARY KEY,
    target_type         VARCHAR(32)  NOT NULL,
    target_key          VARCHAR(150) NOT NULL,
    current_value       TEXT,
    proposed_value      TEXT,
    value_type          VARCHAR(32)  NOT NULL,
    description         VARCHAR(500),
    sensitive           BOOLEAN      NOT NULL,
    operation           VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    requested_by        UUID         NOT NULL REFERENCES system_users (id),
    requested_by_name   VARCHAR(128) NOT NULL,
    decided_by          UUID         REFERENCES system_users (id),
    decided_by_name     VARCHAR(128),
    decision_reason     VARCHAR(500),
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    decided_at          TIMESTAMPTZ,
    CONSTRAINT ck_config_change_type CHECK (target_type IN ('SYSTEM_CONFIGURATION', 'FEATURE_FLAG')),
    CONSTRAINT ck_config_change_operation CHECK (operation IN ('UPSERT', 'DELETE')),
    CONSTRAINT ck_config_change_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_config_change_value_type CHECK (value_type IN ('STRING', 'NUMBER', 'BOOLEAN', 'JSON')),
    CONSTRAINT ck_config_change_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND decided_by <> requested_by)
    )
);

CREATE UNIQUE INDEX uq_config_change_pending
    ON configuration_change_requests (target_type, target_key)
    WHERE status = 'PENDING';

CREATE INDEX idx_config_change_status ON configuration_change_requests (status, requested_at DESC);

COMMENT ON TABLE configuration_change_requests IS
    'Maker-checker queue for sensitive configuration and feature flags. The requester cannot approve the change.';

CREATE TABLE business_calendars (
    id            UUID PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    timezone      VARCHAR(64)  NOT NULL,
    weekend_days  VARCHAR(64)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(128),
    updated_by    VARCHAR(128),
    CONSTRAINT uq_business_calendars_code UNIQUE (code),
    CONSTRAINT ck_business_calendars_code CHECK (code = upper(code) AND char_length(btrim(code)) > 0),
    CONSTRAINT ck_business_calendars_zone CHECK (char_length(btrim(timezone)) > 0)
);

CREATE TRIGGER trg_business_calendars_set_updated_at
    BEFORE UPDATE ON business_calendars
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE public_holidays (
    id            UUID PRIMARY KEY,
    calendar_id   UUID         NOT NULL REFERENCES business_calendars (id),
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    VARCHAR(128),
    CONSTRAINT uq_public_holidays_day UNIQUE (calendar_id, holiday_date),
    CONSTRAINT ck_public_holidays_name CHECK (char_length(btrim(name)) > 0)
);

CREATE INDEX idx_public_holidays_calendar ON public_holidays (calendar_id, holiday_date);

INSERT INTO business_calendars (id, code, name, timezone, weekend_days, active, created_by, updated_by)
VALUES (
    '11111111-1111-4111-8111-111111111111',
    'NG-DEFAULT',
    'Nigeria default calendar',
    'Africa/Lagos',
    'SATURDAY,SUNDAY',
    TRUE,
    'SYSTEM',
    'SYSTEM');

CREATE TABLE feature_flags (
    id           UUID PRIMARY KEY,
    flag_key     VARCHAR(100) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    description  VARCHAR(500),
    sensitive    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by   VARCHAR(128),
    CONSTRAINT uq_feature_flags_key UNIQUE (flag_key),
    CONSTRAINT ck_feature_flags_key CHECK (flag_key ~ '^[a-z0-9][a-z0-9._-]{0,98}$')
);

CREATE TRIGGER trg_feature_flags_set_updated_at
    BEFORE UPDATE ON feature_flags
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO system_configurations (config_key, config_value, description, value_type, sensitive) VALUES
    ('notifications.provider.sms', 'mock-sms', 'SMS provider adapter code. Credentials stay in the environment.', 'STRING', TRUE),
    ('notifications.provider.email', 'mock-email', 'Email provider adapter code. Credentials stay in the environment.', 'STRING', TRUE),
    ('notifications.provider.push', 'mock-push', 'Push provider adapter code. Device tokens are not stored on the notification.', 'STRING', TRUE),
    ('notifications.provider.in-app', 'in-app', 'In-app provider adapter code.', 'STRING', FALSE),
    ('integration.core-banking.provider', 'mock', 'Non-secret core banking provider name. URLs and credentials stay in the environment.', 'STRING', TRUE),
    ('complaints.sla-hours', '48', 'Complaint response SLA in hours.', 'NUMBER', FALSE),
    ('compliance.evidence-retention-years', '7', 'Years to retain compliance case evidence after a decision.', 'NUMBER', FALSE);

COMMENT ON TABLE business_calendars IS 'Business calendars used for working-day calculations and holiday management.';
COMMENT ON TABLE public_holidays IS 'Public holidays attached to a business calendar.';
COMMENT ON TABLE feature_flags IS 'Operational feature flags. Sensitive flag changes wait for a second administrator.';
