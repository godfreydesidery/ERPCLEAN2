#!/bin/sh
# restore.sh — restore an ERPCLEAN2 pg_dump custom-format archive with pg_restore.
#
# WARNING: --clean drops and recreates all objects in the target database before
# restoring.  This is DESTRUCTIVE.  Confirm the dump file is correct before running.
# Stop the API container before restoring to avoid Flyway / Hibernate conflicts.
#
# Usage:
#   PGHOST=<host> PGPORT=5432 PGDATABASE=erp PGUSER=erp PGPASSWORD=<pw> \
#     DUMP_FILE=/backups/erpclean2_20260101_120000.dump ./restore.sh
#
# Env vars:
#   PGHOST        Postgres host  (default: 127.0.0.1)
#   PGPORT        Postgres port  (default: 5432)
#   PGDATABASE    Target database name (default: erp)
#   PGUSER        DB superuser or owner (default: erp)
#   PGPASSWORD    DB password (REQUIRED — no default)
#   DUMP_FILE     Path to the .dump file to restore (REQUIRED)
#
# POSIX sh — no bashisms.

set -eu

PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-erp}"
PGUSER="${PGUSER:-erp}"
DUMP_FILE="${DUMP_FILE:-}"

if [ -z "${PGPASSWORD:-}" ]; then
  echo "[restore] ERROR: PGPASSWORD is not set. Aborting." >&2
  exit 1
fi

if [ -z "${DUMP_FILE}" ]; then
  echo "[restore] ERROR: DUMP_FILE is not set. Aborting." >&2
  echo "[restore] Usage: DUMP_FILE=/backups/erpclean2_<timestamp>.dump ./restore.sh" >&2
  exit 1
fi

if [ ! -f "${DUMP_FILE}" ]; then
  echo "[restore] ERROR: dump file not found: ${DUMP_FILE}" >&2
  exit 1
fi

echo "[restore] Restoring ${DUMP_FILE} → ${PGDATABASE}@${PGHOST}:${PGPORT}"
echo "[restore] This will DROP and recreate all objects in ${PGDATABASE}."
echo "[restore] ============================================================"
echo "[restore] THIS IS A WHOLE-DATABASE RESTORE. There is no tenant filter."
echo "[restore] If this database holds more than one organisation, EVERY"
echo "[restore] organisation is taken back to the moment of this dump - they"
echo "[restore] lose a trading day and re-issue invoice numbers already used."
echo "[restore] On a shared instance this needs named vendor sign-off and both"
echo "[restore] customers told first. See docs/ops/backup-restore.md."
echo "[restore] ============================================================"
echo "[restore] Proceeding in 5 seconds — Ctrl-C to abort."
sleep 5

export PGPASSWORD
pg_restore \
  --host="${PGHOST}" \
  --port="${PGPORT}" \
  --username="${PGUSER}" \
  --dbname="${PGDATABASE}" \
  --clean \
  --if-exists \
  --no-password \
  --verbose \
  "${DUMP_FILE}"

echo "[restore] Restore complete. Start the API container to run Flyway validate."
