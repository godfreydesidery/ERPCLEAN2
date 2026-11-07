#!/bin/sh
# prune.sh - keep the newest BASEBACKUP_KEEP complete base backups; delete older ones, then delete
# archived WAL older than the OLDEST backup that is kept (nothing earlier can be needed to restore
# from a kept backup). Called by base-backup.sh; safe to run on its own.
#
# Safety rules:
#   - never prunes unless at least 2 complete backups exist, and never below 2 kept;
#   - never touches *.partial directories (a backup in progress or a crashed one - inspect by hand);
#   - WAL is trimmed only up to the start-WAL file named in the oldest kept backup_label.
# Restore reaches only as far back as the oldest kept backup: BASEBACKUP_KEEP x backup interval
# is the point-in-time window (7 daily backups = 7 days).
#
# Env: COMPOSE_FILE, BASEBACKUP_KEEP (default 7, minimum 2).
#
# POSIX sh.

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
COMPOSE_FILE="${COMPOSE_FILE:-${HERE}/../docker-compose.yml}"
KEEP="${BASEBACKUP_KEEP:-7}"
dc() { docker compose -f "${COMPOSE_FILE}" "$@"; }

case "${KEEP}" in
  ''|*[!0-9]*) echo "[prune] BASEBACKUP_KEEP must be a number, got '${KEEP}'" >&2; exit 1 ;;
esac
if [ "${KEEP}" -lt 2 ]; then
  echo "[prune] BASEBACKUP_KEEP raised from ${KEEP} to the minimum of 2"
  KEEP=2
fi

dc exec -T -u postgres db sh -eu -c '
  keep='"${KEEP}"'
  cd /basebackups
  # Complete backups only, oldest first (names sort chronologically).
  all=$(ls -d base_????????T??????Z 2>/dev/null | sort || true)
  n=$(printf "%s\n" "$all" | grep -c . || true)
  echo "[prune] $n complete base backup(s), keeping $keep"
  if [ "$n" -le "$keep" ]; then
    oldest=$(printf "%s\n" "$all" | head -n 1)
  else
    drop=$((n - keep))
    for b in $(printf "%s\n" "$all" | head -n "$drop"); do
      echo "[prune] deleting $b"
      rm -rf "/basebackups/$b"
    done
    oldest=$(printf "%s\n" "$all" | tail -n "$keep" | head -n 1)
  fi
  if [ "$n" -lt 2 ] || [ -z "$oldest" ]; then
    echo "[prune] fewer than 2 backups - leaving WAL alone"
    exit 0
  fi
  # "START WAL LOCATION: 0/3000028 (file 000000010000000000000003)"
  walfile=$(sed -n "s/^START WAL LOCATION: .*(file \([0-9A-F]*\)).*/\1/p" "/basebackups/$oldest/backup_label")
  if [ -z "$walfile" ]; then
    echo "[prune] could not read start WAL from $oldest/backup_label - leaving WAL alone" >&2
    exit 1
  fi
  echo "[prune] oldest kept backup: $oldest (needs WAL from $walfile); removing older archived WAL"
  pg_archivecleanup /wal-archive "$walfile"
  echo "[prune] WAL segments remaining: $(ls /wal-archive | grep -c "^[0-9A-F]\{24\}$" || true)"
'
