# Security assessment

Assessment date: 2026-11-24. Scope: `loan-backend-service`. This is a code and test review. It is not a penetration test, and it does not by itself make the service production ready.

## Controls already in place

- JDBC uses bound parameters. Dynamic fragments append fixed column lists or null-checked predicates, not raw request text.
- Access tokens are HS256, 15 minutes, and checked against a server-side session, an active user, and permissions loaded from the database. Logout and refresh-token reuse revoke the session.
- Customer and staff routes require a bearer token. Method security and service checks enforce permissions, branch scope, and customer self-access.
- Maker-checker blocks self-approval for product activation, collateral valuation, credit exceptions, configuration changes, and loan decisions. Approval amount is limited by delegation.
- Webhooks require HMAC-SHA256, a timestamp inside the replay window, and a unique message id.
- Disbursement and repayment idempotency keys are unique. A second open disbursement for one application is rejected. Unknown provider outcomes are enquired, not submitted again.
- Uploads accept PDF, JPEG, and PNG after content sniffing, with a size cap and a neutral download name.
- BVN, NIN, and similar identifiers are stored as AES-GCM ciphertext and HMAC hashes. API and audit payloads use masked values.
- Error responses omit stack traces. API documentation is off outside local and development.
- Actuator detail and Prometheus require the ops role. Liveness and readiness are public and hide component details from anonymous callers.
- Core banking TLS verification cannot be disabled. The base URL must be HTTPS except for localhost in tests.

## Findings fixed in this change

| Finding | Severity | Fix |
| --- | --- | --- |
| JWT verification did not pin `alg` to HS256 before the MAC check | High | `JwtService` rejects every algorithm other than HS256, including `none` and `HS384` header swaps |
| Rate-limit counters could lose their TTL when two increments raced | Medium | The counter expiry is set whenever Redis reports no TTL |
| A core banking path could be an absolute URL and retarget the client | High | Base URLs and paths are checked at startup and again before each call. Metadata hosts and credentialed URLs are rejected |
| Browser security headers were left to framework defaults and did not set a strict content security policy | Medium | Responses send `nosniff`, `DENY`, `no-referrer`, `no-store`, and `default-src 'none'` |
| Production could start on the mock core with the local example secrets | High | The production profile requires the HTTP provider, a webhook secret, the API rate limit, and rejects known development secrets |
| Authenticated API traffic had no shared rate limit | Medium | Redis limits each token or anonymous address. Health probes are excluded. Redis failure fails closed. Tests disable the limit |
| Metrics were not exposed for alerting | Medium | Prometheus export is enabled, the endpoint access is read-only, and the scrape path requires the ops role. Spring Boot 3.5 leaves other actuator endpoints at access `none` |
| Disbursement and similar report exports failed with a PostgreSQL grouping error | Medium | Date expressions that use `AT TIME ZONE` also group by the source timestamp, so the export query is valid |
| CI did not run secret, SAST, dependency, or image scans, and the workflow lived only under the service directory | High | The repository `.github/workflows/ci.yml` runs tests, Gitleaks, Semgrep, Dependency-Check, SonarQube when configured, and Trivy |

## Items reviewed and not changed

- CSRF is disabled because the API uses bearer tokens and stateless sessions. The ops account uses HTTP Basic for actuator only. A browser that has cached those ops credentials could replay a request. Keep the ops password out of browsers and restrict the metrics scrape to the monitoring network.
- Login, registration, and password reset already have their own limits. Account lockout remains five failures.
- Credit bureau, BVN, and NIN do not have a vendor HTTP contract. Their adapters do not open a socket. WireMock tests lock that behaviour. Pointing them at a vendor requires a reviewed contract, not a guessed URL.
- Query SQL that concatenates a constant column list is not injection. The Semgrep rule blocks concatenation of request parameters.

## Production readiness

Critical code defects found in this review were fixed and covered by unit tests. The service is still not production ready until the outstanding risk register is closed: a green Dependency-Check and Trivy run, a SonarQube quality gate, a staged k6 run at the stated latency targets, a tested database restore, and production secrets supplied by the secrets manager with a real core banking contract.
