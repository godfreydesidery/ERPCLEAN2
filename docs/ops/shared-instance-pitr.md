# Shared server: point-in-time restore

*MULTITENANCY-PLAN.md D-5 / R-6, owner decision 2026-10-31: **whole-instance point-in-time restore +
per-tenant export + a tested per-tenant delete. Restoring ONE tenant to an earlier state is a documented
support procedure (restore into a scratch database, repair that tenant), not a product feature.***
Stack: [`infra/shared/`](../../infra/shared/). Scripts: [`infra/shared/pitr/`](../../infra/shared/pitr/).
Server runbook: [shared-instance.md](shared-instance.md).

Read this first: **a database restore on this server is whole-instance.** Restoring to 10:00 takes every
customer back to 10:00. They lose everything entered since, and re-issue invoice and receipt numbers that
customers already hold. That is why section 5 needs a named sign-off and section 6 exists.

## 0 - What is protected, and how far

| | |
|---|---|
| Mechanism | Postgres continuous WAL archiving (`archive_mode=on`, plain `cp` to the `wal-archive` volume) plus scheduled physical base backups (`pg_basebackup`, plain format, `-X stream`, verified with `pg_verifybackup`) in the `basebackups` volume. No extra software: everything is in the `postgres:15-alpine` image. |
| Data loss on a crash or an instance restore to "latest" | Up to `PG_ARCHIVE_TIMEOUT` (default **300 s**) of the newest transactions: a WAL segment is archived when full or after that timeout. On a busy server segments fill and archive much faster. Lower the setting for a tighter bound (each forced switch on an idle server archives a 16 MB file). |
| Restore window | From the oldest kept base backup to now. `BASEBACKUP_KEEP` (default 7) x backup interval (daily) = **7 days**. |
| Pruning | `prune.sh` (run by every `base-backup.sh`) keeps the newest `BASEBACKUP_KEEP` complete backups (never fewer than 2), then deletes archived WAL older than the oldest kept backup. It never touches `*.partial` directories. |
| NOT protected | Loss of the host or its disk, if the backup volumes are on it. See section 1. The JWT key and `.env` (back those up separately; they are not in the database). |

## 1 - Put the backups somewhere else (required before go-live)

The compose file creates `erp-shared-wal-archive` and `erp-shared-basebackups` as Docker named volumes
because that works everywhere, but **on the same disk as the database they protect against a bad deploy or
a wrong `DELETE`, not against a dead disk or a lost server.** Before the first customer:

1. Put the two volumes on a separate physical disk or volume (for example a second EBS volume mounted at
   `/mnt/erp-backup`, then `docker volume create --driver local --opt type=none --opt o=bind --opt
   device=/mnt/erp-backup/wal-archive erp-shared-wal-archive` before the first `up`), **and**
2. copy both off the host on a schedule (object storage or another server). Example, after the daily
   base backup:
   ```sh
   rsync -a --delete-after /mnt/erp-backup/ backup-host:/srv/erp-shared-backup/
   ```
   WAL files are write-once and the archive grows between runs, so copy it more often than the base
   backups (for example every 5-15 minutes) if you want the off-host RPO to match the on-host one.
3. Encrypt at rest and restrict access: **these volumes hold every customer's ledger, payroll and
   customer list** (same warning as `infra/prod/backup.sh`). Their handling is a contractual question for
   all tenants.

## 2 - Schedule and monitor

Cron on the host (as a user in the `docker` group); adjust the checkout path:

```cron
15 2 * * *  cd /opt/erp && sh infra/shared/pitr/base-backup.sh >> /var/log/erp-base-backup.log 2>&1
```

Also take one by hand before every tenant creation and every upgrade (shared-instance.md sections 4, 6).

**Watch these; the failure mode is silent until the disk fills.** If `archive_command` keeps failing,
Postgres **keeps every WAL file in `pg_wal` and the database volume fills until the server stops.**

