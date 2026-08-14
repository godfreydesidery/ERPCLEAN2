#!/usr/bin/env bash
#
# OrbixERP — day-to-day control script (Linux / macOS).
# Windows users: use orbixerp.ps1 instead.
#
#   ./orbixerp.sh start            start the system
#   ./orbixerp.sh stop             stop it (your data is kept)
#   ./orbixerp.sh restart          apply changes made to .env
#   ./orbixerp.sh status           is it running and healthy?
#   ./orbixerp.sh logs [-f]        show application logs
#   ./orbixerp.sh backup           write a database backup into backups/
#   ./orbixerp.sh restore <file>   REPLACE the database from a backup file
#   ./orbixerp.sh update <dir>     upgrade to a newer release bundle
#   ./orbixerp.sh version          what is installed
#
# This script works out which database mode you are in (ERP_DB_MODE in .env) and
# assembles the right docker compose command, so you never have to.
#
# Every command is wrapped in a function and dispatched from main() at the bottom.
# bash parses the whole file before running it, which is what makes `update` able to
# replace this very script safely while it is running.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Resolved BEFORE the cd below: $BASH_SOURCE may be relative (./orbixerp.sh), which stops
# resolving the moment the working directory changes.
SCRIPT_PATH="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
cd "$SCRIPT_DIR"

ENV_FILE="$SCRIPT_DIR/.env"
POSTGRES_IMAGE="postgres:15-alpine"
# PROJECT / API_CONTAINER are resolved from .env after env_get is defined (see below) —
# ERP_STACK_NAME namespaces the compose project, containers, network and volume.

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_BLD=$'\033[1m'; C_OFF=$'\033[0m'
else
  C_RED=''; C_GRN=''; C_YEL=''; C_BLD=''; C_OFF=''
fi

info() { printf '%s\n' "$*"; }
step() { printf '%s==>%s %s\n' "$C_BLD" "$C_OFF" "$*"; }
ok()   { printf '%s  ok%s  %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%swarn%s  %s\n' "$C_YEL" "$C_OFF" "$*" >&2; }
die()  { printf '\n%sERROR%s  %s\n\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# .env access
#
# Values are read with grep/sed rather than by sourcing the file: sourcing would
# execute anything in it and would choke on values containing spaces or '#'.
# The trailing \r strip matters — a .env saved by a Windows editor otherwise gives
# every value an invisible carriage return, which produces baffling failures such
# as a password that is silently wrong or a JDBC URL that will not parse.
# ---------------------------------------------------------------------------
env_get() {
  local key="$1" default="${2:-}" value
  [ -f "$ENV_FILE" ] || { printf '%s' "$default"; return; }
  value="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" 2>/dev/null | tail -1 | sed -E "s/^[[:space:]]*${key}=//" | tr -d '\r')" || true
  value="${value%\"}"; value="${value#\"}"
  value="${value%\'}"; value="${value#\'}"
  [ -n "$value" ] && printf '%s' "$value" || printf '%s' "$default"
}

env_set() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  if grep -qE "^[[:space:]]*${key}=" "$ENV_FILE" 2>/dev/null; then
    # awk rather than `sed -i`: sed's in-place flag differs between GNU and BSD/macOS.
    awk -v k="$key" -v v="$value" '
      $0 ~ "^[[:space:]]*"k"=" && !done { print k"="v; done=1; next } { print }
    ' "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"
    printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  cat "$tmp" > "$ENV_FILE"
  rm -f "$tmp"
}

require_env_file() {
  [ -f "$ENV_FILE" ] || die "No .env file found in $SCRIPT_DIR.
This system has not been installed yet. Run:  ./install.sh"
}

# Resolved once, after env_get exists. Must match the ${ERP_STACK_NAME:-orbixerp} defaults in the
# compose files, or the scripts would look for containers and networks that do not exist.
PROJECT="$(env_get ERP_STACK_NAME orbixerp)"
API_CONTAINER="${PROJECT}-api"

