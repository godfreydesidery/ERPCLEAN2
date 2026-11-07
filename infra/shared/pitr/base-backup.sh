#!/bin/sh
# base-backup.sh - take a physical base backup of the shared server's Postgres, verify it, then
# prune old backups and WAL (prune.sh). Run on the Linux host, from cron (docs/ops/shared-instance-pitr.md).
#
# A base backup alone restores to the moment it was taken. Combined with the continuously archived
# WAL in the wal-archive volume it restores to ANY moment after it (point-in-time recovery).
#
# Writes /basebackups/base_<UTC stamp>/ inside the db container (the basebackups volume):
#   plain-format data directory + backup_manifest + BACKUP_COMPLETE (completion time, UTC).
# The directory is built as base_<stamp>.partial and renamed only after pg_verifybackup passes, so
# a crashed or failed run never leaves something restore-pitr.sh could pick up.
#
# Env (all optional):
#   COMPOSE_FILE      compose file to use        (default: ../docker-compose.yml)
#   COMPOSE_PROJECT_NAME  compose project         (default: from compose)
#   NO_PRUNE=1        skip prune.sh after the backup
#   BASEBACKUP_KEEP   complete backups to keep    (default 7; read by prune.sh)
#
# POSIX sh.

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
COMPOSE_FILE="${COMPOSE_FILE:-${HERE}/../docker-compose.yml}"
dc() { docker compose -f "${COMPOSE_FILE}" "$@"; }

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
START=$(date +%s)

echo "[base-backup] starting base_${STAMP}"

# -u postgres: the files must be owned by the postgres user or a restored data directory is refused.
# -c fast: do not wait up to ~2.5 min for a spread checkpoint (a short I/O burst on a small DB).
# -X stream: the WAL needed to make this backup self-consistent is streamed alongside it.
SIZE=$(dc exec -T -u postgres db sh -eu -c '
  d=/basebackups/base_'"${STAMP}"'
  rm -rf "$d.partial"
  pg_basebackup -U "$POSTGRES_USER" -D "$d.partial" -Fp -X stream -c fast
  pg_verifybackup -q "$d.partial"
  date -u +%Y-%m-%dT%H:%M:%SZ > "$d.partial/BACKUP_COMPLETE"
  mv "$d.partial" "$d"
  du -sh "$d" | cut -f1
')   # a failure here aborts the script (set -e) - do not pipe this, a pipe would hide the exit status

END=$(date +%s)
echo "[base-backup] base_${STAMP} complete and verified: ${SIZE}, $((END - START))s"

if [ "${NO_PRUNE:-0}" != "1" ]; then
  sh "${HERE}/prune.sh"
fi