```sh
# archiver health - last_failed_time must not be newer than last_archived_time
docker compose -f infra/shared/docker-compose.yml exec db psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "select archived_count, failed_count, last_archived_wal, last_archived_time, last_failed_time from pg_stat_archiver"
# size of the two backup volumes
docker run --rm -v erp-shared-wal-archive:/a:ro -v erp-shared-basebackups:/b:ro postgres:15-alpine du -sh /a /b
# the newest complete base backup (must be < 26 h old)
docker compose -f infra/shared/docker-compose.yml exec -T db sh -c 'ls -d /basebackups/base_*Z | tail -1'
```

Alert on: `failed_count` increasing, `last_archived_time` older than `PG_ARCHIVE_TIMEOUT` x 3 while the
server is in use, the backup volumes over 80% full, no new `base_*` in 26 hours.

## 3 - Restore to a timestamp, into a SCRATCH instance

This never touches production: it creates a separate container and volume, mounts the WAL archive
**read-only**, and turns archiving **off** in the scratch instance so it cannot write into the production
archive. It runs alongside production. Use it to rehearse, to check what a point in time looked like, for
the one-tenant procedure (section 6), and as step 1 of a whole-instance restore (section 5).

```sh
cd /opt/erp
export POSTGRES_USER=erp                 # the superuser name from infra/shared/.env
TARGET_TIME='2026-11-07 10:15:00+00' sh infra/shared/pitr/restore-pitr.sh
```

- `TARGET_TIME` must carry a zone; use UTC (`+00`). `TARGET_TIME=latest` replays all archived WAL.
  Database timestamps are UTC (`hibernate.jdbc.time_zone: UTC`).
- The script picks the newest base backup that **completed before** the target and fails with the
  earliest available time if there is none. Force one with `BASE=base_<stamp>`.
- The scratch Postgres listens on **`127.0.0.1:5441`** (`SCRATCH_PORT`), container
  `erp-pitr-scratch-<stamp>` (`SCRATCH_NAME`). It stops at the target in **recovery-paused, read-only**
  state (`ACTION=pause`, default) so you can look before committing. `ACTION=promote` promotes by itself.

Verify (read-only while paused):

```sh
docker exec -it erp-pitr-scratch-<stamp> psql -U "$POSTGRES_USER" -d erp
#   select max(created_at) from audit_logs;                    -- last thing that survived
#   select id, name, alias, status from organisations;
#   select count(*) from sales_invoices where company_id = <id>;
```

If the target was wrong, remove the scratch (below) and run again with another time; it costs nothing.
Promote when satisfied (the scratch instance becomes writable and will not replay further):

```sh
docker exec erp-pitr-scratch-<stamp> psql -U "$POSTGRES_USER" -d erp -c 'select pg_promote()'
```

**Gate-3 use (upgrade rehearsal against this server's real data):** run the new image against the promoted
scratch: `docker run --rm -e SPRING_PROFILES_ACTIVE=prod -e ERP_DB_URL=jdbc:postgresql://host.docker.internal:5441/erp
-e ERP_DB_USER=... -e ERP_DB_PASSWORD=... -e ERP_API_PORT=18081 -e ERP_MANAGEMENT_PORT=19090
-e ERP_JWT_SIGNING_MODE=dev-in-memory ... erpclean2:<version>` (adjust host networking; do **not** use
the production JWT key or ports).

**Clean up when finished. The scratch holds real customer data.**

```sh
docker rm -f erp-pitr-scratch-<stamp> && docker volume rm erp-pitr-scratch-<stamp>-data
```

If recovery fails: the script prints the scratch container's log. The two common causes are a target
earlier than the base backup's end (choose an earlier base with `BASE=`) and a missing WAL segment (the
archive was pruned or damaged: pick a later target or an older base that still has its WAL).

## 4 - Verify a restore

A restore that has not been booted is not verified. In order, on the scratch instance:

1. Row sanity (section 3 queries): the last audit row and last invoice are at or just before the target.
2. `select count(*) from flyway_schema_history where not success;` returns 0, and the newest version
   equals what the running release ships.
