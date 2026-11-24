-- Reporting snapshots, asynchronous exports, portfolio aggregation, and supporting indexes.
-- Financial totals in the application are read from posted journals.
-- Account 5100 is the write-off expense. No write-off is implied by an uncollectible collection closure.
-- mv_portfolio_position is a current-book aggregate. NULL branches are stored under the zero UUID.
-- The bank-wide reporting snapshot uses branch_key ffffffff-ffff-ffff-ffff-ffffffffffff.

INSERT INTO permissions (code, description, system_permission) VALUES
    ('REPORT_EXPORT', 'Export operational and financial reports inside authorised branches', TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'REPORT_EXPORT'
WHERE r.code IN ('SYSTEM_ADMIN', 'CREDIT_OFFICER', 'OPERATIONS_OFFICER', 'BRANCH_MANAGER');

INSERT INTO chart_of_accounts (id, code, name, account_type, normal_balance) VALUES
    ('10000000-0000-0000-0000-000000005100', '5100', 'Loan write-off expense', 'EXPENSE', 'DEBIT');

CREATE INDEX idx_loan_accounts_reporting ON loan_accounts (branch_id, product_id, status);
CREATE INDEX idx_loan_accounts_disbursement ON loan_accounts (disbursement_date);
CREATE INDEX idx_loan_accounts_maturity ON loan_accounts (maturity_date, status);
CREATE INDEX idx_loan_accounts_closed ON loan_accounts (closed_at) WHERE closed_at IS NOT NULL;
CREATE INDEX idx_loan_applications_created ON loan_applications (created_at);
CREATE INDEX idx_credit_assessments_revision ON credit_assessments (application_id, revision DESC);
CREATE INDEX idx_journal_entries_posted_event ON journal_entries (event_code, posted_at) WHERE status = 'POSTED';
CREATE INDEX idx_journal_lines_account_code ON journal_entry_lines (account_code, journal_entry_id);
CREATE INDEX idx_loan_repayments_status_value ON loan_repayments (status, value_date);
CREATE INDEX idx_loan_schedule_items_due ON loan_schedule_items (due_date, schedule_id);

CREATE TABLE reporting_snapshots (
    id                      UUID PRIMARY KEY,
    snapshot_date           DATE           NOT NULL,
    branch_key              UUID           NOT NULL,
    currency_code           CHAR(3)        NOT NULL DEFAULT 'NGN',
    loan_count              INTEGER        NOT NULL,
    outstanding_principal   NUMERIC(19, 6) NOT NULL,
    outstanding_interest    NUMERIC(19, 6) NOT NULL,
    overdue_principal       NUMERIC(19, 6) NOT NULL,
    par30_ratio             NUMERIC(12, 6) NOT NULL,
    par60_ratio             NUMERIC(12, 6) NOT NULL,
    par90_ratio             NUMERIC(12, 6) NOT NULL,
    default_rate            NUMERIC(12, 6) NOT NULL,
    disbursement_principal  NUMERIC(19, 6) NOT NULL,
    collection_cash         NUMERIC(19, 6) NOT NULL,
    gl_principal            NUMERIC(19, 6) NOT NULL,
    principal_variance      NUMERIC(19, 6) NOT NULL,
    captured_at             TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_reporting_snapshots_day UNIQUE (snapshot_date, branch_key),
    CONSTRAINT ck_reporting_snapshots_currency CHECK (currency_code = 'NGN'),
    CONSTRAINT ck_reporting_snapshots_counts CHECK (loan_count >= 0)
);

COMMENT ON TABLE reporting_snapshots IS
    'Daily portfolio stock and that day''s disbursement and collection totals. The all-F branch key is the bank-wide row. Branch rows use the branch id, or the zero UUID when the loan has no branch.';

CREATE TABLE report_exports (
    id                  UUID PRIMARY KEY,
    report_code         VARCHAR(40)    NOT NULL,
    format              VARCHAR(8)     NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    filters             JSONB          NOT NULL,
    requested_by        UUID           NOT NULL REFERENCES system_users (id),
    requested_by_name   VARCHAR(128)   NOT NULL,
    file_name           VARCHAR(160),
    content_type        VARCHAR(128),
    storage_path        VARCHAR(512),
    row_count           INTEGER,
    token_hash          VARCHAR(64)    NOT NULL,
    error_message       VARCHAR(500),
    expires_at          TIMESTAMPTZ    NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    downloaded_at       TIMESTAMPTZ,
    CONSTRAINT ck_report_exports_format CHECK (format IN ('CSV', 'XLSX', 'PDF')),
    CONSTRAINT ck_report_exports_status CHECK (status IN ('PENDING', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    CONSTRAINT ck_report_exports_filters CHECK (jsonb_typeof(filters) = 'object'),
    CONSTRAINT ck_report_exports_token CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_report_exports_rows CHECK (row_count IS NULL OR row_count >= 0)
);

CREATE INDEX idx_report_exports_pending ON report_exports (status, created_at);
CREATE INDEX idx_report_exports_requester ON report_exports (requested_by, created_at DESC);

COMMENT ON TABLE report_exports IS
    'Asynchronous report files. token_hash is the SHA-256 of the download token. The token is returned once when the export is requested.';

CREATE TABLE reporting_job_runs (
    id             UUID PRIMARY KEY,
    job_name       VARCHAR(64) NOT NULL,
    business_date  DATE        NOT NULL,
    processed      INTEGER     NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reporting_job_runs_processed CHECK (processed >= 0)
);

CREATE INDEX idx_reporting_job_runs_day ON reporting_job_runs (job_name, business_date);

CREATE MATERIALIZED VIEW mv_portfolio_position AS
SELECT
    COALESCE(la.branch_id, '00000000-0000-0000-0000-000000000000'::uuid) AS branch_id,
    la.product_id,
    app.customer_segment,
    COALESCE(risk.risk_category, 'UNCLASSIFIED') AS risk_category,
    CASE WHEN EXISTS (
        SELECT 1 FROM loan_collaterals lc
        WHERE lc.application_id = la.application_id AND lc.status = 'ACTIVE'
    ) THEN 'SECURED' ELSE 'UNSECURED' END AS security_class,
    la.status,
    COUNT(*)::integer AS loan_count,
    COALESCE(SUM(la.outstanding_principal), 0) AS outstanding_principal,
    COALESCE(SUM(la.outstanding_interest), 0) AS outstanding_interest,
    COALESCE(SUM(la.outstanding_fees), 0) AS outstanding_fees,
    COALESCE(SUM(la.outstanding_penalties), 0) AS outstanding_penalties,
    COALESCE(SUM(la.original_principal), 0) AS original_principal
FROM loan_accounts la
JOIN loan_applications app ON app.id = la.application_id
LEFT JOIN LATERAL (
    SELECT ca.risk_category
    FROM credit_assessments ca
    WHERE ca.application_id = la.application_id AND ca.risk_category IS NOT NULL
    ORDER BY ca.revision DESC, ca.updated_at DESC
    LIMIT 1
) risk ON TRUE
GROUP BY 1, 2, 3, 4, 5, 6
WITH DATA;

CREATE UNIQUE INDEX uq_mv_portfolio_position
    ON mv_portfolio_position (branch_id, product_id, customer_segment, risk_category, security_class, status);

COMMENT ON MATERIALIZED VIEW mv_portfolio_position IS
    'Current loan-book totals by branch, product, segment, risk, security, and status. Refresh replaces this aggregate; it is not the general ledger.';
