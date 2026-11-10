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
| `GET` | `/actuator/health` | Public status. Component details require the ops account |
| `GET` | `/actuator/health/liveness` | Public |
| `GET` | `/actuator/health/readiness` | Public. Checks database, Redis, and RabbitMQ |
| `GET` | `/v3/api-docs/v1` | Local and development only |
| `GET` | `/swagger-ui/index.html` | Local and development only |

Other actuator endpoints are not exposed. Requests that reach a protected path without the ops account receive `401`.

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

The image starts with `SPRING_PROFILES_ACTIVE=production` and does not contain credentials. Supply `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PASSWORD`, `RABBITMQ_HOST`, `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD`, `OPS_USERNAME`, and `OPS_PASSWORD` at runtime.

## Module layout

```text
com.mfb.lending
├── common          API envelope, errors, validation, correlation id
├── config          JDBC, Redis, RabbitMQ, security, OpenAPI
└── module
    ├── system      version endpoint
    ├── branch      branches table; administration comes in a later epic
    ├── audit       audit_logs table
    └── integration integration_outbox and integration_inbox tables
```

Add a later epic as `com.mfb.lending.module.<name>` with its own API, application, and persistence packages. Application SQL goes through `JdbcTemplate` or `NamedParameterJdbcTemplate` with bound parameters.

## Foundation schema

Flyway migration `V1__create_foundation_tables.sql` creates:

- `branches`
- `system_configurations`
- `audit_logs`
- `integration_outbox`
- `integration_inbox`

Customer, loan, repayment, and other domain tables are left for their own epics.

## Pipeline

`.github/workflows/ci.yml` runs `mvn verify -Pci` and builds the image. `.github/workflows/cd-template.yml` is a manual image-build template. Point it at a registry before using it to publish.