3. Boot the release **image** against it (section 3, gate-3 use) and read the log: Flyway validated,
   no pending migration, `Started`, reconcilers quiet. Log in as a tenant admin on the scratch API.
4. Compare per-tenant counts for the key tables with the production export or with the last known figures.

## 5 - Whole-instance restore (every tenant goes back)

**When:** corruption, a bad migration, a destructive mistake that touched more than one tenant, or a
rollback of a release that carried a migration (shared-instance.md section 6). **Not** for one tenant's
problem: use section 6.

**Before you start (record all of these in the incident ticket):**

1. Sign-off by the platform owner (named), with the chosen target time and why.
2. Every tenant told, before the restore, what time they are going back to and that they must re-enter
   or reconcile what happened after it; tills with offline queues must not sync across the restore.
3. Target time chosen from evidence (audit log, the last good report), and a scratch restore at that
   time checked (sections 3-4) **first**. Production is down only for step 6 onward.

**Steps** (all on the server, in the checkout):

1. Safety backup of the current state, so the restore itself is undoable even though it is destructive:
   `sh infra/shared/pitr/base-backup.sh` (note the name). If the database is too damaged to start, skip
   this: the next script keeps a full copy of the current data volume anyway.
2. Scratch restore to the target: section 3. Verify (section 4). Promote it:
   `select pg_promote()`.
3. Maintenance page / stop traffic: `docker compose -f infra/shared/docker-compose.yml stop caddy api`.
4. Make the promoted scratch the live database (does a safety copy, then replaces `db-data`; **does not
   start the API**):
   ```sh
   CONFIRM_REPLACE=erp-shared SCRATCH_NAME=erp-pitr-scratch-<stamp> POSTGRES_USER=erp \
     sh infra/shared/pitr/promote-into-production.sh
   ```
   `CONFIRM_REPLACE` must equal the compose project name (`erp-shared`) - a guard against running it on
   the wrong stack. It prints the name of the undo volume `erp-shared-db-data-before-restore-<stamp>`:
   the pre-restore database, untouched. **Keep it** until the restore is accepted.
5. Check the database (section 4 steps 1-2), then start the API and Caddy:
   `docker compose -f infra/shared/docker-compose.yml up -d api caddy`; read the API log.
6. **Immediately take a new base backup** (`sh infra/shared/pitr/base-backup.sh`). The restore starts a
   new WAL timeline (`timeline 2`); backups from before it still restore, but the new one is your clean
   starting point and `prune.sh` handles both.
7. Tell the tenants it is back, with the restore time. Re-check each tenant's last invoice number against
   their paper.
8. After acceptance (not before), remove the undo volume and the scratch:
   `docker volume rm erp-shared-db-data-before-restore-<stamp> erp-pitr-scratch-<stamp>-data`
   (the scratch container must be removed first).

**Rollback of the restore itself:** `docker compose -f infra/shared/docker-compose.yml stop api caddy db`, then
copy the undo volume back over `erp-shared-db-data` (the same `cp -a` the script uses, in the other
direction), then `up -d`. This returns to the pre-restore state, including whatever was wrong with it.
WAL archived after the restore point on the old timeline stays in the archive; Postgres follows the new
timeline's `.history` file, so later restores are not confused by it.

**Timings to expect:** section 7. Add the time to boot the API (Flyway validate, about 1-2 minutes) and
the time to make the decision; the outage is the restore plus whatever it takes to reach sign-off.

## 6 - Support procedure: put ONE tenant right without touching the others

This is a **careful, manual, audited procedure**, not a feature. Use it when one tenant has lost or
damaged data (a mass delete, a wrong bulk import, a bad price update) and the other tenants must keep
everything they did since. It has been designed on paper plus the machinery rehearsed in section 7; **the
repair steps themselves have not been rehearsed against the ERP schema.** Run the first real one with
two people and take the safety steps literally.

