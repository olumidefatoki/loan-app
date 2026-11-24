# Database migration runbook

Flyway owns the schema. Migration files live in `src/main/resources/db/migration`. `clean` is disabled. Validation runs on migrate.

## Before a release

1. Take a logical backup with `pg_dump` of the production database. Store it outside the database host.
2. Restore that backup onto a scratch database and run the new migrations there:

```bash
flyway -url="$SCRATCH_JDBC_URL" -user="$SPRING_FLYWAY_USER" -password="$SPRING_FLYWAY_PASSWORD" migrate
```

3. Confirm `flyway_schema_history` shows the new versions as success and that the application readiness check passes against the scratch database.

## Production

Set `SPRING_FLYWAY_USER` and `SPRING_FLYWAY_PASSWORD` when the migration role is separate from the application role. Otherwise the application user runs Flyway on startup.

On failure, Flyway stops on the failed version. Do not edit a checksum of a migration that has already been applied. Add a new version instead.

## Rollback

Prefer a forward fix. Restore from the pre-release backup only when the failed migration changed data that the previous application cannot read. Restoring loses transactions committed after the backup, so reconcile those with core banking before reopening disbursement and repayment.
