-- Loan servicing, repayment allocation, and double-entry accounting.

ALTER TABLE core_banking_transactions
    ADD COLUMN servicing_collection BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN core_banking_transactions.servicing_collection IS
    'True when loan servicing authorised the cash collection. Interest and fees can make it larger than remaining principal, so the principal cap is not applied. A confirmed amount still reduces later direct principal capacity.';

CREATE SEQUENCE loan_account_reference_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE loan_repayment_reference_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE journal_reference_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE loan_accounts (
    id                          UUID PRIMARY KEY,
    reference                   VARCHAR(14)    NOT NULL,
    application_id              UUID           NOT NULL UNIQUE REFERENCES loan_applications (id),
    customer_id                 UUID           NOT NULL REFERENCES customers (id),
    branch_id                   UUID           REFERENCES branches (id),
    product_id                  UUID           NOT NULL REFERENCES loan_products (id),
    product_version_id          UUID           NOT NULL REFERENCES loan_product_versions (id),
    offer_id                    UUID           NOT NULL REFERENCES loan_offers (id),
    currency_code               CHAR(3)        NOT NULL DEFAULT 'NGN',
    status                      VARCHAR(32)    NOT NULL,
    original_principal          NUMERIC(19, 6) NOT NULL,
    outstanding_principal       NUMERIC(19, 6) NOT NULL,
    accrued_interest            NUMERIC(19, 6) NOT NULL,
    outstanding_interest        NUMERIC(19, 6) NOT NULL,
    outstanding_fees            NUMERIC(19, 6) NOT NULL,
    outstanding_penalties       NUMERIC(19, 6) NOT NULL,
    overpayment                 NUMERIC(19, 6) NOT NULL,
    disbursement_date           DATE           NOT NULL,
    maturity_date               DATE           NOT NULL,
    accrual_start               DATE           NOT NULL,
    last_accrued_through        DATE           NOT NULL,
    grace_period_days           INTEGER        NOT NULL,
    penalty_grace_days          INTEGER        NOT NULL,
    penalty_basis               VARCHAR(32)    NOT NULL,
    penalty_rate                NUMERIC(8, 4)  NOT NULL,
    penalty_amount              NUMERIC(19, 6) NOT NULL,
    early_repayment_rule        VARCHAR(32)    NOT NULL,
    early_repayment_fee_rate    NUMERIC(8, 4),
    interest_method             VARCHAR(32)    NOT NULL,
    interest_period             VARCHAR(16)    NOT NULL,
    interest_rate               NUMERIC(8, 4)  NOT NULL,
    repayment_frequency         VARCHAR(16)    NOT NULL,
    day_count_convention        VARCHAR(16)    NOT NULL,
    rounding_scale              INTEGER        NOT NULL,
    rounding_method             VARCHAR(16)    NOT NULL,
    accrual_frequency           VARCHAR(16)    NOT NULL,
    repayment_day_of_month      INTEGER,
    holiday_rule                VARCHAR(32)    NOT NULL,
    core_disbursement_reference VARCHAR(32)    NOT NULL,
    current_schedule_id         UUID,
    schedule_version            INTEGER        NOT NULL,
    version                     INTEGER        NOT NULL,
    activated_at                TIMESTAMPTZ,
    closed_at                   TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by                  VARCHAR(128)   NOT NULL,
    CONSTRAINT uq_loan_accounts_reference UNIQUE (reference),
    CONSTRAINT ck_loan_accounts_reference CHECK (reference ~ '^LA[0-9]{12}$'),
    CONSTRAINT ck_loan_accounts_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_loan_accounts_status CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'OVERDUE', 'CLOSED')),
    CONSTRAINT ck_loan_accounts_money CHECK (
        original_principal > 0
        AND outstanding_principal >= 0
        AND accrued_interest >= 0
        AND outstanding_interest >= 0
        AND outstanding_fees >= 0
        AND outstanding_penalties >= 0
        AND overpayment >= 0
        AND accrued_interest >= outstanding_interest),
    CONSTRAINT ck_loan_accounts_closed CHECK (
        status <> 'CLOSED'
        OR (outstanding_principal = 0 AND outstanding_interest = 0 AND outstanding_fees = 0 AND outstanding_penalties = 0)),
    CONSTRAINT ck_loan_accounts_method CHECK (interest_method IN ('FLAT', 'REDUCING_BALANCE')),
    CONSTRAINT ck_loan_accounts_period CHECK (interest_period IN ('ANNUAL', 'MONTHLY')),
    CONSTRAINT ck_loan_accounts_frequency CHECK (repayment_frequency IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_loan_accounts_day_count CHECK (day_count_convention IN ('ACTUAL_365', 'ACTUAL_360', 'THIRTY_360')),
    CONSTRAINT ck_loan_accounts_rounding CHECK (rounding_scale BETWEEN 0 AND 6 AND rounding_method IN ('HALF_UP', 'HALF_EVEN')),
    CONSTRAINT ck_loan_accounts_accrual CHECK (accrual_frequency IN ('DAILY', 'MONTHLY')),
    CONSTRAINT ck_loan_accounts_holiday CHECK (holiday_rule IN ('NONE', 'NEXT_BUSINESS_DAY', 'PREVIOUS_BUSINESS_DAY', 'MODIFIED_FOLLOWING')),
    CONSTRAINT ck_loan_accounts_penalty CHECK (penalty_basis IN ('NONE', 'FLAT_AMOUNT', 'PERCENTAGE_OF_OVERDUE', 'PERCENTAGE_OF_INSTALMENT')),
    CONSTRAINT ck_loan_accounts_early CHECK (early_repayment_rule IN ('ALLOWED', 'ALLOWED_WITH_FEE', 'NOT_ALLOWED')),
    CONSTRAINT ck_loan_accounts_version CHECK (version >= 0 AND schedule_version >= 1 AND grace_period_days >= 0 AND penalty_grace_days >= 0)
);

