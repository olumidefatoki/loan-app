-- Loan origination. An application is pinned to the product version the customer
-- applied against. Submitted applications stay locked until a staff member
-- returns them for correction. Status history and events are append-only.

CREATE SEQUENCE loan_application_reference_seq AS BIGINT START WITH 1;

ALTER TABLE customer_accounts
    ADD CONSTRAINT uq_customer_accounts_id_customer UNIQUE (id, customer_id);

CREATE TABLE loan_applications (
    id                       UUID PRIMARY KEY,
    reference                VARCHAR(14)    NOT NULL,
    customer_id              UUID           NOT NULL REFERENCES customers (id),
    product_id               UUID           NOT NULL REFERENCES loan_products (id),
    product_version_id       UUID           NOT NULL REFERENCES loan_product_versions (id),
    branch_id                UUID           REFERENCES branches (id),
    customer_segment         VARCHAR(16)    NOT NULL,
    status                   VARCHAR(32)    NOT NULL,
    revision_number          INTEGER        NOT NULL DEFAULT 1,
    correction_open          BOOLEAN        NOT NULL DEFAULT FALSE,
    amount                   NUMERIC(19, 2),
    currency_code            CHAR(3)        NOT NULL DEFAULT 'NGN',
    purpose                  VARCHAR(500),
    tenure                   INTEGER,
    tenure_unit              VARCHAR(16)    NOT NULL,
    repayment_frequency      VARCHAR(16),
    disbursement_account_id  UUID,
    channel                  VARCHAR(16)    NOT NULL,
    submitted_at             TIMESTAMPTZ,
    withdrawn_at             TIMESTAMPTZ,
    decision_reason          VARCHAR(500),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by               VARCHAR(128)   NOT NULL,
    updated_by               VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_loan_applications_reference UNIQUE (reference),
    CONSTRAINT fk_loan_applications_disbursement_account
        FOREIGN KEY (disbursement_account_id, customer_id)
        REFERENCES customer_accounts (id, customer_id),
    CONSTRAINT ck_loan_applications_reference CHECK (reference ~ '^LA[0-9]{12}$'),
    CONSTRAINT ck_loan_applications_segment CHECK (customer_segment IN ('INDIVIDUAL', 'SME')),
    CONSTRAINT ck_loan_applications_status CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'KYC_PENDING', 'DOCUMENTS_PENDING', 'UNDER_REVIEW',
        'CREDIT_ASSESSMENT', 'REFERRED', 'COMPLIANCE_HOLD', 'PENDING_APPROVAL',
        'APPROVED', 'REJECTED', 'OFFER_ISSUED', 'OFFER_ACCEPTED',
        'PENDING_DISBURSEMENT', 'DISBURSED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_loan_applications_revision CHECK (revision_number >= 1),
    CONSTRAINT ck_loan_applications_amount CHECK (amount IS NULL OR amount > 0),
    CONSTRAINT ck_loan_applications_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_loan_applications_tenure CHECK (tenure IS NULL OR (tenure >= 1 AND tenure <= 6000)),
    CONSTRAINT ck_loan_applications_tenure_unit CHECK (tenure_unit IN ('DAYS', 'WEEKS', 'MONTHS')),
    CONSTRAINT ck_loan_applications_frequency CHECK (
        repayment_frequency IS NULL OR repayment_frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_loan_applications_channel CHECK (channel IN ('WEB', 'MOBILE')),
    CONSTRAINT ck_loan_applications_correction CHECK (
        correction_open = FALSE OR status = 'DOCUMENTS_PENDING'),
    CONSTRAINT ck_loan_applications_withdrawn CHECK (
        (status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL)
        OR (status <> 'WITHDRAWN' AND withdrawn_at IS NULL))
);

CREATE INDEX idx_loan_applications_customer ON loan_applications (customer_id, status, updated_at DESC);
CREATE INDEX idx_loan_applications_queue ON loan_applications (status, submitted_at DESC);
CREATE INDEX idx_loan_applications_branch ON loan_applications (branch_id, status);
CREATE INDEX idx_loan_applications_product_customer ON loan_applications (customer_id, product_id, status);

CREATE TABLE loan_application_financials (
    application_id              UUID PRIMARY KEY REFERENCES loan_applications (id),
    monthly_income              NUMERIC(19, 2),
    other_income                NUMERIC(19, 2),
    monthly_expenses            NUMERIC(19, 2),
    employment_status           VARCHAR(32),
    employer_name               VARCHAR(255),
    job_title                   VARCHAR(160),
    employment_start_date       DATE,
    annual_turnover             NUMERIC(19, 2),
    monthly_turnover            NUMERIC(19, 2),
    operating_expenses          NUMERIC(19, 2),
    monthly_cash_flow           NUMERIC(19, 2),
    business_liabilities        NUMERIC(19, 2),
    authorised_to_borrow        BOOLEAN,
    authorised_borrower_name    VARCHAR(255),
    authorised_borrowing_limit  NUMERIC(19, 2),
    obligations                 JSONB          NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT ck_loan_application_financials_money CHECK (
        (monthly_income IS NULL OR monthly_income >= 0)
        AND (other_income IS NULL OR other_income >= 0)
        AND (monthly_expenses IS NULL OR monthly_expenses >= 0)
        AND (annual_turnover IS NULL OR annual_turnover >= 0)
        AND (monthly_turnover IS NULL OR monthly_turnover >= 0)
        AND (operating_expenses IS NULL OR operating_expenses >= 0)
        AND (business_liabilities IS NULL OR business_liabilities >= 0)
        AND (authorised_borrowing_limit IS NULL OR authorised_borrowing_limit >= 0)),
    CONSTRAINT ck_loan_application_financials_employment CHECK (
        employment_status IS NULL OR employment_status IN (
            'EMPLOYED', 'SELF_EMPLOYED', 'UNEMPLOYED', 'RETIRED', 'STUDENT')),
    CONSTRAINT ck_loan_application_financials_shape CHECK (
        (annual_turnover IS NULL AND monthly_turnover IS NULL AND operating_expenses IS NULL
            AND monthly_cash_flow IS NULL AND business_liabilities IS NULL
            AND authorised_to_borrow IS NULL AND authorised_borrower_name IS NULL
            AND authorised_borrowing_limit IS NULL)
        OR (monthly_income IS NULL AND other_income IS NULL AND monthly_expenses IS NULL
            AND employment_status IS NULL AND employer_name IS NULL AND job_title IS NULL
            AND employment_start_date IS NULL)),
    CONSTRAINT ck_loan_application_financials_obligations CHECK (jsonb_typeof(obligations) = 'array')
);

CREATE TABLE loan_application_documents (
    id               UUID PRIMARY KEY,
    application_id   UUID          NOT NULL REFERENCES loan_applications (id),
    document_code    VARCHAR(40)   NOT NULL,
    document_name    VARCHAR(160)  NOT NULL,
    file_name        VARCHAR(255)  NOT NULL,
    content_type     VARCHAR(128)  NOT NULL,
    size_bytes       BIGINT        NOT NULL,
    checksum_sha256  VARCHAR(64)   NOT NULL,
    storage_key      VARCHAR(512)  NOT NULL,
    uploaded_by      VARCHAR(128)  NOT NULL,
    uploaded_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    superseded_at    TIMESTAMPTZ,
    CONSTRAINT uq_loan_application_documents_key UNIQUE (storage_key),
    CONSTRAINT ck_loan_application_documents_code CHECK (
        document_code = upper(document_code) AND char_length(document_code) > 0),
    CONSTRAINT ck_loan_application_documents_size CHECK (size_bytes > 0),
    CONSTRAINT ck_loan_application_documents_checksum CHECK (
        checksum_sha256 = lower(checksum_sha256) AND char_length(checksum_sha256) = 64),
    CONSTRAINT ck_loan_application_documents_type CHECK (
        content_type IN ('application/pdf', 'image/jpeg', 'image/png'))
);

CREATE UNIQUE INDEX uq_loan_application_current_document
    ON loan_application_documents (application_id, document_code)
    WHERE superseded_at IS NULL;

CREATE INDEX idx_loan_application_documents_application
    ON loan_application_documents (application_id, uploaded_at DESC);

CREATE TABLE loan_application_guarantors (
    id              UUID PRIMARY KEY,
    application_id  UUID          NOT NULL REFERENCES loan_applications (id),
    full_name       VARCHAR(255)  NOT NULL,
    phone_number    VARCHAR(32)   NOT NULL,
    email           VARCHAR(255),
    relationship    VARCHAR(80)   NOT NULL,
    occupation      VARCHAR(160),
    address_line    VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_application_guarantors_phone CHECK (phone_number ~ '^\+[1-9][0-9]{7,14}$')
);

CREATE INDEX idx_loan_application_guarantors_application
    ON loan_application_guarantors (application_id, created_at);

CREATE TABLE loan_application_collaterals (
    id               UUID PRIMARY KEY,
    application_id   UUID           NOT NULL REFERENCES loan_applications (id),
    collateral_type  VARCHAR(40)    NOT NULL,
    description      VARCHAR(500),
    estimated_value  NUMERIC(19, 2) NOT NULL,
    owner_name       VARCHAR(255)   NOT NULL,
    location         VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_application_collaterals_type CHECK (
        collateral_type = upper(collateral_type) AND char_length(collateral_type) > 0),
    CONSTRAINT ck_loan_application_collaterals_value CHECK (estimated_value > 0)
);

CREATE INDEX idx_loan_application_collaterals_application
    ON loan_application_collaterals (application_id, created_at);

CREATE TABLE loan_application_status_history (
    id              UUID PRIMARY KEY,
    application_id  UUID         NOT NULL REFERENCES loan_applications (id),
    from_status     VARCHAR(32),
    to_status       VARCHAR(32)  NOT NULL,
    reason          VARCHAR(500),
    actor_id        UUID         NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_application_status_history_to CHECK (to_status IN (
        'DRAFT', 'SUBMITTED', 'KYC_PENDING', 'DOCUMENTS_PENDING', 'UNDER_REVIEW',
        'CREDIT_ASSESSMENT', 'REFERRED', 'COMPLIANCE_HOLD', 'PENDING_APPROVAL',
        'APPROVED', 'REJECTED', 'OFFER_ISSUED', 'OFFER_ACCEPTED',
        'PENDING_DISBURSEMENT', 'DISBURSED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_loan_application_status_history_from CHECK (from_status IS NULL OR from_status IN (
        'DRAFT', 'SUBMITTED', 'KYC_PENDING', 'DOCUMENTS_PENDING', 'UNDER_REVIEW',
        'CREDIT_ASSESSMENT', 'REFERRED', 'COMPLIANCE_HOLD', 'PENDING_APPROVAL',
        'APPROVED', 'REJECTED', 'OFFER_ISSUED', 'OFFER_ACCEPTED',
        'PENDING_DISBURSEMENT', 'DISBURSED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED'))
);

CREATE INDEX idx_loan_application_status_history
    ON loan_application_status_history (application_id, occurred_at);

CREATE TABLE loan_application_events (
    id              UUID PRIMARY KEY,
    application_id  UUID         NOT NULL REFERENCES loan_applications (id),
    event_type      VARCHAR(40)  NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    actor_id        UUID         NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_application_events_type CHECK (char_length(event_type) > 0),
    CONSTRAINT ck_loan_application_events_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_loan_application_events
    ON loan_application_events (application_id, occurred_at);

CREATE OR REPLACE FUNCTION loan_application_editable(application uuid)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM loan_applications
        WHERE id = application
          AND (status = 'DRAFT' OR (status = 'DOCUMENTS_PENDING' AND correction_open))
    );
$$;

CREATE OR REPLACE FUNCTION reject_locked_loan_application_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.customer_id IS DISTINCT FROM OLD.customer_id
        OR NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.product_version_id IS DISTINCT FROM OLD.product_version_id
        OR NEW.reference IS DISTINCT FROM OLD.reference
        OR NEW.customer_segment IS DISTINCT FROM OLD.customer_segment
        OR NEW.channel IS DISTINCT FROM OLD.channel
        OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
        OR NEW.branch_id IS DISTINCT FROM OLD.branch_id
        OR NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.tenure_unit IS DISTINCT FROM OLD.tenure_unit
    THEN
        RAISE EXCEPTION 'loan application identity cannot change';
    END IF;

    IF (NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.purpose IS DISTINCT FROM OLD.purpose
        OR NEW.tenure IS DISTINCT FROM OLD.tenure
        OR NEW.repayment_frequency IS DISTINCT FROM OLD.repayment_frequency
        OR NEW.disbursement_account_id IS DISTINCT FROM OLD.disbursement_account_id
        OR NEW.revision_number IS DISTINCT FROM OLD.revision_number)
        AND NOT (OLD.status = 'DRAFT' OR (OLD.status = 'DOCUMENTS_PENDING' AND OLD.correction_open))
    THEN
        RAISE EXCEPTION 'submitted loan application cannot be modified';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_applications_lock_changes
    BEFORE UPDATE ON loan_applications
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_application_change();

CREATE OR REPLACE FUNCTION reject_loan_application_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'loan application history cannot be deleted';
END;
$$;

CREATE TRIGGER trg_loan_applications_no_delete
    BEFORE DELETE ON loan_applications
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_application_delete();

CREATE TRIGGER trg_loan_application_status_history_no_delete
    BEFORE DELETE ON loan_application_status_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_application_delete();

CREATE TRIGGER trg_loan_application_events_no_delete
    BEFORE DELETE ON loan_application_events
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_application_delete();

CREATE OR REPLACE FUNCTION reject_locked_loan_application_child_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    application uuid;
BEGIN
    application := COALESCE(NEW.application_id, OLD.application_id);
    IF NOT loan_application_editable(application) THEN
        RAISE EXCEPTION 'submitted loan application cannot be modified';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_application_financials_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_application_financials
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_application_child_change();

CREATE TRIGGER trg_loan_application_documents_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_application_documents
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_application_child_change();

CREATE TRIGGER trg_loan_application_guarantors_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_application_guarantors
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_application_child_change();

CREATE TRIGGER trg_loan_application_collaterals_lock
    BEFORE INSERT OR UPDATE OR DELETE ON loan_application_collaterals
    FOR EACH ROW
    EXECUTE FUNCTION reject_locked_loan_application_child_change();

CREATE TRIGGER trg_loan_applications_set_updated_at
    BEFORE UPDATE ON loan_applications
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE loan_applications IS 'Customer loan applications. reference is unique. The product version is pinned at creation.';
COMMENT ON COLUMN loan_applications.correction_open IS 'True only while staff have returned the application for a controlled revision.';
COMMENT ON TABLE loan_application_financials IS 'Individual income and employment, or SME turnover and borrowing authority. The two shapes are mutually exclusive.';
COMMENT ON TABLE loan_application_documents IS 'Supporting documents. Replacing a file supersedes the previous row so the upload history remains.';
COMMENT ON TABLE loan_application_guarantors IS 'Guarantors declared on one application.';
COMMENT ON TABLE loan_application_collaterals IS 'Collateral declared on one application.';
COMMENT ON TABLE loan_application_status_history IS 'Append-only status transitions.';
COMMENT ON TABLE loan_application_events IS 'Append-only application activity, including controlled revisions.';
COMMENT ON SEQUENCE loan_application_reference_seq IS 'Suffix for LA references. Combined with the calendar year and padded to eight digits.';
