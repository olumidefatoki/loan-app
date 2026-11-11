-- Configurable loan approval. Limits and stages live in matrix versions and
-- delegations. A recorded decision is not rewritten; a later material change
-- supersedes the approval and leaves the original decision in history.

ALTER TABLE loan_product_versions
    ADD COLUMN automated_approval_allowed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE SEQUENCE loan_approval_reference_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE approval_workflow_transitions (
    from_status VARCHAR(32) NOT NULL,
    to_status   VARCHAR(32) NOT NULL,
    PRIMARY KEY (from_status, to_status)
);

INSERT INTO approval_workflow_transitions (from_status, to_status) VALUES
    ('PENDING', 'APPROVED'),
    ('PENDING', 'REJECTED'),
    ('PENDING', 'RETURNED'),
    ('PENDING', 'INFORMATION_REQUESTED'),
    ('PENDING', 'EXPIRED'),
    ('PENDING', 'SUPERSEDED'),
    ('INFORMATION_REQUESTED', 'PENDING'),
    ('INFORMATION_REQUESTED', 'APPROVED'),
    ('INFORMATION_REQUESTED', 'REJECTED'),
    ('INFORMATION_REQUESTED', 'RETURNED'),
    ('INFORMATION_REQUESTED', 'EXPIRED'),
    ('INFORMATION_REQUESTED', 'SUPERSEDED'),
    ('APPROVED', 'SUPERSEDED');

CREATE TABLE approval_matrices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(40)  NOT NULL,
    name               VARCHAR(160) NOT NULL,
    description        VARCHAR(2000),
    status             VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    current_version_id UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(128) NOT NULL,
    updated_by         VARCHAR(128) NOT NULL,
    CONSTRAINT uq_approval_matrices_code UNIQUE (code),
    CONSTRAINT ck_approval_matrices_code CHECK (code = upper(code) AND char_length(code) >= 2),
    CONSTRAINT ck_approval_matrices_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED'))
);

CREATE TABLE approval_matrix_versions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    matrix_id                UUID           NOT NULL REFERENCES approval_matrices (id),
    version_number           INTEGER        NOT NULL,
    status                   VARCHAR(16)    NOT NULL,
    product_id               UUID           REFERENCES loan_products (id),
    customer_segment         VARCHAR(16),
    branch_id                UUID           REFERENCES branches (id),
    risk_rating              VARCHAR(32),
    collateral_status        VARCHAR(32),
    security_classification  VARCHAR(16),
    exception_type           VARCHAR(40),
    automated_allowed        BOOLEAN        NOT NULL DEFAULT FALSE,
    amount_min               NUMERIC(19, 2) NOT NULL,
    amount_max               NUMERIC(19, 2),
    activated_by             VARCHAR(128),
    activated_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by               VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_approval_matrix_versions_number UNIQUE (matrix_id, version_number),
    CONSTRAINT ck_approval_matrix_versions_status CHECK (status IN ('DRAFT', 'ACTIVE', 'SUPERSEDED')),
    CONSTRAINT ck_approval_matrix_versions_segment CHECK (
        customer_segment IS NULL OR customer_segment IN ('INDIVIDUAL', 'SME')),
    CONSTRAINT ck_approval_matrix_versions_risk CHECK (
        risk_rating IS NULL OR risk_rating IN ('UNASSESSED', 'LOW', 'MEDIUM', 'HIGH', 'UNACCEPTABLE')),
    CONSTRAINT ck_approval_matrix_versions_collateral CHECK (
        collateral_status IS NULL OR collateral_status IN ('NONE', 'PENDING', 'ADEQUATE', 'INADEQUATE')),
    CONSTRAINT ck_approval_matrix_versions_security CHECK (
        security_classification IS NULL OR security_classification IN ('SECURED', 'UNSECURED')),
    CONSTRAINT ck_approval_matrix_versions_exception CHECK (
        exception_type IS NULL OR exception_type = upper(exception_type)),
    CONSTRAINT ck_approval_matrix_versions_amount CHECK (
        amount_min >= 0 AND (amount_max IS NULL OR amount_max >= amount_min)),
    CONSTRAINT ck_approval_matrix_versions_activated CHECK (
        (status = 'DRAFT' AND activated_at IS NULL AND activated_by IS NULL)
        OR (status IN ('ACTIVE', 'SUPERSEDED') AND activated_at IS NOT NULL AND activated_by IS NOT NULL))
);

