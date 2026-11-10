-- Customer master data, KYC evidence, and document metadata.
-- BVN, NIN, TIN, and account numbers are stored as AES-GCM ciphertext.
-- Lookup uses HMAC-SHA256 hashes. Responses return only a masked value.

CREATE SEQUENCE customer_reference_seq AS BIGINT START WITH 10000001;

CREATE TABLE customers (
    id                       UUID PRIMARY KEY,
    customer_reference       VARCHAR(11)  NOT NULL,
    customer_type            VARCHAR(16)  NOT NULL,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    category                 VARCHAR(32)  NOT NULL,
    display_name             VARCHAR(255) NOT NULL,
    email                    VARCHAR(255) NOT NULL,
    phone_number             VARCHAR(32)  NOT NULL,
    user_id                  UUID,
    branch_id                UUID,
    kyc_status               VARCHAR(32)  NOT NULL DEFAULT 'NOT_STARTED',
    risk_classification      VARCHAR(16)  NOT NULL DEFAULT 'UNASSESSED',
    blacklisted              BOOLEAN      NOT NULL DEFAULT FALSE,
    blacklist_reason         VARCHAR(500),
    blacklisted_at           TIMESTAMPTZ,
    compliance_hold          BOOLEAN      NOT NULL DEFAULT FALSE,
    compliance_hold_reason   VARCHAR(500),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by               VARCHAR(128),
    updated_by               VARCHAR(128),
    CONSTRAINT uq_customers_reference UNIQUE (customer_reference),
    CONSTRAINT uq_customers_email UNIQUE (email),
    CONSTRAINT uq_customers_phone UNIQUE (phone_number),
    CONSTRAINT uq_customers_user UNIQUE (user_id),
    CONSTRAINT fk_customers_user FOREIGN KEY (user_id) REFERENCES system_users (id),
    CONSTRAINT fk_customers_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
    CONSTRAINT ck_customers_type CHECK (customer_type IN ('INDIVIDUAL', 'SME')),
    CONSTRAINT ck_customers_status CHECK (status IN ('PENDING', 'ACTIVE', 'INACTIVE', 'BLACKLISTED')),
    CONSTRAINT ck_customers_category CHECK (category IN ('RETAIL', 'MICRO', 'SMALL', 'MEDIUM', 'CORPORATE')),
    CONSTRAINT ck_customers_kyc CHECK (kyc_status IN ('NOT_STARTED', 'VERIFIED', 'FAILED', 'REVIEW')),
    CONSTRAINT ck_customers_risk CHECK (risk_classification IN ('UNASSESSED', 'LOW', 'MEDIUM', 'HIGH', 'UNACCEPTABLE')),
    CONSTRAINT ck_customers_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_customers_reference CHECK (customer_reference ~ '^CUS[0-9]{8}$'),
    CONSTRAINT ck_customers_blacklist CHECK (
        (blacklisted = FALSE AND blacklist_reason IS NULL AND blacklisted_at IS NULL)
        OR (blacklisted = TRUE AND blacklist_reason IS NOT NULL AND blacklisted_at IS NOT NULL)
    )
);

CREATE INDEX idx_customers_branch_status ON customers (branch_id, status);
CREATE INDEX idx_customers_name ON customers (display_name);

