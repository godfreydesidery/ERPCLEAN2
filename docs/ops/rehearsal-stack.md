# The rehearsal stack

A disposable Postgres container holding a **restored production database**, used to test migrations
and backfills against real data before they touch anything a customer uses.

Required by [MULTITENANCY-PLAN.md](../../MULTITENANCY-PLAN.md) §12.2, and the answer to §11 G-B
("nothing has ever been executed"). Every schema step in the multi-tenancy programme rehearses here
first — **twice**, because `R__seed_permissions.sql` re-runs on every deploy and a repeatable-migration
failure only appears on the second boot.

> ## ⚠ This holds a real customer's live business data
>
> Sales, customers, GL, stock, and whatever HR the client has entered. Three rules:
>
> 1. **Never inside the git repo.** Dumps live at `D:\My_Works\ERP\db-dumps\`, a sibling of the repo,
>    so they cannot be committed by accident.
> 2. **Bound to localhost only** (`-p 127.0.0.1:5440:5432`). Do not publish the port.
> 3. **Delete it when the work is done** — `docker rm -f erp-rehearsal-db && docker volume rm
>    erp-rehearsal-data`, and remove the dump. There is no reason for a client's database to outlive
>    the task on a development laptop, particularly one that may sync to cloud backup.

## Why it is not the local dev database

`erp-db-data` is the local development volume and CLAUDE.md says to preserve it — restoring a
production dump over it would destroy local work. The rehearsal stack is deliberately a **separate
container, separate volume, separate port**:

| | Local dev | Rehearsal |
|---|---|---|
| Container | `erpclean2-dev-db` | `erp-rehearsal-db` |
| Volume | `erp-db-data` | `erp-rehearsal-data` |
| Port | 5434 | **5440**, localhost-only |
| Lifetime | persistent | **throwaway** |

It is also where the **two-organisation** work for Phases 4–7 happens. That must never run on QA:
QA has one durable volume that is never wiped, so a second organisation there is permanent, and QA
would stop being able to validate the single-tenant releases the live customer actually runs.

## Taking a fresh dump from production

```bash
KEY=~/.ssh/KILIMANJAROSUPERMARKET.pem
ssh -i "$KEY" ubuntu@51.21.23.170 'cd /opt/orbixerp && sudo ./orbixerp.sh backup'
# then copy the newest file out (it is root-owned on the box)
ssh -i "$KEY" ubuntu@51.21.23.170 'sudo cat /opt/orbixerp/backups/<file>.dump' \
  > "D:/My_Works/ERP/db-dumps/<file>.dump"
```

Use the product's own `orbixerp.sh backup` rather than an ad-hoc `pg_dump`: it is the supported path,
writes to the configured backup directory, and applies the 14-day retention. Verify the transfer with
`sha256sum` at both ends before relying on it.

## Creating the stack

```bash
docker rm -f erp-rehearsal-db 2>/dev/null; docker volume rm erp-rehearsal-data 2>/dev/null

docker run -d --name erp-rehearsal-db \
  -e POSTGRES_PASSWORD=rehearsal -e POSTGRES_DB=postgres \
  -p 127.0.0.1:5440:5432 \
  -v erp-rehearsal-data:/var/lib/postgresql/data \
  postgres:15

docker exec erp-rehearsal-db psql -U postgres -c "CREATE ROLE erp LOGIN PASSWORD 'erp';"
docker exec erp-rehearsal-db psql -U postgres -c "CREATE DATABASE erp OWNER erp;"

docker exec -i erp-rehearsal-db pg_restore --no-owner --no-privileges --exit-on-error \
  -U postgres -d erp < "D:/My_Works/ERP/db-dumps/<file>.dump"
```

### Then reassign ownership — do not skip this

`--no-owner` leaves everything owned by `postgres`, i.e. a superuser. Migrations would then run with
privileges the application does not have, and a permission problem would be invisible until
production. Restore the app role as owner so Flyway runs the way it really runs:

```bash
docker exec -i erp-rehearsal-db psql -U postgres -d erp -v ON_ERROR_STOP=1 <<'SQL'
ALTER DATABASE erp OWNER TO erp;
ALTER SCHEMA public OWNER TO erp;
DO $$ DECLARE r record; BEGIN
  FOR r IN SELECT tablename FROM pg_tables WHERE schemaname='public' LOOP
    EXECUTE format('ALTER TABLE public.%I OWNER TO erp', r.tablename); END LOOP;
  FOR r IN SELECT sequencename FROM pg_sequences WHERE schemaname='public' LOOP
    EXECUTE format('ALTER SEQUENCE public.%I OWNER TO erp', r.sequencename); END LOOP;
END $$;
SQL
```

> **`docker exec` needs `-i` for a heredoc.** Without it stdin is never attached, psql reads EOF and
> silently does nothing — it reports success having executed no statements. This cost a cycle the
> first time.

## Verifying fidelity

Re-run the Stage A script and diff it against the production output:

```bash
docker exec -i erp-rehearsal-db psql -U postgres -d erp -P pager=off \
  -f - < docs/ops/multitenancy-phase0-measurements.sql > D:/My_Works/ERP/db-dumps/rehearsal-measurements.txt

diff D:/My_Works/ERP/db-dumps/prod-measurements.txt \
     D:/My_Works/ERP/db-dumps/rehearsal-measurements.txt
```

**Every row count must match.** Differences that are expected and fine:

- **Physical sizes.** Production carries bloat a fresh restore does not — measured 2026-08-14:
  `audit_logs` 3,400 kB vs 2,632 kB (~29%), `stock_movements` 1,184 kB vs 872 kB (~36%), same row
  counts in both. Worth noting on its own: autovacuum is not keeping up on the live box.
- **`largest_tables` ordering**, which follows physical size.
- **Ties in `audit_logs_by_action`** sorting differently (two actions at 460 rows).
- The environment block: host, user and timestamp.

Anything else is a restore that is not faithful — stop and find out why.

## Connecting

```bash
docker exec -it erp-rehearsal-db psql -U erp -d erp     # as the app role
psql "postgresql://erp:erp@127.0.0.1:5440/erp"          # from the host
```

To point the application at it: `ERP_DB_HOST=127.0.0.1 ERP_DB_PORT=5440 ERP_DB_NAME=erp
ERP_DB_USER=erp ERP_DB_PASSWORD=erp`.

## Resetting between rehearsals

A migration test is only meaningful from a known state. Re-run the create block above — it drops the
container and volume first, so each rehearsal starts from the same restored dump.

## Recorded state — 2026-08-14

First stack built from `prod-kilimanjaro-20260814-133814.dump` (1.9 MB, SHA-256 `42d5b4e7…d5a5e`):
**205 tables, Flyway v98 / 98 applied / 0 failed, 35 MB, `organisations = 1`**, all objects owned by
`erp`, and `audit_logs` grants reproducing production's — `erp` holds `UPDATE`, `DELETE` and
`TRUNCATE`, the P8-9 gap, so it is testable here rather than only observable in production.
