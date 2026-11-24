-- Collections, delinquency, and loan restructuring.
-- Days past due use the current approved schedule and POSTED repayment allocations only.
-- An instalment due on the as-of date is not past due.
-- PAR denominator: outstanding principal of ACTIVE and OVERDUE loans that still have principal outstanding.
-- PAR numerator: outstanding principal of those loans whose days past due are at least 30, 60, or 90.
-- A loan in PAR 90 is included in PAR 60 and PAR 30. Pending activation, closed, and zero-principal loans are excluded.
-- Restructuring approval keeps the previous schedule and does not clear accrued amounts or snapshot history.

INSERT INTO permissions (code, description, system_permission) VALUES
    ('COLLECTION_VIEW', 'View collection cases, delinquency, and collection reports', TRUE),
    ('COLLECTION_MANAGE', 'Assign and work collection cases and request restructuring', TRUE),
    ('RESTRUCTURING_APPROVE', 'Approve or reject a loan restructuring requested by another officer', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('COLLECTION_VIEW', 'COLLECTION_MANAGE', 'RESTRUCTURING_APPROVE')
WHERE r.code = 'SYSTEM_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('COLLECTION_VIEW', 'COLLECTION_MANAGE')
WHERE r.code IN ('OPERATIONS_OFFICER', 'CREDIT_OFFICER');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('COLLECTION_VIEW', 'COLLECTION_MANAGE', 'RESTRUCTURING_APPROVE')
WHERE r.code = 'BRANCH_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'COLLECTION_VIEW'
WHERE r.code = 'AUDITOR';

CREATE SEQUENCE collection_case_reference_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE delinquency_buckets (
    code        VARCHAR(32) PRIMARY KEY,
    min_days    INTEGER     NOT NULL,
    max_days    INTEGER,
    sort_order  INTEGER     NOT NULL,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_delinquency_buckets_range CHECK (min_days >= 0 AND (max_days IS NULL OR max_days >= min_days))
);

INSERT INTO delinquency_buckets (code, min_days, max_days, sort_order) VALUES
    ('CURRENT', 0, 0, 1),
    ('DPD_1_30', 1, 30, 2),
    ('DPD_31_60', 31, 60, 3),
    ('DPD_61_90', 61, 90, 4),
    ('DPD_ABOVE_90', 91, NULL, 5);

CREATE TABLE delinquency_snapshots (
    id                      UUID PRIMARY KEY,
    loan_account_id         UUID           NOT NULL REFERENCES loan_accounts (id),
    as_of_date              DATE           NOT NULL,
    schedule_id             UUID           NOT NULL REFERENCES loan_repayment_schedules (id),
    days_past_due           INTEGER        NOT NULL,
    bucket                  VARCHAR(32)    NOT NULL,
    overdue_amount          NUMERIC(19, 6) NOT NULL,
    outstanding_principal   NUMERIC(19, 6) NOT NULL,
    overdue_interest        NUMERIC(19, 6) NOT NULL,
    outstanding_interest    NUMERIC(19, 6) NOT NULL,
    outstanding_fees        NUMERIC(19, 6) NOT NULL,
    outstanding_penalties   NUMERIC(19, 6) NOT NULL,
    missed_instalments      INTEGER        NOT NULL,
    collection_exposure     NUMERIC(19, 6) NOT NULL,
    approaching_due         BOOLEAN        NOT NULL,
    partial_payment         BOOLEAN        NOT NULL,
    overdue                 BOOLEAN        NOT NULL,
    delinquent              BOOLEAN        NOT NULL,
    escalation_required     BOOLEAN        NOT NULL,
    portfolio_included      BOOLEAN        NOT NULL,
    calculated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_delinquency_snapshots_day UNIQUE (loan_account_id, as_of_date),
    CONSTRAINT ck_delinquency_snapshots_non_negative CHECK (
        days_past_due >= 0 AND missed_instalments >= 0
        AND overdue_amount >= 0 AND outstanding_principal >= 0 AND overdue_interest >= 0
        AND outstanding_interest >= 0 AND outstanding_fees >= 0 AND outstanding_penalties >= 0
        AND collection_exposure >= 0)
);

CREATE INDEX idx_delinquency_snapshots_date ON delinquency_snapshots (as_of_date, portfolio_included);

COMMENT ON TABLE delinquency_snapshots IS
    'One current classification per loan and as-of date. Recalculation updates that row. Past-due history for a restructuring is copied onto loan_restructuring and the superseded schedule is kept.';

CREATE TABLE collection_cases (
    id                    UUID PRIMARY KEY,
    reference             VARCHAR(14)    NOT NULL,
    loan_account_id       UUID           NOT NULL REFERENCES loan_accounts (id),
    customer_id           UUID           NOT NULL REFERENCES customers (id),
    branch_id             UUID           REFERENCES branches (id),
    status                VARCHAR(32)    NOT NULL,
    bucket                VARCHAR(32)    NOT NULL,
    days_past_due         INTEGER        NOT NULL,
    overdue_amount        NUMERIC(19, 6) NOT NULL,
    collection_exposure   NUMERIC(19, 6) NOT NULL,
    missed_instalments    INTEGER        NOT NULL,
    opened_on             DATE           NOT NULL,
    closed_at             TIMESTAMPTZ,
    closure_outcome       VARCHAR(32),
    closure_note          VARCHAR(500),
    version               INTEGER        NOT NULL,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_collection_cases_reference UNIQUE (reference),
    CONSTRAINT ck_collection_cases_reference CHECK (reference ~ '^CC[0-9]{12}$'),
    CONSTRAINT ck_collection_cases_status CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED', 'CLOSED')),
    CONSTRAINT ck_collection_cases_outcome CHECK (
        closure_outcome IS NULL OR closure_outcome IN ('RECOVERED', 'PARTIAL_RECOVERY', 'RESTRUCTURED', 'UNCOLLECTIBLE')),
    CONSTRAINT ck_collection_cases_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND closure_outcome IS NOT NULL)),
    CONSTRAINT ck_collection_cases_amounts CHECK (
        days_past_due >= 0 AND missed_instalments >= 0 AND version >= 0
        AND overdue_amount >= 0 AND collection_exposure >= 0)
);

