-- Credit assessment, versioned scoring, and append-only decision evidence.
-- A published scoring version cannot be rewritten. Re-evaluation adds a result
-- and keeps the model and policy version that produced each decision.
-- A credit score cannot replace a compliance hold or a mandatory policy failure.

CREATE SEQUENCE credit_assessment_reference_seq AS BIGINT START WITH 1;

CREATE TABLE credit_scoring_models (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(40)    NOT NULL,
    name                VARCHAR(160)   NOT NULL,
    segment             VARCHAR(16)    NOT NULL,
    product_id          UUID           REFERENCES loan_products (id),
    status              VARCHAR(16)    NOT NULL,
    current_version_id  UUID,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(128)   NOT NULL,
    updated_by          VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_credit_scoring_models_code UNIQUE (code),
    CONSTRAINT ck_credit_scoring_models_code CHECK (code = upper(code) AND code ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_credit_scoring_models_segment CHECK (segment IN ('INDIVIDUAL', 'SME', 'PRODUCT')),
    CONSTRAINT ck_credit_scoring_models_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_credit_scoring_models_product CHECK (
        (segment = 'PRODUCT' AND product_id IS NOT NULL)
        OR (segment <> 'PRODUCT' AND product_id IS NULL))
);

CREATE TABLE credit_scoring_model_versions (
    id                    UUID PRIMARY KEY,
    model_id              UUID         NOT NULL REFERENCES credit_scoring_models (id),
    version_number        INTEGER      NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    weights               JSONB        NOT NULL,
    risk_categories       JSONB        NOT NULL,
    decision_thresholds   JSONB        NOT NULL,
    policy_version        VARCHAR(40)  NOT NULL,
    change_reason         VARCHAR(500),
    activated_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(128) NOT NULL,
    CONSTRAINT uq_credit_scoring_model_versions UNIQUE (model_id, version_number),
    CONSTRAINT ck_credit_scoring_versions_number CHECK (version_number >= 1),
    CONSTRAINT ck_credit_scoring_versions_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_credit_scoring_versions_policy CHECK (char_length(btrim(policy_version)) > 0),
    CONSTRAINT ck_credit_scoring_versions_weights CHECK (
        jsonb_typeof(weights) = 'object' AND jsonb_typeof(weights -> 'components') = 'array'),
    CONSTRAINT ck_credit_scoring_versions_risk CHECK (jsonb_typeof(risk_categories) = 'array'),
    CONSTRAINT ck_credit_scoring_versions_thresholds CHECK (jsonb_typeof(decision_thresholds) = 'object'),
    CONSTRAINT ck_credit_scoring_versions_activated CHECK (
        (status = 'ACTIVE' AND activated_at IS NOT NULL) OR (status <> 'ACTIVE'))
);

ALTER TABLE credit_scoring_models
    ADD CONSTRAINT fk_credit_scoring_models_current_version
    FOREIGN KEY (current_version_id) REFERENCES credit_scoring_model_versions (id);

CREATE UNIQUE INDEX uq_credit_scoring_one_active_version
    ON credit_scoring_model_versions (model_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_credit_scoring_one_draft_version
    ON credit_scoring_model_versions (model_id)
    WHERE status = 'DRAFT';

CREATE TABLE credit_assessment_rules (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(40)    NOT NULL,
    name                VARCHAR(160)   NOT NULL,
    description         VARCHAR(500)   NOT NULL,
    segment             VARCHAR(16)    NOT NULL,
    product_id          UUID           REFERENCES loan_products (id),
    rule_type           VARCHAR(40)    NOT NULL,
    mandatory           BOOLEAN        NOT NULL,
    exception_allowed   BOOLEAN        NOT NULL,
    threshold           NUMERIC(19, 6),
    failure_outcome     VARCHAR(32)    NOT NULL,
    policy_version      VARCHAR(40)    NOT NULL,
    active              BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(128)   NOT NULL,
    updated_by          VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_credit_assessment_rules_code UNIQUE (code),
    CONSTRAINT ck_credit_assessment_rules_code CHECK (code = upper(code) AND code ~ '^[A-Z0-9_]{2,40}$'),
    CONSTRAINT ck_credit_assessment_rules_segment CHECK (segment IN ('INDIVIDUAL', 'SME', 'ANY')),
    CONSTRAINT ck_credit_assessment_rules_type CHECK (rule_type IN (
        'KYC_VERIFIED', 'CUSTOMER_ACTIVE', 'COMPLIANCE_CLEAR', 'NOT_BLACKLISTED',
        'PRODUCT_ELIGIBLE', 'LOAN_LIMIT', 'DTI_MAX', 'DISPOSABLE_INCOME_MIN',
        'EMPLOYMENT_ACCEPTABLE', 'REPAYMENT_DEFAULT', 'REPAYMENT_DELINQUENT',
        'DSCR_MIN', 'COLLATERAL_COVERAGE_MIN', 'BUREAU_REQUIRED', 'BUREAU_SCORE_MIN',
        'EXPOSURE_LIMIT', 'BUSINESS_AGE_MIN', 'FINANCIAL_STATEMENTS', 'SCORE_THRESHOLD')),
    CONSTRAINT ck_credit_assessment_rules_outcome CHECK (
        failure_outcome IN ('AUTO_REJECT', 'COMPLIANCE_HOLD', 'PENDING_DATA')),
    CONSTRAINT ck_credit_assessment_rules_threshold CHECK (threshold IS NULL OR threshold >= 0),
    CONSTRAINT ck_credit_assessment_rules_policy CHECK (char_length(btrim(policy_version)) > 0),
    CONSTRAINT ck_credit_assessment_rules_exception CHECK (
        exception_allowed = FALSE OR failure_outcome <> 'COMPLIANCE_HOLD')
);

CREATE INDEX idx_credit_assessment_rules_active ON credit_assessment_rules (active, segment, code);

CREATE TABLE credit_assessments (
    id                          UUID PRIMARY KEY,
    reference                   VARCHAR(14)    NOT NULL,
    application_id              UUID           NOT NULL REFERENCES loan_applications (id),
    customer_id                 UUID           NOT NULL REFERENCES customers (id),
    product_id                  UUID           NOT NULL REFERENCES loan_products (id),
    product_version_id          UUID           NOT NULL REFERENCES loan_product_versions (id),
    branch_id                   UUID           REFERENCES branches (id),
    segment                     VARCHAR(16)    NOT NULL,
    status                      VARCHAR(32)    NOT NULL,
    decision                    VARCHAR(32),
    score                       NUMERIC(6, 2),
    risk_category               VARCHAR(32),
    scoring_model_id            UUID           REFERENCES credit_scoring_models (id),
    scoring_model_version_id    UUID           REFERENCES credit_scoring_model_versions (id),
    policy_version              VARCHAR(40),
    inputs                      JSONB          NOT NULL,
    calculations                JSONB,
    analyst_recommendation      VARCHAR(32),
    analyst_justification       VARCHAR(2000),
    recommended_by              UUID           REFERENCES system_users (id),
    recommended_at              TIMESTAMPTZ,
    assigned_to                 UUID           REFERENCES system_users (id),
    revision                    INTEGER        NOT NULL DEFAULT 1,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by                  VARCHAR(128)   NOT NULL,
    updated_by                  VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_credit_assessments_reference UNIQUE (reference),
    CONSTRAINT ck_credit_assessments_reference CHECK (reference ~ '^CA[0-9]{12}$'),
    CONSTRAINT ck_credit_assessments_segment CHECK (segment IN ('INDIVIDUAL', 'SME')),
    CONSTRAINT ck_credit_assessments_status CHECK (status IN (
        'OPEN', 'EVALUATED', 'REFERRED', 'PENDING_INFORMATION', 'COMPLIANCE_HOLD', 'CLOSED')),
    CONSTRAINT ck_credit_assessments_decision CHECK (decision IS NULL OR decision IN (
        'AUTO_APPROVE', 'REFER', 'AUTO_REJECT', 'PENDING_DATA', 'COMPLIANCE_HOLD')),
    CONSTRAINT ck_credit_assessments_score CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    CONSTRAINT ck_credit_assessments_inputs CHECK (jsonb_typeof(inputs) = 'object'),
    CONSTRAINT ck_credit_assessments_calculations CHECK (
        calculations IS NULL OR jsonb_typeof(calculations) = 'object'),
    CONSTRAINT ck_credit_assessments_recommendation CHECK (
        analyst_recommendation IS NULL OR analyst_recommendation IN (
            'APPROVE', 'REJECT', 'REFER', 'REQUEST_INFORMATION')),
    CONSTRAINT ck_credit_assessments_recommendation_actor CHECK (
        (analyst_recommendation IS NULL AND recommended_by IS NULL AND recommended_at IS NULL)
        OR (analyst_recommendation IS NOT NULL AND recommended_by IS NOT NULL AND recommended_at IS NOT NULL
            AND analyst_justification IS NOT NULL)),
    CONSTRAINT ck_credit_assessments_revision CHECK (revision >= 1)
);

CREATE UNIQUE INDEX uq_credit_assessments_open_application
    ON credit_assessments (application_id)
    WHERE status <> 'CLOSED';

CREATE INDEX idx_credit_assessments_queue
    ON credit_assessments (assigned_to, decision, updated_at DESC);

CREATE INDEX idx_credit_assessments_customer
    ON credit_assessments (customer_id, created_at DESC);

CREATE TABLE credit_assessment_results (
    id                  UUID PRIMARY KEY,
    assessment_id       UUID           NOT NULL REFERENCES credit_assessments (id),
    model_id            UUID           NOT NULL REFERENCES credit_scoring_models (id),
    model_version_id    UUID           NOT NULL REFERENCES credit_scoring_model_versions (id),
    policy_version      VARCHAR(40)    NOT NULL,
    score               NUMERIC(6, 2),
    risk_category       VARCHAR(32),
    decision            VARCHAR(32)    NOT NULL,
    explanation         TEXT           NOT NULL,
    calculations        JSONB          NOT NULL,
    evaluated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_credit_results_score CHECK (score IS NULL OR (score >= 0 AND score <= 100)),
    CONSTRAINT ck_credit_results_decision CHECK (decision IN (
        'AUTO_APPROVE', 'REFER', 'AUTO_REJECT', 'PENDING_DATA', 'COMPLIANCE_HOLD')),
    CONSTRAINT ck_credit_results_calculations CHECK (jsonb_typeof(calculations) = 'object')
);

CREATE INDEX idx_credit_results_assessment
    ON credit_assessment_results (assessment_id, evaluated_at DESC);

CREATE TABLE credit_score_components (
    id               UUID PRIMARY KEY,
    assessment_id    UUID           NOT NULL REFERENCES credit_assessments (id),
    result_id        UUID           NOT NULL REFERENCES credit_assessment_results (id),
    component_code   VARCHAR(40)    NOT NULL,
    metric           VARCHAR(40)    NOT NULL,
    raw_value        NUMERIC(19, 6),
    raw_score        NUMERIC(6, 2),
    weight           NUMERIC(8, 4)  NOT NULL,
    weighted_score   NUMERIC(8, 4),
    explanation      VARCHAR(1000)  NOT NULL,
    CONSTRAINT ck_credit_components_code CHECK (component_code = upper(component_code)),
    CONSTRAINT ck_credit_components_weight CHECK (weight > 0 AND weight <= 1),
    CONSTRAINT ck_credit_components_raw_score CHECK (raw_score IS NULL OR (raw_score >= 0 AND raw_score <= 100))
);

CREATE INDEX idx_credit_components_result ON credit_score_components (result_id, component_code);

CREATE TABLE credit_decision_reasons (
    id                UUID PRIMARY KEY,
    assessment_id     UUID          NOT NULL REFERENCES credit_assessments (id),
    result_id         UUID          REFERENCES credit_assessment_results (id),
    code              VARCHAR(40)   NOT NULL,
    severity          VARCHAR(16)   NOT NULL,
    message           VARCHAR(1000) NOT NULL,
    blocks_approval   BOOLEAN       NOT NULL,
    CONSTRAINT ck_credit_reasons_severity CHECK (severity IN ('INFO', 'DATA', 'POLICY', 'MANDATORY', 'COMPLIANCE'))
);

CREATE INDEX idx_credit_reasons_assessment ON credit_decision_reasons (assessment_id, code);

CREATE TABLE credit_bureau_checks (
    id                       UUID PRIMARY KEY,
    assessment_id            UUID           NOT NULL REFERENCES credit_assessments (id),
    customer_id              UUID           NOT NULL REFERENCES customers (id),
    provider_code            VARCHAR(40)    NOT NULL,
    status                   VARCHAR(16)    NOT NULL,
    identification_matched   BOOLEAN,
    bureau_score             INTEGER,
    existing_exposure        NUMERIC(19, 2),
    mapped_response          JSONB          NOT NULL,
    failure_reason           VARCHAR(500),
    requested_by             UUID           NOT NULL REFERENCES system_users (id),
    requested_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    completed_at             TIMESTAMPTZ,
    CONSTRAINT ck_credit_bureau_provider CHECK (provider_code = upper(provider_code) AND char_length(provider_code) > 0),
    CONSTRAINT ck_credit_bureau_status CHECK (status IN ('REQUESTED', 'COMPLETED', 'FAILED', 'UNAVAILABLE')),
    CONSTRAINT ck_credit_bureau_score CHECK (bureau_score IS NULL OR bureau_score BETWEEN 0 AND 900),
    CONSTRAINT ck_credit_bureau_exposure CHECK (existing_exposure IS NULL OR existing_exposure >= 0),
    CONSTRAINT ck_credit_bureau_mapped CHECK (jsonb_typeof(mapped_response) = 'object'),
    CONSTRAINT ck_credit_bureau_failure CHECK (
        (status IN ('FAILED', 'UNAVAILABLE') AND failure_reason IS NOT NULL)
        OR (status NOT IN ('FAILED', 'UNAVAILABLE')))
);

CREATE INDEX idx_credit_bureau_assessment ON credit_bureau_checks (assessment_id, requested_at DESC);
CREATE INDEX idx_credit_bureau_customer ON credit_bureau_checks (customer_id, requested_at DESC);

CREATE TABLE credit_assessment_comments (
    id              UUID PRIMARY KEY,
    assessment_id   UUID          NOT NULL REFERENCES credit_assessments (id),
    author_id       UUID          NOT NULL REFERENCES system_users (id),
    body            VARCHAR(2000) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_credit_comments_body CHECK (char_length(btrim(body)) > 0)
);

CREATE INDEX idx_credit_comments_assessment ON credit_assessment_comments (assessment_id, created_at);

CREATE TABLE credit_policy_exceptions (
    id              UUID PRIMARY KEY,
    assessment_id   UUID          NOT NULL REFERENCES credit_assessments (id),
    rule_code       VARCHAR(40)   NOT NULL,
    justification   VARCHAR(2000) NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    requested_by    UUID          NOT NULL REFERENCES system_users (id),
    requested_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    decided_by      UUID          REFERENCES system_users (id),
    decided_at      TIMESTAMPTZ,
    decision_note   VARCHAR(2000),
    CONSTRAINT ck_credit_exceptions_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_credit_exceptions_rule CHECK (rule_code = upper(rule_code)),
    CONSTRAINT ck_credit_exceptions_justification CHECK (char_length(btrim(justification)) >= 10),
    CONSTRAINT ck_credit_exceptions_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED')
            AND decided_by IS NOT NULL
            AND decided_at IS NOT NULL
            AND decided_by <> requested_by))
);

