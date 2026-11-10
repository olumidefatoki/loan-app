-- Configurable loan products. A version is the commercial contract.
-- Terms on a version that has left draft are immutable, so a booked loan
-- keeps the configuration it was opened against.

CREATE TABLE loan_products (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_code        VARCHAR(32)  NOT NULL,
    category            VARCHAR(40)  NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    current_version_id  UUID,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(128) NOT NULL,
    updated_by          VARCHAR(128) NOT NULL,
    CONSTRAINT uq_loan_products_code UNIQUE (product_code),
    CONSTRAINT ck_loan_products_code CHECK (product_code = upper(product_code) AND char_length(product_code) >= 2),
    CONSTRAINT ck_loan_products_category CHECK (category IN (
        'PERSONAL', 'SALARY_ADVANCE', 'EMERGENCY', 'MICROENTERPRISE', 'SME_WORKING_CAPITAL',
        'BUSINESS_EXPANSION', 'INVENTORY_FINANCING', 'ASSET_FINANCING', 'AGRICULTURAL',
        'SECURED', 'UNSECURED')),
    CONSTRAINT ck_loan_products_status CHECK (status IN (
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED'))
);

CREATE TABLE loan_product_versions (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id                 UUID         NOT NULL REFERENCES loan_products (id),
    version_number             INTEGER      NOT NULL,
    status                     VARCHAR(32)  NOT NULL,
    product_name               VARCHAR(160) NOT NULL,
    description                VARCHAR(2000),
    customer_segment           VARCHAR(16)  NOT NULL,
    currency_code              CHAR(3)      NOT NULL DEFAULT 'NGN',
    minimum_amount             NUMERIC(19, 2) NOT NULL,
    maximum_amount             NUMERIC(19, 2) NOT NULL,
    minimum_tenure             INTEGER      NOT NULL,
    maximum_tenure             INTEGER      NOT NULL,
    tenure_unit                VARCHAR(16)  NOT NULL,
    interest_rate              NUMERIC(8, 4) NOT NULL,
    interest_method            VARCHAR(32)  NOT NULL,
    interest_period            VARCHAR(16)  NOT NULL,
    repayment_frequency        VARCHAR(16)  NOT NULL,
    day_count_convention       VARCHAR(16)  NOT NULL,
    rounding_precision         INTEGER      NOT NULL,
    rounding_method            VARCHAR(16)  NOT NULL,
    grace_period_days          INTEGER      NOT NULL,
    early_repayment_rule       VARCHAR(32)  NOT NULL,
    early_repayment_fee_rate   NUMERIC(8, 4),
    guarantor_required         BOOLEAN      NOT NULL,
    minimum_guarantors         INTEGER      NOT NULL,
    guarantor_notes            VARCHAR(500),
    credit_bureau_required     BOOLEAN      NOT NULL,
    minimum_bureau_score       INTEGER,
    penalty_basis              VARCHAR(32)  NOT NULL,
    penalty_rate               NUMERIC(8, 4) NOT NULL,
    penalty_amount             NUMERIC(19, 2) NOT NULL,
    penalty_grace_days         INTEGER      NOT NULL,
    change_reason              VARCHAR(500),
    effective_from             TIMESTAMPTZ,
    effective_to               TIMESTAMPTZ,
    submitted_by               VARCHAR(128),
    submitted_at               TIMESTAMPTZ,
    approved_by                VARCHAR(128),
    approved_at                TIMESTAMPTZ,
    activated_by               VARCHAR(128),
    activated_at               TIMESTAMPTZ,
    suspended_by               VARCHAR(128),
    suspended_at               TIMESTAMPTZ,
    deactivated_by             VARCHAR(128),
    deactivated_at             TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                 VARCHAR(128) NOT NULL,
    updated_by                 VARCHAR(128) NOT NULL,
    CONSTRAINT uq_loan_product_versions_number UNIQUE (product_id, version_number),
    CONSTRAINT ck_loan_product_versions_status CHECK (status IN (
        'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'SUPERSEDED')),
    CONSTRAINT ck_loan_product_versions_segment CHECK (customer_segment IN ('INDIVIDUAL', 'SME', 'BOTH')),
    CONSTRAINT ck_loan_product_versions_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_loan_product_versions_amounts CHECK (
        minimum_amount >= 0 AND maximum_amount > 0 AND maximum_amount >= minimum_amount),
    CONSTRAINT ck_loan_product_versions_tenure CHECK (
        minimum_tenure >= 1 AND maximum_tenure >= minimum_tenure AND maximum_tenure <= 6000),
    CONSTRAINT ck_loan_product_versions_tenure_unit CHECK (tenure_unit IN ('DAYS', 'WEEKS', 'MONTHS')),
    CONSTRAINT ck_loan_product_versions_interest CHECK (interest_rate >= 0 AND interest_rate <= 100),
    CONSTRAINT ck_loan_product_versions_interest_method CHECK (interest_method IN ('FLAT', 'REDUCING_BALANCE')),
    CONSTRAINT ck_loan_product_versions_interest_period CHECK (interest_period IN ('ANNUAL', 'MONTHLY')),
    CONSTRAINT ck_loan_product_versions_frequency CHECK (repayment_frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_loan_product_versions_day_count CHECK (day_count_convention IN ('ACTUAL_365', 'ACTUAL_360', 'THIRTY_360')),
    CONSTRAINT ck_loan_product_versions_rounding CHECK (
        rounding_precision BETWEEN 0 AND 6 AND rounding_method IN ('HALF_UP', 'HALF_EVEN')),
    CONSTRAINT ck_loan_product_versions_grace CHECK (grace_period_days >= 0 AND penalty_grace_days >= 0),
    CONSTRAINT ck_loan_product_versions_early CHECK (
        (early_repayment_rule = 'ALLOWED_WITH_FEE'
            AND early_repayment_fee_rate IS NOT NULL
            AND early_repayment_fee_rate >= 0
            AND early_repayment_fee_rate <= 100)
        OR (early_repayment_rule IN ('ALLOWED', 'NOT_ALLOWED') AND early_repayment_fee_rate IS NULL)),
    CONSTRAINT ck_loan_product_versions_guarantor CHECK (
        minimum_guarantors BETWEEN 0 AND 20
        AND ((guarantor_required = FALSE AND minimum_guarantors = 0)
            OR (guarantor_required = TRUE AND minimum_guarantors >= 1))),
    CONSTRAINT ck_loan_product_versions_bureau CHECK (
        (credit_bureau_required = FALSE AND minimum_bureau_score IS NULL)
        OR (credit_bureau_required = TRUE
            AND (minimum_bureau_score IS NULL OR minimum_bureau_score BETWEEN 0 AND 900))),
    CONSTRAINT ck_loan_product_versions_penalty CHECK (
        (penalty_basis = 'NONE' AND penalty_rate = 0 AND penalty_amount = 0)
        OR (penalty_basis = 'FLAT_AMOUNT' AND penalty_amount > 0 AND penalty_rate = 0)
        OR (penalty_basis IN ('PERCENTAGE_OF_OVERDUE', 'PERCENTAGE_OF_INSTALMENT')
            AND penalty_rate > 0 AND penalty_rate <= 100 AND penalty_amount = 0)),
    CONSTRAINT ck_loan_product_versions_effective CHECK (
        (status NOT IN ('ACTIVE', 'SUSPENDED', 'SUPERSEDED') OR effective_from IS NOT NULL)
        AND (status <> 'SUPERSEDED' OR effective_to IS NOT NULL)
        AND (effective_to IS NULL OR effective_from IS NULL OR effective_to >= effective_from))
);

ALTER TABLE loan_products
    ADD CONSTRAINT fk_loan_products_current_version
    FOREIGN KEY (current_version_id) REFERENCES loan_product_versions (id);

CREATE UNIQUE INDEX uq_loan_product_unpublished
    ON loan_product_versions (product_id)
    WHERE status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED');

CREATE UNIQUE INDEX uq_loan_product_live
    ON loan_product_versions (product_id)
    WHERE status IN ('ACTIVE', 'SUSPENDED');

CREATE INDEX idx_loan_products_status ON loan_products (status, product_code);
CREATE INDEX idx_loan_product_versions_product ON loan_product_versions (product_id, version_number DESC);

CREATE TABLE loan_product_fees (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id   UUID           NOT NULL REFERENCES loan_product_versions (id) ON DELETE CASCADE,
    fee_type     VARCHAR(32)    NOT NULL,
    name         VARCHAR(120)   NOT NULL,
    calculation  VARCHAR(32)    NOT NULL,
    amount       NUMERIC(19, 2),
    rate         NUMERIC(8, 4),
    CONSTRAINT uq_loan_product_fees_name UNIQUE (version_id, name),
    CONSTRAINT ck_loan_product_fees_type CHECK (fee_type IN ('PROCESSING', 'MANAGEMENT')),
    CONSTRAINT ck_loan_product_fees_calculation CHECK (
        (calculation = 'FLAT_AMOUNT' AND amount IS NOT NULL AND amount >= 0 AND rate IS NULL)
        OR (calculation = 'PERCENTAGE' AND rate IS NOT NULL AND rate >= 0 AND rate <= 100 AND amount IS NULL))
);

CREATE TABLE loan_product_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id      UUID         NOT NULL REFERENCES loan_product_versions (id) ON DELETE CASCADE,
    document_code   VARCHAR(40)  NOT NULL,
    document_name   VARCHAR(160) NOT NULL,
    required        BOOLEAN      NOT NULL,
    CONSTRAINT uq_loan_product_documents_code UNIQUE (version_id, document_code),
    CONSTRAINT ck_loan_product_documents_code CHECK (document_code = upper(document_code) AND char_length(document_code) > 0)
);

CREATE TABLE loan_product_eligibility_rules (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id   UUID          NOT NULL REFERENCES loan_product_versions (id) ON DELETE CASCADE,
    rule_code    VARCHAR(40)   NOT NULL,
    description  VARCHAR(1000) NOT NULL,
    CONSTRAINT uq_loan_product_eligibility_code UNIQUE (version_id, rule_code),
    CONSTRAINT ck_loan_product_eligibility_code CHECK (rule_code = upper(rule_code) AND char_length(rule_code) > 0)
);

CREATE TABLE loan_product_collateral_rules (
    id                         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id                 UUID          NOT NULL REFERENCES loan_product_versions (id) ON DELETE CASCADE,
    collateral_type            VARCHAR(40)   NOT NULL,
    description                VARCHAR(500),
    mandatory                  BOOLEAN       NOT NULL,
    minimum_coverage_percent   NUMERIC(5, 2),
    CONSTRAINT uq_loan_product_collateral_type UNIQUE (version_id, collateral_type),
    CONSTRAINT ck_loan_product_collateral_type CHECK (collateral_type = upper(collateral_type) AND char_length(collateral_type) > 0),
    CONSTRAINT ck_loan_product_collateral_coverage CHECK (
        (mandatory = TRUE AND minimum_coverage_percent IS NOT NULL
            AND minimum_coverage_percent > 0 AND minimum_coverage_percent <= 100)
        OR (mandatory = FALSE AND (minimum_coverage_percent IS NULL
            OR (minimum_coverage_percent >= 0 AND minimum_coverage_percent <= 100))))
);

CREATE TABLE loan_product_approval_rules (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id   UUID         NOT NULL REFERENCES loan_product_versions (id) ON DELETE CASCADE,
    step_order   INTEGER      NOT NULL,
    role_code    VARCHAR(64)  NOT NULL,
    description  VARCHAR(500),
    CONSTRAINT uq_loan_product_approval_step UNIQUE (version_id, step_order),
    CONSTRAINT ck_loan_product_approval_step CHECK (step_order BETWEEN 1 AND 20),
    CONSTRAINT ck_loan_product_approval_role CHECK (role_code = upper(role_code) AND char_length(role_code) > 0)
);

-- Pins a booked loan to the product version and a frozen copy of its terms.
-- Product maintenance must not update this table. Later servicing epics extend it.
CREATE TABLE loan_agreements (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id          UUID        NOT NULL REFERENCES loan_products (id),
    product_version_id  UUID        NOT NULL REFERENCES loan_product_versions (id),
    terms_snapshot      JSONB       NOT NULL,
    booked_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_agreements_version ON loan_agreements (product_version_id);

CREATE OR REPLACE FUNCTION reject_locked_loan_product_version_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND (
        NEW.product_name IS DISTINCT FROM OLD.product_name
        OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.customer_segment IS DISTINCT FROM OLD.customer_segment
        OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
        OR NEW.minimum_amount IS DISTINCT FROM OLD.minimum_amount
        OR NEW.maximum_amount IS DISTINCT FROM OLD.maximum_amount
        OR NEW.minimum_tenure IS DISTINCT FROM OLD.minimum_tenure
        OR NEW.maximum_tenure IS DISTINCT FROM OLD.maximum_tenure
        OR NEW.tenure_unit IS DISTINCT FROM OLD.tenure_unit
        OR NEW.interest_rate IS DISTINCT FROM OLD.interest_rate
        OR NEW.interest_method IS DISTINCT FROM OLD.interest_method
        OR NEW.interest_period IS DISTINCT FROM OLD.interest_period
        OR NEW.repayment_frequency IS DISTINCT FROM OLD.repayment_frequency
        OR NEW.day_count_convention IS DISTINCT FROM OLD.day_count_convention
        OR NEW.rounding_precision IS DISTINCT FROM OLD.rounding_precision
        OR NEW.rounding_method IS DISTINCT FROM OLD.rounding_method
        OR NEW.grace_period_days IS DISTINCT FROM OLD.grace_period_days
        OR NEW.early_repayment_rule IS DISTINCT FROM OLD.early_repayment_rule
        OR NEW.early_repayment_fee_rate IS DISTINCT FROM OLD.early_repayment_fee_rate
        OR NEW.guarantor_required IS DISTINCT FROM OLD.guarantor_required
        OR NEW.minimum_guarantors IS DISTINCT FROM OLD.minimum_guarantors
        OR NEW.guarantor_notes IS DISTINCT FROM OLD.guarantor_notes
        OR NEW.credit_bureau_required IS DISTINCT FROM OLD.credit_bureau_required
        OR NEW.minimum_bureau_score IS DISTINCT FROM OLD.minimum_bureau_score
        OR NEW.penalty_basis IS DISTINCT FROM OLD.penalty_basis
        OR NEW.penalty_rate IS DISTINCT FROM OLD.penalty_rate
        OR NEW.penalty_amount IS DISTINCT FROM OLD.penalty_amount
        OR NEW.penalty_grace_days IS DISTINCT FROM OLD.penalty_grace_days
        OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
    ) THEN
        RAISE EXCEPTION 'locked loan product version terms cannot change';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_product_versions_lock_terms
    BEFORE UPDATE ON loan_product_versions
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_product_version_change();

CREATE OR REPLACE FUNCTION reject_loan_product_version_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'loan product version history cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_loan_product_versions_no_delete
    BEFORE DELETE ON loan_product_versions
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_product_version_delete();

CREATE OR REPLACE FUNCTION reject_locked_loan_product_component_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status VARCHAR(32);
BEGIN
    SELECT status INTO parent_status
    FROM loan_product_versions
    WHERE id = COALESCE(NEW.version_id, OLD.version_id);
    IF parent_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'components of a locked loan product version cannot change';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_product_fees_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_product_fees
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_product_component_change();

CREATE TRIGGER trg_loan_product_documents_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_product_documents
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_product_component_change();

CREATE TRIGGER trg_loan_product_eligibility_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_product_eligibility_rules
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_product_component_change();

CREATE TRIGGER trg_loan_product_collateral_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_product_collateral_rules
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_product_component_change();

CREATE TRIGGER trg_loan_product_approval_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_product_approval_rules
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_product_component_change();

CREATE OR REPLACE FUNCTION reject_booked_loan_term_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'booked loan agreement terms cannot be removed';
    END IF;
    IF NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.product_version_id IS DISTINCT FROM OLD.product_version_id
        OR NEW.terms_snapshot IS DISTINCT FROM OLD.terms_snapshot THEN
        RAISE EXCEPTION 'booked loan agreement terms cannot change';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_agreements_lock_terms
    BEFORE UPDATE OR DELETE ON loan_agreements
    FOR EACH ROW
    EXECUTE FUNCTION reject_booked_loan_term_change();

CREATE TRIGGER trg_loan_products_set_updated_at
    BEFORE UPDATE ON loan_products
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_loan_product_versions_set_updated_at
    BEFORE UPDATE ON loan_product_versions
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO permissions (code, description, system_permission) VALUES
    ('LOAN_PRODUCT_VIEW', 'View loan product configuration, including drafts and version history', TRUE),
    ('LOAN_PRODUCT_MANAGE', 'Create and revise draft loan products', TRUE),
    ('LOAN_PRODUCT_APPROVE', 'Approve, activate, suspend, and deactivate loan products', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('LOAN_PRODUCT_VIEW', 'LOAN_PRODUCT_MANAGE', 'LOAN_PRODUCT_APPROVE')
WHERE r.code = 'SYSTEM_ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('LOAN_PRODUCT_VIEW', 'LOAN_PRODUCT_MANAGE')
WHERE r.code = 'BRANCH_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('LOAN_PRODUCT_VIEW', 'LOAN_PRODUCT_APPROVE')
WHERE r.code = 'CREDIT_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'LOAN_PRODUCT_VIEW'
WHERE r.code IN ('AUDITOR', 'RELATIONSHIP_OFFICER');

COMMENT ON TABLE loan_products IS 'Lending product identity. product_code is permanent. Status is the version currently offered.';
COMMENT ON TABLE loan_product_versions IS 'Immutable commercial terms once a version leaves draft. Existing loans keep their version.';
COMMENT ON TABLE loan_product_fees IS 'Processing and management fees for one product version.';
COMMENT ON TABLE loan_product_documents IS 'Documents required to apply for one product version.';
COMMENT ON TABLE loan_product_eligibility_rules IS 'Eligibility conditions for one product version.';
COMMENT ON TABLE loan_product_collateral_rules IS 'Collateral requirements for one product version.';
COMMENT ON TABLE loan_product_approval_rules IS 'Approval steps required to book a loan on one product version.';
COMMENT ON TABLE loan_agreements IS 'Booked loans pinned to a product version and a frozen terms snapshot.';