CREATE UNIQUE INDEX uq_collection_cases_open
    ON collection_cases (loan_account_id)
    WHERE status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED');

CREATE INDEX idx_collection_cases_branch ON collection_cases (branch_id, status);

CREATE TABLE collection_assignments (
    id            UUID PRIMARY KEY,
    case_id       UUID        NOT NULL REFERENCES collection_cases (id),
    officer_id    UUID        NOT NULL,
    branch_id     UUID        NOT NULL REFERENCES branches (id),
    assigned_by   UUID        NOT NULL,
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active        BOOLEAN     NOT NULL
);

CREATE UNIQUE INDEX uq_collection_assignments_active
    ON collection_assignments (case_id)
    WHERE active = TRUE;

CREATE TABLE collection_activities (
    id             UUID PRIMARY KEY,
    case_id        UUID         NOT NULL REFERENCES collection_cases (id),
    activity_type  VARCHAR(32)  NOT NULL,
    channel        VARCHAR(16)  NOT NULL,
    summary        VARCHAR(500) NOT NULL,
    contacted_at   TIMESTAMPTZ  NOT NULL,
    actor_id       UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_collection_activities_type CHECK (
        activity_type IN ('CONTACT', 'CALL', 'VISIT', 'NOTE', 'PAYMENT_FOLLOW_UP', 'PROMISE', 'ESCALATION', 'CLOSURE')),
    CONSTRAINT ck_collection_activities_channel CHECK (channel IN ('PHONE', 'SMS', 'EMAIL', 'VISIT', 'BRANCH', 'LETTER'))
);