CREATE UNIQUE INDEX uq_credit_exceptions_pending
    ON credit_policy_exceptions (assessment_id, rule_code)
    WHERE status = 'PENDING';

CREATE INDEX idx_credit_exceptions_assessment ON credit_policy_exceptions (assessment_id, status);

CREATE OR REPLACE FUNCTION reject_locked_scoring_model_version_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND (
        NEW.weights IS DISTINCT FROM OLD.weights
        OR NEW.risk_categories IS DISTINCT FROM OLD.risk_categories
        OR NEW.decision_thresholds IS DISTINCT FROM OLD.decision_thresholds
        OR NEW.policy_version IS DISTINCT FROM OLD.policy_version
        OR NEW.version_number IS DISTINCT FROM OLD.version_number
        OR NEW.model_id IS DISTINCT FROM OLD.model_id
    ) THEN
        RAISE EXCEPTION 'published credit scoring model version cannot change';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_credit_scoring_versions_lock
    BEFORE UPDATE ON credit_scoring_model_versions
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_scoring_model_version_change();

CREATE OR REPLACE FUNCTION reject_scoring_version_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'published credit scoring model version cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_credit_scoring_versions_no_delete
    BEFORE DELETE ON credit_scoring_model_versions
    FOR EACH ROW
    EXECUTE FUNCTION reject_scoring_version_delete();