CREATE TABLE approval_matrix_levels (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id          UUID           NOT NULL REFERENCES approval_matrix_versions (id),
    stage               VARCHAR(40)    NOT NULL,
    sequence_no         INTEGER        NOT NULL,
    role_code           VARCHAR(64)    NOT NULL REFERENCES roles (code),
    min_amount          NUMERIC(19, 2),
    max_amount          NUMERIC(19, 2),
    quorum              INTEGER        NOT NULL,
    expiry_hours        INTEGER        NOT NULL,
    manual_required     BOOLEAN        NOT NULL,
    automated           BOOLEAN        NOT NULL,
    escalate_on_expiry  BOOLEAN        NOT NULL,
    CONSTRAINT uq_approval_matrix_levels_sequence UNIQUE (version_id, sequence_no),
    CONSTRAINT uq_approval_matrix_levels_stage UNIQUE (version_id, stage),
    CONSTRAINT ck_approval_matrix_levels_stage CHECK (stage IN (
        'LOAN_OFFICER', 'CREDIT_ANALYST', 'BRANCH_MANAGER', 'CREDIT_MANAGER',
        'SENIOR_CREDIT_MANAGER', 'CREDIT_COMMITTEE')),
    CONSTRAINT ck_approval_matrix_levels_sequence CHECK (sequence_no >= 1),
    CONSTRAINT ck_approval_matrix_levels_quorum CHECK (
        quorum >= 1 AND (stage <> 'CREDIT_COMMITTEE' OR quorum >= 2)),
    CONSTRAINT ck_approval_matrix_levels_expiry CHECK (expiry_hours >= 0 AND expiry_hours <= 8760),
    CONSTRAINT ck_approval_matrix_levels_mode CHECK (NOT (manual_required AND automated)),
    CONSTRAINT ck_approval_matrix_levels_amount CHECK (
        (min_amount IS NULL OR min_amount >= 0)
        AND (max_amount IS NULL OR min_amount IS NULL OR max_amount >= min_amount))
);

ALTER TABLE approval_matrices
    ADD CONSTRAINT fk_approval_matrices_current_version
    FOREIGN KEY (current_version_id) REFERENCES approval_matrix_versions (id);

CREATE INDEX idx_approval_matrix_versions_matrix ON approval_matrix_versions (matrix_id, version_number);
CREATE INDEX idx_approval_matrix_levels_version ON approval_matrix_levels (version_id, sequence_no);

CREATE TABLE approval_exceptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id     UUID,
    application_id  UUID         NOT NULL REFERENCES loan_applications (id),
    exception_type  VARCHAR(40)  NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    requested_by    UUID         NOT NULL REFERENCES system_users (id),
    decided_by      UUID         REFERENCES system_users (id),
    decided_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_approval_exceptions_type CHECK (exception_type = upper(exception_type) AND char_length(exception_type) >= 2),
    CONSTRAINT ck_approval_exceptions_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_approval_exceptions_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decided_by <> requested_by))
);