CREATE TABLE individual_profiles (
    customer_id              UUID PRIMARY KEY REFERENCES customers (id),
    full_name                VARCHAR(255) NOT NULL,
    date_of_birth            DATE         NOT NULL,
    phone_number             VARCHAR(32)  NOT NULL,
    email                    VARCHAR(255) NOT NULL,
    bvn_encrypted            TEXT         NOT NULL,
    bvn_hash                 VARCHAR(64)  NOT NULL,
    bvn_last4                VARCHAR(4)      NOT NULL,
    nin_encrypted            TEXT         NOT NULL,
    nin_hash                 VARCHAR(64)  NOT NULL,
    nin_last4                VARCHAR(4)      NOT NULL,
    employment_status        VARCHAR(32)  NOT NULL,
    employer_name            VARCHAR(255),
    occupation               VARCHAR(255),
    monthly_income           NUMERIC(18, 2) NOT NULL,
    monthly_expenses         NUMERIC(18, 2) NOT NULL,
    nok_full_name            VARCHAR(255) NOT NULL,
    nok_relationship         VARCHAR(64)  NOT NULL,
    nok_phone_number         VARCHAR(32)  NOT NULL,
    nok_address              VARCHAR(500) NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_individual_profiles_bvn UNIQUE (bvn_hash),
    CONSTRAINT uq_individual_profiles_nin UNIQUE (nin_hash),
    CONSTRAINT ck_individual_employment CHECK (employment_status IN ('EMPLOYED', 'SELF_EMPLOYED', 'UNEMPLOYED', 'STUDENT', 'RETIRED')),
    CONSTRAINT ck_individual_income CHECK (monthly_income >= 0 AND monthly_expenses >= 0),
    CONSTRAINT ck_individual_bvn_hash CHECK (bvn_hash = lower(bvn_hash) AND char_length(btrim(bvn_hash)) = 64),
    CONSTRAINT ck_individual_nin_hash CHECK (nin_hash = lower(nin_hash) AND char_length(btrim(nin_hash)) = 64),
    CONSTRAINT ck_individual_bvn_last4 CHECK (bvn_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_individual_nin_last4 CHECK (nin_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_individual_email_lower CHECK (email = lower(email))
);

CREATE TABLE sme_profiles (
    customer_id              UUID PRIMARY KEY REFERENCES customers (id),
    registered_name          VARCHAR(255) NOT NULL,
    trading_name             VARCHAR(255),
    cac_number               VARCHAR(20)  NOT NULL,
    business_type            VARCHAR(32)  NOT NULL,
    business_sector          VARCHAR(128) NOT NULL,
    incorporation_date       DATE,
    annual_turnover          NUMERIC(18, 2) NOT NULL,
    monthly_revenue          NUMERIC(18, 2) NOT NULL,
    operating_expenses       NUMERIC(18, 2) NOT NULL,
    tin_encrypted            TEXT         NOT NULL,
    tin_hash                 VARCHAR(64)  NOT NULL,
    tin_last4                VARCHAR(4)      NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_sme_profiles_cac UNIQUE (cac_number),
    CONSTRAINT uq_sme_profiles_tin UNIQUE (tin_hash),
    CONSTRAINT ck_sme_business_type CHECK (business_type IN (
        'SOLE_PROPRIETORSHIP', 'PARTNERSHIP', 'LIMITED_LIABILITY', 'PUBLIC_LIMITED', 'INCORPORATED_TRUSTEE', 'COOPERATIVE')),
    CONSTRAINT ck_sme_amounts CHECK (annual_turnover >= 0 AND monthly_revenue >= 0 AND operating_expenses >= 0),
    CONSTRAINT ck_sme_tin_hash CHECK (tin_hash = lower(tin_hash) AND char_length(btrim(tin_hash)) = 64),
    CONSTRAINT ck_sme_tin_last4 CHECK (tin_last4 ~ '^[A-Z0-9]{4}$'),
    CONSTRAINT ck_sme_cac CHECK (cac_number = upper(cac_number))
);

CREATE TABLE customer_directors (
    id                UUID PRIMARY KEY,
    customer_id       UUID         NOT NULL REFERENCES customers (id),
    full_name         VARCHAR(255) NOT NULL,
    date_of_birth     DATE         NOT NULL,
    phone_number      VARCHAR(32)  NOT NULL,
    email             VARCHAR(255),
    position          VARCHAR(128),
    bvn_encrypted     TEXT         NOT NULL,
    bvn_hash          VARCHAR(64)  NOT NULL,
    bvn_last4         VARCHAR(4)      NOT NULL,
    nin_encrypted     TEXT,
    nin_hash          VARCHAR(64),
    nin_last4         VARCHAR(4),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_directors_bvn UNIQUE (customer_id, bvn_hash),
    CONSTRAINT ck_customer_directors_bvn_hash CHECK (bvn_hash = lower(bvn_hash)),
    CONSTRAINT ck_customer_directors_bvn_last4 CHECK (bvn_last4 ~ '^[0-9]{4}$')
);

CREATE INDEX idx_customer_directors_customer ON customer_directors (customer_id);

CREATE TABLE customer_beneficial_owners (
    id                     UUID PRIMARY KEY,
    customer_id            UUID         NOT NULL REFERENCES customers (id),
    full_name              VARCHAR(255) NOT NULL,
    date_of_birth          DATE         NOT NULL,
    phone_number           VARCHAR(32)  NOT NULL,
    email                  VARCHAR(255),
    ownership_percentage   NUMERIC(5, 2) NOT NULL,
    bvn_encrypted          TEXT         NOT NULL,
    bvn_hash               VARCHAR(64)  NOT NULL,
    bvn_last4              VARCHAR(4)      NOT NULL,
    nin_encrypted          TEXT,
    nin_hash               VARCHAR(64),
    nin_last4              VARCHAR(4),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_owners_bvn UNIQUE (customer_id, bvn_hash),
    CONSTRAINT ck_customer_owners_percentage CHECK (ownership_percentage > 0 AND ownership_percentage <= 100),
    CONSTRAINT ck_customer_owners_bvn_last4 CHECK (bvn_last4 ~ '^[0-9]{4}$')
);

CREATE INDEX idx_customer_owners_customer ON customer_beneficial_owners (customer_id);

CREATE TABLE customer_authorised_representatives (
    id             UUID PRIMARY KEY,
    customer_id    UUID         NOT NULL REFERENCES customers (id),
    full_name      VARCHAR(255) NOT NULL,
    phone_number   VARCHAR(32)  NOT NULL,
    email          VARCHAR(255),
    position       VARCHAR(128) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customer_representatives_customer ON customer_authorised_representatives (customer_id);

CREATE TABLE customer_addresses (
    id              UUID PRIMARY KEY,
    customer_id     UUID         NOT NULL REFERENCES customers (id),
    address_type    VARCHAR(32)  NOT NULL,
    line1           VARCHAR(255) NOT NULL,
    line2           VARCHAR(255),
    city            VARCHAR(120) NOT NULL,
    state           VARCHAR(120) NOT NULL,
    country_code    CHAR(2)      NOT NULL DEFAULT 'NG',
    postal_code     VARCHAR(16),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_addresses_type UNIQUE (customer_id, address_type),
    CONSTRAINT ck_customer_addresses_type CHECK (address_type IN ('RESIDENTIAL', 'BUSINESS')),
    CONSTRAINT ck_customer_addresses_country CHECK (country_code = upper(country_code) AND char_length(country_code) = 2)
);

CREATE TABLE customer_accounts (
    id                      UUID PRIMARY KEY,
    customer_id             UUID         NOT NULL REFERENCES customers (id),
    bank_code               VARCHAR(6)   NOT NULL,
    bank_name               VARCHAR(128) NOT NULL,
    account_number_encrypted TEXT        NOT NULL,
    account_number_hash     VARCHAR(64)  NOT NULL,
    account_number_last4    VARCHAR(4)      NOT NULL,
    account_name            VARCHAR(255),
    currency_code           CHAR(3)      NOT NULL DEFAULT 'NGN',
    verification_status     VARCHAR(16)  NOT NULL,
    verified_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_accounts_number UNIQUE (customer_id, account_number_hash),
    CONSTRAINT ck_customer_accounts_status CHECK (verification_status IN ('VERIFIED', 'FAILED', 'TIMED_OUT')),
    CONSTRAINT ck_customer_accounts_last4 CHECK (account_number_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_customer_accounts_hash CHECK (account_number_hash = lower(account_number_hash)),
    CONSTRAINT ck_customer_accounts_currency CHECK (currency_code = 'NGN')
);

CREATE INDEX idx_customer_accounts_customer ON customer_accounts (customer_id, verification_status);

CREATE TABLE customer_documents (
    id                   UUID PRIMARY KEY,
    customer_id          UUID         NOT NULL REFERENCES customers (id),
    document_type        VARCHAR(32)  NOT NULL,
    file_name            VARCHAR(255) NOT NULL,
    content_type         VARCHAR(128) NOT NULL,
    size_bytes           BIGINT       NOT NULL,
    checksum_sha256      VARCHAR(64)  NOT NULL,
    storage_key          VARCHAR(512) NOT NULL,
    malware_scan_status  VARCHAR(16)  NOT NULL DEFAULT 'CLEAN',
    uploaded_by          VARCHAR(128) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_documents_key UNIQUE (storage_key),
    CONSTRAINT ck_customer_documents_type CHECK (document_type IN (
        'IDENTITY', 'PROOF_OF_ADDRESS', 'PAYSLIP', 'BANK_STATEMENT', 'CAC',
        'FINANCIAL_STATEMENT', 'BOARD_RESOLUTION', 'DIRECTOR_IDENTIFICATION', 'COLLATERAL')),
    CONSTRAINT ck_customer_documents_size CHECK (size_bytes > 0),
    CONSTRAINT ck_customer_documents_checksum CHECK (checksum_sha256 = lower(checksum_sha256) AND char_length(btrim(checksum_sha256)) = 64),
    CONSTRAINT ck_customer_documents_scan CHECK (malware_scan_status = 'CLEAN'),
    CONSTRAINT ck_customer_documents_content_type CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png'))
);

CREATE INDEX idx_customer_documents_customer ON customer_documents (customer_id, document_type);

CREATE TABLE customer_consents (
    id            UUID PRIMARY KEY,
    customer_id   UUID         NOT NULL REFERENCES customers (id),
    consent_type  VARCHAR(32)  NOT NULL,
    granted       BOOLEAN      NOT NULL,
    version       VARCHAR(32)  NOT NULL,
    channel       VARCHAR(16)  NOT NULL,
    granted_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_customer_consents UNIQUE (customer_id, consent_type),
    CONSTRAINT ck_customer_consents_type CHECK (consent_type IN ('DATA_PROCESSING', 'IDENTITY_VERIFICATION', 'CREDIT_BUREAU', 'MARKETING')),
    CONSTRAINT ck_customer_consents_channel CHECK (channel IN ('WEB', 'MOBILE')),
    CONSTRAINT ck_customer_consents_granted CHECK (
        (granted = TRUE AND granted_at IS NOT NULL) OR (granted = FALSE AND granted_at IS NULL)
    )
);

CREATE TABLE customer_kyc (
    id            UUID PRIMARY KEY,
    customer_id   UUID         NOT NULL REFERENCES customers (id),
    check_type    VARCHAR(32)  NOT NULL,
    outcome       VARCHAR(16)  NOT NULL,
    provider      VARCHAR(64)  NOT NULL,
    reference     VARCHAR(128),
    detail        VARCHAR(500),
    completed_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_customer_kyc_type CHECK (check_type IN ('BVN', 'NIN', 'CAC', 'BANK_ACCOUNT')),
    CONSTRAINT ck_customer_kyc_outcome CHECK (outcome IN ('PASSED', 'FAILED', 'TIMED_OUT'))
);

CREATE INDEX idx_customer_kyc_customer ON customer_kyc (customer_id, completed_at DESC);

CREATE TABLE customer_screening_results (
    id              UUID PRIMARY KEY,
    customer_id     UUID         NOT NULL REFERENCES customers (id),
    screening_type  VARCHAR(16)  NOT NULL,
    outcome         VARCHAR(16)  NOT NULL,
    provider        VARCHAR(64)  NOT NULL,
    detail          VARCHAR(500),
    screened_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_customer_screening_type CHECK (screening_type IN ('SANCTIONS', 'PEP', 'AML')),
    CONSTRAINT ck_customer_screening_outcome CHECK (outcome IN ('CLEAR', 'MATCH', 'REVIEW'))
);

CREATE INDEX idx_customer_screening_customer ON customer_screening_results (customer_id, screened_at DESC);

CREATE TABLE customer_risk_profiles (
    customer_id      UUID PRIMARY KEY REFERENCES customers (id),
    classification   VARCHAR(16) NOT NULL,
    score            INTEGER     NOT NULL,
    factors          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    assessed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assessed_by      VARCHAR(128) NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_customer_risk_classification CHECK (classification IN ('UNASSESSED', 'LOW', 'MEDIUM', 'HIGH', 'UNACCEPTABLE')),
    CONSTRAINT ck_customer_risk_score CHECK (score >= 0 AND score <= 100)
);

CREATE TABLE customer_review_cases (
    id              UUID PRIMARY KEY,
    customer_id     UUID         NOT NULL REFERENCES customers (id),
    kyc_id          UUID         REFERENCES customer_kyc (id),
    case_type       VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    mandatory_hold  BOOLEAN      NOT NULL,
    summary         VARCHAR(500) NOT NULL,
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    resolved_by     VARCHAR(128),
    CONSTRAINT ck_customer_review_type CHECK (case_type IN (
        'KYC_FAILURE', 'PROVIDER_TIMEOUT', 'SANCTIONS_MATCH', 'PEP_MATCH', 'AML_RISK')),
    CONSTRAINT ck_customer_review_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT ck_customer_review_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
);

CREATE INDEX idx_customer_review_open ON customer_review_cases (customer_id, status, mandatory_hold);

CREATE TRIGGER trg_customers_set_updated_at
    BEFORE UPDATE ON customers
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_individual_profiles_set_updated_at
    BEFORE UPDATE ON individual_profiles
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_sme_profiles_set_updated_at
    BEFORE UPDATE ON sme_profiles
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_customer_addresses_set_updated_at
    BEFORE UPDATE ON customer_addresses
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_customer_accounts_set_updated_at
    BEFORE UPDATE ON customer_accounts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_customer_consents_set_updated_at
    BEFORE UPDATE ON customer_consents
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_customer_risk_profiles_set_updated_at
    BEFORE UPDATE ON customer_risk_profiles
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE customers IS 'Lending customers. Identity secrets are not stored on this table.';
COMMENT ON COLUMN individual_profiles.bvn_encrypted IS 'AES-GCM ciphertext of the BVN. bvn_hash is HMAC-SHA256 for duplicate detection.';
COMMENT ON COLUMN individual_profiles.nin_encrypted IS 'AES-GCM ciphertext of the NIN.';
COMMENT ON COLUMN sme_profiles.tin_encrypted IS 'AES-GCM ciphertext of the tax identification number.';
COMMENT ON COLUMN customer_accounts.account_number_encrypted IS 'AES-GCM ciphertext of the NUBAN. The plaintext is not returned by the API.';
COMMENT ON TABLE customer_documents IS 'Document metadata and SHA-256 checksums. File bytes live in private object storage.';
COMMENT ON TABLE customer_review_cases IS 'Opened when KYC, screening, or a provider timeout needs a person to review the customer.';