CREATE INDEX idx_loan_accounts_customer ON loan_accounts (customer_id, status);

CREATE TABLE loan_repayment_schedules (
    id                    UUID PRIMARY KEY,
    loan_account_id       UUID         NOT NULL REFERENCES loan_accounts (id),
    version_number        INTEGER      NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    previous_schedule_id  UUID,
    engine_version        VARCHAR(32)  NOT NULL,
    servicing_version     VARCHAR(32)  NOT NULL,
    fingerprint           VARCHAR(64)  NOT NULL,
    parameters            JSONB        NOT NULL,
    reason                VARCHAR(500) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(128) NOT NULL,
    CONSTRAINT uq_loan_repayment_schedules_version UNIQUE (loan_account_id, version_number),
    CONSTRAINT ck_loan_repayment_schedules_status CHECK (status IN ('CURRENT', 'SUPERSEDED')),
    CONSTRAINT ck_loan_repayment_schedules_version CHECK (version_number >= 1),
    CONSTRAINT ck_loan_repayment_schedules_fingerprint CHECK (fingerprint = lower(fingerprint) AND char_length(fingerprint) = 64),
    CONSTRAINT ck_loan_repayment_schedules_parameters CHECK (jsonb_typeof(parameters) = 'object')
);

CREATE UNIQUE INDEX uq_loan_schedule_current
    ON loan_repayment_schedules (loan_account_id)
    WHERE status = 'CURRENT';