CREATE INDEX idx_collection_activities_case ON collection_activities (case_id, contacted_at);

CREATE TABLE collection_promises (
    id              UUID PRIMARY KEY,
    case_id         UUID           NOT NULL REFERENCES collection_cases (id),
    loan_account_id UUID           NOT NULL REFERENCES loan_accounts (id),
    amount          NUMERIC(19, 6) NOT NULL,
    promise_date    DATE           NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    note            VARCHAR(500),
    created_by      UUID           NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    CONSTRAINT ck_collection_promises_amount CHECK (amount > 0),
    CONSTRAINT ck_collection_promises_status CHECK (status IN ('OPEN', 'KEPT', 'BROKEN', 'CANCELLED')),
    CONSTRAINT ck_collection_promises_resolved CHECK (status = 'OPEN' OR resolved_at IS NOT NULL)
);

CREATE INDEX idx_collection_promises_open ON collection_promises (status, promise_date);

CREATE TABLE collection_escalations (
    id            UUID PRIMARY KEY,
    case_id       UUID         NOT NULL REFERENCES collection_cases (id),
    reason        VARCHAR(500) NOT NULL,
    from_bucket   VARCHAR(32)  NOT NULL,
    level         INTEGER      NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    requested_by  UUID         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_collection_escalations_level CHECK (level BETWEEN 1 AND 3),
    CONSTRAINT ck_collection_escalations_status CHECK (status IN ('OPEN', 'CLOSED'))
);

CREATE UNIQUE INDEX uq_collection_escalations_open
    ON collection_escalations (case_id)
    WHERE status = 'OPEN';

CREATE TABLE loan_restructuring (
    id                           UUID PRIMARY KEY,
    loan_account_id              UUID           NOT NULL REFERENCES loan_accounts (id),
    restructuring_type           VARCHAR(32)    NOT NULL,
    status                       VARCHAR(16)    NOT NULL,
    justification                VARCHAR(500)   NOT NULL,
    tenure                       INTEGER,
    interest_rate                NUMERIC(8, 4),
    repayment_frequency          VARCHAR(16),
    repayment_day_of_month       INTEGER,
    holiday_rule                 VARCHAR(32),
    revised_instalment           NUMERIC(19, 6),
    moratorium_days              INTEGER,
    partial_settlement_amount    NUMERIC(19, 6),
    revised_maturity             DATE,
    value_date                   DATE,
    requested_by                 UUID           NOT NULL,
    requested_by_name            VARCHAR(128)   NOT NULL,
    requested_at                 TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    dpd_at_request               INTEGER        NOT NULL,
    bucket_at_request            VARCHAR(32)    NOT NULL,
    overdue_at_request           NUMERIC(19, 6) NOT NULL,
    dpd_at_decision              INTEGER,
    bucket_at_decision           VARCHAR(32),
    overdue_at_decision          NUMERIC(19, 6),
    previous_schedule_id         UUID,
    new_schedule_id              UUID,
    outstanding_principal_before NUMERIC(19, 6),
    outstanding_interest_before  NUMERIC(19, 6),
    outstanding_principal_after  NUMERIC(19, 6),
    outstanding_interest_after   NUMERIC(19, 6),
    reconciliation_matched       BOOLEAN,
    reconciliation_difference    NUMERIC(19, 6),
    version                      INTEGER        NOT NULL,
    CONSTRAINT ck_loan_restructuring_type CHECK (
        restructuring_type IN (
            'TENOR_EXTENSION', 'FREQUENCY_CHANGE', 'REVISED_AMOUNT', 'MORATORIUM',
            'INTEREST_RESTRUCTURE', 'PARTIAL_SETTLEMENT', 'MATURITY_REVISION')),
    CONSTRAINT ck_loan_restructuring_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_loan_restructuring_justification CHECK (char_length(justification) >= 15),
    CONSTRAINT ck_loan_restructuring_version CHECK (version >= 0),
    CONSTRAINT ck_loan_restructuring_money CHECK (
        (revised_instalment IS NULL OR revised_instalment > 0)
        AND (partial_settlement_amount IS NULL OR partial_settlement_amount > 0)
        AND overdue_at_request >= 0)
);

