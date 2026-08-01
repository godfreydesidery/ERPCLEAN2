#!/usr/bin/env bash
#
# Builds a client distribution bundle. MAINTAINER TOOL — never shipped to a client.
#
#   bash dist/build-release.sh --version 1.0.0                 # amd64 + arm64
#   bash dist/build-release.sh --version 1.0.0 --arch amd64    # one architecture only
#   bash dist/build-release.sh --refresh-docs                  # after editing a guide
#
# The guides in dist/bundle/docs/ are committed as BOTH .md and .txt. The .txt are
# generated - edit the .md, run --refresh-docs, and commit both. A release refuses to
# build if a committed .txt no longer matches its .md.
#
# Produces, under dist/release/:
#   orbixerp-<version>-amd64/      the client bundle (compose files, scripts, docs, images)
#   orbixerp-<version>-amd64.zip   the same thing, zipped, ready to hand over
#   ... and the arm64 pair.
#
# The compiler runs ONCE, natively. app.jar is JVM bytecode plus a static web bundle and
# is identical on every architecture — only the JRE base image differs. Building the
# arm64 image is therefore a file copy onto an arm64 base, not an emulated compile.
# See the header of dist/Dockerfile.build.
#
# Requirements: Docker with buildx (Docker Desktop 4.x / Engine 23+ have it built in).
#
# On plain Linux Docker Engine, building the arm64 bundle from an x86 machine additionally
# needs QEMU registered once per boot. Docker Desktop does this for you; elsewhere run:
#   docker run --privileged --rm tonistiigi/binfmt --install all

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION=""
ARCHES="amd64 arm64"
REFRESH_DOCS_ONLY=0
IMAGE_NAME="orbixerp-api"
POSTGRES_IMAGE="postgres:15-alpine"
CADDY_IMAGE="caddy:2-alpine"

C_BLD=$'\033[1m'; C_GRN=$'\033[32m'; C_RED=$'\033[31m'; C_OFF=$'\033[0m'
step() { printf '\n%s==>%s %s\n' "$C_BLD" "$C_OFF" "$*"; }
ok()   { printf '%s  ok%s  %s\n' "$C_GRN" "$C_OFF" "$*"; }
die()  { printf '\n%sERROR%s %s\n\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

usage() {
  sed -n '3,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

# ---------------------------------------------------------------------------
parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --version) VERSION="${2:-}"; shift 2 ;;
      --arch)    ARCHES="${2:-}"; [ "$ARCHES" = "both" ] && ARCHES="amd64 arm64"; shift 2 ;;
      --refresh-docs) REFRESH_DOCS_ONLY=1; shift ;;
      -h|--help) usage ;;
      *) die "Unknown option '$1'. Try --help." ;;
    esac
  done
  [ "$REFRESH_DOCS_ONLY" = "1" ] && return 0
  [ -n "$VERSION" ] || die "--version is required, e.g. --version 1.0.0"
  # A moving tag in a client's compose file makes it impossible to know what they are
  # running when they call for support. Releases are always an explicit version.
  case "$VERSION" in
    latest|"") die "'latest' is not a valid release version. Use an explicit version such as 1.0.0." ;;
  esac
}

check_tools() {
  command -v docker >/dev/null 2>&1 || die "docker is not installed."
  docker buildx version >/dev/null 2>&1 || die "docker buildx is not available. Update Docker."
  docker info >/dev/null 2>&1 || die "Docker is not running."
}

