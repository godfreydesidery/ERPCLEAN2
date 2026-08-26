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
# The installer schedules `backup` to run every night, so you should not have to
# type it. `restore` asks you to type RESTORE first; add --yes to skip that when a
# script is driving it.
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
# Set by `restore` once it has staged the backup file it was given; removed by an EXIT trap
# whether the restore succeeds or fails. See cmd_restore.
STAGED_COPY=""
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
# Backup housekeeping
#
# Three different kinds of file end up in backups/, and they must NOT expire together:
#
#   orbixerp_<stamp>.dump                  the nightly and manual backups
#   orbixerp-preupdate_<stamp>_<from-to>   taken by `update`; the ONLY way to undo a
#                                          release that changed the database
#   safety-before-restore-<stamp>.dump     taken by `restore` just before it overwrites
#
# Older versions deleted only the first kind. That meant the safety copies accumulated
# for ever, while the rollback point for an update — the one file that can undo a
# release — was thrown away after fourteen days. Each kind now has its own lifetime,
# plus a floor (never prune below this many) and a ceiling (never let the folder grow
# past this many files or this many megabytes).
#
# Everything below writes to stderr, never stdout: `update` captures this script's
# stdout to learn the name of the rollback file.
# ---------------------------------------------------------------------------
BACKUP_DIR="$SCRIPT_DIR/backups"
ANY_BACKUP='^(orbixerp_|orbixerp-preupdate_|safety-before-restore-).*\.dump$'

# A setting that is not a whole number falls back to its default rather than producing a
# shell error in the middle of a backup.
env_int() {
  local raw; raw="$(env_get "$1" "$2")"
  case "$raw" in
    ''|*[!0-9]*) printf '%s' "$2" ;;
    *)           printf '%s' "$raw" ;;
  esac
}

# Backup files matching a pattern, newest first. `ls -t` sorts by modification time and is
# in POSIX, so it behaves the same on Linux and macOS. Backup filenames never contain spaces.
backup_list() {
  ( cd "$BACKUP_DIR" 2>/dev/null && ls -t 2>/dev/null ) | grep -E "$1" || true
}

# prune_by_age <pattern> <keep-days> <always-keep-newest>
#
# The "always keep newest" floor is what stops the machine being switched off for a month
# and the next backup then deleting every backup that exists.
prune_by_age() {
  local pattern="$1" days="$2" floor="$3" n=0 f
  [ "$days" -gt 0 ] 2>/dev/null || return 0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    n=$((n + 1))
    [ "$n" -le "$floor" ] && continue
    # -mtime +N means "last changed more than N days ago" and is portable.
    if [ -n "$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "$f" -mtime +"$days" 2>/dev/null)" ]; then
      rm -f "$BACKUP_DIR/$f" && info "  removed $f (older than ${days} days)" >&2
    fi
  done <<EOF
$(backup_list "$pattern")
EOF
}

# The ceilings, applied across all three kinds together: delete the oldest file until the
# folder is back inside its limits, and never go below the floor.
prune_to_limits() {
  local keep_min="$1" keep_max="$2" dir_max_mb="$3" files count size oldest
  while :; do
    files="$(backup_list "$ANY_BACKUP")"
    count="$(printf '%s' "$files" | grep -c . || true)"
    [ "${count:-0}" -gt "$keep_min" ] || return 0
    size="$(du -sm "$BACKUP_DIR" 2>/dev/null | cut -f1)"; size="${size:-0}"
    [ "$count" -gt "$keep_max" ] || [ "$size" -gt "$dir_max_mb" ] || return 0
    oldest="$(printf '%s\n' "$files" | grep . | tail -1)"
    [ -n "$oldest" ] || return 0
    rm -f "$BACKUP_DIR/$oldest"
    info "  removed $oldest to stay inside the backup limits (${count} files, ${size} MB)" >&2
  done
}

