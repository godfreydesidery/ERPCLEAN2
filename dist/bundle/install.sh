#!/usr/bin/env bash
#
# OrbixERP — installer (Linux / macOS).
# Windows users: use install.ps1 instead.
#
#   ./install.sh              guided install — asks a few questions
#   ./install.sh --defaults   unattended install using the values already in .env
#
# Safe to run more than once: an existing .env is never overwritten, existing signing
# keys are never regenerated, and an existing database is never touched.
#
# What it does, in order:
#   1. checks this bundle matches your machine and that Docker is usable
#   2. loads the application images from images/ (no internet required)
#   3. creates .env and generates the passwords and signing keys
#   4. checks the network port is free and, in `host` database mode, that your
#      database is reachable, the credentials work, and it is safe to install into
#   5. starts everything and waits until it is genuinely ready
#
# Everything is inside functions and dispatched from main() at the bottom, so the file
# is fully parsed before any of it runs.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Resolved BEFORE the cd below: $BASH_SOURCE may be relative (./install.sh), which stops
# resolving the moment the working directory changes.
SCRIPT_PATH="$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")"
cd "$SCRIPT_DIR"

ENV_FILE="$SCRIPT_DIR/.env"
ENV_TEMPLATE="$SCRIPT_DIR/.env.example"
VERSION_FILE="$SCRIPT_DIR/VERSION"
POSTGRES_IMAGE="postgres:15-alpine"
ASSUME_DEFAULTS=0

if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'; C_BLD=$'\033[1m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
  C_RED=''; C_GRN=''; C_YEL=''; C_BLD=''; C_DIM=''; C_OFF=''
fi

info() { printf '%s\n' "$*"; }
step() { printf '\n%s==>%s %s\n' "$C_BLD" "$C_OFF" "$*"; }
ok()   { printf '%s  ok%s  %s\n' "$C_GRN" "$C_OFF" "$*"; }
warn() { printf '%swarn%s  %s\n' "$C_YEL" "$C_OFF" "$*" >&2; }
die()  { printf '\n%sINSTALLATION STOPPED%s\n\n%s\n\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# .env access. See the note in orbixerp.sh about why values are grepped, not sourced,
# and why the carriage return is stripped.
# ---------------------------------------------------------------------------
env_get() {
  local key="$1" default="${2:-}" value
  [ -f "$ENV_FILE" ] || { printf '%s' "$default"; return; }
  value="$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" 2>/dev/null | tail -1 | sed -E "s/^[[:space:]]*${key}=//" | tr -d '\r')" || true
  value="${value%\"}"; value="${value#\"}"
  [ -n "$value" ] && printf '%s' "$value" || printf '%s' "$default"
}

env_set() {
  local key="$1" value="$2" tmp; tmp="$(mktemp)"
  if grep -qE "^[[:space:]]*${key}=" "$ENV_FILE" 2>/dev/null; then
    awk -v k="$key" -v v="$value" '
      $0 ~ "^[[:space:]]*"k"=" && !done { print k"="v; done=1; next } { print }
    ' "$ENV_FILE" > "$tmp"
  else
    cat "$ENV_FILE" > "$tmp"; printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
}

meta_get() {
  [ -f "$VERSION_FILE" ] || { printf ''; return; }
  grep -E "^$1=" "$VERSION_FILE" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r'
}

ask() {
  # ask <prompt> <default> ; echoes the answer
  local prompt="$1" default="$2" answer
  if [ "$ASSUME_DEFAULTS" = "1" ]; then printf '%s' "$default"; return; fi
  printf '%s %s[%s]%s: ' "$prompt" "$C_DIM" "$default" "$C_OFF" >&2
  read -r answer </dev/tty || answer=""
  [ -n "$answer" ] && printf '%s' "$answer" || printf '%s' "$default"
}

image_ref() { printf '%s:%s' "$(env_get ERP_IMAGE orbixerp-api)" "$(env_get ERP_VERSION "$(meta_get ERP_VERSION)")"; }

# ---------------------------------------------------------------------------
# 1. Environment checks
# ---------------------------------------------------------------------------
detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64)  printf 'amd64' ;;
    aarch64|arm64) printf 'arm64' ;;
    *)             printf '%s' "$(uname -m)" ;;
  esac
}