# The PowerShell scripts must contain NOTHING but ASCII.
#
# Windows PowerShell 5.1 — still the default interpreter on every Windows client — reads a
# file without a byte-order mark as CP1252, not UTF-8. A UTF-8 em dash (E2 80 94) is then
# decoded as three characters ending in U+201D, a curly closing quote, which PowerShell
# accepts as a STRING DELIMITER. The result: one decorative dash in a comment silently
# breaks the installer's parsing on the client's machine while looking perfect here.
#
# Adding a BOM would also fix it, but ASCII-only survives every editor and encoding, so
# that is the rule and this gate is what keeps it true.
# Batch files are included for the same reason: a .cmd is interpreted under whatever OEM
# code page the machine happens to use, so a non-ASCII character renders as mojibake on one
# client and fine on another.
check_powershell_ascii() {
  local bad=0 f
  for f in "$SCRIPT_DIR"/bundle/*.ps1 "$SCRIPT_DIR"/bundle/*.cmd "$SCRIPT_DIR"/build-release.ps1; do
    [ -f "$f" ] || continue
    # LC_ALL=C so bytes above 0x7F fall outside [:print:] and [:space:]. Portable across
    # GNU and BSD grep, unlike -P.
    if LC_ALL=C grep -q '[^[:print:][:space:]]' "$f"; then
      printf '  %s contains non-ASCII characters:\n' "$(basename "$f")"
      LC_ALL=C grep -n '[^[:print:][:space:]]' "$f" | head -5 | sed 's/^/      /'
      bad=1
    fi
  done
  [ "$bad" = "0" ] || die "PowerShell scripts must be ASCII-only — see the explanation above this check
in build-release.sh. Replace the characters listed and build again."
  ok "PowerShell scripts are ASCII-only"
}

# The default stack name is declared in THREE independent places, and they must agree.
#
# They did not, once: a rebrand updated the compose files and .env.example to "orbixerp" but
# missed a bare PowerShell string literal in the wizard, so the installer wrote
# ERP_STACK_NAME=erp into .env and the client got containers called erp-db and erp-api -
# colliding with anything else on the machine using those names. The whole point of the
# setting is to prevent exactly that collision.
#
# Pattern-matching across many files cannot be trusted to catch every category of match, so
# the build asserts the outcome instead of trusting the edit.
check_stack_name() {
  local from_env from_compose from_wizard
  from_env="$(grep -E '^ERP_STACK_NAME=' "$SCRIPT_DIR/bundle/.env.example" 2>/dev/null | head -1 | cut -d= -f2 | tr -d '\r')"
  from_compose="$(grep -oE 'ERP_STACK_NAME:-[a-zA-Z0-9_-]+' "$SCRIPT_DIR/bundle/docker-compose.yml" 2>/dev/null | head -1 | sed 's/.*:-//')"
  from_wizard="$(grep -oE "StackName[[:space:]]*=[[:space:]]*'[a-zA-Z0-9_-]+'" "$SCRIPT_DIR/bundle/setup-wizard.ps1" 2>/dev/null | head -1 | sed "s/.*'\(.*\)'/\1/")"

  [ -n "$from_env" ] && [ -n "$from_compose" ] && [ -n "$from_wizard" ] \
    || die "Could not read the default stack name from all three sources.
  .env.example       '${from_env}'
  docker-compose.yml '${from_compose}'
  setup-wizard.ps1   '${from_wizard}'"

  if [ "$from_env" != "$from_compose" ] || [ "$from_env" != "$from_wizard" ]; then
    die "The default stack name disagrees between files:
  .env.example       ${from_env}
  docker-compose.yml ${from_compose}
  setup-wizard.ps1   ${from_wizard}

All three must match, or the installer writes one name into .env while compose defaults to
another - and the client ends up with wrongly-named containers that collide on their machine."
  fi
  ok "Stack name is consistent everywhere (${from_env})"
}

# ---------------------------------------------------------------------------
# Compile once
# ---------------------------------------------------------------------------
build_jar() {
  step "Compiling the application (web bundle + API) — this is the slow part"
  BUILD_DIR="$REPO_ROOT/dist/build"
  rm -rf "$BUILD_DIR"; mkdir -p "$BUILD_DIR"

  docker buildx build \
    --target export \
    --output "type=local,dest=$BUILD_DIR" \
    -f "$SCRIPT_DIR/Dockerfile.build" \
    "$REPO_ROOT" || die "The application build failed. The compiler output is above."

  [ -f "$BUILD_DIR/app.jar" ] || die "The build finished but produced no app.jar."
  ok "app.jar built ($(du -h "$BUILD_DIR/app.jar" | cut -f1))"
}

# ---------------------------------------------------------------------------
# Package one architecture
# ---------------------------------------------------------------------------
build_bundle() {
  local arch="$1"
  local out="$REPO_ROOT/dist/release/orbixerp-${VERSION}-${arch}"

  step "Packaging for ${arch}"
  rm -rf "$out"; mkdir -p "$out/images" "$out/docs"

  # --- the application image -------------------------------------------------
  # `--output type=docker,dest=` writes a loadable archive straight to disk, so the
  # foreign-architecture image never has to enter the local image store.
  printf '  building %s:%s (%s)\n' "$IMAGE_NAME" "$VERSION" "$arch"
  docker buildx build \
    --platform "linux/${arch}" \
    --build-arg "ERP_VERSION=${VERSION}" \
    --build-arg "BUILD_DATE=${BUILD_DATE}" \
    -t "${IMAGE_NAME}:${VERSION}" \
    --output "type=docker,dest=${out}/images/${IMAGE_NAME}-${VERSION}-${arch}.tar" \
    -f "$SCRIPT_DIR/Dockerfile.runtime" \
    "$REPO_ROOT/dist/build" || die "Could not build the ${arch} image."

  # --- third-party images ----------------------------------------------------
  # PostgreSQL ships even when the client uses their own database: backup and restore
  # run pg_dump/pg_restore from this image in both modes.
  # Caddy ships so the optional HTTPS overlay also works without internet access.
  save_third_party "$arch" "$out" "$POSTGRES_IMAGE" "postgres-15-alpine"
  save_third_party "$arch" "$out" "$CADDY_IMAGE"    "caddy-2-alpine"

  printf '  compressing images\n'
  gzip -9 -f "$out"/images/*.tar

  # --- bundle contents -------------------------------------------------------
  copy_bundle_files "$out" "$arch"
  generate_text_docs "$out"
  write_metadata "$out" "$arch"
  write_checksums "$out"
  make_archive "$out"

  ok "orbixerp-${VERSION}-${arch} ready ($(du -sh "$out" | cut -f1))"
}

save_third_party() {
  local arch="$1" out="$2" image="$3" name="$4"
  printf '  fetching %s (%s)\n' "$image" "$arch"
  docker pull --platform "linux/${arch}" -q "$image" >/dev/null || die "Could not pull $image for $arch."

  # --platform on the SAVE is essential, not decorative. Once both architectures have been
  # pulled (which happens the moment you build both bundles), one tag holds several platform
  # variants, and a bare `docker save` exports every one of them - inflating a bundle by
  # ~100 MB with layers the client can never run. Pin the export to the one we want.
  docker save --platform "linux/${arch}" "$image" -o "${out}/images/${name}-${arch}.tar" \
    || die "Could not save $image for $arch."

  # Prove what came out. A wrong-architecture Postgres fails on the client with only
  # "exec format error" to go on, days after the handover.
  local got
  got="$(docker image inspect --format '{{.Architecture}}' "$image" 2>/dev/null)"
  [ "$got" = "$arch" ] || die "Pulled $image but Docker reports architecture '$got', expected '$arch'."
}

copy_bundle_files() {
  local out="$1" arch="$2"
  printf '  assembling bundle files\n'

  for f in docker-compose.yml docker-compose.db-docker.yml docker-compose.db-host.yml \
           docker-compose.tls.yml Caddyfile orbixerp.sh orbixerp.ps1 install.sh install.ps1 \
           Setup.cmd setup-wizard.ps1 Install.cmd OrbixERP.cmd LICENSE.txt; do
    cp "$SCRIPT_DIR/bundle/$f" "$out/$f" || die "Missing bundle file: $f"
  done

  # Batch launchers must carry CRLF endings. A .cmd with bare LF misbehaves around labels
  # and goto, and a release cut from a Linux checkout would otherwise ship broken ones.
  # awk rather than sed: `sed 's/$/\r/'` inserts a literal "r" on BSD/macOS.
  for f in Setup.cmd Install.cmd OrbixERP.cmd; do
    awk '{ sub(/\r$/, ""); printf "%s\r\n", $0 }' "$out/$f" > "$out/$f.crlf" \
      && mv "$out/$f.crlf" "$out/$f"
  done
  # Only the .md are copied; the .txt are regenerated into the bundle a moment later and
  # checked against the committed copies.
  cp "$SCRIPT_DIR"/bundle/docs/*.md "$out/docs/"

  # Placeholders replaced so the client's .env needs no hand-editing to match the build.
  sed -e "s|__ERP_VERSION__|${VERSION}|g" \
      -e "s|__BUNDLE_ARCH__|${arch}|g" \
      "$SCRIPT_DIR/bundle/.env.example" > "$out/.env.example"

  chmod +x "$out/orbixerp.sh" "$out/install.sh"

  # Empty working folders, so a first-time client sees where things will go.
  mkdir -p "$out/backups" "$out/secrets/jwt"
  printf 'Database backups written by "erp backup" appear here.\n' > "$out/backups/README.txt"
  printf 'Signing keys generated by the installer appear here. Keep them private and backed up.\n' > "$out/secrets/README.txt"
}

# Plain-text copies of the guides, generated from the Markdown so the two cannot drift.
# They exist because a client on Windows can double-click a .txt and read it, whereas a .md
# opens in something unhelpful or not at all.
#
# The conversion runs in a container rather than on the host: dist/md2txt.js is the single
# implementation shared with build-release.ps1, so the Linux and Windows release paths cannot
# produce different output, and no node installation is required to cut a release.
run_md2txt() {
  local dir="$1"

  # Copied in rather than bind-mounted separately: one mount, and no single-file bind mount
  # to behave differently across platforms.
  cp "$SCRIPT_DIR/md2txt.js" "$dir/.md2txt.js"

  # MSYS_NO_PATHCONV / MSYS2_ARG_CONV_EXCL are for git-bash on Windows, which "helpfully"
  # rewrites any argument that looks like a Unix path: the container's own /docs became
  # C:/Program Files/Git/docs and node could not find the script. Both are inert on Linux
  # and macOS, so the same line is correct everywhere.
  local rc=0
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    docker run --rm -v "$dir:/docs" node:20-alpine node /docs/.md2txt.js /docs || rc=$?
  rm -f "$dir/.md2txt.js"
  return $rc
}

# `--refresh-docs`: regenerate the committed .txt in dist/bundle/docs/ after editing a guide.
refresh_source_docs() {
  step "Regenerating dist/bundle/docs/*.txt from the Markdown"
  run_md2txt "$SCRIPT_DIR/bundle/docs" || die "Could not regenerate the plain-text guides."
  ok "Done. Commit the .txt files alongside the .md you changed."
}

# The .txt are committed so they are visible and reviewable in the repository, but they are
# GENERATED - so the build regenerates them and refuses to ship if the committed copies do
# not match. Without this gate, editing a .md and forgetting to regenerate would quietly hand
# a client documentation that contradicts itself.
generate_text_docs() {
  local out="$1"
  printf '  generating plain-text copies of the guides\n'
  run_md2txt "$out/docs" || die "Could not generate the plain-text guides."

  local stale=""
  for txt in "$out"/docs/*.txt; do
    local name; name="$(basename "$txt")"
    if ! cmp -s "$txt" "$SCRIPT_DIR/bundle/docs/$name"; then stale="$stale $name"; fi
  done

  [ -z "$stale" ] || die "The committed plain-text guides are out of date:${stale}

A .md guide was edited without regenerating its .txt. Run:

    bash dist/build-release.sh --refresh-docs

then commit the regenerated .txt files and build again."
}

write_metadata() {
  local out="$1" arch="$2"

  cat > "$out/VERSION" <<EOF
ERP_VERSION=${VERSION}
BUNDLE_ARCH=${arch}
BUILD_DATE=${BUILD_DATE}
BUILD_COMMIT=${BUILD_COMMIT}
EOF

  cat > "$out/RELEASE-NOTES.md" <<EOF
# OrbixERP ${VERSION}

Released ${BUILD_DATE}.

## Before you update

\`./orbixerp.sh update\` takes a database backup automatically and stops if that backup fails.
Keep the backup it names: because database changes only run forwards, restoring that
backup is the only way to return to your previous version.

## Changes in this release

_To be completed for each release._
EOF
}

write_checksums() {
  local out="$1"
  printf '  writing checksums\n'
  (
    cd "$out"
    # A client can verify a handover arrived intact, and support can confirm which build
    # is on a machine without relying on what anyone remembers installing.
    {
      printf '# OrbixERP %s (%s) — SHA-256\n' "$VERSION" "$BUILD_DATE"
      printf '# Verify on Linux/macOS:  sha256sum -c CHECKSUMS.txt\n'
      printf '# Verify on Windows:      Get-FileHash <file> -Algorithm SHA256\n\n'
      find . -type f ! -name CHECKSUMS.txt -print0 | sort -z | xargs -0 sha256sum
    } > CHECKSUMS.txt
  )
}

make_archive() {
  local out="$1" dir base
  dir="$(dirname "$out")"; base="$(basename "$out")"
  printf '  creating archive\n'
  ( cd "$dir"
    if command -v zip >/dev/null 2>&1; then
      zip -qr "${base}.zip" "$base"
    else
      # zip is not present on every machine; a tar.gz is just as easy to hand over.
      tar -czf "${base}.tar.gz" "$base"
    fi )
}

# ---------------------------------------------------------------------------
main() {
  parse_args "$@"
  check_tools

  if [ "$REFRESH_DOCS_ONLY" = "1" ]; then refresh_source_docs; exit 0; fi

  check_powershell_ascii
  check_stack_name

  BUILD_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  BUILD_COMMIT="$(cd "$REPO_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo unknown)"

  printf '\n%s  OrbixERP release build%s\n' "$C_BLD" "$C_OFF"
  printf '  version      %s\n' "$VERSION"
  printf '  architecture %s\n' "$ARCHES"
  printf '  commit       %s\n' "$BUILD_COMMIT"

  build_jar
  for arch in $ARCHES; do build_bundle "$arch"; done

  step "Done"
  ls -1 "$REPO_ROOT/dist/release" | sed 's/^/  /'
  printf '\nHand the .zip to the client along with its CHECKSUMS.txt.\n'
  printf 'They run install.sh (Linux/macOS) or install.ps1 (Windows). Nothing else is needed.\n\n'
}

main "$@"