ALTER TABLE loan_accounts
    ADD CONSTRAINT fk_loan_accounts_current_schedule
    FOREIGN KEY (current_schedule_id) REFERENCES loan_repayment_schedules (id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE loan_schedule_items (
    id                    UUID PRIMARY KEY,
    schedule_id           UUID           NOT NULL REFERENCES loan_repayment_schedules (id),
    instalment_number     INTEGER        NOT NULL,
    contractual_due_date  DATE           NOT NULL,
    due_date              DATE           NOT NULL,
    opening_balance       NUMERIC(19, 6) NOT NULL,
    principal_due         NUMERIC(19, 6) NOT NULL,
    interest_due          NUMERIC(19, 6) NOT NULL,
    instalment_amount     NUMERIC(19, 6) NOT NULL,
    closing_balance       NUMERIC(19, 6) NOT NULL,
    principal_paid        NUMERIC(19, 6) NOT NULL,
    interest_paid         NUMERIC(19, 6) NOT NULL,
    penalty_accrued       NUMERIC(19, 6) NOT NULL,
    status                VARCHAR(16)    NOT NULL,
    CONSTRAINT uq_loan_schedule_items_number UNIQUE (schedule_id, instalment_number),
    CONSTRAINT ck_loan_schedule_items_money CHECK (
        opening_balance >= 0 AND principal_due >= 0 AND interest_due >= 0 AND instalment_amount >= 0
        AND closing_balance >= 0 AND principal_paid >= 0 AND interest_paid >= 0 AND penalty_accrued >= 0
        AND principal_paid <= principal_due),
    CONSTRAINT ck_loan_schedule_items_status CHECK (status IN ('SCHEDULED', 'PARTIAL', 'OVERDUE', 'PAID', 'WAIVED'))
);

CREATE TABLE loan_account_balances (
    id                      UUID PRIMARY KEY,
    loan_account_id         UUID           NOT NULL REFERENCES loan_accounts (id),
    value_date              DATE           NOT NULL,
    outstanding_principal   NUMERIC(19, 6) NOT NULL,
    accrued_interest        NUMERIC(19, 6) NOT NULL,
    outstanding_interest    NUMERIC(19, 6) NOT NULL,
    outstanding_fees        NUMERIC(19, 6) NOT NULL,
    outstanding_penalties   NUMERIC(19, 6) NOT NULL,
    overpayment             NUMERIC(19, 6) NOT NULL,
    reason                  VARCHAR(64)    NOT NULL,
    posted_at               TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_account_balances_account ON loan_account_balances (loan_account_id, value_date, posted_at);

CREATE TABLE loan_account_events (
    id               UUID PRIMARY KEY,
    loan_account_id  UUID         NOT NULL REFERENCES loan_accounts (id),
    event_type       VARCHAR(64)  NOT NULL,
    payload          JSONB        NOT NULL,
    actor_id         UUID,
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_loan_account_events_account ON loan_account_events (loan_account_id, occurred_at);

CREATE TABLE repayment_allocation_policies (
    id              UUID PRIMARY KEY,
    version_number  INTEGER      NOT NULL UNIQUE,
    priority        VARCHAR(200) NOT NULL,
    effective_from  DATE         NOT NULL,
    approved_by     VARCHAR(128) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_repayment_allocation_policies_version CHECK (version_number >= 1)
);

CREATE TABLE loan_repayments (
    id                         UUID PRIMARY KEY,
    reference                  VARCHAR(14)    NOT NULL UNIQUE,
    loan_account_id            UUID           NOT NULL REFERENCES loan_accounts (id),
    idempotency_key            VARCHAR(64)    NOT NULL UNIQUE,
    channel                    VARCHAR(32)    NOT NULL,
    status                     VARCHAR(32)    NOT NULL,
    amount                     NUMERIC(19, 6) NOT NULL,
    value_date                 DATE           NOT NULL,
    settlement                 BOOLEAN        NOT NULL,
    fee_charged                NUMERIC(19, 6) NOT NULL,
    core_transaction_id        UUID,
    core_reference             VARCHAR(32),
    external_reference         VARCHAR(64),
    cash_journal_id            UUID,
    fee_journal_id             UUID,
    allocation_policy_version  INTEGER        NOT NULL,
    version                    INTEGER        NOT NULL,
    failure_reason             VARCHAR(500),
    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    actor_id                   UUID,
    actor_name                 VARCHAR(128),
    CONSTRAINT ck_loan_repayments_reference CHECK (reference ~ '^RP[0-9]{12}$'),
    CONSTRAINT ck_loan_repayments_channel CHECK (channel IN ('BANK_TRANSFER', 'INTERNAL_DEBIT', 'PAYMENT_GATEWAY', 'BRANCH')),
    CONSTRAINT ck_loan_repayments_status CHECK (status IN ('RECEIVED', 'AWAITING_CONFIRMATION', 'FAILED', 'POSTED', 'REVERSAL_PENDING', 'REVERSED')),
    CONSTRAINT ck_loan_repayments_amount CHECK (amount > 0 AND fee_charged >= 0 AND version >= 0),
    CONSTRAINT ck_loan_repayments_key CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,64}$')
);

CREATE INDEX idx_loan_repayments_account ON loan_repayments (loan_account_id, created_at);

CREATE TABLE loan_repayment_allocations (
    id                UUID PRIMARY KEY,
    repayment_id      UUID           NOT NULL REFERENCES loan_repayments (id),
    component         VARCHAR(16)    NOT NULL,
    amount            NUMERIC(19, 6) NOT NULL,
    schedule_item_id  UUID           REFERENCES loan_schedule_items (id),
    currency_code     CHAR(3)        NOT NULL,
    CONSTRAINT ck_loan_repayment_allocations_component CHECK (component IN ('PENALTY', 'FEE', 'INTEREST', 'PRINCIPAL', 'OVERPAYMENT')),
    CONSTRAINT ck_loan_repayment_allocations_amount CHECK (amount > 0),
    CONSTRAINT ck_loan_repayment_allocations_currency CHECK (currency_code = 'NGN')
);

CREATE UNIQUE INDEX uq_loan_repayment_allocations_account
    ON loan_repayment_allocations (repayment_id, component)
    WHERE schedule_item_id IS NULL;

CREATE UNIQUE INDEX uq_loan_repayment_allocations_item
    ON loan_repayment_allocations (repayment_id, component, schedule_item_id)
    WHERE schedule_item_id IS NOT NULL;

CREATE TABLE loan_repayment_history (
    id            UUID PRIMARY KEY,
    repayment_id  UUID         NOT NULL REFERENCES loan_repayments (id),
    from_status   VARCHAR(32)  NOT NULL,
    to_status     VARCHAR(32)  NOT NULL,
    reason        VARCHAR(500),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE loan_charges (
    id               UUID PRIMARY KEY,
    loan_account_id  UUID           NOT NULL REFERENCES loan_accounts (id),
    charge_type      VARCHAR(32)    NOT NULL,
    amount           NUMERIC(19, 6) NOT NULL,
    journal_id       UUID,
    reason           VARCHAR(500)   NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_loan_charges_amount CHECK (amount > 0)
);

CREATE TABLE interest_accruals (
    id                    UUID PRIMARY KEY,
    loan_account_id       UUID           NOT NULL REFERENCES loan_accounts (id),
    accrual_date          DATE           NOT NULL,
    frequency             VARCHAR(16)    NOT NULL,
    component             VARCHAR(16)    NOT NULL,
    amount                NUMERIC(19, 6) NOT NULL,
    day_count_convention  VARCHAR(16)    NOT NULL,
    journal_id            UUID,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_interest_accruals_amount CHECK (amount > 0),
    CONSTRAINT ck_interest_accruals_component CHECK (component IN ('INTEREST', 'PENALTY', 'ADJUSTMENT')),
    CONSTRAINT ck_interest_accruals_frequency CHECK (frequency IN ('DAILY', 'MONTHLY'))
);

CREATE UNIQUE INDEX uq_interest_accruals_day
    ON interest_accruals (loan_account_id, accrual_date, component)
    WHERE component IN ('INTEREST', 'PENALTY');

CREATE TABLE chart_of_accounts (
    id              UUID PRIMARY KEY,
    code            VARCHAR(8)   NOT NULL UNIQUE,
    name            VARCHAR(160) NOT NULL,
    account_type    VARCHAR(16)  NOT NULL,
    normal_balance  VARCHAR(8)   NOT NULL,
    currency_code   CHAR(3)      NOT NULL DEFAULT 'NGN',
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_chart_of_accounts_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE')),
    CONSTRAINT ck_chart_of_accounts_normal CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_chart_of_accounts_currency CHECK (currency_code = 'NGN')
);

CREATE TABLE journal_entries (
    id                  UUID PRIMARY KEY,
    reference           VARCHAR(14)  NOT NULL UNIQUE,
    loan_account_id     UUID         NOT NULL REFERENCES loan_accounts (id),
    event_code          VARCHAR(32)  NOT NULL,
    currency_code       CHAR(3)      NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    reversal_of         UUID,
    requires_approval   BOOLEAN      NOT NULL,
    requested_by        UUID,
    approved_by         UUID,
    posted_at           TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_journal_entries_reference CHECK (reference ~ '^JE[0-9]{12}$'),
    CONSTRAINT ck_journal_entries_status CHECK (status IN ('DRAFT', 'POSTED')),
    CONSTRAINT ck_journal_entries_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_journal_entries_posted CHECK (status <> 'POSTED' OR posted_at IS NOT NULL)
);

CREATE INDEX idx_journal_entries_account ON journal_entries (loan_account_id, created_at DESC);

CREATE TABLE journal_entry_lines (
    id               UUID PRIMARY KEY,
    journal_entry_id UUID           NOT NULL REFERENCES journal_entries (id),
    line_number      INTEGER        NOT NULL,
    account_code     VARCHAR(8)     NOT NULL REFERENCES chart_of_accounts (code),
    direction        VARCHAR(8)     NOT NULL,
    amount           NUMERIC(19, 6) NOT NULL,
    currency_code    CHAR(3)        NOT NULL,
    narration        VARCHAR(500)   NOT NULL,
    CONSTRAINT uq_journal_entry_lines_number UNIQUE (journal_entry_id, line_number),
    CONSTRAINT ck_journal_entry_lines_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_journal_entry_lines_amount CHECK (amount > 0),
    CONSTRAINT ck_journal_entry_lines_currency CHECK (currency_code = 'NGN')
);

CREATE TABLE accounting_posting_rules (
    id                 UUID PRIMARY KEY,
    event_code         VARCHAR(32) NOT NULL,
    line_number        INTEGER     NOT NULL,
    component          VARCHAR(32) NOT NULL,
    direction          VARCHAR(8)  NOT NULL,
    account_code       VARCHAR(8)  NOT NULL REFERENCES chart_of_accounts (code),
    requires_approval  BOOLEAN     NOT NULL,
    version_number     INTEGER     NOT NULL,
    CONSTRAINT uq_accounting_posting_rules UNIQUE (event_code, version_number, line_number),
    CONSTRAINT ck_accounting_posting_rules_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_accounting_posting_rules_version CHECK (version_number >= 1)
);

CREATE TABLE accounting_events (
    id               UUID PRIMARY KEY,
    loan_account_id  UUID         NOT NULL REFERENCES loan_accounts (id),
    event_code       VARCHAR(32)  NOT NULL,
    source_id        UUID,
    journal_id       UUID         REFERENCES journal_entries (id),
    payload          VARCHAR(500) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE transaction_reversals (
    id                    UUID PRIMARY KEY,
    repayment_id          UUID         NOT NULL REFERENCES loan_repayments (id),
    original_journal_id   UUID         NOT NULL UNIQUE REFERENCES journal_entries (id),
    reversal_journal_id   UUID         NOT NULL UNIQUE REFERENCES journal_entries (id),
    reason                VARCHAR(500) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE interest_adjustments (
    id               UUID PRIMARY KEY,
    loan_account_id  UUID           NOT NULL REFERENCES loan_accounts (id),
    amount           NUMERIC(19, 6) NOT NULL,
    reason           VARCHAR(500)   NOT NULL,
    journal_id       UUID           NOT NULL UNIQUE REFERENCES journal_entries (id),
    status           VARCHAR(16)    NOT NULL,
    requested_by     UUID           NOT NULL,
    approved_by      UUID,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_interest_adjustments_status CHECK (status IN ('PENDING', 'POSTED')),
    CONSTRAINT ck_interest_adjustments_amount CHECK (amount <> 0)
);

ALTER TABLE loan_repayments
    ADD CONSTRAINT fk_loan_repayments_cash_journal FOREIGN KEY (cash_journal_id) REFERENCES journal_entries (id),
    ADD CONSTRAINT fk_loan_repayments_fee_journal FOREIGN KEY (fee_journal_id) REFERENCES journal_entries (id);

ALTER TABLE loan_charges
    ADD CONSTRAINT fk_loan_charges_journal FOREIGN KEY (journal_id) REFERENCES journal_entries (id);

ALTER TABLE interest_accruals
    ADD CONSTRAINT fk_interest_accruals_journal FOREIGN KEY (journal_id) REFERENCES journal_entries (id);

CREATE OR REPLACE FUNCTION reject_posted_journal_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'A journal cannot be deleted';
    END IF;
    IF OLD.status = 'POSTED' THEN
        RAISE EXCEPTION 'A posted journal cannot be edited';
    END IF;
    IF NEW.reference IS DISTINCT FROM OLD.reference
        OR NEW.loan_account_id IS DISTINCT FROM OLD.loan_account_id
        OR NEW.event_code IS DISTINCT FROM OLD.event_code
        OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
        OR NEW.reversal_of IS DISTINCT FROM OLD.reversal_of
        OR NEW.requires_approval IS DISTINCT FROM OLD.requires_approval
        OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.status <> 'POSTED' THEN
        RAISE EXCEPTION 'A draft journal can only be posted';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW
    EXECUTE FUNCTION reject_posted_journal_change();

CREATE OR REPLACE FUNCTION reject_journal_line_change()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'A journal line cannot be edited or deleted';
END;
$$;

CREATE TRIGGER trg_journal_entry_lines_immutable
    BEFORE UPDATE OR DELETE ON journal_entry_lines
    FOR EACH ROW
    EXECUTE FUNCTION reject_journal_line_change();

CREATE OR REPLACE FUNCTION assert_journal_balanced()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    debit_total NUMERIC(19, 6);
    credit_total NUMERIC(19, 6);
BEGIN
    IF NEW.status <> 'POSTED' THEN
        RETURN NEW;
    END IF;
    SELECT COALESCE(SUM(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO debit_total, credit_total
      FROM journal_entry_lines
     WHERE journal_entry_id = NEW.id;
    IF debit_total <> credit_total OR debit_total = 0 THEN
        RAISE EXCEPTION 'Journal % does not balance by currency', NEW.reference;
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_journal_entries_balanced
    AFTER INSERT OR UPDATE ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION assert_journal_balanced();

CREATE TRIGGER trg_loan_accounts_set_updated_at
    BEFORE UPDATE ON loan_accounts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_loan_repayments_set_updated_at
    BEFORE UPDATE ON loan_repayments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO chart_of_accounts (id, code, name, account_type, normal_balance) VALUES
    ('10000000-0000-0000-0000-000000001200', '1200', 'Loans receivable - principal', 'ASSET', 'DEBIT'),
    ('10000000-0000-0000-0000-000000001300', '1300', 'Interest receivable', 'ASSET', 'DEBIT'),
    ('10000000-0000-0000-0000-000000001400', '1400', 'Fee receivable', 'ASSET', 'DEBIT'),
    ('10000000-0000-0000-0000-000000001500', '1500', 'Penalty receivable', 'ASSET', 'DEBIT'),
    ('10000000-0000-0000-0000-000000002100', '2100', 'Core banking settlement', 'LIABILITY', 'CREDIT'),
    ('10000000-0000-0000-0000-000000002200', '2200', 'Customer overpayment', 'LIABILITY', 'CREDIT'),
    ('10000000-0000-0000-0000-000000002300', '2300', 'Tax payable', 'LIABILITY', 'CREDIT'),
    ('10000000-0000-0000-0000-000000004100', '4100', 'Fee income', 'INCOME', 'CREDIT'),
    ('10000000-0000-0000-0000-000000004200', '4200', 'Interest income', 'INCOME', 'CREDIT'),
    ('10000000-0000-0000-0000-000000004300', '4300', 'Penalty income', 'INCOME', 'CREDIT');

INSERT INTO accounting_posting_rules (id, event_code, line_number, component, direction, account_code, requires_approval, version_number) VALUES
    (gen_random_uuid(), 'DISBURSEMENT', 1, 'PRINCIPAL', 'DEBIT', '1200', FALSE, 1),
    (gen_random_uuid(), 'DISBURSEMENT', 2, 'FEE', 'CREDIT', '4100', FALSE, 1),
    (gen_random_uuid(), 'DISBURSEMENT', 3, 'TAX', 'CREDIT', '2300', FALSE, 1),
    (gen_random_uuid(), 'DISBURSEMENT', 4, 'NET_CASH', 'CREDIT', '2100', FALSE, 1),
    (gen_random_uuid(), 'INTEREST_ACCRUAL', 1, 'INTEREST', 'DEBIT', '1300', FALSE, 1),
    (gen_random_uuid(), 'INTEREST_ACCRUAL', 2, 'INTEREST', 'CREDIT', '4200', FALSE, 1),
    (gen_random_uuid(), 'INTEREST_REVERSAL', 1, 'INTEREST', 'DEBIT', '4200', TRUE, 1),
    (gen_random_uuid(), 'INTEREST_REVERSAL', 2, 'INTEREST', 'CREDIT', '1300', TRUE, 1),
    (gen_random_uuid(), 'PENALTY_ACCRUAL', 1, 'PENALTY', 'DEBIT', '1500', FALSE, 1),
    (gen_random_uuid(), 'PENALTY_ACCRUAL', 2, 'PENALTY', 'CREDIT', '4300', FALSE, 1),
    (gen_random_uuid(), 'FEE_CHARGE', 1, 'FEE', 'DEBIT', '1400', FALSE, 1),
    (gen_random_uuid(), 'FEE_CHARGE', 2, 'FEE', 'CREDIT', '4100', FALSE, 1),
    (gen_random_uuid(), 'REPAYMENT', 1, 'CASH', 'DEBIT', '2100', FALSE, 1),
    (gen_random_uuid(), 'REPAYMENT', 2, 'PENALTY', 'CREDIT', '1500', FALSE, 1),
    (gen_random_uuid(), 'REPAYMENT', 3, 'FEE', 'CREDIT', '1400', FALSE, 1),
    (gen_random_uuid(), 'REPAYMENT', 4, 'INTEREST', 'CREDIT', '1300', FALSE, 1),
    (gen_random_uuid(), 'REPAYMENT', 5, 'PRINCIPAL', 'CREDIT', '1200', FALSE, 1),
    (gen_random_uuid(), 'REPAYMENT', 6, 'OVERPAYMENT', 'CREDIT', '2200', FALSE, 1),
    (gen_random_uuid(), 'CAPITALISE_INTEREST', 1, 'PRINCIPAL', 'DEBIT', '1200', FALSE, 1),
    (gen_random_uuid(), 'CAPITALISE_INTEREST', 2, 'INTEREST', 'CREDIT', '1300', FALSE, 1);

INSERT INTO repayment_allocation_policies (id, version_number, priority, effective_from, approved_by)
VALUES (gen_random_uuid(), 1, 'PENALTY,FEE,INTEREST,PRINCIPAL', DATE '2020-01-01', 'credit-policy');

COMMENT ON TABLE journal_entries IS 'Posted journals are immutable. A correction is a reversal journal plus a replacement entry.';
COMMENT ON TABLE loan_repayment_schedules IS 'Each schedule keeps the calculation parameters and version that produced it.';
COMMENT ON TABLE loan_repayments IS 'A repayment is posted only after core banking confirms the cash collection.';