CREATE OR REPLACE FUNCTION reject_credit_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'credit decision evidence is append-only';
END;
$$;

CREATE TRIGGER trg_credit_results_append_only
    BEFORE UPDATE OR DELETE ON credit_assessment_results
    FOR EACH ROW
    EXECUTE FUNCTION reject_credit_evidence_mutation();

CREATE TRIGGER trg_credit_components_append_only
    BEFORE UPDATE OR DELETE ON credit_score_components
    FOR EACH ROW
    EXECUTE FUNCTION reject_credit_evidence_mutation();

CREATE TRIGGER trg_credit_reasons_append_only
    BEFORE UPDATE OR DELETE ON credit_decision_reasons
    FOR EACH ROW
    EXECUTE FUNCTION reject_credit_evidence_mutation();

CREATE TRIGGER trg_credit_bureau_append_only
    BEFORE UPDATE OR DELETE ON credit_bureau_checks
    FOR EACH ROW
    EXECUTE FUNCTION reject_credit_evidence_mutation();

CREATE TRIGGER trg_credit_comments_append_only
    BEFORE UPDATE OR DELETE ON credit_assessment_comments
    FOR EACH ROW
    EXECUTE FUNCTION reject_credit_evidence_mutation();

CREATE OR REPLACE FUNCTION reject_credit_exception_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'credit policy exceptions cannot be deleted';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a decided credit policy exception cannot change';
    END IF;
    IF NEW.assessment_id IS DISTINCT FROM OLD.assessment_id
        OR NEW.rule_code IS DISTINCT FROM OLD.rule_code
        OR NEW.justification IS DISTINCT FROM OLD.justification
        OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION 'a credit policy exception request cannot change';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_credit_exceptions_immutable_request
    BEFORE UPDATE OR DELETE ON credit_policy_exceptions
    FOR EACH ROW
    EXECUTE FUNCTION reject_credit_exception_rewrite();