check_arch() {
  local bundle host; bundle="$(meta_get BUNDLE_ARCH)"; host="$(detect_arch)"
  [ -n "$bundle" ] || return 0
  # Caught here deliberately. Docker's own failure for a wrong-architecture image is
  # "exec format error", which tells a client nothing actionable.
  [ "$bundle" = "$host" ] || die "This installation bundle is built for ${bundle} processors,
but this machine is ${host}.

Ask your supplier for the ${host} bundle. Nothing has been changed."
  ok "Processor architecture: ${host}"
}

check_docker() {
  command -v docker >/dev/null 2>&1 || die "Docker is not installed on this machine (or not available to this user).

Install Docker first:
  Linux  — curl -fsSL https://get.docker.com | sudo sh
           then: sudo usermod -aG docker \$USER   (log out and back in)
  macOS  — install Docker Desktop from docker.com"

  docker info >/dev/null 2>&1 || die "Docker is installed but is not running, or this user may not use it.

  - start Docker (or Docker Desktop) and try again
  - on Linux, if you see a permissions error:  sudo usermod -aG docker \$USER
    then log out and back in for the change to take effect"

  docker compose version >/dev/null 2>&1 || die "Your Docker installation is too old — it does not have the 'docker compose' command.
Upgrade to Docker Engine 20.10+ or Docker Desktop 4.x or newer."

  ok "Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null || echo '') is running"
}

# ---------------------------------------------------------------------------
# Install location
#
# The same convention as the Windows wizard: OrbixERP lives in its OWN directory
# (/opt/orbixerp by default), never inside a system directory. Everything - the compose
# files, .env, the signing keys and the backups - stays in that one tree, so it can be
# copied to another machine, handed to an IT provider, or backed up wholesale.
#
# If the bundle was already unpacked somewhere sensible, installing in place is offered as
# the default so nobody is forced to move files around.
# ---------------------------------------------------------------------------
DEFAULT_INSTALL_DIR="/opt/orbixerp"
INSTALL_DIR=""

