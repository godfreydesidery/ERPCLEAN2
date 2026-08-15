# Backup and restore — the runbook

PostgreSQL is the only datastore. Everything here is `pg_dump -Fc` and `pg_restore`.

> **⬤ Corrected 2026-08-15.** This document used to describe `infra/prod/backup.sh` and
> `infra/prod/restore.sh` only — **scripts that are not on any customer's machine**. The paying
> customer runs `orbixerp.sh`, which writes different filenames into a different directory and
> reads a differently-named environment variable. An operator following the old page would have
> looked for `erpclean2_*.dump` in `/backups` on a box that has `orbixerp_*.dump` in
> `/opt/orbixerp/backups`. The two tools are now separated below, with a plain statement of which
> one applies where.

## Which tool is on which box

| | **`orbixerp.sh` / `orbixerp.ps1`** | **`infra/prod/*.sh`** |
|---|---|---|
| Where | every customer installation, from `dist/` | the `infra/prod/` compose stack |
| Who runs it | the customer, and the nightly schedule | us, by hand |
| Backup name | `orbixerp_<stamp>.dump` | `erpclean2_<stamp>.dump` |
| Directory | `<install dir>/backups` | `$BACKUP_DIR`, default `/backups` |
| Retention setting | `ERP_BACKUP_RETAIN_DAYS` (+ four more, below) | `BACKUP_RETAIN_DAYS` |
| Scheduled | **yes** — by the installer | no |
| Restore method | `DROP SCHEMA public CASCADE` then `pg_restore` | `pg_restore --clean --if-exists` |

**The customer's box is the first column.** Kilimanjaro Supermarket runs `sudo ./orbixerp.sh
backup` in `/opt/orbixerp`. Reach for the second column only when working on an `infra/prod/`
stack, and check which one you are on before quoting a filename to anyone.

---

## The customer path — `orbixerp.sh` / `orbixerp.ps1`

### Scheduling

The installer creates it, and has since this change:

- **Linux** — a block in the install-directory owner's crontab, marked
  `# >>> orbixerp backup [<dir>] ... >>>`. Keyed on the directory, so re-running the installer
  replaces the entry and a second instance gets its own. cron rather than a systemd timer
  deliberately: a system timer needs root to install, and a user timer silently stops firing
  without `loginctl enable-linger`.
- **Windows** — a Scheduled Task `\OrbixERP\Backup (<folder>)`, registered `-Force` (which is
  the idempotence mechanism), `-LogonType Interactive`, `-StartWhenAvailable`.

Flags: `--backup-time HH:MM` / `-BackupTime`, `--no-schedule` / `-NoSchedule`. Both installers
fail **soft** — a machine that cannot schedule still finishes with a working installation and
prints the manual instructions.

> **Windows needs elevation more often than you would expect.** Measured on Windows 11 Home,
> 2026-08-15: a non-elevated standard user gets `Access is denied` from **both**
> `Register-ScheduledTask` and `schtasks.exe`, at the root task path as well as a sub-folder. The
> installer detects this, says so before it tries, and prints the two ways out. Assume a Windows
> customer who did not right-click → Run as administrator has **no schedule**, and check.

### Retention

Three classes of file, three lifetimes, then a floor and two ceilings:

| Class | Pattern | Default days | Setting |
|---|---|---|---|
| Nightly / manual | `orbixerp_*.dump` | 14 | `ERP_BACKUP_RETAIN_DAYS` |
| Pre-update rollback | `orbixerp-preupdate_*.dump` | 90 | `ERP_BACKUP_PREUPDATE_RETAIN_DAYS` |
| Pre-restore safety | `safety-before-restore-*.dump` | 30 | `ERP_BACKUP_SAFETY_RETAIN_DAYS` |

| Bound | Default | Applies |
|---|---|---|
| `ERP_BACKUP_KEEP_MIN` | 7 | evaluated first, always wins — never prune below this many |
| `ERP_BACKUP_KEEP_MAX` | 90 | across all three classes |
| `ERP_BACKUP_DIR_MAX_MB` | 2048 | across all three classes |

Two of these fix real defects rather than adding polish:

- **Safety copies were never pruned at all.** The old prune glob was `orbixerp_*.dump`; the
  safety copy is `safety-before-restore-*.dump` and never matched it. Every restore left a
  full-size dump behind for ever.
- **The pre-update rollback point was pruned at 14 days**, because `update` took an ordinary
  nightly backup. For a release containing a migration that file is the *only* rollback
  ([release-staging-and-rollback.md](release-staging-and-rollback.md)) — reinstalling the old
  bundle does not work. It now has its own name and a 90-day life.

Class floors (keep newest 3 safety, newest 5 pre-update) and `KEEP_MIN` are ordered before the
ceilings, so a machine that was off for a month cannot delete every backup it has on the next
run. The ceilings delete globally-oldest-first and **can** remove a pre-update file — at the
defaults, against a 1.9 MB dump, they never bind.

### Taking one

```bash
cd /opt/orbixerp && sudo ./orbixerp.sh backup        # progress on stderr, path on stdout
```

Only the path goes to stdout — `update` captures it with `$(...)`. Nothing may be added to
`cmd_backup` / `Invoke-Backup` that writes to stdout or the PowerShell pipeline.

It now also: trims `backups/backup.log` past 1 MB, refuses to start if free space is under 3×
the newest dump, and **deletes the part-written file** if `pg_dump` fails (it used to leave a
truncated dump sitting in the listing, indistinguishable from a good one).

### Restoring

```bash
ssh -tt -i <key> ubuntu@<host>          # -tt: the prompt reads /dev/tty
cd /opt/orbixerp && sudo ./orbixerp.sh restore backups/<file>
# scripted / drill:
sudo ./orbixerp.sh restore backups/<file> --yes
```