CREATE OR REPLACE FUNCTION reject_credit_input_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.inputs IS DISTINCT FROM OLD.inputs
        OR NEW.application_id IS DISTINCT FROM OLD.application_id
        OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
        OR NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.product_version_id IS DISTINCT FROM OLD.product_version_id
        OR NEW.reference IS DISTINCT FROM OLD.reference
        OR NEW.segment IS DISTINCT FROM OLD.segment THEN
        RAISE EXCEPTION 'credit assessment inputs are immutable';
    END IF;
    IF NEW.revision < OLD.revision THEN
        RAISE EXCEPTION 'credit assessment revision cannot move backwards';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_credit_assessments_immutable_inputs
    BEFORE UPDATE ON credit_assessments
    FOR EACH ROW
    EXECUTE FUNCTION reject_credit_input_rewrite();

CREATE TRIGGER trg_credit_scoring_models_set_updated_at
    BEFORE UPDATE ON credit_scoring_models
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_credit_rules_set_updated_at
    BEFORE UPDATE ON credit_assessment_rules
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_credit_assessments_set_updated_at
    BEFORE UPDATE ON credit_assessments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO credit_scoring_models (id, code, name, segment, status, created_by, updated_by)
VALUES
    ('a1111111-1111-4111-8111-111111111111', 'INDIVIDUAL_STANDARD', 'Individual standard scorecard', 'INDIVIDUAL', 'ACTIVE', 'system', 'system'),
    ('a3333333-3333-4333-8333-333333333333', 'SME_STANDARD', 'SME standard scorecard', 'SME', 'ACTIVE', 'system', 'system');