CREATE UNIQUE INDEX uq_loan_restructuring_pending
    ON loan_restructuring (loan_account_id)
    WHERE status = 'REQUESTED';

CREATE INDEX idx_loan_restructuring_account ON loan_restructuring (loan_account_id, requested_at);

CREATE TABLE restructuring_approvals (
    id                 UUID PRIMARY KEY,
    restructuring_id   UUID         NOT NULL UNIQUE REFERENCES loan_restructuring (id),
    decision           VARCHAR(16)  NOT NULL,
    approver_id        UUID         NOT NULL,
    approver_name      VARCHAR(128) NOT NULL,
    reason             VARCHAR(500),
    decided_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_restructuring_approvals_decision CHECK (decision IN ('APPROVED', 'REJECTED'))
);

CREATE OR REPLACE FUNCTION reject_self_restructuring_approval()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM loan_restructuring
        WHERE id = NEW.restructuring_id
          AND requested_by = NEW.approver_id
    ) THEN
        RAISE EXCEPTION 'The officer who requested a restructuring cannot approve it';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_restructuring_approvals_maker
    BEFORE INSERT ON restructuring_approvals
    FOR EACH ROW
    EXECUTE FUNCTION reject_self_restructuring_approval();

CREATE TABLE restructuring_schedule_versions (
    id                    UUID PRIMARY KEY,
    restructuring_id      UUID    NOT NULL REFERENCES loan_restructuring (id),
    previous_schedule_id  UUID    NOT NULL REFERENCES loan_repayment_schedules (id),
    new_schedule_id       UUID    NOT NULL REFERENCES loan_repayment_schedules (id),
    previous_version      INTEGER NOT NULL,
    new_version           INTEGER NOT NULL,
    preserved             BOOLEAN NOT NULL,
    CONSTRAINT ck_restructuring_schedule_versions_preserved CHECK (preserved = TRUE),
    CONSTRAINT ck_restructuring_schedule_versions_distinct CHECK (previous_schedule_id <> new_schedule_id),
    CONSTRAINT uq_restructuring_schedule_versions UNIQUE (restructuring_id)
);

CREATE TABLE recovery_transactions (
    id               UUID PRIMARY KEY,
    case_id          UUID           NOT NULL REFERENCES collection_cases (id),
    loan_account_id  UUID           NOT NULL REFERENCES loan_accounts (id),
    repayment_id     UUID           REFERENCES loan_repayments (id),
    amount           NUMERIC(19, 6) NOT NULL,
    outcome          VARCHAR(32)    NOT NULL,
    status           VARCHAR(16)    NOT NULL,
    note             VARCHAR(500),
    recorded_by      UUID           NOT NULL,
    recorded_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_recovery_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_recovery_transactions_outcome CHECK (outcome IN ('RECOVERED', 'PARTIAL_RECOVERY')),
    CONSTRAINT ck_recovery_transactions_status CHECK (status = 'CONFIRMED'),
    CONSTRAINT ck_recovery_transactions_repayment CHECK (repayment_id IS NOT NULL)
);

CREATE UNIQUE INDEX uq_recovery_transactions_repayment
    ON recovery_transactions (repayment_id);

