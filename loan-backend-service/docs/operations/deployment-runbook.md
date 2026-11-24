# Deployment runbook

## Preconditions

- The image has passed the CI `verify` and `image` jobs, including the Trivy scan.
- `NVD_API_KEY` Dependency-Check and, where the SonarQube server is connected, the quality gate are green.
- Production values are injected by the approved secrets manager. Do not copy `.env.example` or commit a filled `.env.production.example`.
- `CORE_BANKING_PROVIDER=http`, the provider base URL is HTTPS, operation paths are relative, and the webhook secret is set.
- A database backup from the current release has completed. See the disaster recovery runbook.

## Deploy

1. Publish the scanned image digest. Record the digest in the change ticket.
2. Run the Flyway migration as the migration role before switching traffic. The application also migrates on startup with `validate-on-migrate` and `clean-disabled`.
3. Start the new instances with `SPRING_PROFILES_ACTIVE=production`.
4. Wait until `GET /actuator/health/readiness` returns `UP`. Liveness is `GET /actuator/health/liveness`.
5. Shift traffic only after readiness is `UP` on every new instance.
6. Watch `core_banking_transactions_total{state="UNKNOWN"}` and the API 5xx rate for 30 minutes.

The container entrypoint execs the JVM, so `SIGTERM` starts Spring's graceful shutdown. The shutdown phase budget is 30 seconds and Compose sets `stop_grace_period: 40s`.

## Rollback

1. Stop the shift of traffic.
2. Return traffic to the previous image digest.
3. Do not run `flyway clean`. A forward-only migration that cannot be reversed stays in place; the previous image must be compatible with that schema or the recovery runbook's restore is required.
4. Confirm readiness on the restored instances and confirm no new `UNKNOWN` core banking outcomes.

## Local development

`docker compose up -d` starts PostgreSQL, Redis, and RabbitMQ only. `docker compose --profile app up --build` also starts the application with the local profile. That profile reads `.env`.
