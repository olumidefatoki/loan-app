# Outstanding risk register

| ID | Risk | Status | Owner action |
| --- | --- | --- | --- |
| R1 | OWASP Dependency-Check has not been executed here. It needs `NVD_API_KEY` and fails the CI job on CVSS 7 and above | Open | Add the key and keep the `dependencies` job required |
| R2 | Trivy image and filesystem scans are configured and were not executed in this session | Open | Require the `image` job on the protected branch |
| R3 | SonarQube runs only when `SONAR_HOST_URL` and `SONAR_TOKEN` exist | Open | Connect the server and wait on the quality gate |
| R4 | The 1,000-user k6 profile was not run against a staging stack | Open | Run `K6_PROFILE=target` and keep p95 evidence |
| R5 | Backup restore has no completed production drill, and no recovery-time objective is agreed | Open | Restore a production dump onto an isolated host |
| R6 | Credit bureau, BVN, NIN, and notification providers have no live vendor contract | Accepted for now | Keep the unconfigured adapters until a contract is reviewed |
| R7 | Ops HTTP Basic can be replayed by a browser that cached the credentials | Accepted with control | Use the account only from the scraper, on a private network |
| R8 | API rate limit fails closed when Redis is unavailable, so a Redis outage blocks the API | Accepted | Readiness already depends on Redis. Restore Redis before opening traffic |
| R9 | Gitleaks allowlists the local Compose file, `.env.example`, and tests because those hold disposable developer values | Accepted | Do not reuse those values. Production startup rejects them |

No open item above is a licence to deploy. R1 through R5 block a production-ready claim.