**What it can and cannot do.** It can put back rows of **masters and documents** that were deleted or
overwritten (customers, products, prices, draft/unposted documents, settings). It **cannot** safely roll a
tenant's whole dataset back, because the application's invariants forbid it: posted records are
append-only (`stock_move`, GL postings, `audit_log`; CLAUDE.md invariant 9), and number sequences,
stock balances, GL balances and outbox state must agree with each other and with documents the customer
has already printed or emailed since the restore point. **Corrections to posted data are new postings
made through the application**, never rows copied from the past. If the answer is "everything since
10:00 is wrong", the options are a whole-instance restore (section 5, every tenant) or moving that
tenant to its own stack; not this procedure.

**Roles:** an operator who runs it, a second person who reviews every SQL file before it runs, and the
tenant's contact who confirms the result. The platform owner approves in writing.

1. **Scope.** Write down: tenant (organisation id, alias), what is damaged, the last good time T, which
   tables/rows are to be put back. Suspend the tenant so nobody trades while data is swapped
   (`POST /api/v1/organisations/uid/<uid>/suspend`, shared-instance.md section 5).
2. **Safety backup** of production now: `sh infra/shared/pitr/base-backup.sh`. Note the name.
3. **Scratch restore** to just before the damage (section 3, `TARGET_TIME` = T). Verify (section 4).
   Promote it (`select pg_promote()`): the scratch API needs a writable database to start.
4. **Lift the tenant out of the scratch.** Run a **scratch API of the same release as the scratch
   database's schema** (section 3, gate-3 use), on loopback, and call the per-tenant export
   (R-7) as the platform operator:
   `POST /api/v1/organisations/uid/<uid>/export` - a zip with **one file per table** and a **manifest with
   row counts**. That zip is the tenant's state at T. If the export endpoint is not yet in the release,
   use `\copy (select ...) to ... csv` for the specific tables, filtered by `company_id` /
   `organisation_id`.
5. **Export the same tenant from production now** the same way (state after the damage; also your
   record of what is about to be overwritten). Keep both zips with the incident ticket. They contain
   customer data: store them as you would the backups and delete them when the ticket is closed.
6. **Diff** the two exports per table (manifest counts first, then ids/uids). Decide, table by table,
   what to put back. Prefer **fewer, smaller repairs**. Remember `id`+`uid` duality: restore both, and
   use `ON CONFLICT (id) DO NOTHING` or an explicit `UPDATE ... WHERE id = ...` so a row that changed
   legitimately since T is not overwritten silently.
7. **Write the repair as one SQL file** (inserts/updates in FK order, parents first, in one transaction,
   ending with count queries and `COMMIT` replaced by `ROLLBACK` on the first, rehearsal pass). The
   reviewer reads it line by line. Every statement filters by the tenant's `company_id`/`organisation_id`;
   a statement that cannot be so filtered is a reason to stop.
8. **Rehearse on a second scratch** (a fresh section 3 restore of *now*, or the safety backup): run the
   file with `ROLLBACK`, then with `COMMIT`; read the counts; boot the scratch API and open the affected
   screens as a tenant admin.
9. **Apply to production** with the tenant still suspended: `psql -1 -v ON_ERROR_STOP=1 -f repair.sql`
   with output saved (`| tee repair-<ticket>.log`). Then run the counts again.
10. **Audit.** The application's audit aspect does **not** see manual SQL, so the audit trail for this
    change is: the approved ticket, both exports, `repair.sql`, `repair-<ticket>.log`, the safety backup
    name, and the names of both people. Store them together. If R-7's export is in the release it
    already wrote an audit row per call.
11. **Resume** the tenant, have their contact confirm in the application, and watch the outbox gauge for
    that tenant for a day (`erp_outbox_failed_by_tenant{organisation="<id>"}`): restored rows that
    reference posted events can leave events stuck.
12. **Clean up** the scratch instances and the zips (customer data on a server that is not theirs).

## 7 - The drill, with timings