prune_backups() {
  [ -d "$BACKUP_DIR" ] || return 0
  prune_by_age '^orbixerp_.*\.dump$'              "$(env_int ERP_BACKUP_RETAIN_DAYS 14)"           0
  prune_by_age '^safety-before-restore-.*\.dump$' "$(env_int ERP_BACKUP_SAFETY_RETAIN_DAYS 30)"    3
  prune_by_age '^orbixerp-preupdate_.*\.dump$'    "$(env_int ERP_BACKUP_PREUPDATE_RETAIN_DAYS 90)" 5
  prune_to_limits "$(env_int ERP_BACKUP_KEEP_MIN 7)" \
                  "$(env_int ERP_BACKUP_KEEP_MAX 90)" \
                  "$(env_int ERP_BACKUP_DIR_MAX_MB 2048)"
}

# The nightly backup appends what it prints to backups/backup.log. Left alone that file
# grows for ever, in the same folder — and on the same disk — as the backups themselves.
trim_backup_log() {
  local log="$BACKUP_DIR/backup.log" bytes tmp
  [ -f "$log" ] || return 0
  bytes="$(wc -c < "$log" 2>/dev/null || echo 0)"
  [ "${bytes:-0}" -gt 1048576 ] 2>/dev/null || return 0
  tmp="$(mktemp)"
  tail -n 200 "$log" > "$tmp" && cat "$tmp" > "$log"
  rm -f "$tmp"
}

# Refuse to START a dump that cannot finish. A dump that runs out of disk halfway leaves a
# truncated file behind that looks exactly like a good backup in a directory listing.
check_disk_headroom() {
  local free_kb need_kb newest
  free_kb="$(df -Pk "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4}')" || free_kb=""
  case "${free_kb:-}" in ''|*[!0-9]*) return 0 ;; esac
  newest="$(backup_list '^orbixerp[_-].*\.dump$' | head -1)"
  if [ -n "$newest" ] && [ -f "$BACKUP_DIR/$newest" ]; then
    need_kb=$(( $(du -k "$BACKUP_DIR/$newest" | cut -f1) * 3 ))
  else
    need_kb=204800   # 200 MB, when there is no previous backup to measure against
  fi
  [ "$free_kb" -ge "$need_kb" ] || die "There is not enough free disk space to take a backup safely.

Free space where backups are kept : $((free_kb / 1024)) MB
Wanted before starting            : $((need_kb / 1024)) MB

