#!/bin/sh
# promote-into-production.sh - WHOLE-INSTANCE restore, step 2: make a verified, promoted scratch
# restore (from restore-pitr.sh) the live database.
#
# THIS ROLLS EVERY TENANT BACK to the restore point. Every customer on the server loses everything
# after it and re-issues invoice numbers already used. Run it only with the sign-off recorded in
# docs/ops/shared-instance-pitr.md section 5.
#
# What it does, in order (nothing is deleted before a safety copy exists):
#   1. stops the API and Caddy, then production Postgres (clean shutdown);
#   2. copies the CURRENT production data volume to <prefix>-db-data-before-restore-<stamp>
#      (kept until you remove it - it is your undo);
#   3. stops the scratch container (it must already be PROMOTED) and replaces the contents of the
#      production db-data volume with the scratch data;
#   4. starts Postgres only. It does NOT start the API: you start it after the checks in the runbook.
#
# Usage (from the repo root or anywhere):
#   CONFIRM_REPLACE=<compose project name> SCRATCH_NAME=<container from restore-pitr.sh> \
#     sh infra/shared/pitr/promote-into-production.sh
#
# Env:
#   CONFIRM_REPLACE   REQUIRED. Must equal the compose project name (docker compose ls) - a
#                     typed-in guard against running this against the wrong stack.
#   SCRATCH_NAME      REQUIRED. The promoted scratch container.
#   COMPOSE_FILE, ERP_SHARED_VOLUME_PREFIX (default erp-shared), PG_IMAGE
#
# After it: take a base backup IMMEDIATELY (the restore starts a new timeline, and backups taken
# before it do not cover it): sh infra/shared/pitr/base-backup.sh
#
# POSIX sh.

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
COMPOSE_FILE="${COMPOSE_FILE:-${HERE}/../docker-compose.yml}"
PREFIX="${ERP_SHARED_VOLUME_PREFIX:-erp-shared}"
PG_IMAGE="${PG_IMAGE:-postgres:15-alpine}"
SCRATCH_NAME="${SCRATCH_NAME:-}"
dc() { docker compose -f "${COMPOSE_FILE}" "$@"; }
die() { echo "[promote] ERROR: $*" >&2; exit 1; }

PROJECT=$(dc config --format json 2>/dev/null | sed -n 's/^ *"name": *"\([^"]*\)".*/\1/p' | head -n 1)
[ -n "${PROJECT}" ] || die "cannot determine the compose project name"
[ "${CONFIRM_REPLACE:-}" = "${PROJECT}" ] || die "set CONFIRM_REPLACE=${PROJECT} to confirm you mean this stack"
[ -n "${SCRATCH_NAME}" ] || die "SCRATCH_NAME is required"

state=$(docker exec -u postgres "${SCRATCH_NAME}" psql -U "${POSTGRES_USER:-erp}" -d postgres -Atc "select pg_is_in_recovery()" 2>/dev/null) \
  || die "scratch container ${SCRATCH_NAME} is not running"
[ "${state}" = "f" ] || die "scratch container is still in recovery - verify the data, then run select pg_promote() first"

DATAVOL="${PREFIX}-db-data"
SAFEVOL="${PREFIX}-db-data-before-restore-$(date -u +%Y%m%dT%H%M%SZ)"
SCRATCHVOL="${SCRATCH_NAME}-data"
docker volume inspect "${DATAVOL}" >/dev/null 2>&1 || die "volume ${DATAVOL} not found"
docker volume inspect "${SCRATCHVOL}" >/dev/null 2>&1 || die "volume ${SCRATCHVOL} not found"

T0=$(date +%s)
echo "[promote] stopping caddy, api, db"
dc stop caddy api db

echo "[promote] safety copy ${DATAVOL} -> ${SAFEVOL}"
docker volume create "${SAFEVOL}" >/dev/null
docker run --rm -u root -v "${DATAVOL}:/from:ro" -v "${SAFEVOL}:/to" "${PG_IMAGE}" sh -eu -c 'cp -a /from/. /to/'

echo "[promote] stopping scratch ${SCRATCH_NAME} (clean shutdown)"
docker stop -t 120 "${SCRATCH_NAME}" >/dev/null

echo "[promote] replacing ${DATAVOL} with the restored data"
docker run --rm -u root -v "${SCRATCHVOL}:/from:ro" -v "${DATAVOL}:/to" "${PG_IMAGE}" sh -eu -c '
  find /to -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  cp -a /from/. /to/
  rm -f /to/recovery.signal /to/standby.signal
  # drop the recovery settings restore-pitr.sh appended (they would re-trigger nothing without the
  # signal file, but must not linger in the live data directory)
  sed -i -e "/^restore_command/d" -e "/^recovery_target/d" /to/postgresql.auto.conf
  chown -R postgres:postgres /to && chmod 700 /to
'

echo "[promote] starting db only"
dc up -d db
echo "[promote] done in $(( $(date +%s) - T0 ))s."
echo "  undo copy  : ${SAFEVOL}  (keep until the restore is accepted; then docker volume rm it)"
echo "  next       : verify with psql; then docker compose -f infra/shared/docker-compose.yml up -d api caddy; then sh infra/shared/pitr/base-backup.sh"