INSERT INTO credit_scoring_model_versions (
    id, model_id, version_number, status, weights, risk_categories, decision_thresholds,
    policy_version, change_reason, activated_at, created_by)
VALUES (
    'a2222222-2222-4222-8222-222222222222',
    'a1111111-1111-4111-8111-111111111111',
    1,
    'ACTIVE',
    '{
      "components": [
        {"code": "DTI", "weight": 0.25, "metric": "DTI", "direction": "LOWER_IS_BETTER", "best": 0.20, "worst": 0.60},
        {"code": "DISPOSABLE_INCOME", "weight": 0.15, "metric": "DISPOSABLE_INCOME_RATIO", "direction": "HIGHER_IS_BETTER", "best": 0.40, "worst": 0.00},
        {"code": "EMPLOYMENT_STABILITY", "weight": 0.15, "metric": "EMPLOYMENT_MONTHS", "direction": "HIGHER_IS_BETTER", "best": 36, "worst": 0},
        {"code": "BANK_TURNOVER", "weight": 0.10, "metric": "TURNOVER_TO_INCOME", "direction": "HIGHER_IS_BETTER", "best": 1.00, "worst": 0.00},
        {"code": "REPAYMENT_HISTORY", "weight": 0.20, "metric": "REPAYMENT_HISTORY", "direction": "CATEGORICAL"},
        {"code": "BUREAU", "weight": 0.15, "metric": "BUREAU_SCORE", "direction": "HIGHER_IS_BETTER", "best": 800, "worst": 300}
      ],
      "categoricalScores": {
        "REPAYMENT_HISTORY": {"CLEAN": 100, "MINOR_ARREARS": 60, "DELINQUENT": 20, "DEFAULT": 0}
      }
    }'::jsonb,
    '[
      {"code": "LOW", "minimumScore": 80},
      {"code": "MEDIUM", "minimumScore": 65},
      {"code": "HIGH", "minimumScore": 50},
      {"code": "VERY_HIGH", "minimumScore": 0}
    ]'::jsonb,
    '{"autoApproveMinimum": 75, "referMinimum": 55}'::jsonb,
    'POL-2026.1',
    'Initial individual scorecard',
    NOW(),
    'system'
),
(
    'a4444444-4444-4444-8444-444444444444',
    'a3333333-3333-4333-8333-333333333333',
    1,
    'ACTIVE',
    '{
      "components": [
        {"code": "DSCR", "weight": 0.30, "metric": "DSCR", "direction": "HIGHER_IS_BETTER", "best": 2.00, "worst": 1.00},
        {"code": "CASH_FLOW", "weight": 0.15, "metric": "CASH_FLOW_MARGIN", "direction": "HIGHER_IS_BETTER", "best": 0.25, "worst": 0.00},
        {"code": "BUSINESS_AGE", "weight": 0.10, "metric": "BUSINESS_AGE_MONTHS", "direction": "HIGHER_IS_BETTER", "best": 60, "worst": 0},
        {"code": "ACCOUNT_TURNOVER", "weight": 0.10, "metric": "ACCOUNT_TURNOVER_TO_REVENUE", "direction": "HIGHER_IS_BETTER", "best": 1.00, "worst": 0.00},
        {"code": "SECTOR", "weight": 0.10, "metric": "SECTOR", "direction": "CATEGORICAL"},
        {"code": "BUREAU_EXPOSURE", "weight": 0.10, "metric": "BUREAU_EXPOSURE_RATIO", "direction": "LOWER_IS_BETTER", "best": 0.00, "worst": 1.00},
        {"code": "REPAYMENT_HISTORY", "weight": 0.15, "metric": "REPAYMENT_HISTORY", "direction": "CATEGORICAL"}
      ],
      "categoricalScores": {
        "REPAYMENT_HISTORY": {"CLEAN": 100, "MINOR_ARREARS": 60, "DELINQUENT": 20, "DEFAULT": 0},
        "SECTOR": {"TRADE": 80, "SERVICES": 75, "MANUFACTURING": 70, "AGRICULTURE": 65, "CONSTRUCTION": 45, "UNKNOWN": 40}
      }
    }'::jsonb,
    '[
      {"code": "LOW", "minimumScore": 80},
      {"code": "MEDIUM", "minimumScore": 65},
      {"code": "HIGH", "minimumScore": 50},
      {"code": "VERY_HIGH", "minimumScore": 0}
    ]'::jsonb,
    '{"autoApproveMinimum": 75, "referMinimum": 55}'::jsonb,
    'POL-2026.1',
    'Initial SME scorecard',
    NOW(),
    'system'
);

