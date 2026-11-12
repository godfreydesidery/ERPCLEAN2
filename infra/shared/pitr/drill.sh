#!/bin/sh
# drill.sh - timed point-in-time-recovery drill on a THROWAWAY stack. Run it before first go-live
# and again after any change to the backup scripts or the Postgres major version.
#
# It brings up only the shared stack's Postgres under its own compose project and volumes
# (prefix 'erpdrill'), so it cannot touch erp-shared-* volumes, erp-db-data or localhost:5434.
#
#   1. start Postgres with WAL archiving
#   2. load phase A rows, take a base backup                       (timed)
#   3. load phase B rows, note a timestamp T, load phase C rows
#   4. restore to T into a scratch container                        (timed)
#   5. assert A and B are present and C is absent; promote; assert the restored DB accepts writes
#   6. promote-into-production.sh into the drill stack, assert again (timed)
#   7. tear down ONLY the drill project and its volumes
#
# The data is synthetic (one wide table), not the ERP schema: it exercises the backup/restore
# machinery and sizes it, it does not prove the application boots on the result - the runbook's
# verification step does that on a real restore.
#
# Env: DRILL_ROWS_A (default 2000000), DRILL_ROWS_B (200000), DRILL_ROWS_C (50000), KEEP=1 to skip teardown.
#
# POSIX sh; run on the Linux host (or Git Bash with MSYS_NO_PATHCONV=1).

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
SHARED=$(cd "${HERE}/.." && pwd)
# Git Bash on Windows hands docker a /d/... path it cannot open; real Linux hosts skip this.
if command -v cygpath >/dev/null 2>&1; then SHARED=$(cygpath -m "${SHARED}"); fi

export COMPOSE_PROJECT_NAME=erpdrill
export ERP_SHARED_VOLUME_PREFIX=erpdrill
export POSTGRES_DB=erp POSTGRES_USER=erp POSTGRES_PASSWORD=drill-only-password
export PG_ARCHIVE_TIMEOUT=60 ERP_PUBLIC_HOST=localhost ERP_MGMT_HOST_PORT=9199
export COMPOSE_FILE="${SHARED}/docker-compose.yml"
export MSYS_NO_PATHCONV=1

A="${DRILL_ROWS_A:-2000000}"; B="${DRILL_ROWS_B:-200000}"; C="${DRILL_ROWS_C:-50000}"

dc() { docker compose -f "${COMPOSE_FILE}" "$@"; }
sql() { dc exec -T db psql -v ON_ERROR_STOP=1 -U erp -d erp -Atq "$@"; }
now() { date +%s; }
fail() { echo "[drill] FAIL: $*" >&2; exit 1; }
count() { docker exec "$1" psql -U erp -d erp -Atc "select count(*) filter (where phase='A'), count(*) filter (where phase='B'), count(*) filter (where phase='C') from drill_rows" ; }

MADE_ENV=0
if [ ! -f "${SHARED}/.env" ]; then : > "${SHARED}/.env"; MADE_ENV=1; fi   # compose needs the file to exist; the drill starts only db

cleanup() {
  [ "${MADE_ENV}" = "1" ] && rm -f "${SHARED}/.env"
  if [ "${KEEP:-0}" != "1" ]; then
    echo "[drill] tearing down the drill project only (erpdrill)"
    docker rm -f erpdrill-scratch erpdrill-scratch2 >/dev/null 2>&1 || true
    [ "${COMPOSE_PROJECT_NAME}" = "erpdrill" ] && dc down -v >/dev/null 2>&1 || true
    for v in $(docker volume ls -q | grep '^erpdrill-' || true); do docker volume rm "$v" >/dev/null 2>&1 || true; done
  fi
}
trap cleanup EXIT

T_START=$(now)
echo "[drill] 1. start postgres (project erpdrill)"
dc up -d db >/dev/null
i=0; until [ "$(docker inspect -f '{{.State.Health.Status}}' erpdrill-db-1)" = "healthy" ]; do i=$((i+1)); [ $i -lt 60 ] || fail "db not healthy"; sleep 2; done

echo "[drill] 2. load phase A (${A} rows) and take a base backup"
sql -c "create table drill_rows(id bigserial primary key, phase text not null, tenant int not null, payload text not null, created_at timestamptz not null default clock_timestamp())"
sql -c "create index drill_rows_tenant on drill_rows(tenant)"
LOAD_A0=$(now)
sql -c "insert into drill_rows(phase,tenant,payload) select 'A', (g % 10), repeat(md5(g::text),4) from generate_series(1,${A}) g"
LOAD_A1=$(now)
sql -c "checkpoint"
DB_SIZE=$(sql -c "select pg_size_pretty(pg_database_size('erp'))")
BB0=$(now)
sh "${HERE}/base-backup.sh"
BB1=$(now)

