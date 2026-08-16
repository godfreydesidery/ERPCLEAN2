#!/bin/sh
# backup.sh — pg_dump ERPCLEAN2 production database to a timestamped custom-format
# archive, then prune archives older than BACKUP_RETAIN_DAYS (default 14).
#
# Usage:
#   PGHOST=<host> PGPORT=5432 PGDATABASE=erp PGUSER=erp PGPASSWORD=<pw> \
#     BACKUP_DIR=/backups BACKUP_RETAIN_DAYS=14 ./backup.sh
#
# When running via docker exec inside the prod compose stack:
#   docker exec erp-prod-db sh /scripts/backup.sh
#   (mount this script into the db container or run from the host with PGHOST set)
#
# Env vars:
#   PGHOST            Postgres host  (default: 127.0.0.1)
#   PGPORT            Postgres port  (default: 5432)
#   PGDATABASE        Database name  (default: erp)
#   PGUSER            DB user        (default: erp)
#   PGPASSWORD        DB password    (REQUIRED — no default)
#   BACKUP_DIR        Destination directory for dump files (default: /backups)
#   BACKUP_RETAIN_DAYS  Number of days to keep — older files are deleted (default: 14)
#
# Output file: ${BACKUP_DIR}/erpclean2_<YYYYMMDD_HHMMSS>.dump (pg_dump -Fc format).
# Restore with infra/prod/restore.sh.
#
# NOT the script on a customer's machine. Customer installations run dist/bundle/orbixerp.sh,
# which writes orbixerp_<stamp>.dump into <install-dir>/backups and reads
# ERP_BACKUP_RETAIN_DAYS - a different name, directory and variable from every one below.
# Check which box you are on before quoting a filename. docs/ops/backup-restore.md has both.
#
# This dump is the WHOLE database. There is no per-organisation dump: on an instance shared
# by more than one customer, this file holds every customer's ledger, payroll and customer
# list, and its handling is a contractual question for all of them.
#
# POSIX sh — no bashisms.

set -eu

PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGDATABASE="${PGDATABASE:-erp}"
PGUSER="${PGUSER:-erp}"
BACKUP_DIR="${BACKUP_DIR:-/backups}"
BACKUP_RETAIN_DAYS="${BACKUP_RETAIN_DAYS:-14}"

if [ -z "${PGPASSWORD:-}" ]; then
  echo "[backup] ERROR: PGPASSWORD is not set. Aborting." >&2
  exit 1
fi

TIMESTAMP=$(date -u +"%Y%m%d_%H%M%S")
DUMP_FILE="${BACKUP_DIR}/erpclean2_${TIMESTAMP}.dump"

mkdir -p "${BACKUP_DIR}"

echo "[backup] Starting pg_dump of ${PGDATABASE}@${PGHOST}:${PGPORT} → ${DUMP_FILE}"

export PGPASSWORD
pg_dump \
  --host="${PGHOST}" \
  --port="${PGPORT}" \
  --username="${PGUSER}" \
  --dbname="${PGDATABASE}" \
  --format=custom \
  --compress=9 \
  --no-password \
  --file="${DUMP_FILE}"

DUMP_SIZE=$(du -sh "${DUMP_FILE}" | cut -f1)
echo "[backup] Dump complete: ${DUMP_FILE} (${DUMP_SIZE})"

# Prune archives older than BACKUP_RETAIN_DAYS days.
echo "[backup] Pruning archives older than ${BACKUP_RETAIN_DAYS} days in ${BACKUP_DIR}"
find "${BACKUP_DIR}" -maxdepth 1 -name "erpclean2_*.dump" \
  -mtime "+${BACKUP_RETAIN_DAYS}" -print -delete

echo "[backup] Done."
