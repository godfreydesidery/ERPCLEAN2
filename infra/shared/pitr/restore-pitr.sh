#!/bin/sh
# restore-pitr.sh - restore the shared server's database to a point in time into a SCRATCH
# Postgres container + volume, alongside production. It never touches the production db-data
# volume, the production container, or the WAL archive (mounted read-only; archiving is OFF in
# the scratch instance so it cannot write into the production archive).
#
# Use it for: (a) rehearsing a restore, (b) the one-tenant support procedure (restore, then lift
# one tenant's rows out - docs/ops/shared-instance-pitr.md section 6), (c) step 1 of a whole-instance
# restore, finished by promote-into-production.sh.
#
# Usage:
#   TARGET_TIME='2026-11-07 10:15:00+00' sh restore-pitr.sh
#   TARGET_TIME=latest sh restore-pitr.sh            # replay all archived WAL
#
# Env:
#   TARGET_TIME     REQUIRED. A timestamp WITH a zone (use +00 / UTC), or 'latest'.
#   BASE            base backup name, e.g. base_20261107T091500Z (default: newest one that was
#                   COMPLETE at or before TARGET_TIME).
#   SCRATCH_NAME    container + volume stem (default erp-pitr-scratch-<stamp>)
#   SCRATCH_PORT    127.0.0.1 port for the scratch Postgres (default 5441)
#   ACTION          pause (default: stop at the target, read-only, so you can verify; then
#                   promote with the printed command) | promote (promote automatically)
#   ERP_SHARED_VOLUME_PREFIX  volume prefix of the source stack (default erp-shared)
#   PG_IMAGE        default postgres:15-alpine (must match the production major version)
#   WAIT_SECONDS    how long to wait for replay to reach the target (default 3600)
#
# The scratch instance holds REAL CUSTOMER DATA. Loopback only; delete it when finished
# (the cleanup commands are printed at the end).
#
# POSIX sh; needs GNU date on the host (any Linux server).

set -eu

PREFIX="${ERP_SHARED_VOLUME_PREFIX:-erp-shared}"
PG_IMAGE="${PG_IMAGE:-postgres:15-alpine}"
ACTION="${ACTION:-pause}"
SCRATCH_PORT="${SCRATCH_PORT:-5441}"
WAIT_SECONDS="${WAIT_SECONDS:-3600}"
TARGET_TIME="${TARGET_TIME:-}"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
SCRATCH_NAME="${SCRATCH_NAME:-erp-pitr-scratch-${STAMP}}"
BASEVOL="${PREFIX}-basebackups"
WALVOL="${PREFIX}-wal-archive"
SCRATCHVOL="${SCRATCH_NAME}-data"

die() { echo "[restore-pitr] ERROR: $*" >&2; exit 1; }

[ -n "${TARGET_TIME}" ] || die "TARGET_TIME is required (e.g. '2026-11-07 10:15:00+00' or 'latest')"
case "${ACTION}" in pause|promote) ;; *) die "ACTION must be pause or promote" ;; esac
docker volume inspect "${BASEVOL}" >/dev/null 2>&1 || die "volume ${BASEVOL} not found"
docker volume inspect "${WALVOL}" >/dev/null 2>&1 || die "volume ${WALVOL} not found"
docker volume inspect "${SCRATCHVOL}" >/dev/null 2>&1 && die "scratch volume ${SCRATCHVOL} already exists - pick another SCRATCH_NAME"

T0=$(date +%s)

# ---- choose the base backup ---------------------------------------------------------------------
if [ "${TARGET_TIME}" = "latest" ]; then
  TARGET_EPOCH=$(date +%s)
else
  TARGET_EPOCH=$(date -u -d "${TARGET_TIME}" +%s 2>/dev/null) || die "cannot parse TARGET_TIME '${TARGET_TIME}'"
fi

LIST=$(docker run --rm -v "${BASEVOL}:/b:ro" "${PG_IMAGE}" sh -c \
  'for d in /b/base_????????T??????Z; do [ -f "$d/BACKUP_COMPLETE" ] && echo "$(basename "$d") $(cat "$d/BACKUP_COMPLETE")"; done; true')
[ -n "${LIST}" ] || die "no complete base backup in ${BASEVOL}"

if [ -z "${BASE:-}" ]; then
  BASE=""
  # Names sort chronologically; keep the last one completed at or before the target.
  for name in $(printf '%s\n' "${LIST}" | awk '{print $1}' | sort); do
    done_at=$(printf '%s\n' "${LIST}" | awk -v n="${name}" '$1==n {print $2}')
    done_epoch=$(date -u -d "${done_at}" +%s)
    if [ "${done_epoch}" -le "${TARGET_EPOCH}" ]; then BASE="${name}"; fi
  done
  [ -n "${BASE}" ] || die "no base backup was complete at or before ${TARGET_TIME}; oldest available is $(printf '%s\n' "${LIST}" | sort | head -n 1)"
