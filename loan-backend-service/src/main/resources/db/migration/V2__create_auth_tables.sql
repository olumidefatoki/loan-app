-- Authentication, sessions, and role-based access control.
-- Passwords, OTPs, and refresh tokens are stored only as one-way hashes.
-- MFA secrets are stored only as ciphertext.

CREATE TABLE system_users (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type           VARCHAR(16)  NOT NULL,
    customer_type            VARCHAR(16)  NOT NULL DEFAULT 'NONE',
    full_name                VARCHAR(255) NOT NULL,
    business_name            VARCHAR(255),
    email                    VARCHAR(255) NOT NULL,
    phone_number             VARCHAR(32)  NOT NULL,
    password_hash            VARCHAR(255) NOT NULL,
    status                   VARCHAR(32)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified           BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verified           BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_count       INTEGER      NOT NULL DEFAULT 0,
    locked_until             TIMESTAMPTZ,
    password_changed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    password_expires_at      TIMESTAMPTZ,
    mfa_enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    mfa_secret_encrypted     TEXT,
    mfa_last_accepted_step   BIGINT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by               VARCHAR(128),
    updated_by               VARCHAR(128),
    CONSTRAINT uq_system_users_email UNIQUE (email),
    CONSTRAINT uq_system_users_phone UNIQUE (phone_number),
    CONSTRAINT ck_system_users_principal CHECK (principal_type IN ('CUSTOMER', 'STAFF')),
    CONSTRAINT ck_system_users_customer_type CHECK (customer_type IN ('INDIVIDUAL', 'SME', 'NONE')),
    CONSTRAINT ck_system_users_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED')),
    CONSTRAINT ck_system_users_shape CHECK (
        (principal_type = 'CUSTOMER' AND customer_type IN ('INDIVIDUAL', 'SME'))
        OR (principal_type = 'STAFF' AND customer_type = 'NONE')
    ),
    CONSTRAINT ck_system_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_system_users_failed CHECK (failed_login_count >= 0),
    CONSTRAINT ck_system_users_password_hash CHECK (char_length(password_hash) >= 50),
    CONSTRAINT ck_system_users_mfa_step CHECK (mfa_last_accepted_step IS NULL OR mfa_last_accepted_step >= 0)
);

CREATE INDEX idx_system_users_status ON system_users (status, principal_type);

CREATE TABLE roles (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                    VARCHAR(64)  NOT NULL,
    name                    VARCHAR(128) NOT NULL,
    description             VARCHAR(500),
    requires_mfa            BOOLEAN      NOT NULL DEFAULT FALSE,
    unrestricted_branches   BOOLEAN      NOT NULL DEFAULT FALSE,
    system_role             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_roles_code UNIQUE (code),
    CONSTRAINT ck_roles_code CHECK (code = upper(code) AND char_length(btrim(code)) > 0)
);

CREATE TABLE permissions (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 VARCHAR(64)  NOT NULL,
    description          VARCHAR(500) NOT NULL,
    system_permission    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_permissions_code UNIQUE (code),
    CONSTRAINT ck_permissions_code CHECK (code = upper(code) AND char_length(btrim(code)) > 0)
);

CREATE TABLE user_roles (
    user_id      UUID        NOT NULL REFERENCES system_users (id),
    role_id      UUID        NOT NULL REFERENCES roles (id),
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_by  VARCHAR(128),
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role ON user_roles (role_id);

CREATE TABLE role_permissions (
    role_id        UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id  UUID NOT NULL REFERENCES permissions (id),
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX idx_role_permissions_permission ON role_permissions (permission_id);

CREATE TABLE user_branches (
    user_id         UUID        NOT NULL REFERENCES system_users (id),
    branch_id       UUID        NOT NULL REFERENCES branches (id),
    primary_branch  BOOLEAN     NOT NULL DEFAULT FALSE,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, branch_id)
);

CREATE INDEX idx_user_branches_branch ON user_branches (branch_id);

CREATE TABLE user_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES system_users (id),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_reason  VARCHAR(128),
    CONSTRAINT ck_user_sessions_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX idx_user_sessions_user ON user_sessions (user_id, status, created_at DESC);

CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID        NOT NULL REFERENCES user_sessions (id),
    user_id      UUID        NOT NULL REFERENCES system_users (id),
    family_id    UUID        NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_hash CHECK (token_hash = lower(token_hash) AND char_length(token_hash) = 64)
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE login_attempts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES system_users (id),
    identifier      VARCHAR(255) NOT NULL,
    principal_type  VARCHAR(16)  NOT NULL,
    success         BOOLEAN      NOT NULL,
    failure_reason  VARCHAR(64),
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_login_attempts_principal CHECK (principal_type IN ('CUSTOMER', 'STAFF'))
);

CREATE INDEX idx_login_attempts_identifier ON login_attempts (identifier, occurred_at DESC);
CREATE INDEX idx_login_attempts_ip ON login_attempts (ip_address, occurred_at DESC);
CREATE INDEX idx_login_attempts_user ON login_attempts (user_id, occurred_at DESC);

