# Disaster recovery runbook

## Backup

- PostgreSQL: nightly `pg_dump --format=custom` plus continuous WAL archiving where the database host provides it. Retain dumps for the evidence retention period configured on the platform.
- Document bytes under `CUSTOMER_DOCUMENT_DIR`, agreement files, and report exports are not in PostgreSQL. Snapshot those volumes with the database backup.
- Redis holds rate-limit counters and cache. It is rebuilt from PostgreSQL and does not need a point-in-time restore.
- RabbitMQ holds undelivered integration messages. After a broker loss, the outbox publisher sends rows still marked pending.

Test a restore every quarter onto an isolated host. A backup that has not been restored is not evidence of recovery.

## Recovery

1. Declare the incident and stop disbursement and repayment traffic.
2. Restore PostgreSQL to the last consistent point.
3. Restore the document volume taken at the same time.
4. Start Redis and RabbitMQ empty, then start the application.
5. Readiness must be `UP` before traffic returns.
6. Run core banking enquiry for every instruction left in `UNKNOWN` or `SUBMITTED`. Do not submit those instructions again.
7. Reconcile the restored ledger with core banking postings. Record mismatches before reopening the channels.

## Targets

The current single-region deployment does not have a measured recovery-time or recovery-point objective. Set those with the infrastructure owner before calling the service production ready. Until a restore test has been completed on the production backup path, recovery is unverified.
