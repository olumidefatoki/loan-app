# MFB Digital Lending Platform

Java 21 modular monolith for the MFB digital lending platform. This service is the foundation other lending modules build on. Persistence uses JDBC and Flyway. JPA and Hibernate are not part of the platform.

## Prerequisites

- JDK 21
- Maven 3.9+
- Docker, for the local infrastructure and the image build

## Local setup

From `loan-backend-service`:

```bash
cp .env.example .env
docker compose up -d
mvn spring-boot:run
```

`mvn spring-boot:run` activates the `local` profile. That profile reads `.env` from the working directory. The values in `.env.example` match the disposable accounts created by Docker Compose. Do not reuse them outside a developer machine.

Shared development, staging, and production take every credential from the environment. Those profiles have no default passwords. Optional `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD` override the application user when migrations run as a separate role.

| Profile | Purpose | API docs | Log format |
| --- | --- | --- | --- |
| `local` | Developer machine | On | Console |
| `development` | Shared development environment | On | JSON |
| `staging` | Pre-production | Off | JSON |
| `production` | Production | Off | JSON |

Select a profile with `SPRING_PROFILES_ACTIVE`.

## Runtime endpoints

| Method | Path | Access |
| --- | --- | --- |
| `GET` | `/api/v1/system/version` | Public |
| `POST` | `/api/v1/auth/*` | Public customer registration, OTP, login, refresh, and password reset. Logout and `/me` require a bearer token |
| `POST` | `/api/v1/admin/auth/login`, `/mfa/verify`, `/refresh-token` | Public staff authentication. Logout requires a bearer token |
| `GET`/`POST`/`PUT`/`DELETE` | `/api/v1/admin/users`, `/roles`, `/permissions` | Bearer token plus `USER_MANAGE` or `SYSTEM_CONFIGURE` |
| `GET`/`POST`/`PUT` | `/api/v1/customers` | Bearer token. Customers manage their own record. Staff need `CUSTOMER_VIEW` or `CUSTOMER_CREATE`, limited to assigned branches |
| `GET` | `/api/v1/loan-products` | Bearer token. Customers and originators see active products only. `LOAN_PRODUCT_VIEW` sees every status |
| `POST`/`PUT` | `/api/v1/admin/loan-products` | Bearer token plus `LOAN_PRODUCT_MANAGE`. Approval, activation, suspension, and deactivation need `LOAN_PRODUCT_APPROVE` and a different administrator from the submitter |
| `GET`/`POST` | `/api/v1/admin/integrations/core-banking/**` | Bearer token plus `INTEGRATION_VIEW`, `INTEGRATION_OPERATE`, `LOAN_DISBURSE`, or `REPAYMENT_PROCESS` for the matching operation |
| `POST` | `/api/v1/webhooks/core-banking/events` | HMAC signature and a fresh timestamp. No bearer token |
| `GET` | `/actuator/health` | Public status. Component details require the ops account |
| `GET` | `/actuator/health/liveness` | Public |
| `GET` | `/actuator/health/readiness` | Public. Checks database, Redis, and RabbitMQ |
| `GET` | `/v3/api-docs/v1` | Local and development only |
| `GET` | `/swagger-ui/index.html` | Local and development only |

React and Flutter clients use the same authentication and customer contract. Send `Authorization: Bearer` for the access token and send the refresh token in the JSON body. There is no separate mobile flow. Customer calls may send `X-Client-Channel: WEB` or `MOBILE`.

Other actuator endpoints are not exposed. A request without a valid bearer token receives `401`. A token without the required permission receives `403`. The ops account is only for actuator details.

Send `X-Correlation-Id` on a request to keep a trace across the API, outbound HTTP clients, and RabbitMQ messages. Unsafe values are replaced with a UUID. The response always returns the id that was used.

When `OPS_PASSWORD` is set, HTTP Basic with `OPS_USERNAME` (default `ops`) can read full health details. Leave the password unset on a developer machine if you do not need those details.

RabbitMQ management is at `http://localhost:15672` after Compose starts.

## Tests and build

```bash
mvn test
mvn verify -Punit-tests
mvn verify -Pintegration-tests
mvn verify -Pci
```