`infra/shared/pitr/drill.sh` runs the whole machinery on throwaway volumes under its own compose project
(`erpdrill`, volume prefix `erpdrill`), then tears down only that project: it cannot touch `erp-shared-*`,
`erp-db-data` or `localhost:5434`. Run it on the production host before go-live and after any change to
the backup scripts or the Postgres major version: `sh infra/shared/pitr/drill.sh`
(`DRILL_ROWS_A=12000000 ...` for a bigger one).

What it does: start Postgres with archiving; load phase A rows and take a base backup; load phase B rows;
note a timestamp T; load phase C rows; force the segment out; restore to T into a scratch; assert A and B
present and C absent; promote the scratch; run `promote-into-production.sh` into the drill stack and assert
again (and timeline 2); take two more backups with `BASEBACKUP_KEEP=2` and assert pruning left exactly 2
and trimmed the WAL; restore to `latest` from a timeline-2 backup.

**Run on 2026-11-07**, developer laptop (Windows 11, Docker Desktop/WSL2, local SSD), `postgres:15-alpine`.
Synthetic table (one wide table, 4 x md5 payload per row, two indexes) - not the ERP schema.

| | Small run | Large run |
|---|---|---|
| Rows A (before backup) / B (after backup, before T) / C (after T) | 2,000,000 / 200,000 / 50,000 | 12,000,000 / 1,000,000 / 200,000 |
| Database size at backup | **426 MB** | **2,519 MB** |
| Base backup, taken, verified, pruned | 4 s (464 MB on disk) | **17 s** |
| Restore to T into scratch (base copy 6 s, WAL replay 4 s, to "paused at target") | **11 s** | **34 s** (copy 24 s, replay 10 s) |
| Promote scratch into production (stop, safety copy, replace, start db) | 18 s | **50 s** |
| Rows A,B present / C absent after restore | 2,000,000 / 200,000 / 0 | 12,000,000 / 1,000,000 / 0 |
| Whole drill (incl. loading data and the retention test) | 87 s | 285 s |

Reading it:

- **Backup duration**: roughly 7 s per GB on this hardware. **Restore to a point in time into scratch**:
  about 13 s per GB. **Whole-instance restore end to end** (scratch restore, verification by hand not
  counted, promote into production): about **85 s for 2.5 GB**, i.e. a tenant-visible outage of roughly
  **one to two minutes plus API boot plus your decision time** at this size. The big costs on a real
  server are the volume copy (proportional to database size and disk speed) and the human steps. A
  real server's disk is slower than this laptop's SSD; **repeat the drill on the actual host and replace
  these numbers.**
- The drill's replay was about a quarter-million rows of WAL; a 24-hour replay on a busy ERP will take
  longer than 4-10 s. WAL replay is roughly single-threaded; measure it from a base backup that is
  23 hours old on the real host.
- Not measured here: booting the API on the restored database, restoring over a network, and any
  restore of the actual ERP schema. The drill proves the backup/restore machinery and the data
  boundary at the timestamp, not that the application boots on the result: section 4 does that on a
  real restore.

## 8 - Known limits

- **RPO is `PG_ARCHIVE_TIMEOUT`, not zero.** A crash loses up to the last un-archived segment's
  transactions; the base backup volume and archive are a separate failure domain only if you did
  section 1.
- **Same major version only.** Physical backups restore into the same Postgres major version (15). A
  major upgrade needs a fresh base backup afterwards and a plan of its own.
- **Prune and timelines.** `pg_archivecleanup` removes by file name. After a restore starts timeline 2,
  keep at least two backups taken after it before lowering `BASEBACKUP_KEEP` below what you rely on.
- **A restore does not un-send mail, un-print receipts, or recall payments.** Per-tenant restore has the
  same limit and is why section 6 is narrow.
- Tenant deletion on departure (R-8) must be drilled **on a restored copy** using section 3; its backups
  keep the departed tenant's data until they age out of the window. Say so in the contract.