# ---------------------------------------------------------------------------
# Compose command assembly — the heart of the two database modes
# ---------------------------------------------------------------------------
# The file list is assembled directly here, in the CALLER's shell, rather than by a helper
# whose output is captured. A helper would run in a subshell, where `die` exits only that
# subshell — an invalid ERP_DB_MODE would print the error and then run docker compose with
# an empty file list anyway.
dc() {
  local mode; mode="$(env_get ERP_DB_MODE docker)"
  local files=(-f docker-compose.yml)

  case "$mode" in
    docker) files+=(-f docker-compose.db-docker.yml) ;;
    host)   files+=(-f docker-compose.db-host.yml) ;;
    *) die "ERP_DB_MODE in .env is '$mode' but must be either 'docker' or 'host'.
  docker = we run the database for you in a container
  host   = you point us at your own PostgreSQL server (see docs/HOST-DB-SETUP.md)" ;;
  esac

  if [ "$(env_get ERP_TLS_ENABLED false)" = "true" ]; then
    files+=(-f docker-compose.tls.yml)
  fi

  docker compose -p "$PROJECT" "${files[@]}" "$@"
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die "Docker is not installed, or not on this account's PATH."
  docker info >/dev/null 2>&1 || die "Docker is installed but not running. Start Docker and try again."
  docker compose version >/dev/null 2>&1 || die "This version of Docker is too old — it has no 'docker compose' command.
Install Docker Engine 20.10+ / Docker Desktop 4.x or newer."
}

# ---------------------------------------------------------------------------
# Health
# ---------------------------------------------------------------------------
container_health() {
  # Reports one of: healthy | starting | unhealthy | running | stopped | absent.
  # Both fields are needed: a container that has crashed can still report a stale
  # health value, so the running state is checked first.
  local out status health
  out="$(docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$API_CONTAINER" 2>/dev/null)" \
    || { printf 'absent'; return; }
  status="${out%%|*}"; health="${out##*|}"
  [ "$status" = "running" ] || { printf 'stopped'; return; }
  [ "$health" = "none" ] && printf 'running' || printf '%s' "$health"
}

wait_healthy() {
  # First start applies 93 database migrations, so this deliberately waits a long time.
  local timeout="${1:-900}" waited=0 state
  step "Waiting for the system to become ready (first start applies database migrations and can take several minutes)"
  while [ "$waited" -lt "$timeout" ]; do
    state="$(container_health)"
    case "$state" in
      healthy) printf '\n'; ok "System is ready."; return 0 ;;
      stopped) printf '\n'; show_failure_context
        die "The application stopped unexpectedly. The last log lines are above.
docs/TROUBLESHOOTING.md lists what usually causes this." ;;
    esac
    printf '.'
    sleep 5
    waited=$((waited + 5))
  done
  printf '\n'
  show_failure_context
  die "The system did not become ready within $((timeout / 60)) minutes.
The last log lines are above — docs/TROUBLESHOOTING.md explains the common causes."
}

show_failure_context() {
  warn "Last 40 log lines from the application:"
  docker logs --tail 40 "$API_CONTAINER" 2>&1 | sed 's/^/    /' || true
}

access_url() {
  local port host scheme
  if [ "$(env_get ERP_TLS_ENABLED false)" = "true" ]; then
    scheme="https"; port="$(env_get ERP_HTTPS_PORT 443)"; host="$(env_get ERP_PUBLIC_HOST localhost)"
    [ "$port" = "443" ] && printf '%s://%s' "$scheme" "$host" || printf '%s://%s:%s' "$scheme" "$host" "$port"
  else
    port="$(env_get ERP_HTTP_PORT 8080)"
    printf 'http://%s:%s' "$(hostname 2>/dev/null || echo localhost)" "$port"
  fi
}

# ---------------------------------------------------------------------------
# Database access for backup / restore
#
# One code path for both modes: a throwaway postgres container runs pg_dump or
# pg_restore. In docker mode it joins the stack's private network and talks to the
# `db` service; in host mode it reaches your own server. This is also why the
# postgres image is shipped even when you use your own database.
# ---------------------------------------------------------------------------
pg_run() {
  local mode; mode="$(env_get ERP_DB_MODE docker)"
  local args=(run --rm
    -e "PGPASSWORD=$(env_get ERP_DB_PASSWORD)"
    -v "$SCRIPT_DIR/backups:/backups")

  if [ "$mode" = "docker" ]; then
    docker network inspect "${PROJECT}_default" >/dev/null 2>&1 \
      || die "The system is not running, so its database cannot be reached.
Start it first:  ./orbixerp.sh start"
    args+=(--network "${PROJECT}_default")
  else
    args+=(--add-host "host.docker.internal:host-gateway")
  fi

  docker "${args[@]}" "$POSTGRES_IMAGE" "$@"
}

