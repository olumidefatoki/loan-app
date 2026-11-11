-- Loan offers pin the calculation engine output and the product version the
-- application was approved against. A material change adds a version and a new
-- agreement. The previous agreement stays readable. loan_agreements already
-- freezes a booking snapshot; offer documents extend that table and leave
-- rows that have no offer version free for a later disbursement booking.

INSERT INTO permissions (code, description, system_permission) VALUES
    ('LOAN_OFFER_VIEW', 'View loan offers, schedules, and agreements', TRUE),
    ('LOAN_OFFER_MANAGE', 'Issue, revise, and cancel loan offers and agreement templates', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('LOAN_OFFER_VIEW', 'LOAN_OFFER_MANAGE')
WHERE r.code IN ('SYSTEM_ADMIN', 'CREDIT_OFFICER');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'LOAN_OFFER_VIEW'
WHERE r.code IN ('AUDITOR', 'RELATIONSHIP_OFFICER', 'BRANCH_MANAGER');

CREATE SEQUENCE loan_offer_reference_seq AS BIGINT START WITH 1;

CREATE TABLE loan_offers (
    id                  UUID PRIMARY KEY,
    reference           VARCHAR(14)    NOT NULL,
    application_id      UUID           NOT NULL REFERENCES loan_applications (id),
    customer_id         UUID           NOT NULL REFERENCES customers (id),
    status              VARCHAR(16)    NOT NULL,
    current_version_id  UUID,
    expires_at          TIMESTAMPTZ    NOT NULL,
    accepted_at         TIMESTAMPTZ,
    declined_at         TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    decision_reason     VARCHAR(500),
    version             INTEGER        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(128)   NOT NULL,
    updated_by          VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_loan_offers_reference UNIQUE (reference),
    CONSTRAINT uq_loan_offers_application UNIQUE (application_id),
    CONSTRAINT ck_loan_offers_reference CHECK (reference ~ '^OF[0-9]{12}$'),
    CONSTRAINT ck_loan_offers_status CHECK (status IN (
        'DRAFT', 'ISSUED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_loan_offers_version CHECK (version >= 0),
    CONSTRAINT ck_loan_offers_acceptance CHECK (
        (status = 'ACCEPTED' AND accepted_at IS NOT NULL)
        OR (status <> 'ACCEPTED' AND accepted_at IS NULL)),
    CONSTRAINT ck_loan_offers_declined CHECK (
        (status = 'DECLINED' AND declined_at IS NOT NULL AND decision_reason IS NOT NULL)
        OR (status <> 'DECLINED' AND declined_at IS NULL)),
    CONSTRAINT ck_loan_offers_cancelled CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND decision_reason IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL))
);

CREATE INDEX idx_loan_offers_customer ON loan_offers (customer_id, created_at DESC);
CREATE INDEX idx_loan_offers_expiry ON loan_offers (status, expires_at);

CREATE TABLE loan_offer_versions (
    id               UUID PRIMARY KEY,
    offer_id         UUID         NOT NULL REFERENCES loan_offers (id),
    version_number   INTEGER      NOT NULL,
    fingerprint      VARCHAR(64)     NOT NULL,
    engine_version   VARCHAR(32)  NOT NULL,
    superseded_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(128) NOT NULL,
    CONSTRAINT uq_loan_offer_versions_number UNIQUE (offer_id, version_number),
    CONSTRAINT ck_loan_offer_versions_number CHECK (version_number >= 1),
    CONSTRAINT ck_loan_offer_versions_fingerprint CHECK (fingerprint = lower(fingerprint) AND char_length(fingerprint) = 64),
    CONSTRAINT ck_loan_offer_versions_engine CHECK (char_length(engine_version) > 0)
);

CREATE INDEX idx_loan_offer_versions_offer ON loan_offer_versions (offer_id, version_number DESC);

ALTER TABLE loan_offers
    ADD CONSTRAINT fk_loan_offers_current_version
    FOREIGN KEY (current_version_id) REFERENCES loan_offer_versions (id);

CREATE TABLE loan_offer_terms (
    id                       UUID PRIMARY KEY,
    offer_version_id         UUID           NOT NULL REFERENCES loan_offer_versions (id),
    customer_id              UUID           NOT NULL,
    customer_name            VARCHAR(255)   NOT NULL,
    customer_reference       VARCHAR(11)    NOT NULL,
    application_reference    VARCHAR(14)    NOT NULL,
    product_id               UUID           NOT NULL,
    product_code             VARCHAR(32)    NOT NULL,
    product_name             VARCHAR(160)   NOT NULL,
    product_version_id       UUID           NOT NULL,
    principal                NUMERIC(19, 6) NOT NULL,
    currency_code            CHAR(3)        NOT NULL,
    interest_rate            NUMERIC(8, 4)  NOT NULL,
    interest_method          VARCHAR(32)    NOT NULL,
    interest_period          VARCHAR(16)    NOT NULL,
    tenure                   INTEGER        NOT NULL,
    tenure_unit              VARCHAR(16)    NOT NULL,
    repayment_frequency      VARCHAR(16)    NOT NULL,
    day_count_convention     VARCHAR(16)    NOT NULL,
    rounding_precision       INTEGER        NOT NULL,
    rounding_method          VARCHAR(16)    NOT NULL,
    grace_period_days        INTEGER        NOT NULL,
    instalment_amount        NUMERIC(19, 6) NOT NULL,
    processing_fees          NUMERIC(19, 6) NOT NULL,
    other_fees               NUMERIC(19, 6) NOT NULL,
    taxes                    NUMERIC(19, 6) NOT NULL,
    tax_rate                 NUMERIC(8, 4)  NOT NULL,
    total_interest           NUMERIC(19, 6) NOT NULL,
    total_repayment          NUMERIC(19, 6) NOT NULL,
    net_disbursement         NUMERIC(19, 6) NOT NULL,
    disbursement_date        DATE           NOT NULL,
    engine_version           VARCHAR(32)    NOT NULL,
    fee_breakdown            JSONB          NOT NULL,
    disbursement_conditions  TEXT           NOT NULL,
    collateral_details       TEXT           NOT NULL,
    guarantor_details        TEXT           NOT NULL,
    default_provisions       TEXT           NOT NULL,
    CONSTRAINT uq_loan_offer_terms_version UNIQUE (offer_version_id),
    CONSTRAINT ck_loan_offer_terms_money CHECK (
        principal > 0 AND processing_fees >= 0 AND other_fees >= 0 AND taxes >= 0
        AND total_interest >= 0 AND total_repayment >= principal AND net_disbursement >= 0),
    CONSTRAINT ck_loan_offer_terms_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_loan_offer_terms_rate CHECK (interest_rate >= 0 AND interest_rate <= 100 AND tax_rate >= 0 AND tax_rate <= 100),
    CONSTRAINT ck_loan_offer_terms_method CHECK (interest_method IN ('FLAT', 'REDUCING_BALANCE')),
    CONSTRAINT ck_loan_offer_terms_period CHECK (interest_period IN ('ANNUAL', 'MONTHLY')),
    CONSTRAINT ck_loan_offer_terms_tenure CHECK (tenure >= 1 AND tenure <= 6000),
    CONSTRAINT ck_loan_offer_terms_unit CHECK (tenure_unit IN ('DAYS', 'WEEKS', 'MONTHS')),
    CONSTRAINT ck_loan_offer_terms_frequency CHECK (repayment_frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_loan_offer_terms_day_count CHECK (day_count_convention IN ('ACTUAL_365', 'ACTUAL_360', 'THIRTY_360')),
    CONSTRAINT ck_loan_offer_terms_rounding CHECK (
        rounding_precision BETWEEN 0 AND 6 AND rounding_method IN ('HALF_UP', 'HALF_EVEN')),
    CONSTRAINT ck_loan_offer_terms_fees CHECK (jsonb_typeof(fee_breakdown) = 'array')
);

CREATE TABLE loan_offer_schedule_items (
    id                  UUID PRIMARY KEY,
    offer_version_id    UUID           NOT NULL REFERENCES loan_offer_versions (id),
    instalment_number   INTEGER        NOT NULL,
    due_date            DATE           NOT NULL,
    opening_balance     NUMERIC(19, 6) NOT NULL,
    principal_due       NUMERIC(19, 6) NOT NULL,
    interest_due        NUMERIC(19, 6) NOT NULL,
    instalment_amount   NUMERIC(19, 6) NOT NULL,
    closing_balance     NUMERIC(19, 6) NOT NULL,
    CONSTRAINT uq_loan_offer_schedule_item UNIQUE (offer_version_id, instalment_number),
    CONSTRAINT ck_loan_offer_schedule_number CHECK (instalment_number >= 1),
    CONSTRAINT ck_loan_offer_schedule_money CHECK (
        opening_balance >= 0 AND principal_due >= 0 AND interest_due >= 0
        AND instalment_amount >= 0 AND closing_balance >= 0)
);

CREATE INDEX idx_loan_offer_schedule_version ON loan_offer_schedule_items (offer_version_id, instalment_number);

CREATE TABLE loan_offer_history (
    id               UUID PRIMARY KEY,
    offer_id         UUID         NOT NULL REFERENCES loan_offers (id),
    from_status      VARCHAR(16),
    to_status        VARCHAR(16)  NOT NULL,
    version_number   INTEGER      NOT NULL,
    reason           VARCHAR(500),
    actor_id         UUID,
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_offer_history_status CHECK (
        to_status IN ('DRAFT', 'ISSUED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')
        AND (from_status IS NULL OR from_status IN ('DRAFT', 'ISSUED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED'))),
    CONSTRAINT ck_loan_offer_history_version CHECK (version_number >= 1)
);

CREATE INDEX idx_loan_offer_history_offer ON loan_offer_history (offer_id, occurred_at);

CREATE TABLE loan_agreement_templates (
    id               UUID PRIMARY KEY,
    code             VARCHAR(40)  NOT NULL,
    name             VARCHAR(160) NOT NULL,
    kind             VARCHAR(16)  NOT NULL,
    version_number   INTEGER      NOT NULL,
    body             TEXT         NOT NULL,
    active           BOOLEAN      NOT NULL,
    checksum_sha256  VARCHAR(64)     NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(128) NOT NULL,
    CONSTRAINT uq_loan_agreement_templates_version UNIQUE (code, version_number),
    CONSTRAINT ck_loan_agreement_templates_code CHECK (code = upper(code) AND char_length(code) >= 2),
    CONSTRAINT ck_loan_agreement_templates_kind CHECK (kind IN ('LOAN', 'GUARANTOR', 'COLLATERAL')),
    CONSTRAINT ck_loan_agreement_templates_version CHECK (version_number >= 1),
    CONSTRAINT ck_loan_agreement_templates_body CHECK (char_length(body) > 0),
    CONSTRAINT ck_loan_agreement_templates_checksum CHECK (
        checksum_sha256 = lower(checksum_sha256) AND char_length(checksum_sha256) = 64)
);

CREATE UNIQUE INDEX uq_loan_agreement_templates_one_active
    ON loan_agreement_templates (code)
    WHERE active = TRUE;

CREATE UNIQUE INDEX uq_loan_agreement_templates_one_active_kind
    ON loan_agreement_templates (kind)
    WHERE active = TRUE;

CREATE INDEX idx_loan_agreement_templates_kind ON loan_agreement_templates (kind, active, created_at DESC);

ALTER TABLE loan_agreements
    ADD COLUMN offer_version_id   UUID,
    ADD COLUMN agreement_kind     VARCHAR(16),
    ADD COLUMN template_id        UUID,
    ADD COLUMN template_version   INTEGER,
    ADD COLUMN document_version   INTEGER,
    ADD COLUMN title              VARCHAR(200),
    ADD COLUMN file_name          VARCHAR(255),
    ADD COLUMN content_type       VARCHAR(80),
    ADD COLUMN storage_key        VARCHAR(500),
    ADD COLUMN checksum_sha256    VARCHAR(64),
    ADD COLUMN size_bytes         BIGINT,
    ADD COLUMN rendered_text      TEXT,
    ADD COLUMN superseded_at      TIMESTAMPTZ,
    ADD COLUMN created_by         VARCHAR(128);

ALTER TABLE loan_agreements
    ADD CONSTRAINT fk_loan_agreements_offer_version
        FOREIGN KEY (offer_version_id) REFERENCES loan_offer_versions (id),
    ADD CONSTRAINT fk_loan_agreements_template
        FOREIGN KEY (template_id) REFERENCES loan_agreement_templates (id),
    ADD CONSTRAINT ck_loan_agreements_offer_document CHECK (
        offer_version_id IS NULL
        OR (
            agreement_kind IN ('LOAN', 'GUARANTOR', 'COLLATERAL')
            AND template_id IS NOT NULL
            AND template_version >= 1
            AND document_version >= 1
            AND checksum_sha256 = lower(checksum_sha256)
            AND char_length(checksum_sha256) = 64
            AND storage_key IS NOT NULL
            AND size_bytes > 0
            AND rendered_text IS NOT NULL
        ));

CREATE UNIQUE INDEX uq_loan_agreements_offer_kind
    ON loan_agreements (offer_version_id, agreement_kind)
    WHERE offer_version_id IS NOT NULL;

CREATE INDEX idx_loan_agreements_offer_version ON loan_agreements (offer_version_id);

CREATE TABLE loan_agreement_acceptances (
    id                              UUID PRIMARY KEY,
    agreement_id                    UUID         NOT NULL REFERENCES loan_agreements (id),
    offer_id                        UUID         NOT NULL REFERENCES loan_offers (id),
    customer_id                     UUID         NOT NULL REFERENCES customers (id),
    accepted_at                     TIMESTAMPTZ  NOT NULL,
    identity_verification_reference VARCHAR(128) NOT NULL,
    kyc_status                      VARCHAR(32)  NOT NULL,
    signature_provider              VARCHAR(64)  NOT NULL,
    signature_assurance             VARCHAR(32)  NOT NULL,
    signature_reference             VARCHAR(64)  NOT NULL,
    disclaimer                      VARCHAR(2000) NOT NULL,
    channel                         VARCHAR(16),
    ip_address                      VARCHAR(64),
    user_agent                      VARCHAR(512),
    actor_id                        UUID         NOT NULL,
    CONSTRAINT uq_loan_agreement_acceptances_agreement UNIQUE (agreement_id),
    CONSTRAINT ck_loan_agreement_acceptances_kyc CHECK (kyc_status = 'VERIFIED'),
    CONSTRAINT ck_loan_agreement_acceptances_assurance CHECK (signature_assurance = 'RECORDED_ACCEPTANCE'),
    CONSTRAINT ck_loan_agreement_acceptances_reference CHECK (char_length(btrim(identity_verification_reference)) > 0)
);

CREATE INDEX idx_loan_agreement_acceptances_offer ON loan_agreement_acceptances (offer_id, accepted_at DESC);

CREATE TRIGGER trg_loan_offers_set_updated_at
    BEFORE UPDATE ON loan_offers
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE OR REPLACE FUNCTION reject_loan_offer_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.application_id IS DISTINCT FROM OLD.application_id
        OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
        OR NEW.reference IS DISTINCT FROM OLD.reference THEN
        RAISE EXCEPTION 'loan offer identity cannot change';
    END IF;
    IF NEW.version < OLD.version THEN
        RAISE EXCEPTION 'loan offer version cannot move backwards';
    END IF;
    IF NEW.version = OLD.version AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.current_version_id IS DISTINCT FROM OLD.current_version_id
        OR NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
        OR NEW.declined_at IS DISTINCT FROM OLD.declined_at
        OR NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at
        OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
    ) THEN
        RAISE EXCEPTION 'loan offer changes require a new version';
    END IF;
    IF OLD.status = NEW.status THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status IN ('ISSUED', 'EXPIRED', 'CANCELLED') THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'ISSUED' AND NEW.status IN ('ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED') THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('EXPIRED', 'CANCELLED') AND NEW.status = 'ISSUED' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'loan offer cannot move from % to %', OLD.status, NEW.status;
END;
$$;

CREATE TRIGGER trg_loan_offers_status
    BEFORE UPDATE ON loan_offers
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_offer_rewrite();

CREATE OR REPLACE FUNCTION reject_loan_offer_version_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'loan offer versions cannot be deleted';
    END IF;
    IF NEW.offer_id IS DISTINCT FROM OLD.offer_id
        OR NEW.version_number IS DISTINCT FROM OLD.version_number
        OR NEW.fingerprint IS DISTINCT FROM OLD.fingerprint
        OR NEW.engine_version IS DISTINCT FROM OLD.engine_version
        OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'loan offer version terms cannot change';
    END IF;
    IF OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at THEN
        RAISE EXCEPTION 'a superseded offer version cannot be reopened';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_offer_versions_lock
    BEFORE UPDATE OR DELETE ON loan_offer_versions
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_offer_version_rewrite();

CREATE OR REPLACE FUNCTION reject_loan_offer_child_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'loan offer terms, schedule, and history are append-only';
END;
$$;

CREATE TRIGGER trg_loan_offer_terms_lock
    BEFORE UPDATE OR DELETE ON loan_offer_terms
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_offer_child_rewrite();

CREATE TRIGGER trg_loan_offer_schedule_lock
    BEFORE UPDATE OR DELETE ON loan_offer_schedule_items
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_offer_child_rewrite();

CREATE TRIGGER trg_loan_offer_history_lock
    BEFORE UPDATE OR DELETE ON loan_offer_history
    FOR EACH ROW
    EXECUTE FUNCTION reject_loan_offer_child_rewrite();

CREATE OR REPLACE FUNCTION reject_offer_agreement_document_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.offer_version_id IS DISTINCT FROM OLD.offer_version_id
        OR NEW.agreement_kind IS DISTINCT FROM OLD.agreement_kind
        OR NEW.template_id IS DISTINCT FROM OLD.template_id
        OR NEW.checksum_sha256 IS DISTINCT FROM OLD.checksum_sha256
        OR NEW.storage_key IS DISTINCT FROM OLD.storage_key
        OR NEW.rendered_text IS DISTINCT FROM OLD.rendered_text
        OR NEW.size_bytes IS DISTINCT FROM OLD.size_bytes THEN
        RAISE EXCEPTION 'an agreement document cannot be rewritten';
    END IF;
    IF OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at THEN
        RAISE EXCEPTION 'a superseded agreement cannot be reopened';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_agreements_offer_document
    BEFORE UPDATE ON loan_agreements
    FOR EACH ROW
    EXECUTE FUNCTION reject_offer_agreement_document_rewrite();

CREATE OR REPLACE FUNCTION reject_agreement_acceptance_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'agreement acceptance is immutable';
END;
$$;

CREATE TRIGGER trg_loan_agreement_acceptances_lock
    BEFORE UPDATE OR DELETE ON loan_agreement_acceptances
    FOR EACH ROW
    EXECUTE FUNCTION reject_agreement_acceptance_rewrite();

CREATE OR REPLACE FUNCTION reject_agreement_template_rewrite()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM loan_agreements WHERE template_id = OLD.id) THEN
            RAISE EXCEPTION 'an agreement template that has been used cannot be deleted';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.code IS DISTINCT FROM OLD.code
        OR NEW.kind IS DISTINCT FROM OLD.kind
        OR NEW.version_number IS DISTINCT FROM OLD.version_number
        OR NEW.body IS DISTINCT FROM OLD.body
        OR NEW.checksum_sha256 IS DISTINCT FROM OLD.checksum_sha256
        OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'agreement template text is versioned and cannot be rewritten';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_loan_agreement_templates_lock
    BEFORE UPDATE OR DELETE ON loan_agreement_templates
    FOR EACH ROW
    EXECUTE FUNCTION reject_agreement_template_rewrite();

COMMENT ON TABLE loan_offers IS 'One current offer for an application. Status changes are versioned; material terms live on loan_offer_versions.';
COMMENT ON TABLE loan_offer_versions IS 'Immutable commercial fingerprint produced by the loan calculation engine.';
COMMENT ON TABLE loan_offer_terms IS 'Customer, product, money, and narrative terms for one offer version.';
COMMENT ON TABLE loan_offer_schedule_items IS 'Repayment schedule copied from the calculation engine for one offer version.';
COMMENT ON TABLE loan_agreement_templates IS 'Configurable agreement text. A change inserts a new version.';
COMMENT ON TABLE loan_agreement_acceptances IS 'Electronic acceptance of one agreement version. This is not a qualified digital signature.';
COMMENT ON COLUMN loan_agreements.offer_version_id IS 'Set when the row is an offer document. Null rows remain available for loan booking snapshots.';
COMMENT ON COLUMN loan_agreements.checksum_sha256 IS 'SHA-256 of the stored PDF. Retrieval rejects a file that does not match.';
