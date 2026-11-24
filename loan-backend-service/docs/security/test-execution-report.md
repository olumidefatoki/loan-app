# Final test execution report

Date: 2026-11-24. Command host: local workstation, Java 26 compiling to release 21. Working directory: `loan-backend-service`.

This report records what actually ran. It does not declare the service production ready. Open items R1 through R5 in `docs/security/risk-register.md` still block that claim.

## Commands and results

| Command | Result |
| --- | --- |
| `mvn -B verify` | BUILD SUCCESS. Surefire: 140 tests, 0 failures, 0 errors, 0 skipped. Failsafe: 52 tests, 0 failures, 0 errors, 0 skipped. The verify lifecycle also packaged `target/loan-backend-service.jar` |
| `docker build -t loan-backend-service:epic15 .` | Exit 0. Image `loan-backend-service:epic15`. Manifest list `sha256:855ff5a300fa71b6c7111148418897d8dfde39995c8214464a2f4b2a88e40ccc` |

Flyway applied migrations V1 through V14 on the Testcontainers PostgreSQL 16 database used by the integration tests. `ReportingIT` and `ServicingIT` passed, including the disbursement export and the chart-of-accounts seed (11 accounts, including write-off expense 5100).

## What the automated suite covers

- API integration against PostgreSQL 16, RabbitMQ 3.13, and Redis 7 via Testcontainers: authentication, customers, products, applications, credit, collateral, approval, offers, servicing, collections, reporting, platform operations, and the foundation health contract.
- WireMock for the HTTP core banking client. Unconfigured credit bureau, BVN, NIN, and notification adapters are asserted to make no outbound call.
- Security unit tests: JWT algorithm pinning, rate-limit TTL, production startup guard, webhook signature, and actuator access. `PlatformFoundationIT` checks anonymous denial of `/actuator/prometheus` and a 200 response for the ops account, including a `jvm_` series.
- Financial unit tests: concurrent disbursement and repayment post once, a stale webhook is rejected, and unknown provider outcomes are not submitted again.

## Defects fixed before this green run

- Report SQL that selected `(posted_at AT TIME ZONE ?)::date` was rejected by PostgreSQL unless the source timestamp was also in `GROUP BY`. Exports of that shape returned 500.
- `/actuator/prometheus` was not registered. Spring Boot 3.5 defaults endpoint access to `none`, and `@SpringBootTest` sets `management.defaults.metrics.export.enabled=false`. The scrape endpoint is now read-only, and Prometheus export is explicitly enabled.
- The chart-of-accounts assertion counted the ten accounts from V11 and omitted the V13 write-off account 5100.

## Not executed in this session

| Check | Why it is still open |
| --- | --- |
| OWASP Dependency-Check | The `dependency-check` profile needs `NVD_API_KEY`. CI fails the build at CVSS 7 and above once that key exists |
| Trivy filesystem and image scan | Configured on the `image` job. Not run locally |
| SonarQube quality gate | The job runs only when `SONAR_HOST_URL` and `SONAR_TOKEN` are set |
| k6 profile `target` (1,000 concurrent users, read p95 under 2 seconds, application submission under 5 seconds) | `performance/k6/lending.js` is the script. It was not executed against a staging stack, so the latency targets are not evidenced |
| Database restore drill | Procedures are in the disaster-recovery runbook. No restore was performed |

Money-moving k6 scenarios are read-only GETs so a load run cannot create a second disbursement or repayment. Duplicate-posting protection is covered by the concurrent unit tests, not by the load script.
