# ERPCLEAN2 — Backup and Restore Runbook

PostgreSQL is the only datastore.  These procedures use `pg_dump` (custom format,
`-Fc`) and `pg_restore`.  Scripts live in `infra/prod/`.

---

## Scripts

| Script | Purpose |
|---|---|
| `infra/prod/backup.sh` | pg_dump → timestamped `.dump` file; prune old archives |
| `infra/prod/restore.sh` | pg_restore --clean from a named `.dump` file |

Both scripts are POSIX sh and read connection details from environment variables.

---

## Backup

### Environment variables

| Variable | Default | Required |
|---|---|---|
| `PGHOST` | `127.0.0.1` | No |
| `PGPORT` | `5432` | No |
| `PGDATABASE` | `erp` | No |
| `PGUSER` | `erp` | No |
| `PGPASSWORD` | — | **Yes** |
| `BACKUP_DIR` | `/backups` | No |
| `BACKUP_RETAIN_DAYS` | `14` | No |

### Steps — run a manual backup

1. Source the prod env (never echo `PGPASSWORD` in logs):

   ```sh
   set -a && . infra/prod/.env && set +a
   export PGHOST=127.0.0.1 PGPORT=5432 PGDATABASE="${POSTGRES_DB}" \
          PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD}"
   ```

2. Run the backup script (adjust `BACKUP_DIR` to your mount):

   ```sh
   BACKUP_DIR=/backups BACKUP_RETAIN_DAYS=14 sh infra/prod/backup.sh
   ```

3. Verify the `.dump` file was created and is non-zero:

   ```sh
   ls -lh /backups/erpclean2_*.dump | tail -5
   ```

### Steps — run inside the running db container

```sh
docker exec -e PGPASSWORD="<pw>" erp-prod-db \
  pg_dump -U erp -d erp -Fc -Z9 \
  -f /var/lib/postgresql/backups/erpclean2_$(date +%Y%m%d_%H%M%S).dump
```

### Automated (cron example)

```cron
# Daily at 02:00 UTC — adjust PGPASSWORD source to your secret manager
0 2 * * * PGPASSWORD="$(cat /run/secrets/pg-password)" \
  BACKUP_DIR=/backups BACKUP_RETAIN_DAYS=14 \
  sh /opt/erpclean2/infra/prod/backup.sh >> /var/log/erpclean2-backup.log 2>&1
```

---

## Restore

### Pre-restore checklist

- [ ] Confirm you have the correct `.dump` file (timestamp matches the target point-in-time).
- [ ] Stop the API container to prevent Flyway / Hibernate from writing during restore:
      `docker stop erp-prod-api`
- [ ] Notify users of the maintenance window.
- [ ] Take a fresh backup of the CURRENT state (even if corrupted — for forensics):
      `BACKUP_DIR=/backups sh infra/prod/backup.sh`

### Steps

1. Set connection env:

   ```sh
   export PGHOST=127.0.0.1 PGPORT=5432 PGDATABASE=erp \
          PGUSER=erp PGPASSWORD=<pw>
   ```

2. Identify the dump file to restore:

   ```sh
   ls -lh /backups/erpclean2_*.dump
   ```

3. Run restore (5-second abort window in the script):

   ```sh
   DUMP_FILE=/backups/erpclean2_<timestamp>.dump sh infra/prod/restore.sh
   ```

4. Restart the API — Flyway validate runs on startup and confirms the schema matches:

   ```sh
   docker start erp-prod-api
   docker logs -f erp-prod-api  # watch for "Flyway validate passed" or error
   ```

5. Smoke-test:

   ```sh
   curl -sf http://localhost:8081/actuator/health/readiness
   ```

### Rollback story for a failed schema migration

If a Flyway migration fails mid-run and leaves the DB in a broken state:

1. Stop the API: `docker stop erp-prod-api`
2. Restore the backup taken BEFORE the deploy (step 4 above).
3. Fix the migration SQL (backend-engineer).
4. Redeploy with the corrected migration jar.

**Never edit V-prefixed migration files that have already been applied to any
environment — that is a backend-engineer call, not an ops call.**

---

## Backup file layout

```
/backups/
  erpclean2_20260601_020000.dump   ← pg_dump custom format, compressed
  erpclean2_20260602_020000.dump
  ...
```

Files older than `BACKUP_RETAIN_DAYS` are pruned automatically by `backup.sh`.
Keep at least one backup off-host (S3, rsync to a second server, etc.) — the
`/backups` volume is on the same host as the database.
