#!/usr/bin/env bash
#
# On-demand QA deploy for ERPCLEAN2 (single container: API + Angular + Postgres).
# SSHes to the EC2 box, pulls the branch, rebuilds the image, restarts the
# container. Replaces whatever was previously deployed on the box.
#
# Prereq (one-time, see README "First-time bootstrap"): the box has Docker and
# the ERPCLEAN2 repo cloned at ~/$REMOTE_DIR.
#
# Usage:
#   export ERP_SSH_KEY=~/keys/orbix-qa.pem
#   ./deploy.sh                 # deploys BRANCH from deploy.env
#   BRANCH=main ./deploy.sh     # override the branch
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Precedence: environment > deploy.env.local > deploy.env.
#
# Capture the environment BEFORE sourcing. `source deploy.env` is a plain
# assignment, so it used to overwrite an env-supplied value — which meant the
# override this script's own usage documents, `BRANCH=develop ./deploy.sh`,
# silently deployed deploy.env's branch instead and still exited 0. A deploy
# that ships the wrong branch must not look like a successful one.
_env_BRANCH="${BRANCH:-}"
_env_EC2_HOST="${EC2_HOST:-}"
_env_EC2_USER="${EC2_USER:-}"
_env_REMOTE_DIR="${REMOTE_DIR:-}"
_env_CONTAINER="${CONTAINER:-}"
_env_IMAGE="${IMAGE:-}"
_env_DB_VOLUME="${DB_VOLUME:-}"

# shellcheck disable=SC1091
[ -f "$here/deploy.env" ] && source "$here/deploy.env"
# optional local, gitignored overrides (SSH_KEY=..., GH_PAT=...)
# shellcheck disable=SC1091
[ -f "$here/deploy.env.local" ] && source "$here/deploy.env.local"

BRANCH="${_env_BRANCH:-${BRANCH:-}}"
EC2_HOST="${_env_EC2_HOST:-${EC2_HOST:-}}"
EC2_USER="${_env_EC2_USER:-${EC2_USER:-}}"
REMOTE_DIR="${_env_REMOTE_DIR:-${REMOTE_DIR:-}}"
CONTAINER="${_env_CONTAINER:-${CONTAINER:-}}"
IMAGE="${_env_IMAGE:-${IMAGE:-}}"
DB_VOLUME="${_env_DB_VOLUME:-${DB_VOLUME:-}}"

: "${EC2_HOST:?set EC2_HOST in deploy.env}"
: "${EC2_USER:=ubuntu}"
: "${BRANCH:=main}"
: "${REMOTE_DIR:=erpclean2}"
: "${CONTAINER:=erpclean2}"
: "${IMAGE:=erpclean2:qa}"
: "${DB_VOLUME:=erpclean2-data}"
KEY="${SSH_KEY:-${ERP_SSH_KEY:?set SSH_KEY or ERP_SSH_KEY to your .pem path}}"

echo "Deploying $BRANCH to $EC2_USER@$EC2_HOST ..."

# ---------------------------------------------------------------------------
# Back up the database BEFORE anything else — before the pull, before the build,
# and long before Flyway runs.
#
# This deploy applies migrations on container start, and a migration is one-way:
# Flyway cannot unapply one, so "roll back" means "restore a dump". Without this
# step the only rollback available is whatever dump somebody happened to take for
# another reason — which is exactly the position this deploy was in on
# 2026-08-16, when V104 (NOT NULL, two foreign keys, an index swap) went onto QA
# with no backup of its own.
#
# Deliberately BEFORE the build: a build takes minutes, and a backup taken after
# it is a backup of a database somebody may have used in the meantime.
#
# The dump is pulled to the OPERATOR'S machine rather than left on the box. A
# backup that only exists on the host is not a backup of the host.
# ---------------------------------------------------------------------------
BACKUP_DIR="${ERP_QA_BACKUP_DIR:-$HOME/qa-backups}"
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/qa-$(date +%Y%m%d-%H%M%S)-pre-deploy.sql"
echo "==> backing up the QA database first -> $BACKUP_FILE"
if ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "$EC2_USER@$EC2_HOST" \
        "docker exec ${CONTAINER} pg_dump -U erp -d erp --no-owner --no-privileges" \
        > "$BACKUP_FILE" 2>/dev/null && [ -s "$BACKUP_FILE" ]; then
    echo "==> backup OK ($(wc -c < "$BACKUP_FILE") bytes)"
else
    # Refuse rather than continue. An unbackupable database is a reason to stop and
    # look, not to deploy faster — and a zero-byte file next to a broken database is
    # worse than no file, because it reads as a rollback point that does not exist.
    rm -f "$BACKUP_FILE"
    echo "ABORTING: could not back up the QA database." >&2
    echo "  The container may not be running, or pg_dump failed. Fix that first —" >&2
    echo "  this deploy can apply migrations, and they cannot be unapplied." >&2
    echo "  To deploy anyway (you are accepting there is no rollback point):" >&2
    echo "    ERP_SKIP_BACKUP=1 BRANCH=$BRANCH ./deploy.sh" >&2
    [ "${ERP_SKIP_BACKUP:-0}" = "1" ] || exit 1
    echo "==> ERP_SKIP_BACKUP=1 set — continuing WITHOUT a rollback point"
fi

ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "$EC2_USER@$EC2_HOST" bash -s <<REMOTE
set -euo pipefail
cd "\$HOME/${REMOTE_DIR}"
echo '==> git pull (${BRANCH})'
git fetch --all --quiet
git checkout "${BRANCH}" --quiet
git pull --ff-only
# Print what actually landed. Docker reports every layer CACHED when the source
# has not moved, so without this line a deploy of the wrong branch reads exactly
# like a successful one.
echo "==> now at \$(git rev-parse --abbrev-ref HEAD) \$(git rev-parse --short HEAD)"
echo '==> docker build'
docker build -f infra/qa/Dockerfile -t "${IMAGE}" .
echo '==> restart container'
docker stop "${CONTAINER}" 2>/dev/null || true
docker rm "${CONTAINER}" 2>/dev/null || true
docker volume create "${DB_VOLUME}" >/dev/null
ENV_ARG=""
if [ -f infra/qa/qa.env ]; then
  ENV_ARG="--env-file infra/qa/qa.env"
  echo '==> using qa.env (env-driven bootstrap)'
else
  echo 'WARNING: infra/qa/qa.env missing — app will start NOT bootstrapped'
fi
docker run -d --name "${CONTAINER}" -p 80:8081 -v "${DB_VOLUME}":/var/lib/postgresql/data \$ENV_ARG --restart unless-stopped "${IMAGE}"
docker image prune -f >/dev/null || true
echo '==> deployed'
REMOTE
echo "Done -> http://$EC2_HOST/"
