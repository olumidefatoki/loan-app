# Monitoring and alerting

Prometheus scrapes `GET /actuator/prometheus`. The endpoint is not public. The ops basic-auth account, supplied from the secrets manager, is the scraper identity. Health liveness and readiness are public and do not include component details unless that same ops account is used.

Scrape and alert examples are in `deploy/monitoring/prometheus.yml` and `deploy/monitoring/lending-alerts.yml`.

## Signals

| Signal | Meaning |
| --- | --- |
| `up{job="lending-api"}` | The metrics endpoint answered |
| `/actuator/health/liveness` | The process should be restarted when this fails |
| `/actuator/health/readiness` | Database, Redis, and RabbitMQ are usable |
| `http_server_requests_seconds` | API latency and status |
| `core_banking_transactions_total{state="UNKNOWN"}` | An instruction whose provider outcome is not known |
| `core_banking_reconciliation_mismatches_total` | Local and provider amounts disagree |
| `core_banking_dead_letters_total` | An instruction needs an operator |

Logs in development, staging, and production are JSON to stdout and include `correlationId`. Do not log secrets, raw BVN or NIN, or tokens. The application scrubs those fields from audit and notification payloads.

## Performance check

`performance/k6/lending.js` is the load script. The acceptance profile is:

```bash
K6_PROFILE=target BASE_URL=https://staging.example STAFF_TOKEN=... k6 run performance/k6/lending.js
```

The target profile uses 1,000 virtual users on the read-heavy scenarios. Loan submission is tagged separately and is expected under 5 seconds at p95, excluding the external provider call. The script does not post a second financial instruction. A staging run is required before the latency target is treated as met. This repository run did not execute that 1,000-user profile.
