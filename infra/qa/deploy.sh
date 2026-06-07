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
# shellcheck disable=SC1091
[ -f "$here/deploy.env" ] && source "$here/deploy.env"
# optional local, gitignored overrides (SSH_KEY=..., GH_PAT=...)
# shellcheck disable=SC1091
[ -f "$here/deploy.env.local" ] && source "$here/deploy.env.local"

: "${EC2_HOST:?set EC2_HOST in deploy.env}"
: "${EC2_USER:=ubuntu}"
: "${BRANCH:=main}"
: "${REMOTE_DIR:=erpclean2}"
: "${CONTAINER:=erpclean2}"
: "${IMAGE:=erpclean2:qa}"
: "${DB_VOLUME:=erpclean2-data}"
KEY="${SSH_KEY:-${ERP_SSH_KEY:?set SSH_KEY or ERP_SSH_KEY to your .pem path}}"

echo "Deploying $BRANCH to $EC2_USER@$EC2_HOST ..."
ssh -i "$KEY" -o StrictHostKeyChecking=accept-new "$EC2_USER@$EC2_HOST" bash -s <<REMOTE
set -euo pipefail
cd "\$HOME/${REMOTE_DIR}"
echo '==> git pull (${BRANCH})'
git fetch --all --quiet
git checkout "${BRANCH}" --quiet
git pull --ff-only
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