fi
printf '%s\n' "${LIST}" | grep -q "^${BASE} " || die "base backup ${BASE} not found or not complete"
echo "[restore-pitr] base backup : ${BASE}"
echo "[restore-pitr] target      : ${TARGET_TIME} (action=${ACTION})"
echo "[restore-pitr] scratch     : container ${SCRATCH_NAME}, volume ${SCRATCHVOL}, 127.0.0.1:${SCRATCH_PORT}"

# ---- lay the base backup down and write the recovery settings -----------------------------------
docker volume create "${SCRATCHVOL}" >/dev/null

if [ "${TARGET_TIME}" = "latest" ]; then
  TARGET_LINE="# recovery target: end of archived WAL"
else
  TARGET_LINE="recovery_target_time = '${TARGET_TIME}'"
fi

docker run --rm -u root \
  -v "${BASEVOL}:/basebackups:ro" -v "${SCRATCHVOL}:/data" \
  -e BASE="${BASE}" -e TARGET_LINE="${TARGET_LINE}" -e ACTION="${ACTION}" \
  "${PG_IMAGE}" sh -eu -c '
    cp -a "/basebackups/$BASE/." /data/
    rm -f /data/BACKUP_COMPLETE
    chown -R postgres:postgres /data
    chmod 700 /data
    touch /data/recovery.signal
    {
      echo "restore_command = '"'"'cp /wal-archive/%f \"%p\"'"'"'"
      echo "$TARGET_LINE"
      echo "recovery_target_action = $ACTION"
      echo "recovery_target_inclusive = on"
    } >> /data/postgresql.auto.conf
  '

# The standby-style recovery needs max_connections (and friends) >= the source's value, which
# pg_controldata records.
MAXCONN=$(docker run --rm -u postgres -v "${SCRATCHVOL}:/data:ro" "${PG_IMAGE}" pg_controldata /data \
  | sed -n 's/^max_connections setting: *//p')
[ -n "${MAXCONN}" ] || MAXCONN=100
T1=$(date +%s)
echo "[restore-pitr] base backup copied in $((T1 - T0))s; starting recovery (max_connections=${MAXCONN})"

docker run -d --name "${SCRATCH_NAME}" \
  -v "${SCRATCHVOL}:/var/lib/postgresql/data" -v "${WALVOL}:/wal-archive:ro" \
  -p "127.0.0.1:${SCRATCH_PORT}:5432" \
  "${PG_IMAGE}" postgres \
    -c max_connections="${MAXCONN}" -c wal_level=replica -c max_wal_senders=4 \
    -c archive_mode=off -c hot_standby=on >/dev/null

# ---- wait for the target -----------------------------------------------------------------------
waited=0
while :; do
  state=$(docker exec -u postgres "${SCRATCH_NAME}" psql -U "${POSTGRES_USER:-erp}" -d postgres -Atc \
    "select case when not pg_is_in_recovery() then 'promoted' when pg_is_wal_replay_paused() then 'paused' else 'replaying' end" 2>/dev/null || echo "starting")
  case "${state}" in
    paused)   break ;;
    promoted) break ;;
  esac
  if [ "$(docker inspect -f '{{.State.Running}}' "${SCRATCH_NAME}")" != "true" ]; then
    docker logs --tail 30 "${SCRATCH_NAME}" >&2 || true
    die "scratch container exited during recovery (log above). If it says the recovery target is before the consistent point, choose a base backup completed earlier than the target."
  fi
  waited=$((waited + 2))
  [ "${waited}" -le "${WAIT_SECONDS}" ] || die "recovery did not reach the target within ${WAIT_SECONDS}s (state: ${state})"
  sleep 2
done
T2=$(date +%s)

echo "[restore-pitr] recovery reached target: state=${state}; recovery took $((T2 - T1))s, total $((T2 - T0))s"
echo
echo "Verify (read-only while paused):"
echo "  docker exec -it ${SCRATCH_NAME} psql -U <POSTGRES_USER> -d <POSTGRES_DB>"
echo "Promote (ends recovery; the scratch instance becomes writable):"
echo "  docker exec ${SCRATCH_NAME} psql -U <POSTGRES_USER> -d <POSTGRES_DB> -c 'select pg_promote()'"
echo "Clean up when finished (it holds real customer data):"
echo "  docker rm -f ${SCRATCH_NAME} && docker volume rm ${SCRATCHVOL}"
echo "SCRATCH_NAME=${SCRATCH_NAME}"