Sequence: safety dump → `pg_terminate_backend` on every session → `DROP SCHEMA public CASCADE`
→ `CREATE SCHEMA` → `pg_restore --no-owner --exit-on-error` → `up -d` → wait healthy.

Three things to know:

- **The supplied file is staged under a unique name** (`restore-source-<pid>-<basename>`) and
  removed afterwards. It used to be copied in under its own basename *only if absent*, so
  restoring `/mnt/usb/orbixerp_20260815_020000.dump` when a local file of that name existed
  silently restored the **local** one and printed "Restore complete".
- **`--no-owner` reassigns ownership to the connecting role.** Check `pg_tables.tableowner`
  after any restore — this is the hazard [rehearsal-stack.md](rehearsal-stack.md) exists to
  counter.
- **It is whole-database and destructive by construction.** See the shared-instance section.

Measured timings: [restore-drill.md](restore-drill.md).

---

## The infra path — `infra/prod/backup.sh` / `restore.sh`

For an `infra/prod/` compose stack only. Env: `PGHOST` (127.0.0.1), `PGPORT` (5432),
`PGDATABASE` (erp), `PGUSER` (erp), **`PGPASSWORD` required**, `BACKUP_DIR` (/backups),
`BACKUP_RETAIN_DAYS` (14).

```sh
set -a && . infra/prod/.env && set +a
export PGHOST=127.0.0.1 PGPORT=5432 PGDATABASE="${POSTGRES_DB}" \
       PGUSER="${POSTGRES_USER}" PGPASSWORD="${POSTGRES_PASSWORD}"
BACKUP_DIR=/backups BACKUP_RETAIN_DAYS=14 sh infra/prod/backup.sh
ls -lh /backups/erpclean2_*.dump | tail -5
```

Restore — stop the API first, take a fresh dump of the current state even if it is corrupt,
then:

```sh
DUMP_FILE=/backups/erpclean2_<timestamp>.dump sh infra/prod/restore.sh
docker start erp-prod-api && docker logs -f erp-prod-api    # watch Flyway validate
curl -sf http://localhost:8081/actuator/health/readiness
```

`restore.sh` uses `pg_restore --clean --if-exists`, which drops objects one at a time in the
dump's order and fails when the live database holds an object the dump does not know about.
`orbixerp.sh` moved to `DROP SCHEMA ... CASCADE` for exactly that reason; this script has not.
It is also unscheduled and unpruned beyond `BACKUP_RETAIN_DAYS`. **Prefer the customer path
where both exist.**

### Rollback for a failed migration

1. `docker stop erp-prod-api`
2. Restore the backup taken **before** the deploy
3. Fix the migration SQL (backend-engineer)
4. Redeploy

**Never edit a V-prefixed migration that has been applied anywhere** — a backend-engineer call,
not an ops call.

---

## Off-host storage — still nothing does this automatically

Neither tool copies anything off the machine. The `/backups` volume is on the same host, same
disk and same room as the database, so on its own it protects against mistakes, not against
losing the machine.

Requirements for wherever they go:

- server-side encryption; no public access
- **object versioning and object lock**, so a compromised box cannot delete backup history
- access limited to a named operator list, with downloads logged
- never a personal cloud drive and never a laptop that syncs — the rule
  [rehearsal-stack.md](rehearsal-stack.md) already sets for one customer's dump binds twice as
  hard for a file holding two

Keep **dumps and signing keys in separate stores with separate access lists**. A dump without
`secrets/jwt` cannot be signed into; that is framed as a recovery hazard in the customer guide,
and it is also a useful property worth preserving deliberately — one leaked store is not enough
on its own.

---

## Under a shared instance, whose backup is it?

The owner's decision of 2026-08-15 (**D-11**) puts two paying customers in one database, one
application, separated by `organisation_id`. That changes what a dump *is*.

**A dump of a shared database is every customer's ledger in one file.** Not "customer B's data
plus some rows we can ignore" — payroll, prices, customers, the lot, for everyone.

### Custody

- The shared box is **the vendor's**, not either customer's. `backups/` lives inside the install
  tree next to `.env` and `secrets/jwt`.
- **No customer, and no customer's IT provider, may be given shell, RDP or file access.** This
  retires, for shared-instance customers, the standing advice in the shipped
  `OPERATIONS.md`/`INSTALL.md` to "copy that folder somewhere else" — a customer following it
  would walk out with the other customer's books. The shipped guides now carry a shared-instance
  section that says so.
- One RS256 keypair now signs tokens for **both** customers. Its compromise is a two-customer
  breach, and rotating it logs both customers out at once.

### Restore authority

`orbixerp.sh` terminates every session and drops the whole schema; `infra/prod/restore.sh` is
`pg_restore --clean`. Rolling customer B back to yesterday **costs customer A a trading day and
re-issues their invoice numbers**, and the `pg_terminate_backend` kicks A's tills mid-sale.

So on a shared instance:

- a whole-database restore requires **named vendor sign-off**, not a customer request
- **both** customers are told before it happens
- the MSA carries a stated RPO and RTO, and the RTO is the measured one from
  [restore-drill.md](restore-drill.md), not an estimate

### What does not exist, and must not be improvised

- **Per-tenant restore (D-5).** There is no tenant filter anywhere in either tool. The interim
  answer, if one customer must be rolled back without the other: restore the whole dump into a
  **separate scratch database**, extract that organisation's rows, re-apply into live. Manual,
  unproven and slow. Cost it before promising it.
- **Per-tenant export (D-6 / P5-4).** On departure a customer must **never** be handed a dump.
  There is no per-organisation extract, so there is nothing to hand over yet.