CREATE TABLE otp_records (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES system_users (id),
    purpose        VARCHAR(32)  NOT NULL,
    channel        VARCHAR(16)  NOT NULL,
    destination    VARCHAR(255) NOT NULL,
    code_hash      CHAR(64)     NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    attempt_count  INTEGER      NOT NULL DEFAULT 0,
    max_attempts   INTEGER      NOT NULL DEFAULT 5,
    consumed_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_otp_purpose CHECK (purpose IN ('PHONE_VERIFICATION', 'EMAIL_VERIFICATION', 'PASSWORD_RESET', 'LOGIN_MFA')),
    CONSTRAINT ck_otp_channel CHECK (channel IN ('SMS', 'EMAIL', 'APP')),
    CONSTRAINT ck_otp_attempts CHECK (attempt_count >= 0 AND max_attempts > 0),
    CONSTRAINT ck_otp_hash CHECK (code_hash = lower(code_hash) AND char_length(code_hash) = 64)
);

CREATE INDEX idx_otp_records_lookup ON otp_records (user_id, purpose, created_at DESC);

CREATE TRIGGER trg_system_users_set_updated_at
    BEFORE UPDATE ON system_users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_roles_set_updated_at
    BEFORE UPDATE ON roles
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_permissions_set_updated_at
    BEFORE UPDATE ON permissions
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO permissions (code, description, system_permission) VALUES
    ('CUSTOMER_VIEW', 'View customer records inside authorised branches', TRUE),
    ('CUSTOMER_CREATE', 'Create customer records', TRUE),
    ('LOAN_APPLICATION_VIEW', 'View loan applications', TRUE),
    ('LOAN_APPLICATION_CREATE', 'Create loan applications', TRUE),
    ('LOAN_APPROVE', 'Approve a loan application', TRUE),
    ('LOAN_REJECT', 'Reject a loan application', TRUE),
    ('LOAN_DISBURSE', 'Disburse an approved loan', TRUE),
    ('REPAYMENT_VIEW', 'View repayments', TRUE),
    ('REPAYMENT_PROCESS', 'Process a repayment', TRUE),
    ('COLLATERAL_VERIFY', 'Verify collateral', TRUE),
    ('REPORT_VIEW', 'View operational reports', TRUE),
    ('USER_MANAGE', 'Administer staff accounts, roles assignments, and sessions', TRUE),
    ('SYSTEM_CONFIGURE', 'Change platform security configuration and privileged roles', TRUE);

INSERT INTO roles (code, name, description, requires_mfa, unrestricted_branches, system_role) VALUES
    ('CUSTOMER', 'Customer', 'Self-service individual or SME customer', FALSE, FALSE, TRUE),
    ('RELATIONSHIP_OFFICER', 'Relationship officer', 'Originates customers and loan applications in assigned branches', FALSE, FALSE, TRUE),
    ('CREDIT_OFFICER', 'Credit officer', 'Assesses, approves, rejects, and disburses loans', TRUE, FALSE, TRUE),
    ('OPERATIONS_OFFICER', 'Operations officer', 'Processes repayments and views reports', FALSE, FALSE, TRUE),
    ('BRANCH_MANAGER', 'Branch manager', 'Manages staff and customers inside assigned branches', TRUE, FALSE, TRUE),
    ('AUDITOR', 'Auditor', 'Read-only access for control reviews', TRUE, TRUE, TRUE),
    ('SYSTEM_ADMIN', 'System administrator', 'Platform administration across all branches', TRUE, TRUE, TRUE);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'CUSTOMER_VIEW', 'CUSTOMER_CREATE', 'LOAN_APPLICATION_VIEW', 'LOAN_APPLICATION_CREATE', 'COLLATERAL_VERIFY')
WHERE r.code = 'RELATIONSHIP_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'CUSTOMER_VIEW', 'LOAN_APPLICATION_VIEW', 'LOAN_APPROVE', 'LOAN_REJECT', 'LOAN_DISBURSE',
    'COLLATERAL_VERIFY', 'REPORT_VIEW')
WHERE r.code = 'CREDIT_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('REPAYMENT_VIEW', 'REPAYMENT_PROCESS', 'REPORT_VIEW')
WHERE r.code = 'OPERATIONS_OFFICER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'CUSTOMER_VIEW', 'CUSTOMER_CREATE', 'USER_MANAGE', 'LOAN_APPLICATION_VIEW', 'REPORT_VIEW')
WHERE r.code = 'BRANCH_MANAGER';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'CUSTOMER_VIEW', 'LOAN_APPLICATION_VIEW', 'REPAYMENT_VIEW', 'REPORT_VIEW')
WHERE r.code = 'AUDITOR';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'SYSTEM_ADMIN';

COMMENT ON TABLE system_users IS 'Customer and staff identities. password_hash is Argon2id. mfa_secret_encrypted is AES-GCM ciphertext.';
COMMENT ON TABLE refresh_tokens IS 'SHA-256 hashes of opaque refresh tokens. Reuse of a rotated token revokes the token family.';
COMMENT ON TABLE otp_records IS 'HMAC-SHA256 hashes of one-time codes. The plaintext code is never stored.';
COMMENT ON TABLE login_attempts IS 'Authentication attempts for monitoring and lockout. Passwords are not stored.';
COMMENT ON TABLE user_sessions IS 'Server-side sessions. Access tokens are accepted only while the session is active.';