db_host() { [ "$(env_get ERP_DB_MODE docker)" = "docker" ] && printf 'db' || printf '%s' "$(env_get ERP_DB_HOST host.docker.internal)"; }
db_port() { [ "$(env_get ERP_DB_MODE docker)" = "docker" ] && printf '5432' || printf '%s' "$(env_get ERP_DB_PORT 5432)"; }

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------
cmd_start() {
  require_env_file; require_docker
  step "Starting OrbixERP (database mode: $(env_get ERP_DB_MODE docker))"
  dc up -d
  wait_healthy
  printf '\n  %sOpen%s  %s\n\n' "$C_BLD" "$C_OFF" "$(access_url)"
}

cmd_stop() {
  require_env_file; require_docker
  step "Stopping OrbixERP"
  # `down` WITHOUT -v. The -v flag would delete the database volume and every
  # transaction in it. It is never used anywhere in this script.
  dc down
  ok "Stopped. Your data is intact — './orbixerp.sh start' brings it back."
}

cmd_restart() { cmd_stop; cmd_start; }

cmd_status() {
  require_env_file; require_docker
  step "Containers"
  dc ps
  printf '\n'
  step "Application health"
  local state; state="$(container_health)"
  case "$state" in
    healthy)   ok "healthy — $(access_url)" ;;
    starting)  warn "starting — still applying migrations or warming up" ;;
    unhealthy) warn "unhealthy — see ./orbixerp.sh logs and docs/TROUBLESHOOTING.md" ;;
    absent)    warn "not running — ./orbixerp.sh start" ;;
    *)         warn "$state" ;;
  esac
  printf '\n'
  step "Version"
  info "  installed: $(env_get ERP_VERSION unknown)   database mode: $(env_get ERP_DB_MODE docker)"
}