CREATE TABLE loan_approvals (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference             VARCHAR(16)    NOT NULL,
    application_id        UUID           NOT NULL REFERENCES loan_applications (id),
    application_revision  INTEGER        NOT NULL,
    credit_assessment_id  UUID           NOT NULL REFERENCES credit_assessments (id),
    credit_result_id      UUID           NOT NULL REFERENCES credit_assessment_results (id),
    matrix_id             UUID           NOT NULL REFERENCES approval_matrices (id),
    matrix_version_id     UUID           NOT NULL REFERENCES approval_matrix_versions (id),
    exception_id          UUID           REFERENCES approval_exceptions (id),
    amount                NUMERIC(19, 2) NOT NULL,
    currency_code         CHAR(3)        NOT NULL DEFAULT 'NGN',
    stage                 VARCHAR(40)    NOT NULL,
    stage_sequence        INTEGER        NOT NULL,
    status                VARCHAR(32)    NOT NULL,
    decision              VARCHAR(32),
    decision_reason       VARCHAR(500),
    decided_by            UUID           REFERENCES system_users (id),
    decided_at            TIMESTAMPTZ,
    expires_at            TIMESTAMPTZ,
    automated             BOOLEAN        NOT NULL DEFAULT FALSE,
    requested_by          UUID           REFERENCES system_users (id),
    correlation_id        VARCHAR(64)    NOT NULL,
    version               INTEGER        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_loan_approvals_reference UNIQUE (reference),
    CONSTRAINT ck_loan_approvals_reference CHECK (reference ~ '^AP[0-9]{12}$'),
    CONSTRAINT ck_loan_approvals_revision CHECK (application_revision >= 1 AND version >= 0 AND stage_sequence >= 1),
    CONSTRAINT ck_loan_approvals_amount CHECK (amount > 0),
    CONSTRAINT ck_loan_approvals_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_loan_approvals_stage CHECK (stage IN (
        'LOAN_OFFICER', 'CREDIT_ANALYST', 'BRANCH_MANAGER', 'CREDIT_MANAGER',
        'SENIOR_CREDIT_MANAGER', 'CREDIT_COMMITTEE')),
    CONSTRAINT ck_loan_approvals_status CHECK (status IN (
        'PENDING', 'INFORMATION_REQUESTED', 'APPROVED', 'REJECTED', 'RETURNED', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT ck_loan_approvals_decision CHECK (
        (status IN ('PENDING', 'INFORMATION_REQUESTED') AND decision IS NULL AND decided_at IS NULL)
        OR status = 'SUPERSEDED'
        OR (status IN ('APPROVED', 'REJECTED', 'RETURNED', 'EXPIRED')
            AND decision IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_loan_approvals_decision_value CHECK (
        decision IS NULL OR decision IN ('APPROVE', 'REJECT', 'RETURN', 'EXPIRE')),
    CONSTRAINT ck_loan_approvals_correlation CHECK (char_length(correlation_id) BETWEEN 1 AND 64)
);

ALTER TABLE approval_exceptions
    ADD CONSTRAINT fk_approval_exceptions_approval
    FOREIGN KEY (approval_id) REFERENCES loan_approvals (id);

CREATE UNIQUE INDEX uq_loan_approvals_open_application
    ON loan_approvals (application_id)
    WHERE status IN ('PENDING', 'INFORMATION_REQUESTED');

CREATE INDEX idx_loan_approvals_application ON loan_approvals (application_id, created_at DESC);
CREATE INDEX idx_loan_approvals_queue ON loan_approvals (status, stage, created_at);

CREATE TABLE loan_approval_assignments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id      UUID           NOT NULL REFERENCES loan_approvals (id),
    stage            VARCHAR(40)    NOT NULL,
    assignee_id      UUID           REFERENCES system_users (id),
    assigned_by      UUID           REFERENCES system_users (id),
    status           VARCHAR(16)    NOT NULL,
    delegated_limit  NUMERIC(19, 2),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_approval_assignments_status CHECK (status IN (
        'ASSIGNED', 'COMPLETED', 'DELEGATED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_loan_approval_assignments_limit CHECK (delegated_limit IS NULL OR delegated_limit >= 0)
);

CREATE INDEX idx_loan_approval_assignments_open
    ON loan_approval_assignments (approval_id, status);
CREATE INDEX idx_loan_approval_assignments_assignee
    ON loan_approval_assignments (assignee_id, status);

CREATE TABLE loan_approval_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_id     UUID           NOT NULL REFERENCES loan_approvals (id),
    actor_id        UUID           REFERENCES system_users (id),
    actor_role      VARCHAR(64)    NOT NULL,
    stage           VARCHAR(40)    NOT NULL,
    decision        VARCHAR(32)    NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    reason          VARCHAR(500),
    correlation_id  VARCHAR(64)    NOT NULL,
    occurred_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_approval_history_decision CHECK (decision IN (
        'OPEN', 'APPROVE', 'REJECT', 'RETURN', 'REQUEST_INFORMATION', 'DELEGATE', 'ESCALATE', 'EXPIRE', 'SUPERSEDE')),
    CONSTRAINT ck_loan_approval_history_role CHECK (actor_role = upper(actor_role) AND char_length(actor_role) >= 2),
    CONSTRAINT ck_loan_approval_history_amount CHECK (amount > 0),
    CONSTRAINT ck_loan_approval_history_correlation CHECK (char_length(correlation_id) BETWEEN 1 AND 64)
);

CREATE INDEX idx_loan_approval_history_approval
    ON loan_approval_history (approval_id, occurred_at, id);

CREATE TABLE approval_delegations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID           NOT NULL REFERENCES system_users (id),
    approval_id   UUID           REFERENCES loan_approvals (id),
    role_code     VARCHAR(64)    NOT NULL REFERENCES roles (code),
    stage         VARCHAR(40)    NOT NULL,
    branch_id     UUID           REFERENCES branches (id),
    max_amount    NUMERIC(19, 2) NOT NULL,
    currency_code CHAR(3)        NOT NULL DEFAULT 'NGN',
    valid_from    TIMESTAMPTZ    NOT NULL,
    valid_until   TIMESTAMPTZ,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    reason        VARCHAR(500)   NOT NULL,
    granted_by    UUID           NOT NULL REFERENCES system_users (id),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_approval_delegations_stage CHECK (stage IN (
        'LOAN_OFFICER', 'CREDIT_ANALYST', 'BRANCH_MANAGER', 'CREDIT_MANAGER',
        'SENIOR_CREDIT_MANAGER', 'CREDIT_COMMITTEE')),
    CONSTRAINT ck_approval_delegations_amount CHECK (max_amount >= 0),
    CONSTRAINT ck_approval_delegations_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_approval_delegations_window CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE INDEX idx_approval_delegations_user
    ON approval_delegations (user_id, stage, active, valid_from);

CREATE OR REPLACE FUNCTION reject_loan_approval_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.version < OLD.version THEN
        RAISE EXCEPTION 'approval version cannot move backwards';
    END IF;
    IF OLD.status IN ('REJECTED', 'RETURNED', 'EXPIRED', 'SUPERSEDED') THEN
        RAISE EXCEPTION 'approval decision is immutable';
    END IF;
    IF OLD.status = 'APPROVED' AND NEW.status <> 'SUPERSEDED' AND NEW.status <> 'APPROVED' THEN
        RAISE EXCEPTION 'approval decision is immutable';
    END IF;
    IF OLD.status IN ('APPROVED', 'REJECTED', 'RETURNED', 'EXPIRED', 'SUPERSEDED') THEN
        IF NEW.decision IS DISTINCT FROM OLD.decision
           OR NEW.decided_by IS DISTINCT FROM OLD.decided_by
           OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
           OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
           OR NEW.amount IS DISTINCT FROM OLD.amount
           OR NEW.application_revision IS DISTINCT FROM OLD.application_revision
           OR NEW.credit_result_id IS DISTINCT FROM OLD.credit_result_id THEN
            RAISE EXCEPTION 'approval decision is immutable';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_approvals_immutable_decision
    BEFORE UPDATE ON loan_approvals
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_approval_rewrite();

CREATE OR REPLACE FUNCTION reject_approval_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'approval history is immutable';
END;
$$;

CREATE TRIGGER trg_loan_approval_history_immutable
    BEFORE UPDATE OR DELETE ON loan_approval_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_approval_history_mutation();

CREATE OR REPLACE FUNCTION reject_approval_exception_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'approval exception decision is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_approval_exceptions_immutable
    BEFORE UPDATE ON approval_exceptions
    FOR EACH ROW
    EXECUTE FUNCTION reject_approval_exception_rewrite();

CREATE TRIGGER trg_approval_matrices_set_updated_at
    BEFORE UPDATE ON approval_matrices
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_loan_approvals_set_updated_at
    BEFORE UPDATE ON loan_approvals
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO permissions (code, description, system_permission) VALUES
    ('APPROVAL_VIEW', 'View loan approval requests, queues, and history', TRUE),
    ('APPROVAL_DELEGATE', 'Delegate an assigned loan approval within the caller authority', TRUE),
    ('APPROVAL_MATRIX_MANAGE', 'Configure approval matrices and delegated approval limits', TRUE);

INSERT INTO roles (code, name, description, requires_mfa, unrestricted_branches, system_role) VALUES
    ('LOAN_OFFICER', 'Loan officer', 'Originates applications and approves within a delegated limit', FALSE, FALSE, TRUE),
    ('CREDIT_ANALYST', 'Credit analyst', 'Reviews credit assessments and approves within a delegated limit', TRUE, FALSE, TRUE),
    ('CREDIT_MANAGER', 'Credit manager', 'Approves loans within a delegated credit limit', TRUE, FALSE, TRUE),
    ('SENIOR_CREDIT_MANAGER', 'Senior credit manager', 'Approves higher-value loans within a delegated limit', TRUE, FALSE, TRUE),
    ('CREDIT_COMMITTEE', 'Credit committee', 'Votes on committee approval stages', TRUE, FALSE, TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('APPROVAL_VIEW', 'APPROVAL_DELEGATE', 'APPROVAL_MATRIX_MANAGE')
WHERE r.code = 'SYSTEM_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'CUSTOMER_VIEW', 'LOAN_APPLICATION_VIEW', 'LOAN_APPLICATION_CREATE',
    'LOAN_APPROVE', 'LOAN_REJECT', 'APPROVAL_VIEW', 'APPROVAL_DELEGATE')
WHERE r.code IN ('LOAN_OFFICER', 'CREDIT_ANALYST', 'CREDIT_MANAGER', 'SENIOR_CREDIT_MANAGER', 'CREDIT_COMMITTEE');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('LOAN_APPROVE', 'LOAN_REJECT', 'APPROVAL_VIEW', 'APPROVAL_DELEGATE')
WHERE r.code = 'BRANCH_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('APPROVAL_VIEW', 'APPROVAL_DELEGATE')
WHERE r.code = 'CREDIT_OFFICER';

COMMENT ON TABLE approval_matrices IS 'Named approval rule. The live rule is the active version.';
COMMENT ON TABLE approval_matrix_versions IS 'Immutable once activated. Match criteria and the amount band are versioned.';
COMMENT ON TABLE approval_matrix_levels IS 'Ordered stages for one matrix version. Authority ceilings are data, not code.';
COMMENT ON TABLE approval_workflow_transitions IS 'Allowed approval status changes. The service rejects a change that is not in this table.';
COMMENT ON TABLE loan_approvals IS 'One open approval per application. version supports optimistic locking.';
COMMENT ON TABLE loan_approval_history IS 'Append-only decision log. Updates and deletes are rejected.';
COMMENT ON TABLE approval_delegations IS 'Standing or single-request authority. A null approval_id is a standing limit.';
COMMENT ON TABLE approval_exceptions IS 'Exception requests. The requester cannot be the officer who decides them.';
COMMENT ON COLUMN loan_product_versions.automated_approval_allowed IS 'Product gate for automated loan approval. Default is manual.';
