-- Collateral register and guarantor acceptance.
-- Eligible value used for pledging comes only from an approved valuation.
-- Identity numbers are stored as AES-GCM ciphertext and HMAC-SHA256 hashes.

CREATE SEQUENCE collateral_reference_seq AS BIGINT START WITH 1;
CREATE SEQUENCE guarantor_reference_seq AS BIGINT START WITH 1;

CREATE TABLE collaterals (
    id                        UUID PRIMARY KEY,
    reference                 VARCHAR(14)    NOT NULL,
    customer_id               UUID           NOT NULL REFERENCES customers (id),
    collateral_type           VARCHAR(40)    NOT NULL,
    description               VARCHAR(1000)  NOT NULL,
    owner_name                VARCHAR(255)   NOT NULL,
    owner_identifier          VARCHAR(128),
    identification_number     VARCHAR(128),
    location                  VARCHAR(500),
    market_value              NUMERIC(19, 2),
    forced_sale_value         NUMERIC(19, 2),
    eligible_lending_value    NUMERIC(19, 2),
    currency_code             CHAR(3)        NOT NULL DEFAULT 'NGN',
    valuation_date            DATE,
    valuation_expires_on      DATE,
    valuer_name               VARCHAR(255),
    valuer_reference          VARCHAR(128),
    lending_ratio             NUMERIC(9, 6),
    verification_status       VARCHAR(32)    NOT NULL DEFAULT 'PENDING',
    ownership_status          VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    document_status           VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    legal_status              VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    insurance_status          VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    encumbrance_status        VARCHAR(16)    NOT NULL DEFAULT 'UNKNOWN',
    approved_by               UUID,
    approved_at               TIMESTAMPTZ,
    rejected_by               UUID,
    rejected_at               TIMESTAMPTZ,
    rejection_reason          VARCHAR(500),
    released_at               TIMESTAMPTZ,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by                VARCHAR(128)   NOT NULL,
    updated_by                VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_collaterals_reference UNIQUE (reference),
    CONSTRAINT ck_collaterals_reference CHECK (reference ~ '^CL[0-9]{12}$'),
    CONSTRAINT ck_collaterals_type CHECK (collateral_type IN (
        'LAND', 'RESIDENTIAL_PROPERTY', 'COMMERCIAL_PROPERTY', 'VEHICLE', 'EQUIPMENT',
        'MACHINERY', 'INVENTORY', 'FIXED_DEPOSIT', 'RECEIVABLES', 'OTHER')),
    CONSTRAINT ck_collaterals_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_collaterals_text CHECK (
        char_length(btrim(description)) > 0 AND char_length(btrim(owner_name)) > 0),
    CONSTRAINT ck_collaterals_values CHECK (
        (market_value IS NULL OR market_value > 0)
        AND (forced_sale_value IS NULL OR forced_sale_value > 0)
        AND (eligible_lending_value IS NULL OR eligible_lending_value > 0)
        AND (market_value IS NULL OR forced_sale_value IS NULL OR forced_sale_value <= market_value)
        AND (eligible_lending_value IS NULL OR forced_sale_value IS NULL OR eligible_lending_value <= forced_sale_value)),
    CONSTRAINT ck_collaterals_ratio CHECK (lending_ratio IS NULL OR (lending_ratio > 0 AND lending_ratio <= 1)),
    CONSTRAINT ck_collaterals_verification CHECK (verification_status IN (
        'PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'RELEASED')),
    CONSTRAINT ck_collaterals_checks CHECK (
        ownership_status IN ('PENDING', 'VERIFIED', 'FAILED')
        AND document_status IN ('PENDING', 'VERIFIED', 'FAILED')
        AND legal_status IN ('PENDING', 'VERIFIED', 'FAILED')),
    CONSTRAINT ck_collaterals_insurance CHECK (insurance_status IN ('PENDING', 'VERIFIED', 'FAILED', 'NOT_REQUIRED')),
    CONSTRAINT ck_collaterals_encumbrance CHECK (encumbrance_status IN ('UNKNOWN', 'CLEAR', 'ENCUMBERED')),
    CONSTRAINT ck_collaterals_approved CHECK (
        (verification_status = 'APPROVED' AND approved_by IS NOT NULL AND approved_at IS NOT NULL
            AND eligible_lending_value IS NOT NULL)
        OR verification_status <> 'APPROVED'),
    CONSTRAINT ck_collaterals_rejected CHECK (
        (verification_status = 'REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL
            AND rejection_reason IS NOT NULL)
        OR (verification_status <> 'REJECTED' AND rejected_by IS NULL AND rejected_at IS NULL
            AND rejection_reason IS NULL)),
    CONSTRAINT ck_collaterals_released CHECK (
        (verification_status = 'RELEASED' AND released_at IS NOT NULL)
        OR (verification_status <> 'RELEASED' AND released_at IS NULL))
);

CREATE INDEX idx_collaterals_customer ON collaterals (customer_id, verification_status, created_at DESC);
CREATE INDEX idx_collaterals_type ON collaterals (collateral_type, verification_status);

CREATE TABLE collateral_documents (
    id                   UUID PRIMARY KEY,
    collateral_id        UUID          NOT NULL REFERENCES collaterals (id),
    document_code        VARCHAR(32)   NOT NULL,
    file_name            VARCHAR(255)  NOT NULL,
    content_type         VARCHAR(128)  NOT NULL,
    size_bytes           BIGINT        NOT NULL,
    checksum_sha256      VARCHAR(64)   NOT NULL,
    storage_key          VARCHAR(512)  NOT NULL,
    malware_scan_status  VARCHAR(16)   NOT NULL DEFAULT 'CLEAN',
    uploaded_by          VARCHAR(128)  NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_collateral_documents_key UNIQUE (storage_key),
    CONSTRAINT ck_collateral_documents_code CHECK (document_code IN (
        'TITLE', 'VALUATION_REPORT', 'INSURANCE_POLICY', 'LEGAL_OPINION', 'SEARCH_REPORT', 'PHOTOGRAPH', 'OTHER')),
    CONSTRAINT ck_collateral_documents_size CHECK (size_bytes > 0),
    CONSTRAINT ck_collateral_documents_checksum CHECK (
        checksum_sha256 = lower(checksum_sha256) AND char_length(checksum_sha256) = 64),
    CONSTRAINT ck_collateral_documents_scan CHECK (malware_scan_status = 'CLEAN'),
    CONSTRAINT ck_collateral_documents_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png'))
);

CREATE INDEX idx_collateral_documents_collateral ON collateral_documents (collateral_id, document_code);

CREATE TABLE collateral_valuations (
    id                          UUID PRIMARY KEY,
    collateral_id               UUID           NOT NULL REFERENCES collaterals (id),
    kind                        VARCHAR(16)    NOT NULL,
    status                      VARCHAR(32)    NOT NULL,
    market_value                NUMERIC(19, 2) NOT NULL,
    forced_sale_value           NUMERIC(19, 2) NOT NULL,
    lending_ratio               NUMERIC(9, 6)  NOT NULL,
    calculated_eligible_value   NUMERIC(19, 2) NOT NULL,
    accepted_eligible_value     NUMERIC(19, 2) NOT NULL,
    valuation_date              DATE           NOT NULL,
    expiry_date                 DATE           NOT NULL,
    valuer_name                 VARCHAR(255)   NOT NULL,
    valuer_reference            VARCHAR(128),
    submitted_by                UUID           NOT NULL,
    decided_by                  UUID,
    decided_at                  TIMESTAMPTZ,
    decision_reason             VARCHAR(500),
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_collateral_valuations_kind CHECK (kind IN ('INITIAL', 'REVALUATION')),
    CONSTRAINT ck_collateral_valuations_status CHECK (status IN (
        'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT ck_collateral_valuations_amounts CHECK (
        market_value > 0
        AND forced_sale_value > 0
        AND forced_sale_value <= market_value
        AND lending_ratio > 0
        AND lending_ratio <= 1
        AND calculated_eligible_value > 0
        AND calculated_eligible_value <= forced_sale_value
        AND accepted_eligible_value > 0
        AND accepted_eligible_value <= calculated_eligible_value),
    CONSTRAINT ck_collateral_valuations_dates CHECK (expiry_date >= valuation_date),
    CONSTRAINT ck_collateral_valuations_decision CHECK (
        (status = 'PENDING_APPROVAL' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED', 'EXPIRED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CONSTRAINT ck_collateral_valuations_reject_reason CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL),
    CONSTRAINT ck_collateral_valuations_maker CHECK (decided_by IS NULL OR decided_by <> submitted_by)
);

CREATE UNIQUE INDEX uq_collateral_valuations_pending
    ON collateral_valuations (collateral_id)
    WHERE status = 'PENDING_APPROVAL';

CREATE UNIQUE INDEX uq_collateral_valuations_initial
    ON collateral_valuations (collateral_id)
    WHERE kind = 'INITIAL';

CREATE INDEX idx_collateral_valuations_history
    ON collateral_valuations (collateral_id, created_at DESC);

CREATE TABLE collateral_insurance (
    id              UUID PRIMARY KEY,
    collateral_id   UUID           NOT NULL REFERENCES collaterals (id),
    insurer_name    VARCHAR(255)   NOT NULL,
    policy_number   VARCHAR(128)   NOT NULL,
    cover_amount    NUMERIC(19, 2) NOT NULL,
    currency_code   CHAR(3)        NOT NULL DEFAULT 'NGN',
    start_date      DATE           NOT NULL,
    expiry_date     DATE           NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(128)   NOT NULL,
    CONSTRAINT ck_collateral_insurance_cover CHECK (cover_amount > 0),
    CONSTRAINT ck_collateral_insurance_dates CHECK (expiry_date >= start_date),
    CONSTRAINT ck_collateral_insurance_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_collateral_insurance_names CHECK (
        char_length(btrim(insurer_name)) > 0 AND char_length(btrim(policy_number)) > 0)
);

CREATE INDEX idx_collateral_insurance_collateral ON collateral_insurance (collateral_id, created_at DESC);

CREATE TABLE loan_collaterals (
    id                 UUID PRIMARY KEY,
    application_id     UUID           NOT NULL REFERENCES loan_applications (id),
    collateral_id      UUID           NOT NULL REFERENCES collaterals (id),
    allocated_amount   NUMERIC(19, 2) NOT NULL,
    shared_security    BOOLEAN        NOT NULL,
    status             VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    allocated_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    released_at        TIMESTAMPTZ,
    allocated_by       UUID           NOT NULL,
    CONSTRAINT ck_loan_collaterals_amount CHECK (allocated_amount > 0),
    CONSTRAINT ck_loan_collaterals_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_loan_collaterals_released CHECK (
        (status = 'RELEASED' AND released_at IS NOT NULL)
        OR (status = 'ACTIVE' AND released_at IS NULL))
);

CREATE UNIQUE INDEX uq_loan_collaterals_active_pair
    ON loan_collaterals (application_id, collateral_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_loan_collaterals_active ON loan_collaterals (collateral_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_loan_collaterals_application ON loan_collaterals (application_id, allocated_at DESC);

CREATE TABLE collateral_encumbrances (
    id                   UUID PRIMARY KEY,
    collateral_id        UUID           NOT NULL REFERENCES collaterals (id),
    loan_collateral_id   UUID           REFERENCES loan_collaterals (id),
    source               VARCHAR(16)    NOT NULL,
    holder_name          VARCHAR(255)   NOT NULL,
    reference_number     VARCHAR(128)   NOT NULL,
    amount               NUMERIC(19, 2) NOT NULL,
    status               VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
    noted_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    released_at          TIMESTAMPTZ,
    created_by           UUID           NOT NULL,
    CONSTRAINT ck_collateral_encumbrances_source CHECK (source IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_collateral_encumbrances_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT ck_collateral_encumbrances_amount CHECK (amount > 0),
    CONSTRAINT ck_collateral_encumbrances_text CHECK (
        char_length(btrim(holder_name)) > 0 AND char_length(btrim(reference_number)) > 0),
    CONSTRAINT ck_collateral_encumbrances_released CHECK (
        (status = 'RELEASED' AND released_at IS NOT NULL)
        OR (status = 'ACTIVE' AND released_at IS NULL))
);

CREATE INDEX idx_collateral_encumbrances_active
    ON collateral_encumbrances (collateral_id, source)
    WHERE status = 'ACTIVE';

CREATE TABLE collateral_release_history (
    id                     UUID PRIMARY KEY,
    collateral_id          UUID         NOT NULL REFERENCES collaterals (id),
    settlement_reference   VARCHAR(128) NOT NULL,
    discharge_reference    VARCHAR(128) NOT NULL,
    reason                 VARCHAR(500) NOT NULL,
    released_by            UUID         NOT NULL,
    released_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_collateral_release_refs CHECK (
        char_length(btrim(settlement_reference)) > 0
        AND char_length(btrim(discharge_reference)) > 0
        AND char_length(btrim(reason)) > 0)
);

CREATE INDEX idx_collateral_release_history ON collateral_release_history (collateral_id, released_at DESC);

CREATE TABLE guarantors (
    id                            UUID PRIMARY KEY,
    reference                     VARCHAR(14)    NOT NULL,
    full_name                     VARCHAR(255)   NOT NULL,
    phone_number                  VARCHAR(32)    NOT NULL,
    email                         VARCHAR(255),
    address_line                  VARCHAR(500)   NOT NULL,
    bvn_encrypted                 TEXT           NOT NULL,
    bvn_hash                      VARCHAR(64)    NOT NULL,
    bvn_last4                     VARCHAR(4)     NOT NULL,
    nin_encrypted                 TEXT           NOT NULL,
    nin_hash                      VARCHAR(64)    NOT NULL,
    nin_last4                     VARCHAR(4)     NOT NULL,
    bvn_verification_reference    VARCHAR(128)   NOT NULL,
    nin_verification_reference    VARCHAR(128)   NOT NULL,
    employment_status             VARCHAR(32)    NOT NULL,
    employer_name                 VARCHAR(255),
    job_title                     VARCHAR(160),
    monthly_income                NUMERIC(19, 2) NOT NULL,
    monthly_obligations           NUMERIC(19, 2) NOT NULL,
    verification_status           VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    acceptance_status             VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
    approved_security             BOOLEAN        NOT NULL DEFAULT FALSE,
    consent_recorded              BOOLEAN        NOT NULL DEFAULT FALSE,
    verified_by                   UUID,
    verified_at                   TIMESTAMPTZ,
    accepted_by                   UUID,
    accepted_at                   TIMESTAMPTZ,
    rejection_reason              VARCHAR(500),
    created_at                    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by                    VARCHAR(128)   NOT NULL,
    updated_by                    VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_guarantors_reference UNIQUE (reference),
    CONSTRAINT uq_guarantors_bvn UNIQUE (bvn_hash),
    CONSTRAINT uq_guarantors_nin UNIQUE (nin_hash),
    CONSTRAINT ck_guarantors_reference CHECK (reference ~ '^GR[0-9]{12}$'),
    CONSTRAINT ck_guarantors_phone CHECK (phone_number ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_guarantors_email CHECK (email IS NULL OR email = lower(email)),
    CONSTRAINT ck_guarantors_bvn_hash CHECK (bvn_hash = lower(bvn_hash) AND char_length(bvn_hash) = 64),
    CONSTRAINT ck_guarantors_nin_hash CHECK (nin_hash = lower(nin_hash) AND char_length(nin_hash) = 64),
    CONSTRAINT ck_guarantors_bvn_last4 CHECK (bvn_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_guarantors_nin_last4 CHECK (nin_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_guarantors_identity_refs CHECK (
        char_length(btrim(bvn_verification_reference)) > 0
        AND char_length(btrim(nin_verification_reference)) > 0),
    CONSTRAINT ck_guarantors_employment CHECK (employment_status IN (
        'EMPLOYED', 'SELF_EMPLOYED', 'UNEMPLOYED', 'RETIRED', 'STUDENT')),
    CONSTRAINT ck_guarantors_employer CHECK (
        employment_status NOT IN ('EMPLOYED', 'SELF_EMPLOYED') OR char_length(btrim(employer_name)) > 0),
    CONSTRAINT ck_guarantors_income CHECK (monthly_income >= 0 AND monthly_obligations >= 0),
    CONSTRAINT ck_guarantors_verification CHECK (verification_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_guarantors_acceptance CHECK (acceptance_status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_guarantors_approved CHECK (
        approved_security = FALSE
        OR (verification_status = 'VERIFIED' AND acceptance_status = 'ACCEPTED' AND consent_recorded = TRUE)),
    CONSTRAINT ck_guarantors_verified CHECK (
        (verification_status = 'VERIFIED' AND verified_by IS NOT NULL AND verified_at IS NOT NULL)
        OR verification_status <> 'VERIFIED'),
    CONSTRAINT ck_guarantors_accepted CHECK (
        (acceptance_status = 'ACCEPTED' AND accepted_by IS NOT NULL AND accepted_at IS NOT NULL)
        OR acceptance_status <> 'ACCEPTED')
);

CREATE INDEX idx_guarantors_phone ON guarantors (phone_number);

CREATE TABLE loan_guarantors (
    id                  UUID PRIMARY KEY,
    application_id      UUID         NOT NULL REFERENCES loan_applications (id),
    guarantor_id        UUID         NOT NULL REFERENCES guarantors (id),
    relationship        VARCHAR(80)  NOT NULL,
    approved_security   BOOLEAN      NOT NULL DEFAULT FALSE,
    linked_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    linked_by           UUID         NOT NULL,
    CONSTRAINT uq_loan_guarantors_pair UNIQUE (application_id, guarantor_id),
    CONSTRAINT ck_loan_guarantors_relationship CHECK (char_length(btrim(relationship)) > 0)
);

CREATE INDEX idx_loan_guarantors_guarantor ON loan_guarantors (guarantor_id);

CREATE TABLE guarantor_documents (
    id                   UUID PRIMARY KEY,
    guarantor_id         UUID          NOT NULL REFERENCES guarantors (id),
    document_code        VARCHAR(32)   NOT NULL,
    file_name            VARCHAR(255)  NOT NULL,
    content_type         VARCHAR(128)  NOT NULL,
    size_bytes           BIGINT        NOT NULL,
    checksum_sha256      VARCHAR(64)   NOT NULL,
    storage_key          VARCHAR(512)  NOT NULL,
    malware_scan_status  VARCHAR(16)   NOT NULL DEFAULT 'CLEAN',
    uploaded_by          VARCHAR(128)  NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_guarantor_documents_key UNIQUE (storage_key),
    CONSTRAINT ck_guarantor_documents_code CHECK (document_code IN (
        'GUARANTEE_AGREEMENT', 'IDENTITY', 'INCOME_EVIDENCE', 'CONSENT', 'OTHER')),
    CONSTRAINT ck_guarantor_documents_size CHECK (size_bytes > 0),
    CONSTRAINT ck_guarantor_documents_checksum CHECK (
        checksum_sha256 = lower(checksum_sha256) AND char_length(checksum_sha256) = 64),
    CONSTRAINT ck_guarantor_documents_scan CHECK (malware_scan_status = 'CLEAN'),
    CONSTRAINT ck_guarantor_documents_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png'))
);

CREATE INDEX idx_guarantor_documents_guarantor ON guarantor_documents (guarantor_id, document_code);

CREATE TABLE guarantor_consents (
    id                  UUID PRIMARY KEY,
    guarantor_id        UUID          NOT NULL REFERENCES guarantors (id),
    consent_statement   VARCHAR(2000) NOT NULL,
    consented_at        TIMESTAMPTZ   NOT NULL,
    recorded_by         UUID          NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_guarantor_consents_statement CHECK (char_length(btrim(consent_statement)) > 0)
);

CREATE INDEX idx_guarantor_consents_guarantor ON guarantor_consents (guarantor_id, consented_at DESC);

CREATE TRIGGER trg_collaterals_set_updated_at
    BEFORE UPDATE ON collaterals
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_guarantors_set_updated_at
    BEFORE UPDATE ON guarantors
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE OR REPLACE FUNCTION reject_unsafe_collateral_pledge()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    eligible NUMERIC(19, 2);
    expires_on DATE;
    verification VARCHAR(32);
    already NUMERIC(19, 2);
    exclusive_count INTEGER;
BEGIN
    IF NEW.status <> 'ACTIVE' THEN
        RETURN NEW;
    END IF;

    SELECT eligible_lending_value, valuation_expires_on, verification_status
      INTO eligible, expires_on, verification
      FROM collaterals
     WHERE id = NEW.collateral_id
     FOR UPDATE;

    IF verification IS DISTINCT FROM 'APPROVED' OR eligible IS NULL THEN
        RAISE EXCEPTION 'collateral is not approved security';
    END IF;
    IF expires_on IS NULL OR expires_on < (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date THEN
        RAISE EXCEPTION 'collateral valuation has expired';
    END IF;

    SELECT COALESCE(SUM(allocated_amount), 0)
      INTO already
      FROM loan_collaterals
     WHERE collateral_id = NEW.collateral_id
       AND status = 'ACTIVE'
       AND id <> NEW.id;

    SELECT COUNT(*)
      INTO exclusive_count
      FROM loan_collaterals
     WHERE collateral_id = NEW.collateral_id
       AND status = 'ACTIVE'
       AND shared_security = FALSE
       AND id <> NEW.id;

    IF exclusive_count > 0 OR (NEW.shared_security = FALSE AND already > 0) THEN
        RAISE EXCEPTION 'collateral is already pledged';
    END IF;
    IF already + NEW.allocated_amount > eligible THEN
        RAISE EXCEPTION 'collateral cannot be over-allocated';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_collaterals_reject_over_pledge
    BEFORE INSERT ON loan_collaterals
    FOR EACH ROW
    EXECUTE FUNCTION reject_unsafe_collateral_pledge();

CREATE OR REPLACE FUNCTION reject_unauthorised_collateral_release()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    approver UUID;
BEGIN
    SELECT approved_by INTO approver FROM collaterals WHERE id = NEW.collateral_id FOR UPDATE;
    IF approver IS NULL OR approver = NEW.released_by THEN
        RAISE EXCEPTION 'collateral release requires a different authorised officer';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM loan_collaterals pledged
        JOIN loan_applications application ON application.id = pledged.application_id
        WHERE pledged.collateral_id = NEW.collateral_id
          AND pledged.status = 'ACTIVE'
          AND application.status NOT IN ('REJECTED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED', 'DISBURSED')
    ) THEN
        RAISE EXCEPTION 'collateral is still pledged to an open application';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_collateral_release_authorised
    BEFORE INSERT ON collateral_release_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_unauthorised_collateral_release();

CREATE OR REPLACE FUNCTION reject_premature_guarantor_approval()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.approved_security AND (
        NEW.verification_status <> 'VERIFIED'
        OR NEW.acceptance_status <> 'ACCEPTED'
        OR NEW.consent_recorded = FALSE
        OR NOT EXISTS (
            SELECT 1
            FROM guarantor_documents
            WHERE guarantor_id = NEW.id
              AND document_code = 'GUARANTEE_AGREEMENT'
        )
    ) THEN
        RAISE EXCEPTION 'guarantor is not approved security';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_guarantors_approved_security
    BEFORE INSERT OR UPDATE OF approved_security, verification_status, acceptance_status, consent_recorded
    ON guarantors
    FOR EACH ROW
    EXECUTE FUNCTION reject_premature_guarantor_approval();

CREATE OR REPLACE FUNCTION reject_unapproved_loan_guarantor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.approved_security AND NOT EXISTS (
        SELECT 1
        FROM guarantors
        WHERE id = NEW.guarantor_id
          AND approved_security = TRUE
          AND verification_status = 'VERIFIED'
          AND acceptance_status = 'ACCEPTED'
          AND consent_recorded = TRUE
    ) THEN
        RAISE EXCEPTION 'guarantor is not approved security';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_guarantors_approved_security
    BEFORE INSERT OR UPDATE OF approved_security ON loan_guarantors
    FOR EACH ROW
    EXECUTE FUNCTION reject_unapproved_loan_guarantor();

INSERT INTO permissions (code, description, system_permission) VALUES
    ('COLLATERAL_VIEW', 'View collateral, valuations, and pledge positions', TRUE),
    ('COLLATERAL_MANAGE', 'Register collateral, documents, valuations, and pledges', TRUE),
    ('COLLATERAL_RELEASE', 'Release collateral after authorised settlement and discharge', TRUE),
    ('GUARANTOR_VIEW', 'View guarantor records', TRUE),
    ('GUARANTOR_MANAGE', 'Register guarantors, consent, and guarantee documents', TRUE),
    ('GUARANTOR_VERIFY', 'Verify guarantor identity and accept or reject a guarantee', TRUE);

DELETE FROM role_permissions
WHERE role_id = (SELECT id FROM roles WHERE code = 'RELATIONSHIP_OFFICER')
  AND permission_id = (SELECT id FROM permissions WHERE code = 'COLLATERAL_VERIFY');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'COLLATERAL_VIEW', 'COLLATERAL_MANAGE', 'GUARANTOR_VIEW', 'GUARANTOR_MANAGE')
WHERE r.code = 'RELATIONSHIP_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'COLLATERAL_VIEW', 'COLLATERAL_MANAGE', 'COLLATERAL_RELEASE',
    'GUARANTOR_VIEW', 'GUARANTOR_MANAGE', 'GUARANTOR_VERIFY')
WHERE r.code = 'CREDIT_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('COLLATERAL_VIEW', 'GUARANTOR_VIEW')
WHERE r.code IN ('AUDITOR', 'BRANCH_MANAGER');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'COLLATERAL_VIEW', 'COLLATERAL_MANAGE', 'COLLATERAL_RELEASE',
    'GUARANTOR_VIEW', 'GUARANTOR_MANAGE', 'GUARANTOR_VERIFY')
WHERE r.code = 'SYSTEM_ADMIN';

COMMENT ON TABLE collaterals IS 'Security register. Eligible lending value is copied from the latest approved valuation.';
COMMENT ON TABLE collateral_valuations IS 'Append-only valuation history. The officer who submitted a valuation cannot approve it.';
COMMENT ON TABLE collateral_documents IS 'Private collateral evidence. storage_key is internal and is never returned by the API.';
COMMENT ON TABLE collateral_encumbrances IS 'Third-party charges and internal pledges. Internal rows track active allocations.';
COMMENT ON TABLE collateral_release_history IS 'Authorised discharge. The approving officer cannot release the same collateral.';
COMMENT ON TABLE loan_collaterals IS 'Active and released pledges. The sum of active pledges cannot exceed eligible value.';
COMMENT ON TABLE guarantors IS 'Guarantor identity. BVN and NIN are ciphertext. approved_security stays false until acceptance.';
COMMENT ON TABLE guarantor_consents IS 'Recorded guarantor consent. The statement is the evidence of consent.';
COMMENT ON TABLE guarantor_documents IS 'Private guarantor evidence, including the guarantee agreement.';
COMMENT ON TABLE loan_guarantors IS 'Guarantee linked to an application. approved_security follows guarantor acceptance.';