cmd_logs() {
  require_env_file; require_docker
  if [ $# -eq 0 ]; then dc logs --tail 200 api; else dc logs "$@"; fi
}

# Progress messages go to stderr; the ONLY thing on stdout is the path of the file
# written, so `cmd_update` can capture it with $(...) and offer it as the rollback point.
cmd_backup() {
  require_env_file; require_docker
  mkdir -p "$SCRIPT_DIR/backups"

  local stamp file name user
  stamp="$(date +%Y%m%d_%H%M%S)"
  name="orbixerp_${stamp}.dump"
  file="$SCRIPT_DIR/backups/$name"
  user="$(env_get ERP_DB_USER erp)"

  step "Backing up the database to backups/$name" >&2
  # -Fc = PostgreSQL's compressed custom format, which pg_restore reads.
  pg_run pg_dump -h "$(db_host)" -p "$(db_port)" -U "$user" \
    -d "$(env_get ERP_DB_NAME erp)" -Fc -f "/backups/$name" \
    || die "Backup failed. Nothing was written. The database may be unreachable — check ./orbixerp.sh status."

  # An empty file is a failed backup that looks like a successful one. Refuse it, so a
  # broken backup can never be mistaken for a safe rollback point during an update.
  [ -s "$file" ] || die "Backup produced an empty file. Treating this as a failure — do not rely on it."
  ok "Backup complete: backups/$name ($(du -h "$file" | cut -f1))" >&2

  local retain; retain="$(env_get ERP_BACKUP_RETAIN_DAYS 14)"
  if [ "$retain" -gt 0 ] 2>/dev/null; then
    find "$SCRIPT_DIR/backups" -name 'orbixerp_*.dump' -type f -mtime +"$retain" -delete 2>/dev/null || true
    info "  Backups older than ${retain} days have been removed." >&2
  fi
  printf '%s\n' "$file"
}

cmd_restore() {
  require_env_file; require_docker
  local file="${1:-}"
  [ -n "$file" ] || die "Which backup? Usage:  ./orbixerp.sh restore backups/erp_20260801_120000.dump"
  [ -f "$file" ] || die "Backup file not found: $file"

  local base; base="$(basename "$file")"
  [ -f "$SCRIPT_DIR/backups/$base" ] || cp "$file" "$SCRIPT_DIR/backups/$base"

  printf '\n%sThis REPLACES the current database with the contents of %s.%s\n' "$C_YEL" "$base" "$C_OFF"
  printf 'Everything recorded since that backup was taken will be lost.\n\n'
  printf 'Type RESTORE to continue: '
  # Read from the terminal, not stdin, and treat "no terminal" as a refusal. Without the
  # fallback, running this non-interactively would hit EOF and `set -e` would abort with no
  # message at all — for a destructive command, silence is the worst possible outcome.
  local answer; read -r answer </dev/tty || answer=""
  [ "$answer" = "RESTORE" ] || die "Cancelled. Nothing was changed."

  step "Stopping the application (the database keeps running)"
  dc stop api >/dev/null

  local dbuser dbname safety
  dbuser="$(env_get ERP_DB_USER erp)"
  dbname="$(env_get ERP_DB_NAME erp)"
  safety="safety-before-restore-$(date +%Y%m%d-%H%M%S).dump"

  # A restore is the one irreversible command in this script, and until now it took no
  # safety copy: restoring the wrong file destroyed the current database with nothing to
  # go back to. Take one first. If this fails, stop — we are not proceeding without a net.
  step "Taking a safety copy of the CURRENT database first"
  pg_run pg_dump -h "$(db_host)" -p "$(db_port)" -U "$dbuser" -d "$dbname" \
    -Fc -f "/backups/$safety" \
    || die "Could not back up the current database, so the restore has been cancelled.
Nothing was changed. Check that the database is running:  ./orbixerp.sh status"
  ok "Safety copy saved: backups/$safety"

  # Empty the schema, then restore into it.
  #
  # This replaces `pg_restore --clean --if-exists`, which drops objects one by one in the
  # dump's own order. That fails whenever the live database has an object the backup does
  # not know about — a constraint added by a newer release, for example — because the
  # dependent object blocks the drop. The restore then half-succeeds, and the old code
  # downgraded that to a warning and printed "Restore complete" over a database that had
  # only partly been rolled back.
  #
  # DROP SCHEMA ... CASCADE removes everything regardless of what the backup contains, and
  # needs only the owning role — not a superuser, and not CREATEDB, which the application
  # role does not have.
  step "Clearing the current database"
  pg_run psql -h "$(db_host)" -p "$(db_port)" -U "$dbuser" -d "$dbname" \
    -v ON_ERROR_STOP=1 -q \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity
         WHERE datname = current_database() AND pid <> pg_backend_pid();" \
    -c "DROP SCHEMA IF EXISTS public CASCADE;" \
    -c "CREATE SCHEMA public AUTHORIZATION \"$dbuser\";" \
    -c "GRANT ALL ON SCHEMA public TO \"$dbuser\";" \
    || die "Could not clear the database, so nothing has been restored.
Your data is untouched and a safety copy is at backups/$safety
Start the system again with:  ./orbixerp.sh start"

  step "Restoring"
  # No --clean: the schema is already empty. --no-owner tolerates a dump taken under a
  # differently-named role. Errors are now FATAL: a partly-restored database that reports
  # success is worse than a failure you can see.
  pg_run pg_restore -h "$(db_host)" -p "$(db_port)" -U "$dbuser" \
    -d "$dbname" --no-owner --exit-on-error "/backups/$base" \
    || die "The restore FAILED and the database is now incomplete. Do not start the system.
Restore the safety copy taken a moment ago:
    ./orbixerp.sh restore backups/$safety
If that also fails, contact support and quote both file names."

  step "Restarting the application"
  dc up -d
  wait_healthy
  ok "Restore complete."
  printf '  The safety copy of the database as it was before this restore is kept at\n'
  printf '  backups/%s in case you need to undo this.\n' "$safety"
}

cmd_update() {
  require_env_file; require_docker
  local src="${1:-}"
  [ -n "$src" ] || die "Which release? Usage:  ./orbixerp.sh update /path/to/erp-1.1.0"
  [ -d "$src" ] || die "Not a directory: $src"
  [ -f "$src/VERSION" ] || die "$src does not look like an OrbixERP release bundle (no VERSION file)."

  local new_version; new_version="$(grep -E '^ERP_VERSION=' "$src/VERSION" | cut -d= -f2 | tr -d '\r')"
  local cur_version; cur_version="$(env_get ERP_VERSION unknown)"
  local new_arch;    new_arch="$(grep -E '^BUNDLE_ARCH=' "$src/VERSION" | cut -d= -f2 | tr -d '\r')"

  step "Updating from ${cur_version} to ${new_version}"

  local host_arch; host_arch="$(uname -m)"
  case "$host_arch" in
    x86_64|amd64) host_arch=amd64 ;;
    aarch64|arm64) host_arch=arm64 ;;
  esac
  [ "$new_arch" = "$host_arch" ] || die "That release bundle is built for ${new_arch}, but this machine is ${host_arch}.