# System directories are refused outright. There is no "run it with sudo anyway" option:
# taking it yields an installation that needs root for every future update and backup.
is_system_dir() {
  case "${1%/}" in
    ''|/|/bin|/sbin|/lib|/lib64|/usr|/usr/bin|/usr/sbin|/usr/lib|/etc|/boot|/proc|/sys|/dev|/run|/var|/root) return 0 ;;
    /bin/*|/sbin/*|/lib/*|/lib64/*|/usr/bin/*|/usr/sbin/*|/usr/lib/*|/etc/*|/boot/*|/proc/*|/sys/*|/dev/*) return 0 ;;
  esac
  return 1
}

# Cleared on reboot, or synchronised away. The install directory holds the signing keys and
# the backups, so this is worth one question.
is_volatile_dir() {
  case "${1%/}" in
    /tmp|/tmp/*|/var/tmp|/var/tmp/*|"$HOME"/Downloads|"$HOME"/Downloads/*|"$HOME"/Desktop|"$HOME"/Desktop/*) return 0 ;;
  esac
  return 1
}

choose_install_dir() {
  step "Install location"

  # Already unpacked somewhere reasonable? Offer that, rather than making them move files.
  local suggested="$DEFAULT_INSTALL_DIR"
  if ! is_system_dir "$SCRIPT_DIR" && ! is_volatile_dir "$SCRIPT_DIR"; then
    suggested="$SCRIPT_DIR"
  fi

  while true; do
    INSTALL_DIR="$(ask "Install OrbixERP into" "$suggested")"
    INSTALL_DIR="${INSTALL_DIR%/}"

    case "$INSTALL_DIR" in
      /*) ;;
      *) warn "Enter a full path, starting with /"; continue ;;
    esac

    if is_system_dir "$INSTALL_DIR"; then
      warn "$INSTALL_DIR is a system directory."
      info "  OrbixERP installs into its own directory so it needs no root rights to run,"
      info "  update or back up, and so everything stays together in one tree."
      info "  Try $DEFAULT_INSTALL_DIR instead."
      continue
    fi

    # An existing installation must never be written over: a fresh .env carries a NEW
    # database password while the existing database still expects the old one, and new
    # signing keys log every user out. Upgrades are './orbixerp.sh update', which backs up first.
    if [ -f "$INSTALL_DIR/.env" ] && [ "$INSTALL_DIR" != "$SCRIPT_DIR" ]; then
      warn "$INSTALL_DIR already contains an OrbixERP installation."
      info "  Installing over it would replace its settings and security keys, and that"
      info "  system would stop working. To upgrade it instead, run from that directory:"
      info "      ./orbixerp.sh update \"$SCRIPT_DIR\""
      continue
    fi

    if is_volatile_dir "$INSTALL_DIR"; then
      warn "$INSTALL_DIR is cleared or synchronised by the system."
      info "  Your settings, security keys and backups would live there."
      [ "$(ask "  Use it anyway? (yes/no)" "no")" = "yes" ] || continue
    fi

    if ! mkdir -p "$INSTALL_DIR" 2>/dev/null || [ ! -w "$INSTALL_DIR" ]; then
      warn "Cannot write to $INSTALL_DIR with this user account."
      info "  Either pick a directory you own, or create it first:"
      info "      sudo mkdir -p $INSTALL_DIR && sudo chown \$USER $INSTALL_DIR"
      continue
    fi

    break
  done

  # Copy the operating files across, but not images/ - those go into Docker in a moment and
  # are dead weight afterwards.
  if [ "$INSTALL_DIR" != "$SCRIPT_DIR" ]; then
    info "  Copying files to $INSTALL_DIR"
    for entry in "$SCRIPT_DIR"/*; do
      [ "$(basename "$entry")" = "images" ] && continue
      cp -R "$entry" "$INSTALL_DIR"/ 2>/dev/null || true
    done
    ENV_FILE="$INSTALL_DIR/.env"
    ENV_TEMPLATE="$INSTALL_DIR/.env.example"
    chmod +x "$INSTALL_DIR/orbixerp.sh" "$INSTALL_DIR/install.sh" 2>/dev/null || true
  fi
  mkdir -p "$INSTALL_DIR/backups" "$INSTALL_DIR/secrets/jwt"
  chmod 700 "$INSTALL_DIR/secrets" 2>/dev/null || true
  ok "Installing into $INSTALL_DIR"
}

# ---------------------------------------------------------------------------
# 2. Images
# ---------------------------------------------------------------------------
load_images() {
  step "Loading application images"
  local count=0
  shopt -s nullglob
  for tarball in "$SCRIPT_DIR"/images/*.tar.gz "$SCRIPT_DIR"/images/*.tar; do
    info "  $(basename "$tarball")"
    # `docker load` decompresses gzip itself — no need to pipe through gunzip.
    docker load -i "$tarball" >/dev/null || die "Could not load $(basename "$tarball").
The file may be incomplete — check it against CHECKSUMS.txt and ask for a fresh copy if it does not match."
    count=$((count + 1))
  done
  shopt -u nullglob

  [ "$count" -gt 0 ] || die "No image files were found in $SCRIPT_DIR/images/

The bundle appears to be incomplete. It should contain the application image,
the PostgreSQL image and the Caddy image as .tar.gz files."
  ok "$count image(s) loaded"
}

# ---------------------------------------------------------------------------
# 3. Configuration
# ---------------------------------------------------------------------------
create_env() {
  step "Configuration"
  if [ -f "$ENV_FILE" ]; then
    check_version_match
    ok "Using the existing .env (your settings are kept — nothing was overwritten)"
    return
  fi
  [ -f "$ENV_TEMPLATE" ] || die "The configuration template .env.example is missing from this bundle."
  cp "$ENV_TEMPLATE" "$ENV_FILE"
  chmod 600 "$ENV_FILE" 2>/dev/null || true
  ok "Created .env from the template"
  [ "$ASSUME_DEFAULTS" = "1" ] || configure_interactively
}

# An installed system records its version in .env; the bundle records its own in VERSION.
# If a NEWER bundle's installer is run over an existing installation, those disagree — and
# quietly rewriting .env would perform an upgrade with no safety backup, which is exactly
# what `erp update` exists to prevent. So this stops instead and points at the right command.
check_version_match() {
  local installed bundle
  installed="$(env_get ERP_VERSION)"
  bundle="$(meta_get ERP_VERSION)"

  [ -n "$bundle" ] || return 0

  if [ -z "$installed" ] || [ "$installed" = "__ERP_VERSION__" ]; then
    env_set ERP_VERSION "$bundle"
    return 0
  fi

  if [ "$installed" = "$bundle" ]; then return 0; fi

  die "This bundle is version ${bundle}, but version ${installed} is already installed here.

Upgrading is not the installer's job, because an upgrade must take a database backup
first. Use the update command instead, from your existing installation:

    ./orbixerp.sh update \"$SCRIPT_DIR\"

It backs up, upgrades, and tells you how to undo it. Nothing has been changed."
}

configure_interactively() {
  printf '\n%sA few questions. Press Enter to accept the value in brackets.%s\n\n' "$C_DIM" "$C_OFF"

  local mode
  while true; do
    mode="$(ask "Database — type 'docker' to let us run it for you, or 'host' to use your own PostgreSQL server" "$(env_get ERP_DB_MODE docker)")"
    case "$mode" in docker|host) break ;; *) warn "Please answer 'docker' or 'host'." ;; esac
  done
  env_set ERP_DB_MODE "$mode"

  if [ "$mode" = "host" ]; then
    printf '\n%sYour PostgreSQL server must already have an EMPTY database and a login role\nthat owns it. docs/HOST-DB-SETUP.md has the exact commands.%s\n\n' "$C_DIM" "$C_OFF"
    env_set ERP_DB_HOST     "$(ask "  Database server address (use host.docker.internal if it is on this machine)" "$(env_get ERP_DB_HOST host.docker.internal)")"
    env_set ERP_DB_PORT     "$(ask "  Port" "$(env_get ERP_DB_PORT 5432)")"
    env_set ERP_DB_NAME     "$(ask "  Database name" "$(env_get ERP_DB_NAME erp)")"
    env_set ERP_DB_USER     "$(ask "  Login role" "$(env_get ERP_DB_USER erp)")"
    # Read silently from the terminal. If there is no terminal (piped input, CI, a remote
    # runner) this would otherwise hit EOF and abort with no explanation at all, so say
    # plainly what to do instead.
    local pw=""
    while [ -z "$pw" ]; do
      printf '  Password for that role: ' >&2
      if ! read -rs pw </dev/tty; then
        printf '\n' >&2
        die "No terminal is available to read the password.

Put it into .env yourself as   ERP_DB_PASSWORD=<the password>
then run:                      ./install.sh --defaults"
      fi
      printf '\n' >&2
      [ -n "$pw" ] || warn "  A password is required in host mode."
    done
    env_set ERP_DB_PASSWORD "$pw"
  fi

  printf '\n'
  env_set ERP_HTTP_PORT            "$(ask "Port for users to reach the system in their browser" "$(env_get ERP_HTTP_PORT 8080)")"
  env_set ERP_BOOTSTRAP_ORG_NAME   "$(ask "Organisation name" "$(env_get ERP_BOOTSTRAP_ORG_NAME 'My Organisation')")"
  env_set ERP_BOOTSTRAP_COMPANY_NAME "$(ask "Company name" "$(env_get ERP_BOOTSTRAP_COMPANY_NAME 'My Company')")"
  env_set ERP_BOOTSTRAP_BRANCH_NAME  "$(ask "Main branch / location name" "$(env_get ERP_BOOTSTRAP_BRANCH_NAME 'Head Office')")"
  env_set ERP_TIME_ZONE            "$(ask "Timezone" "$(env_get ERP_TIME_ZONE Africa/Dar_es_Salaam)")"
  env_set ERP_BOOTSTRAP_TIME_ZONE  "$(env_get ERP_TIME_ZONE Africa/Dar_es_Salaam)"
  env_set ERP_BOOTSTRAP_CURRENCY_BASE    "$(ask "Currency the accounts are kept in" "$(env_get ERP_BOOTSTRAP_CURRENCY_BASE TZS)")"
  env_set ERP_BOOTSTRAP_CURRENCY_DEFAULT "$(env_get ERP_BOOTSTRAP_CURRENCY_BASE TZS)"
}

# Random secrets are generated by openssl INSIDE the application image we just loaded.
# That is deliberate: it works identically on every platform and needs no tooling on the
# host and no internet connection.
gen_secret() {
  local len="${1:-24}"
  docker run --rm --entrypoint openssl "$(image_ref)" rand -base64 64 \
    | tr -dc 'A-Za-z0-9' | cut -c1-"$len"
}

ensure_secrets() {
  step "Passwords"

  if [ "$(env_get ERP_DB_MODE docker)" = "docker" ] && [ -z "$(env_get ERP_DB_PASSWORD)" ]; then
    env_set ERP_DB_PASSWORD "$(gen_secret 32)"
    ok "Generated the database password (stored in .env)"
  fi

  [ -n "$(env_get ERP_DB_PASSWORD)" ] || die "ERP_DB_PASSWORD is empty in .env. In host mode this must be the password
of the login role on your own PostgreSQL server."

  if [ -z "$(env_get ERP_BOOTSTRAP_ADMIN_PASSWORD)" ]; then
    env_set ERP_BOOTSTRAP_ADMIN_PASSWORD "$(gen_secret 20)"
    ok "Generated the first administrator password (shown at the end, and stored in .env)"
  fi
}

ensure_jwt_keys() {
  step "Security keys"
  local dir="$INSTALL_DIR/secrets/jwt"
  mkdir -p "$dir"; chmod 700 "$INSTALL_DIR/secrets" 2>/dev/null || true

  if [ -f "$dir/private.pem" ] && [ -f "$dir/public.pem" ]; then
    # Never silently regenerate: new keys invalidate every session token and log
    # every user out, which is alarming and unexplained if it happens by itself.
    ok "Existing signing keys found — keeping them"
    return
  fi

  # --user 0 so the container may write into a root-owned bind mount and then hand the
  # files to uid 10001, which is the non-root user the application runs as. Without the
  # chown the application could not read its own private key.
  docker run --rm --user 0 -v "$dir:/keys" --entrypoint sh "$(image_ref)" -c '
    set -e
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /keys/private.pem 2>/dev/null
    openssl pkey -pubout -in /keys/private.pem -out /keys/public.pem
    chown 10001:10001 /keys/private.pem /keys/public.pem
    chmod 600 /keys/private.pem
    chmod 644 /keys/public.pem
  ' || die "Could not generate the security keys."

  ok "Generated a signing key pair in secrets/jwt/"
  warn "Back up secrets/jwt/ together with .env. Losing these keys logs every user out."
}

# ---------------------------------------------------------------------------
# 4. Preflight
# ---------------------------------------------------------------------------
check_port() {
  local port; port="$(env_get ERP_HTTP_PORT 8080)"
  # Anything answering on the port means something else already owns it. Better to say so
  # now than to let Docker fail with "port is already allocated" after loading images.
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    exec 3<&- 3>&- 2>/dev/null || true
    die "Port ${port} on this machine is already in use by another program.

Either stop that program, or pick a different port by editing this line in .env:
    ERP_HTTP_PORT=${port}
and running ./install.sh again."
  fi
  ok "Port ${port} is free"
}

check_host_database() {
  [ "$(env_get ERP_DB_MODE docker)" = "host" ] || return 0

  local h p d u
  h="$(env_get ERP_DB_HOST host.docker.internal)"; p="$(env_get ERP_DB_PORT 5432)"
  d="$(env_get ERP_DB_NAME erp)";                  u="$(env_get ERP_DB_USER erp)"

  step "Checking your PostgreSQL server at ${h}:${p}"

  # Reachability. Without this check an unreachable database just makes the application
  # exit and restart forever, which looks like an unexplained crash.
  docker run --rm --add-host "host.docker.internal:host-gateway" "$POSTGRES_IMAGE" \
    pg_isready -h "$h" -p "$p" -t 10 >/dev/null 2>&1 \
    || die "Cannot reach a PostgreSQL server at ${h}:${p}.

Check, on the database server:
  - PostgreSQL is running and listening on that port
  - postgresql.conf has        listen_addresses = '*'   (or the address this machine uses)
  - pg_hba.conf accepts connections from Docker, e.g.
        host  ${d}  ${u}  172.16.0.0/12  scram-sha-256
  - any firewall between the two machines allows port ${p}

docs/HOST-DB-SETUP.md has the full instructions. Nothing has been changed."
  ok "Server is reachable"

  # Credentials + a look at what is already in there.
  local tables
  tables="$(docker run --rm --add-host "host.docker.internal:host-gateway" \
    -e "PGPASSWORD=$(env_get ERP_DB_PASSWORD)" "$POSTGRES_IMAGE" \
    psql -h "$h" -p "$p" -U "$u" -d "$d" -tAc \
    "select count(*) from information_schema.tables where table_schema = 'public'" 2>&1)" \
    || die "Connected to the server, but could not open database '${d}' as role '${u}'.

Check the database exists, the role exists, the password in .env is correct, and the
role may connect to that database. docs/HOST-DB-SETUP.md has the commands.

The server replied:
    ${tables}"

  tables="$(printf '%s' "$tables" | tr -d '[:space:]')"

  if [ "$tables" = "0" ]; then
    ok "Database '${d}' is empty and ready — tables will be created on first start"
  elif docker run --rm --add-host "host.docker.internal:host-gateway" \
        -e "PGPASSWORD=$(env_get ERP_DB_PASSWORD)" "$POSTGRES_IMAGE" \
        psql -h "$h" -p "$p" -U "$u" -d "$d" -tAc \
        "select to_regclass('public.flyway_schema_history') is not null" 2>/dev/null | tr -d '[:space:]' | grep -q '^t$'; then
    ok "Database '${d}' already holds an installation of this system — it will be upgraded in place, not replaced"
  else
    # Refusing here is the point: the schema tool will not adopt a database it does not
    # recognise, and a half-applied install into someone else's data is far worse than
    # stopping now.
    die "Database '${d}' already contains ${tables} table(s), and they were not created by this system.

This application requires a database of its own. Installing into a database that holds
other data is not supported and will fail partway through.

Create an empty database for it (see docs/HOST-DB-SETUP.md), put its name in .env as
ERP_DB_NAME, and run ./install.sh again. Nothing has been changed."
  fi
}

# ---------------------------------------------------------------------------
# 5. Start
# ---------------------------------------------------------------------------
start_system() {
  step "Starting the system"
  chmod +x "$INSTALL_DIR/orbixerp.sh" 2>/dev/null || true
  "$INSTALL_DIR/orbixerp.sh" start
}

print_summary() {
  local user pass port host
  user="rootadmin"
  pass="$(env_get ERP_BOOTSTRAP_ADMIN_PASSWORD)"
  port="$(env_get ERP_HTTP_PORT 8080)"
  host="$(hostname 2>/dev/null || echo localhost)"

  printf '\n'
  printf '%s============================================================%s\n' "$C_GRN" "$C_OFF"
  printf '%s  OrbixERP is installed and running%s\n' "$C_BLD" "$C_OFF"
  printf '%s============================================================%s\n\n' "$C_GRN" "$C_OFF"
  printf '  Open in a browser\n'
  printf '      on this machine   http://localhost:%s\n' "$port"
  printf '      from the network  http://%s:%s\n\n' "$host" "$port"
  printf '  Sign in with\n'
  printf '      username          %s\n' "$user"
  printf '      password          %s%s%s\n\n' "$C_BLD" "$pass" "$C_OFF"
  printf '  %sChange that password after your first sign-in.%s\n' "$C_YEL" "$C_OFF"
  printf '  It is also saved in .env, which you should keep private.\n\n'
  printf '  Everyday commands, from %s\n' "$INSTALL_DIR"
  printf '      ./orbixerp.sh status       is it running?\n'
  printf '      ./orbixerp.sh backup       back up the database\n'
  printf '      ./orbixerp.sh stop         shut down (your data is kept)\n'
  printf '      ./orbixerp.sh start        start again\n\n'
  printf '  Back up these three things together — a database backup alone\n'
  printf '  cannot be signed into without them:\n'
  printf '      backups/   .env   secrets/\n\n'
  printf '  Guides in docs/ : INSTALL, OPERATIONS, HOST-DB-SETUP, TROUBLESHOOTING\n'
  printf '  Each one comes as .md and as plain .txt — open whichever your machine reads.\n\n'
}

# ---------------------------------------------------------------------------
main() {
  for arg in "$@"; do
    case "$arg" in
      --defaults|--yes|-y) ASSUME_DEFAULTS=1 ;;
      # The client-facing part of this file's header (stops before the maintainer notes).
      -h|--help) sed -n '3,18p' "$SCRIPT_PATH" | sed 's/^# \{0,1\}//'; exit 0 ;;
      *) die "Unknown option '$arg'. Run ./install.sh --help" ;;
    esac
  done

  printf '\n%s  OrbixERP — installation%s\n' "$C_BLD" "$C_OFF"
  printf '  version %s (%s)\n' "$(meta_get ERP_VERSION)" "$(meta_get BUNDLE_ARCH)"

  step "Checking this machine"
  check_arch
  check_docker

  choose_install_dir
  load_images
  create_env
  ensure_secrets
  ensure_jwt_keys

  step "Final checks before starting"
  check_port
  check_host_database

  start_system

  # Only now, once the system has genuinely come up: first-run setup has done its job
  # and must not run again. Doing this earlier would leave a failed install unable to
  # create its administrator on the next attempt.
  env_set ERP_BOOTSTRAP_ENABLED "false"

  print_summary
}

main "$@"