CREATE TABLE collection_message_templates (
    code         VARCHAR(64) PRIMARY KEY,
    body         VARCHAR(500) NOT NULL,
    active       BOOLEAN      NOT NULL,
    approved_by  VARCHAR(128) NOT NULL,
    approved_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO collection_message_templates (code, body, active, approved_by) VALUES
    ('UPCOMING_INSTALMENT', 'Instalment {{amount}} on loan {{loanReference}} is due on {{dueDate}}.', TRUE, 'SYSTEM'),
    ('DUE_DATE_REMINDER', 'Loan {{loanReference}} has {{amount}} due on {{dueDate}}.', TRUE, 'SYSTEM'),
    ('MISSED_REPAYMENT', 'Loan {{loanReference}} missed {{amount}} due on {{dueDate}}. {{daysPastDue}} days past due.', TRUE, 'SYSTEM'),
    ('OVERDUE_BALANCE', 'Loan {{loanReference}} has an overdue balance of {{overdueAmount}} and is {{daysPastDue}} days past due.', TRUE, 'SYSTEM'),
    ('PAYMENT_CONFIRMATION', 'Payment of {{amount}} on loan {{loanReference}} was confirmed on {{dueDate}}.', TRUE, 'SYSTEM'),
    ('PAYMENT_FOLLOW_UP', 'Promise of {{promiseAmount}} on loan {{loanReference}} is due on {{promiseDate}}.', TRUE, 'SYSTEM'),
    ('RESTRUCTURING_DECISION', 'Restructuring for loan {{loanReference}} was {{decision}}.', TRUE, 'SYSTEM');

COMMENT ON TABLE collection_message_templates IS
    'Approved collection templates. Messages may use only loanReference, amount, dueDate, daysPastDue, overdueAmount, promiseDate, promiseAmount, and decision. They must not contain phone numbers, email addresses, or identity numbers.';

CREATE TABLE collection_notifications (
    id                  UUID PRIMARY KEY,
    loan_account_id     UUID         NOT NULL REFERENCES loan_accounts (id),
    customer_id         UUID         NOT NULL REFERENCES customers (id),
    template_code       VARCHAR(64)  NOT NULL REFERENCES collection_message_templates (code),
    business_date       DATE         NOT NULL,
    schedule_item_id    UUID,
    repayment_id        UUID,
    restructuring_id    UUID,
    schedule_item_key   UUID         NOT NULL,
    repayment_key       UUID         NOT NULL,
    restructuring_key   UUID         NOT NULL,
    payload             JSONB        NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMPTZ,
    CONSTRAINT uq_collection_notifications_event UNIQUE (
        template_code, business_date, loan_account_id, schedule_item_key, repayment_key, restructuring_key),
    CONSTRAINT ck_collection_notifications_status CHECK (status IN ('PENDING', 'SENT', 'SKIPPED')),
    CONSTRAINT ck_collection_notifications_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_collection_notifications_pending ON collection_notifications (status, created_at);

COMMENT ON TABLE collection_notifications IS
    'Outbox for the notification module. Payload contains template fields only. Contact channels are resolved by that module from the customer id and are not stored here.';

CREATE TABLE collection_report_snapshots (
    report_date          DATE PRIMARY KEY,
    open_cases           INTEGER        NOT NULL,
    closed_cases         INTEGER        NOT NULL,
    escalated_cases      INTEGER        NOT NULL,
    promises_open        INTEGER        NOT NULL,
    promises_kept        INTEGER        NOT NULL,
    promises_broken      INTEGER        NOT NULL,
    recovered_amount     NUMERIC(19, 6) NOT NULL,
    collection_exposure  NUMERIC(19, 6) NOT NULL,
    officers             JSONB          NOT NULL,
    calculated_at        TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_collection_report_snapshots_officers CHECK (jsonb_typeof(officers) = 'array')
);

CREATE TABLE collection_job_runs (
    id             UUID PRIMARY KEY,
    job_name       VARCHAR(64) NOT NULL,
    business_date  DATE        NOT NULL,
    processed      INTEGER     NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_collection_job_runs_processed CHECK (processed >= 0)
);

CREATE INDEX idx_collection_job_runs_day ON collection_job_runs (job_name, business_date);