UPDATE credit_scoring_models
SET current_version_id = 'a2222222-2222-4222-8222-222222222222'
WHERE id = 'a1111111-1111-4111-8111-111111111111';

UPDATE credit_scoring_models
SET current_version_id = 'a4444444-4444-4444-8444-444444444444'
WHERE id = 'a3333333-3333-4333-8333-333333333333';

INSERT INTO credit_assessment_rules (
    id, code, name, description, segment, rule_type, mandatory, exception_allowed,
    threshold, failure_outcome, policy_version, active, created_by, updated_by)
VALUES
    (gen_random_uuid(), 'KYC_VERIFIED', 'KYC verified', 'The customer KYC status must be verified.', 'ANY', 'KYC_VERIFIED', TRUE, FALSE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'CUSTOMER_ACTIVE', 'Customer active', 'The customer must be active.', 'ANY', 'CUSTOMER_ACTIVE', TRUE, FALSE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'COMPLIANCE_CLEAR', 'Compliance clear', 'A compliance hold blocks every credit outcome.', 'ANY', 'COMPLIANCE_CLEAR', TRUE, FALSE, NULL, 'COMPLIANCE_HOLD', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'NOT_BLACKLISTED', 'Not blacklisted', 'A blacklisted customer stays on compliance hold.', 'ANY', 'NOT_BLACKLISTED', TRUE, FALSE, NULL, 'COMPLIANCE_HOLD', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'PRODUCT_ELIGIBLE', 'Product eligibility', 'The customer segment must match the product.', 'ANY', 'PRODUCT_ELIGIBLE', TRUE, FALSE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'LOAN_LIMIT', 'Loan limit', 'The requested amount must sit inside the product limits.', 'ANY', 'LOAN_LIMIT', TRUE, FALSE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'IND_DTI_MAX', 'Maximum DTI', 'Monthly debt obligations divided by monthly gross income must not exceed 0.40.', 'INDIVIDUAL', 'DTI_MAX', TRUE, TRUE, 0.400000, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'IND_DISPOSABLE_MIN', 'Disposable income', 'Disposable income must not be negative.', 'INDIVIDUAL', 'DISPOSABLE_INCOME_MIN', TRUE, TRUE, 0, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'IND_EMPLOYMENT', 'Employment', 'Unemployed and student applicants fail the employment rule.', 'INDIVIDUAL', 'EMPLOYMENT_ACCEPTABLE', TRUE, TRUE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'REPAYMENT_DEFAULT', 'No default', 'A current default is a mandatory decline.', 'ANY', 'REPAYMENT_DEFAULT', TRUE, FALSE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'REPAYMENT_DELINQUENT', 'Delinquency', 'Delinquent repayment history fails unless an approved exception exists.', 'ANY', 'REPAYMENT_DELINQUENT', TRUE, TRUE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'SME_DSCR_MIN', 'Minimum DSCR', 'Operating cash flow divided by debt service must be at least 1.25.', 'SME', 'DSCR_MIN', TRUE, TRUE, 1.250000, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'SME_BUSINESS_AGE', 'Business age', 'The business must have traded for at least 12 months.', 'SME', 'BUSINESS_AGE_MIN', TRUE, TRUE, 12, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'SME_FINANCIALS', 'Financial statements', 'SME applications need financial statement information.', 'SME', 'FINANCIAL_STATEMENTS', TRUE, TRUE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'COLLATERAL_COVERAGE_MIN', 'Collateral coverage', 'Eligible collateral value divided by secured exposure must be at least 1.00 when collateral is required.', 'ANY', 'COLLATERAL_COVERAGE_MIN', TRUE, TRUE, 1.000000, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'BUREAU_REQUIRED', 'Bureau enquiry', 'A required bureau enquiry must complete before approval.', 'ANY', 'BUREAU_REQUIRED', TRUE, FALSE, NULL, 'PENDING_DATA', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'BUREAU_SCORE_MIN', 'Bureau score', 'A completed bureau score must meet the configured minimum.', 'ANY', 'BUREAU_SCORE_MIN', TRUE, TRUE, 500, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'EXPOSURE_LIMIT', 'Exposure limit', 'Existing bureau exposure plus the new amount must stay within the limit.', 'ANY', 'EXPOSURE_LIMIT', TRUE, TRUE, 5000000, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system'),
    (gen_random_uuid(), 'SCORE_THRESHOLD', 'Score threshold', 'A referred score can be lifted only by an approved exception. Scores below the refer threshold cannot.', 'ANY', 'SCORE_THRESHOLD', FALSE, TRUE, NULL, 'AUTO_REJECT', 'POL-2026.1', TRUE, 'system', 'system');

INSERT INTO permissions (code, description, system_permission) VALUES
    ('CREDIT_ASSESSMENT_VIEW', 'View credit assessments, reports, and the analyst queue', TRUE),
    ('CREDIT_ASSESSMENT_RUN', 'Run credit assessments, bureau checks, comments, and recommendations', TRUE),
    ('CREDIT_POLICY_MANAGE', 'Manage credit scoring models, versions, and assessment rules', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('CREDIT_ASSESSMENT_VIEW', 'CREDIT_ASSESSMENT_RUN', 'CREDIT_POLICY_MANAGE')
WHERE r.code = 'SYSTEM_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('CREDIT_ASSESSMENT_VIEW', 'CREDIT_ASSESSMENT_RUN')
WHERE r.code = 'CREDIT_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'CREDIT_ASSESSMENT_VIEW'
WHERE r.code IN ('AUDITOR', 'BRANCH_MANAGER');

COMMENT ON TABLE credit_assessments IS 'One open assessment per application. Inputs stay fixed. Each evaluation appends a result.';
COMMENT ON TABLE credit_scoring_model_versions IS 'Published weights, risk bands, thresholds, and policy version are immutable.';
COMMENT ON TABLE credit_assessment_results IS 'Append-only decision, score, model version, policy version, and calculation snapshot.';
COMMENT ON TABLE credit_bureau_checks IS 'Provider-neutral bureau enquiry history. Payloads stored here are already mapped.';
COMMENT ON TABLE credit_policy_exceptions IS 'Maker-checker overrides. The requester cannot approve. Compliance holds cannot be waived.';