echo "[drill] 3. phase B, timestamp T, phase C"
sql -c "insert into drill_rows(phase,tenant,payload) select 'B', (g % 10), repeat(md5(g::text),4) from generate_series(1,${B}) g"
sleep 2
T=$(sql -c "select to_char(clock_timestamp() at time zone 'UTC','YYYY-MM-DD HH24:MI:SS.US') || '+00'")
sleep 2
sql -c "insert into drill_rows(phase,tenant,payload) select 'C', (g % 10), repeat(md5(g::text),4) from generate_series(1,${C}) g"
# Make sure the segment holding C has been archived (production relies on archive_timeout instead).
sql -c "select pg_switch_wal()" >/dev/null
i=0; until [ "$(sql -c "select (pg_stat_archiver.last_archived_wal >= pg_walfile_name(pg_current_wal_lsn() - 16777216))::int from pg_stat_archiver")" = "1" ]; do i=$((i+1)); [ $i -lt 60 ] || fail "WAL not archived"; sleep 1; done
echo "[drill]    T = ${T}"
echo "[drill]    live counts (A,B,C): $(sql -c "select count(*) filter (where phase='A')||','||count(*) filter (where phase='B')||','||count(*) filter (where phase='C') from drill_rows")"

echo "[drill] 4. restore to T into a scratch container"
R0=$(now)
OUT=$(TARGET_TIME="${T}" SCRATCH_NAME=erpdrill-scratch SCRATCH_PORT=5499 sh "${HERE}/restore-pitr.sh")
R1=$(now)
printf '%s\n' "${OUT}" | grep '^\[restore-pitr\]'
SCRATCH=erpdrill-scratch

echo "[drill] 5. verify"
got=$(count "${SCRATCH}")
echo "[drill]    restored counts (A|B|C): ${got}"
[ "${got}" = "${A}|${B}|0" ] || fail "expected ${A}|${B}|0"
docker exec "${SCRATCH}" psql -U erp -d erp -Atc "select pg_promote()" >/dev/null
i=0; until [ "$(docker exec "${SCRATCH}" psql -U erp -d erp -Atc 'select pg_is_in_recovery()')" = "f" ]; do i=$((i+1)); [ $i -lt 30 ] || fail "promote"; sleep 1; done
docker exec "${SCRATCH}" psql -U erp -d erp -Atc "insert into drill_rows(phase,tenant,payload) values ('P',0,'after-promote')" >/dev/null
echo "[drill]    promoted; scratch accepts writes"

echo "[drill] 6. promote scratch into the drill production stack"
P0=$(now)
CONFIRM_REPLACE=erpdrill SCRATCH_NAME="${SCRATCH}" sh "${HERE}/promote-into-production.sh" | sed 's/^/    /'
i=0; until [ "$(docker inspect -f '{{.State.Health.Status}}' erpdrill-db-1)" = "healthy" ]; do i=$((i+1)); [ $i -lt 60 ] || fail "db not healthy after promote"; sleep 2; done
P1=$(now)
got=$(count erpdrill-db-1)
[ "${got}" = "${A}|${B}|0" ] || fail "after promote expected ${A}|${B}|0, got ${got}"
echo "[drill]    production now holds (A|B|C): ${got}; timeline $(sql -c "select timeline_id from pg_control_checkpoint()")"
sh "${HERE}/base-backup.sh" | sed 's/^/    /'

echo "[drill] 7. retention: take two more backups with BASEBACKUP_KEEP=2 and confirm pruning"
sql -c "insert into drill_rows(phase,tenant,payload) select 'D', 1, 'x' from generate_series(1,1000)"
sleep 1
BASEBACKUP_KEEP=2 sh "${HERE}/base-backup.sh" | sed 's/^/    /'
sleep 1
BASEBACKUP_KEEP=2 sh "${HERE}/base-backup.sh" | sed 's/^/    /'
left=$(dc exec -T db sh -c 'ls -d /basebackups/base_????????T??????Z | wc -l' | tr -d '[:space:]')
[ "${left}" = "2" ] || fail "expected 2 base backups after pruning, found ${left}"
echo "[drill]    2 base backups remain; archive segments: $(dc exec -T db sh -c 'ls /wal-archive | grep -c "^[0-9A-F]\{24\}$"' | tr -d '[:space:]')"
echo "[drill]    restore from a timeline-2 backup to 'latest' still works:"
OUT=$(TARGET_TIME=latest SCRATCH_NAME=erpdrill-scratch2 SCRATCH_PORT=5498 sh "${HERE}/restore-pitr.sh")
printf '%s\n' "${OUT}" | grep 'recovery reached'
SCRATCH=erpdrill-scratch2
d=$(docker exec "${SCRATCH}" psql -U erp -d erp -Atc "select count(*) from drill_rows where phase='D'")
[ "${d}" = "1000" ] || fail "latest restore missing phase D rows (${d})"

T_END=$(now)
echo
echo "[drill] RESULT"
echo "  database size at backup : ${DB_SIZE} (phase A ${A} rows, B ${B}, C ${C})"
echo "  phase A load            : $((LOAD_A1 - LOAD_A0))s"
echo "  base backup (+prune)    : $((BB1 - BB0))s"
echo "  restore to T (scratch)  : $((R1 - R0))s  (base copy + WAL replay to the target)"
echo "  promote into production : $((P1 - P0))s  (stop, safety copy, replace, start)"
echo "  whole drill             : $((T_END - T_START))s"
echo "[drill] PASS"