Free some space on this disk, or lower ERP_BACKUP_KEEP_MAX / ERP_BACKUP_DIR_MAX_MB
in .env, and try again. Nothing was written."
}

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
# Nothing may be added to this function that prints to stdout.
#
# An optional label marks the backup as an update's rollback point. Those are named
# differently so they are kept for far longer than a nightly backup — see prune_backups().
cmd_backup() {
  require_env_file; require_docker
  mkdir -p "$BACKUP_DIR"
  trim_backup_log
  check_disk_headroom

  local label="${1:-}" stamp file name user
  stamp="$(date +%Y%m%d_%H%M%S)"
  if [ -n "$label" ]; then name="orbixerp-preupdate_${stamp}_${label}.dump"
  else                     name="orbixerp_${stamp}.dump"; fi
  file="$BACKUP_DIR/$name"
  user="$(env_get ERP_DB_USER erp)"

  step "Backing up the database to backups/$name" >&2
  # -Fc = PostgreSQL's compressed custom format, which pg_restore reads.
  # On failure the part-written file is DELETED. Left in place it would sit in the folder
  # looking like every other backup, and could be picked for a restore.
  pg_run pg_dump -h "$(db_host)" -p "$(db_port)" -U "$user" \
    -d "$(env_get ERP_DB_NAME erp)" -Fc -f "/backups/$name" \
    || { rm -f "$file"; die "Backup failed, and the part-written file has been deleted.
The database may be unreachable — check ./orbixerp.sh status."; }

  # An empty file is a failed backup that looks like a successful one. Refuse it, so a
  # broken backup can never be mistaken for a safe rollback point during an update.
  [ -s "$file" ] || { rm -f "$file"; die "Backup produced an empty file. Treating this as a failure — do not rely on it."; }
  ok "Backup complete: backups/$name ($(du -h "$file" | cut -f1))" >&2

  prune_backups
  printf '%s\n' "$file"
}

cmd_restore() {
  require_env_file; require_docker

  local assume_yes=0 file="" arg
  for arg in "$@"; do
    case "$arg" in
      # For a scheduled or scripted restore — a recovery drill, or a remote session with no
      # terminal. Typing RESTORE stays the default and nothing else about the command changes.
      --yes|-y) assume_yes=1 ;;
      -*) die "Unknown option '$arg'. Usage:  ./orbixerp.sh restore <file> [--yes]" ;;
      *)  if [ -n "$file" ]; then die "Restore takes one backup file, but two were given: $file and $arg"; fi
          file="$arg" ;;
    esac
  done
  [ -n "$file" ] || die "Which backup? Usage:  ./orbixerp.sh restore backups/orbixerp_20260801_120000.dump"
  [ -f "$file" ] || die "Backup file not found: $file"

  # The restore runs inside a container that can only see backups/, so the file has to be
  # in there. It is copied in under a name of OUR choosing, not its own.
  #
  # This used to reuse any file already in backups/ with the same name — so restoring
  # /media/usb/orbixerp_20260801_120000.dump, when a local file of that name existed,
  # silently restored the LOCAL one and reported success.
  local base staged
  base="$(basename "$file")"
  staged="restore-source-$$-$base"
  cp "$file" "$BACKUP_DIR/$staged" || die "Could not read the backup file: $file"
  # STAGED_COPY is deliberately global, not local: an EXIT trap runs after the function has
  # returned, where a local is already out of scope and would expand to nothing - leaving a
  # full-size file behind on every successful restore.
  STAGED_COPY="$BACKUP_DIR/$staged"
  trap 'rm -f "${STAGED_COPY:-}"' EXIT

  printf '\n%sThis REPLACES the current database with the contents of %s.%s\n' "$C_YEL" "$base" "$C_OFF"
  printf 'Everything recorded since that backup was taken will be lost.\n'
  printf 'It replaces the WHOLE database, so on a system shared by more than one\n'
  printf 'organisation every organisation is taken back to that moment, not just yours.\n\n'
  if [ "$assume_yes" = "1" ]; then
    warn "Continuing without asking, because --yes was given."
  else
    printf 'Type RESTORE to continue: '
    # Read from the terminal, not stdin, and treat "no terminal" as a refusal. Without the
    # fallback, running this non-interactively would hit EOF and `set -e` would abort with no
    # message at all — for a destructive command, silence is the worst possible outcome.
    local answer; read -r answer </dev/tty || answer=""
    [ "$answer" = "RESTORE" ] || die "Cancelled. Nothing was changed."
  fi

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
    -d "$dbname" --no-owner --exit-on-error "/backups/$staged" \
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