`mvn test` runs unit tests and does not need Docker. `mvn verify` and the `integration-tests` and `ci` profiles also run the Flyway and stack test, which starts PostgreSQL, Redis, and RabbitMQ with Testcontainers. The enforcer plugin rejects a Java version below 21 and rejects JPA or Hibernate dependencies.

## Container

Infrastructure only:

```bash
docker compose up -d
```

Infrastructure plus the application image:

```bash
docker compose --profile app up --build
```

Image build on its own:

```bash
docker build -t loan-backend-service:local .
```

The image starts with `SPRING_PROFILES_ACTIVE=production` and does not contain credentials. Supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `OPS_USERNAME`, `OPS_PASSWORD`, `JWT_HMAC_SECRET`, `OTP_PEPPER`, `APP_DATA_KEY`, and `CUSTOMER_DOCUMENT_DIR` at runtime. `CUSTOMER_DOCUMENT_DIR` is the private directory used for customer document bytes. `JWT_HMAC_SECRET` and `APP_DATA_KEY` are Base64. The HMAC secret must decode to at least 32 bytes, and the data key must decode to exactly 32 bytes. Optional `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_PASSWORD`, `BOOTSTRAP_ADMIN_PHONE`, and `BOOTSTRAP_ADMIN_MFA_SECRET` create the first system administrator when that email does not already exist.

## Module layout

```text
com.mfb.lending
├── common          API envelope, errors, validation, correlation id
├── config          JDBC, Redis, RabbitMQ, security, OpenAPI
└── module
    ├── system      version endpoint
    ├── auth        customers, staff, sessions, OTP, JWT, and RBAC
    ├── customer    onboarding, profiles, documents, and KYC
    ├── branch      branches table; administration comes in a later epic
    ├── audit       audit_logs table and security audit writes
    └── integration outbox, inbox, and the core banking adapter
```

Add a later epic as `com.mfb.lending.module.<name>` with its own API, application, and persistence packages. Application SQL goes through `JdbcTemplate` or `NamedParameterJdbcTemplate` with bound parameters.

## Foundation schema

Flyway migration `V1__create_foundation_tables.sql` creates:

- `branches`
- `system_configurations`
- `audit_logs`
- `integration_outbox`
- `integration_inbox`

`V2__create_auth_tables.sql` adds `system_users`, `roles`, `permissions`, `user_roles`, `role_permissions`, `user_branches`, `user_sessions`, `refresh_tokens`, `login_attempts`, and `otp_records`. Passwords are Argon2id hashes, refresh tokens and OTP codes are SHA-256 or HMAC hashes, and MFA secrets are AES-GCM ciphertext. Access tokens last 15 minutes. Refresh tokens rotate, and presenting a replaced token revokes that token family.

`V3__create_customer_tables.sql` adds the customer master data and KYC evidence:

- `customers`
- `individual_profiles`
- `sme_profiles`
- `customer_directors`
- `customer_beneficial_owners`
- `customer_authorised_representatives`
- `customer_addresses`
- `customer_accounts`
- `customer_documents`
- `customer_consents`
- `customer_kyc`
- `customer_screening_results`
- `customer_risk_profiles`
- `customer_review_cases`

BVN, NIN, TIN, and account numbers are AES-GCM ciphertext. Duplicate detection uses HMAC-SHA256 hashes. API responses return a masked value. Document bytes stay in private storage; PostgreSQL stores the metadata and SHA-256 checksum. KYC providers are interfaces. Development and test use mock adapters and do not call a vendor URL.

Loan, repayment, and other domain tables are left for their own epics.

`V10__create_core_banking_tables.sql` adds the core banking instruction log: requests, responses, transactions, transaction events, webhook receipts, reconciliation batches, reconciliation items, and integration errors. It reuses `integration_outbox` and `integration_inbox`. The default provider is `mock`. `CORE_BANKING_PROVIDER=http` is inert until `CORE_BANKING_BASE_URL`, an authentication method, and operation paths are supplied. Those paths are not a guessed core banking contract. Do not point a developer machine at a production core.

## Pipeline

`.github/workflows/ci.yml` runs `mvn verify -Pci` and builds the image. `.github/workflows/cd-template.yml` is a manual image-build template. Point it at a registry before using it to publish.
