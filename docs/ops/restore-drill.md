# The restore drill — proving a restore works, and measuring RTO

*Written 2026-08-15, and executed the same day. The measured results are at the bottom.*

Before this drill, **no OrbixERP restore had ever been performed**. When one was actually needed —
the 1.8.0 crash-loop on the live customer — it was avoided, and the database was hand-edited
instead ([release-staging-and-rollback.md](release-staging-and-rollback.md)). So the RTO was not a
number anybody could quote; it was an assumption. This document is how it becomes a number, and
how it is re-measured when anything in the path changes.

It is also the thing [`OPERATIONS.md`](../../dist/bundle/docs/OPERATIONS.md)'s *"practise this
once, on purpose"* points at, so the customer is not left to invent a method.

## What this measures, and what it does not

| | |
|---|---|
| **RTO** | wall-clock from starting the restore to a signed-in, healthy system |
| **RPO** | how much work is lost — the gap between the backup and the failure. Set by the schedule: 24 h at the default 02:00 nightly |
| **Not measured** | detection time (how long before anybody noticed) and decision time (how long before somebody chose to restore). On a real incident these dominate |

> ### This is a WHOLE-DATABASE RTO, not a per-customer one
>
> Both tools drop and re-create the entire schema; there is no tenant filter anywhere
> (**D-5**, reopened by the shared-instance decision). On a shared instance this number is the
> time to take **every** customer back to the backup, not the time to fix one of them. Do not
> quote it in a contract as a per-customer RTO. See
> [backup-restore.md](backup-restore.md#under-a-shared-instance-whose-backup-is-it).

## Rules for running it

1. **A throwaway stack, always.** Its own compose project name, its own containers, its own
   network, its own volume, its own port. Never QA, never production, never the local dev stack.
2. **Never a bare `docker compose down -v`.** Every command carries `-p <drill project>`.
   Teardown removes the drill volume *by name*, and afterwards you assert that the durable
   volumes are still there.
3. **Outside the repository.** Both installers write `.env` (with real generated passwords),
   `secrets/jwt/private.pem` and `backups/` into the folder they run from — and `install.ps1`
   installs **in place**. Copy the bundle to a scratch directory first. Never run either
   installer from `dist/release/`.
4. **Synthetic data only.** If a production-shaped drill is ever needed it follows the
   [rehearsal-stack.md](rehearsal-stack.md) rules — outside the repo, localhost-only, deleted at
   the end.
5. **Time everything from outside the script.** Stamp `date -u +%Y-%m-%dT%H:%M:%SZ` (or
   `Get-Date -Format o`) at every phase boundary and keep the log.

## Setting up the throwaway stack

```bash
DRILL=/path/to/scratch/orbixerp-drill
cp -R dist/release/orbixerp-<version>-amd64 "$DRILL"
cd "$DRILL"
cp .env.example .env
```

Then edit `.env` — **before the first install command**, or compose adopts the default names:

```
ERP_STACK_NAME=orbixerp-drill      # namespaces project, containers, network AND volume
ERP_HTTP_PORT=8099                 # verified free first
ERP_DB_MODE=docker
ERP_BOOTSTRAP_ADMIN_PASSWORD=<something you will type again later>
```

Check nothing collides before you start:

```bash
docker ps -a  --filter 'name=orbixerp-drill' --format '{{.Names}}'   # must be empty
docker volume ls --format '{{.Name}}' | grep orbixerp                # must be empty
(exec 3<>/dev/tcp/127.0.0.1/8099) 2>/dev/null && echo BUSY || echo free
```

If the drill is testing **unreleased** script changes, copy `orbixerp.sh`, `orbixerp.ps1`,
`install.sh`, `install.ps1` and `.env.example` from `dist/bundle/` over the bundle's copies. That
way the drill exercises the scripts you intend to ship against the image the customer will run —
which is gate 4 of [release-staging-and-rollback.md](release-staging-and-rollback.md).

## Drill 1 — in-place recovery, timed

### P1 · Install (this is also the fresh-install rehearsal)

```bash
./install.sh --defaults --no-schedule        # Linux / macOS
.\install.ps1 -Defaults -NoSchedule          # Windows
```

`--no-schedule` because a drill machine should not acquire a nightly job; test the scheduling
separately (below).

This phase is doing double duty. It is the **only** rehearsal of the path customer #2 takes:
Flyway V1→V103 against an empty schema, `R__seed_permissions`, then `BootstrapRunner` — which
fires only when `organisations.count() == 0` and therefore never executes in any other
environment — then `TenancyReconciler`. Record the wall time, whether it reached healthy, and
keep the whole container log.

### P2 · Seed something recognisable

Sign in as `rootadmin` and create a marker you can name — a customer called `DRILL-MARKER-A`.
Then create a **second organisation** through `POST /api/v1/organisations`, and post one document
in each. Two tenants is what makes the shared-instance harm measurable instead of asserted.

### P3 · Baseline

```bash
docker exec -i <drill-db> psql -U erp -d erp -P pager=off \
  -f - < docs/ops/multitenancy-phase0-measurements.sql > baseline.txt
```

### P4 · Back up — this is the RPO boundary

```bash
./orbixerp.sh backup
```

Record the time it took, the filename, the size and the SHA-256.

### P5 · Diverge

**After** the backup, create `DRILL-MARKER-B` in tenant A and `DRILL-MARKER-B2` in tenant B, and
post one more document in **each**. These rows must be gone afterwards. Their absence is the only
proof the restore *replaced* rather than merged — a restore that silently no-ops passes every
other check in this document.

### P6 · Break it

Run both variants separately, each followed by P7 and P8:

- **(a) data loss** — delete rows from a business table with a throwaway `postgres:15-alpine`
  psql joined to the drill network.
- **(b) will not start** — the realistic 1.8.0 shape: stop the API and leave the database in a
  state the application rejects.

### P7 · Restore — **start the clock**

```bash
./orbixerp.sh restore backups/<file> --yes      # -Yes on Windows
```

Timestamp each printed step so the RTO decomposes: safety copy, `DROP SCHEMA`, `pg_restore`,
`up -d`, wait-healthy.

**Stop the clock when `status` reports healthy AND a sign-in returns a token** — not when the
script prints "Restore complete", which precedes neither.

### P8 · Verify — the half that decides whether the number means anything

1. `orbixerp status` → healthy
2. **Sign in as `rootadmin`** — proves `secrets/jwt` survived and sessions still validate
3. `DRILL-MARKER-A` present; `DRILL-MARKER-B` and `DRILL-MARKER-B2` **absent**
4. `select count(*), max(version) from flyway_schema_history` matches the P3 baseline, and
   `select count(*) from flyway_schema_history where success = false` is `0`
5. Container log shows Flyway validate clean, no migration re-applied
6. Re-run the measurements SQL and diff against P3. Every row count must match; only physical
   sizes, `largest_tables` ordering and the environment block may differ
7. **Cross-tenant damage, measured.** Post a fresh document in tenant B and confirm it re-issues
   a number already used before the restore
8. `select tableowner, count(*) from pg_tables where schemaname='public' group by 1` — after
   `--no-owner` everything should belong to the connecting role
9. The `safety-before-restore-*` copy exists, is non-empty, and is classified by housekeeping
   (it used to match no prune pattern at all and lived for ever)

### P9 · Record

Fill in the results table below: date, version, environment, RTO by phase, RPO, database size,
row counts, operator. **Label it with the environment and the script family** — a Windows/Docker
Desktop number measured with `orbixerp.ps1` is not the customer's Linux `orbixerp.sh` number.

### P10 · Tear down, then prove you tore down the right thing

```bash
docker compose -p orbixerp-drill -f docker-compose.yml -f docker-compose.db-docker.yml down -v
docker volume rm orbixerp-drill-db-data 2>/dev/null
rm -rf "$DRILL"

# and then, always:
docker volume ls --format '{{.Name}}' | grep -E 'erp-db-data|erpclean2-dev-db-data|erp-rehearsal-data'
```

All three durable volumes must still be listed.

## Drill 2 — bare-metal recovery (run once, time-boxed)

The claim that `backups/` + `.env` + `secrets/` together are sufficient to rebuild from nothing
has never been tested. Copy **only those three** into a clean directory alongside a fresh unpack
of the bundle, set a **third** stack name and port, install, then restore. Sign in with the
**original** admin password. That is the real disaster RTO, and it includes transferring the
bundle.

## Testing that the schedule actually fires

Separate from the recovery drill, and easy to forget because the installer now makes it look
handled:

- **Linux** — `crontab -l` shows one block per install directory. Re-run the installer and check
  it is still one. Set `--backup-time` to two minutes ahead, wait, then look for a new file in
  `backups/` and a fresh entry in `backups/backup.log`.
- **Windows** — `Get-ScheduledTask -TaskPath '\OrbixERP\'`, then after a run
  `Get-ScheduledTaskInfo ... | Select LastRunTime, LastTaskResult`. **`LastTaskResult` is the
  only thing that distinguishes "scheduled" from "working"**; a task registered as SYSTEM or S4U
  registers cleanly and then fails every night because it cannot reach Docker Desktop's per-user
  engine.

## Traps — all of these are in the shipped scripts, and all cost time

| | Trap |
|---|---|
| T1 | `restore` reads `/dev/tty` (`Read-Host` on Windows). Over SSH it needs `ssh -tt`; under `-NonInteractive` it throws. **Fixed** — `--yes` / `-Yes` added for scripted runs; typing RESTORE is still the default |
| T2 | The supplied backup used to be copied in under its own basename *only if absent*, then restored by that name — so a same-named local file was silently restored instead, reporting success. **Fixed** — staged under a unique name |
| T3 | In `docker` mode, backup needs the stack **running**; `stop` removes the network it needs. `OPERATIONS.md` used to say "stop, then backup". **Fixed** — order corrected |
| T4 | A failed `pg_dump` left a truncated file that looked like a backup; the empty-file guard only ran on the success path. **Fixed** — the part-written file is deleted |
| T5 | Safety copies were never pruned, and the pre-update rollback point *was* pruned at 14 days despite being the only rollback for a migration release. **Fixed** — three classes, three lifetimes |
| T6 | `wait_healthy` waits up to 900 s and the healthcheck's `start_period` is 180 s. A stopwatch must not read "still migrating" as "hung". Its message still says "93 database migrations" — stale at V103, harmless |
| T7 | `pg_restore --no-owner` reassigns ownership to the connecting role. Check `pg_tables.tableowner` after any restore |
| T8 | The safety copy and the new dump both land in `backups/`, on the same disk as the volume, and `DROP SCHEMA` runs **after** the safety dump. That ordering is correct and worth preserving. Nothing used to check headroom — **fixed**, it now refuses to start below 3× the newest dump |
| T9 | `cmd_backup` / `Invoke-Backup` return their path as the **sole** stdout/pipeline output, consumed by `update`. Anything added to them that writes to stdout silently corrupts the rollback filename |
| T10 | `install.ps1` installs **in place** with no volatile-directory guard, unlike `install.sh`. Run from `Downloads`, the keys, `.env` and every backup live there — and the scheduled task points there. Not fixed; reported |

---

## Results

Every row names its environment and script family. **A laptop number is not a production number**,
and must not leave engineering as one.

### Run 1 — 2026-08-15, first restore ever performed on OrbixERP

| | |
|---|---|
| **Bundle** | `orbixerp-1.8.3-amd64` (`BUILD_COMMIT d9d77baa`), image loaded from the bundle tarball — not the source tree |
| **Scripts** | `dist/bundle/` at this change, copied over the bundle's own copies |
| **Environment** | Windows 11 Home laptop, Docker Desktop 28.5.1, `ERP_DB_MODE=docker` |
| **Script family** | **`orbixerp.ps1` (PowerShell).** The customer's box runs `orbixerp.sh` — see the caveat below |
| **Stack** | `ERP_STACK_NAME=orbixerp-drill`, port 8097, own network and volume, torn down afterwards |
| **Database** | 206 tables, 26 MB, Flyway max version 103 (104 history rows), 0 failed. Synthetic |

| Phase | Measured |
|---|---|
| **P1 · fresh install, empty database** | **11 min 48 s** end-to-end — load 3 images (373 MB), Flyway V1→V103, `R__seed_permissions`, `BootstrapRunner`, 22 seeders, healthy. Of which ≈3 min 15 s was waiting for the application after containers started |
| **P4 · backup** | **28.5 s** → 1.09 MB dump (`sha256 CEC80170…97312`) |
| **P7 · restore, script** | **4 min 25 s** — safety copy, `DROP SCHEMA CASCADE`, `pg_restore`, `up -d`, wait-healthy |
| **P7 · RTO (clock stops at a token, not at "Restore complete")** | **≈ 4 min 29 s** |
| **RPO** | **24 h** at the default nightly 02:00 schedule |

**P8 verification — every check passed:**

| Check | Result |
|---|---|
| `status` healthy, sign-in returns a token | yes — so `secrets/jwt` survived and sessions still validate |
| `DRILL-MARKER-A` (pre-backup, deleted by the disaster) | restored |
| `DRILL-MARKER-B`, `DRILL-MARKER-B2` (post-backup) | **gone** — the restore *replaced*, it did not merge |
| Row counts vs baseline | `organisations`, `companies`, `branches`, `app_users`, `roles` (13), `permissions` (252), `drill_marker`, `flyway_schema_history` (104) — **all match** |
| `flyway` max version / failed rows | 103 / **0**; no migration re-applied |
| `pg_tables.tableowner` after `--no-owner` | all `erp` — the connecting role, as intended |
| Table count | 206 = baseline |
| Staged `restore-source-*` file | **0 left behind** — the collision fix cleans up |
| `safety-before-restore-*.dump` | present, 1.09 MB, and now classified by housekeeping |
| Database size | 26 MB → 28 MB. Expected: a restore lays pages out differently. Row counts are what must match |

**Cross-tenant damage, measured rather than asserted.** A second organisation, *Drill Tenant B*,
was created through `POST /api/v1/organisations` **after** the backup — a whole tenant: organisation,
company, branch, its own root admin, and 23 sets of company defaults. After the restore,
`organisations` was back to 1 and Tenant B did not exist in any form. On a shared instance that is
not a hypothetical: **restoring for one customer erases everything the other did since the backup.**

### What this run does NOT establish

| | |
|---|---|
| **Linux** | It ran `orbixerp.ps1`. The customer runs `orbixerp.sh` on Ubuntu. The logic is mirrored and the bash pruning was unit-tested separately, but **the RTO must be re-measured on Linux before it is quoted to anyone** |
| **Scale** | 26 MB / 1.09 MB dump. The live customer measured 35 MB / 1.9 MB at V98 — the same order, so the restore step should be comparable. A database ten times larger is not |
| **Disaster variant (b)** | Only the data-loss variant was run. The "will not start" shape — the realistic 1.8.0 case — was not |
| **Drill 2** | Bare-metal recovery from `backups/` + `.env` + `secrets/` alone has still never been tested |
| **The schedule firing** | Not observed end to end. On this machine Windows refused to register the task at all (below) |
| **Detection and decision time** | Not measured, and on a real incident they dominate |

### Findings from the run itself

1. **`restore` with no argument crashed** with a PowerShell binding error instead of printing usage
   — a regression introduced by the `-Yes` parsing in this same change (`@($null)` is an array
   holding one `$null`, not an empty array). Found by the drill, fixed, re-tested.
2. **`POST /api/v1/organisations` requires `adminUsername`** on 1.8.3; a request without it is
   rejected with `adminUsername: must not be blank`. Worth having in the onboarding runbook, since
   this endpoint is how customer #2 is created.
3. **Windows would not register a scheduled task without elevation.** Measured here: a standard
   user gets `Access is denied` from **both** `Register-ScheduledTask` and `schtasks.exe`, at the
   root task path as well as in a sub-folder. The installer now detects this and prints the two
   ways out. **Assume a Windows customer who did not "Run as administrator" has no schedule.**
4. **The port pre-flight is a race.** Port 8099 was verified free, and something had taken it by
   the time the installer checked five minutes later — the install stopped cleanly and said so,
   which is the right behaviour. Re-check the port immediately before installing, and note that a
   `/dev/tcp` probe to `127.0.0.1` will not see an IPv6-only listener.

### Re-run this when any of these change

The scripts in `dist/bundle/`, the compose files, the PostgreSQL version, the healthcheck timings,
or the database's order of magnitude. And once on Linux, before the number is put in front of a
customer.