# An update rewrites most of the installation directory. Checking that it can, up front,
# turns a permission problem into one clear message BEFORE anything changes — instead of a
# raw `rm:` error half-way through, with the new images already loaded, .env still naming
# the old version, and the running container untouched.
#
# That is exactly what a root-owned docs/ produced: an installation created with sudo made
# `update` fail at the same line on every release, with nothing in the output naming the
# cause and the VERSION file already copied, so it read as though the update had worked.
assert_install_writable() {
  local blocked="" f
  local targets=(
    docker-compose.yml docker-compose.db-docker.yml docker-compose.db-host.yml
    docker-compose.tls.yml Caddyfile .env.example VERSION RELEASE-NOTES.md .env
    orbixerp.sh install.sh orbixerp.ps1 install.ps1 Setup.cmd setup-wizard.ps1
    Install.cmd OrbixERP.cmd Remote-Setup.cmd remote-setup-wizard.ps1
  )

  # New files land in the directory itself, so it must be writable even when every existing
  # file already is.
  if [ ! -w "$SCRIPT_DIR" ]; then blocked="${blocked}
  $SCRIPT_DIR"; fi

  for f in "${targets[@]}"; do
    if [ -e "$SCRIPT_DIR/$f" ] && [ ! -w "$SCRIPT_DIR/$f" ]; then blocked="${blocked}
  $SCRIPT_DIR/$f"; fi
  done

  # docs/ is refreshed guide by guide, so every existing guide must be writable; the folder
  # itself is only written to when a release adds or withdraws one.
  if [ -d "$SCRIPT_DIR/docs" ]; then
    if [ ! -w "$SCRIPT_DIR/docs" ]; then blocked="${blocked}
  $SCRIPT_DIR/docs"; fi
    for f in "$SCRIPT_DIR"/docs/*; do
      if [ -e "$f" ] && [ ! -w "$f" ]; then blocked="${blocked}
  $f"; fi
    done
  fi

  [ -n "$blocked" ] || return 0

  die "This update cannot replace the following, because they belong to another user:
${blocked}

You are running as $(id -un). Take ownership of those paths and run the update again:

    sudo chown -R $(id -un) <each path listed above>

Two things NOT to do:

  - Do not do this to secrets/. The signing keys must stay owned by uid 10001, the user the
    application runs as inside the container. Change them and nobody can sign in.
  - Do not run this script with sudo. It would work once and leave every file root-owned,
    putting you back here at the next release."
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

  # Before the backup and before the images are loaded: both are slow, and neither is worth
  # doing for an update that cannot finish.
  assert_install_writable

  # A backup BEFORE anything else, and a hard stop if it fails.
  #
  # This is not belt-and-braces. Database migrations only run forwards: once the new
  # version has upgraded the schema, going back to the old version will not start.
  # Restoring this backup is the ONLY way to undo an update.
  # Labelled, so housekeeping keeps it for ERP_BACKUP_PREUPDATE_RETAIN_DAYS (90 by default)
  # rather than the fourteen days a nightly backup gets. It used to be an ordinary nightly
  # backup, which meant the only file able to undo a release expired in a fortnight.
  step "Taking a safety backup first — the update stops here if it fails"
  local backup_file; backup_file="$(cmd_backup "${cur_version}-to-${new_version}")"
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
  # VERSION is deliberately NOT copied here — see the end of this function. It is the file a
  # human reads to ask "what is installed?", and copying it before the new version is
  # actually running makes a failed update claim success.
  for f in docker-compose.yml docker-compose.db-docker.yml docker-compose.db-host.yml \
           docker-compose.tls.yml Caddyfile .env.example; do
    [ -f "$src/$f" ] && cp "$src/$f" "$SCRIPT_DIR/$f"
  done
  # docs/ is refreshed in place rather than deleted and recreated. `rm -rf` needs write
  # permission on the FOLDER, which an installation created with sudo does not give the user
  # running the update; overwriting each guide needs write permission on the files only.
  if [ -d "$src/docs" ]; then
    mkdir -p "$SCRIPT_DIR/docs"
    # Guides the new release no longer ships are dropped, so an installation does not
    # accumulate documentation for features it no longer has. Best-effort: one leftover that
    # will not delete is worth a warning, not a failed update.
    for stale in "$SCRIPT_DIR"/docs/*; do
      [ -e "$stale" ] || continue
      if [ ! -e "$src/docs/$(basename "$stale")" ]; then
        rm -f "$stale" 2>/dev/null || warn "could not remove the withdrawn guide $(basename "$stale")"
      fi
    done
    cp -r "$src/docs/." "$SCRIPT_DIR/docs/"
  fi
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

  # VERSION last, once the new release is genuinely up. Copied early (as it was), a update
  # that died part-way left this file naming a version that was not running — so the
  # obvious "what is installed?" check confirmed a success that had not happened.
  [ -f "$src/VERSION" ] && cp "$src/VERSION" "$SCRIPT_DIR/VERSION"

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
  sed -n '3,21p' "$SCRIPT_PATH" | sed 's/^# \{0,1\}//'
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
    # No arguments passed through: the label is for `update`'s use only, and a stray word
    # on the command line must not quietly change the name — and the lifetime — of a backup.
    backup)       cmd_backup >/dev/null ;;
    restore)      cmd_restore "$@" ;;
    update)       cmd_update "$@" ;;
    version)      cmd_version "$@" ;;
    config)       require_env_file; require_docker; dc config ;;
    help|-h|--help) cmd_help ;;
    *) die "Unknown command '$cmd'. Run './orbixerp.sh help' to see what is available." ;;
  esac
}

main "$@"