Ask your supplier for the ${host_arch} bundle."

  # A backup BEFORE anything else, and a hard stop if it fails.
  #
  # This is not belt-and-braces. Database migrations only run forwards: once the new
  # version has upgraded the schema, going back to the old version will not start.
  # Restoring this backup is the ONLY way to undo an update.
  step "Taking a safety backup first — the update stops here if it fails"
  local backup_file; backup_file="$(cmd_backup)"
  info "  If this update goes wrong, undo it with:"
  info "    ./orbixerp.sh restore $backup_file"

  step "Loading the new application image"
  local loaded=0
  for tarball in "$src"/images/*.tar.gz "$src"/images/*.tar; do
    [ -f "$tarball" ] || continue
    info "  loading $(basename "$tarball")"
    docker load -i "$tarball" >/dev/null || die "Could not load $tarball"
    loaded=$((loaded + 1))
  done
  [ "$loaded" -gt 0 ] || die "No image files found in $src/images/"

  step "Updating configuration files"
  # .env, secrets/ and backups/ are yours and are never touched.
  for f in docker-compose.yml docker-compose.db-docker.yml docker-compose.db-host.yml \
           docker-compose.tls.yml Caddyfile .env.example VERSION; do
    [ -f "$src/$f" ] && cp "$src/$f" "$SCRIPT_DIR/$f"
  done
  [ -d "$src/docs" ] && { rm -rf "$SCRIPT_DIR/docs"; cp -r "$src/docs" "$SCRIPT_DIR/docs"; }
  [ -f "$src/RELEASE-NOTES.md" ] && cp "$src/RELEASE-NOTES.md" "$SCRIPT_DIR/RELEASE-NOTES.md"

  env_set ERP_VERSION "$new_version"
  # First-run setup must not re-trigger on an existing database.
  env_set ERP_BOOTSTRAP_ENABLED "false"

  step "Starting version ${new_version}"
  dc up -d
  wait_healthy

  # Control scripts replaced last. Safe mid-run because bash has already parsed this
  # entire file into memory before executing main().
  for f in orbixerp.sh install.sh; do
    [ -f "$src/$f" ] && { cp "$src/$f" "$SCRIPT_DIR/$f"; chmod +x "$SCRIPT_DIR/$f"; }
  done
  # The Windows launchers travel with the bundle too, so a shared installation stays
  # consistent whichever platform performed the update.
  for f in orbixerp.ps1 install.ps1 Setup.cmd setup-wizard.ps1 Install.cmd OrbixERP.cmd \
           Remote-Setup.cmd remote-setup-wizard.ps1; do
    [ -f "$src/$f" ] && cp "$src/$f" "$SCRIPT_DIR/$f"
  done

  printf '\n'
  ok "Updated to ${new_version}."
  info "  Rollback if needed:  ./orbixerp.sh restore $backup_file"
  printf '\n'
}

cmd_version() {
  info "installed version : $(env_get ERP_VERSION unknown)"
  info "database mode     : $(env_get ERP_DB_MODE docker)"
  [ -f "$SCRIPT_DIR/VERSION" ] && { info ""; info "release bundle:"; sed 's/^/  /' "$SCRIPT_DIR/VERSION"; }
}

# Prints the client-facing part of this file's header (stops before the maintainer notes).
cmd_help() {
  sed -n '3,17p' "$SCRIPT_PATH" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
main() {
  local cmd="${1:-help}"; shift || true
  case "$cmd" in
    start|up)     cmd_start "$@" ;;
    stop|down)    cmd_stop "$@" ;;
    restart)      cmd_restart "$@" ;;
    status|ps)    cmd_status "$@" ;;
    logs)         cmd_logs "$@" ;;
    backup)       cmd_backup "$@" >/dev/null ;;
    restore)      cmd_restore "$@" ;;
    update)       cmd_update "$@" ;;
    version)      cmd_version "$@" ;;
    config)       require_env_file; require_docker; dc config ;;
    help|-h|--help) cmd_help ;;
    *) die "Unknown command '$cmd'. Run './orbixerp.sh help' to see what is available." ;;
  esac
}

main "$@"
