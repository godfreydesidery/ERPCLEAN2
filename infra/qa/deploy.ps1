<#
.SYNOPSIS
  On-demand QA deploy for ERPCLEAN2. SSHes to the EC2 box, pulls the branch,
  rebuilds the single-container image (API + Angular + Postgres), and restarts
  the container. Replaces whatever was previously deployed on the box.

.PREREQUISITES (one-time, see README "First-time bootstrap")
  - The box has Docker installed and the ERPCLEAN2 repo cloned at ~/<REMOTE_DIR>.
  - You can SSH in with your .pem key.

.EXAMPLE
  $env:ERP_SSH_KEY = "C:\keys\orbix-qa.pem"
  ./deploy.ps1                       # deploys the branch from deploy.env
  ./deploy.ps1 -Branch main          # override the branch
  ./deploy.ps1 -KeyPath C:\k\x.pem   # override the key
#>
#requires -version 5
param(
  [string]$KeyPath = $env:ERP_SSH_KEY,
  [string]$Branch
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# --- load deploy.env (KEY=VALUE lines) -------------------------------------
$cfg = @{}
Get-Content (Join-Path $here 'deploy.env') |
  Where-Object { $_ -match '^\s*[^#].*=' } |
  ForEach-Object { $k, $v = $_ -split '=', 2; $cfg[$k.Trim()] = $v.Trim() }

# optional gitignored local overrides (SSH_KEY=...)
$localEnv = Join-Path $here 'deploy.env.local'
if (Test-Path $localEnv) {
  Get-Content $localEnv |
    Where-Object { $_ -match '^\s*[^#].*=' } |
    ForEach-Object { $k, $v = $_ -split '=', 2; $cfg[$k.Trim()] = $v.Trim() }
}

$ec2       = $cfg['EC2_HOST']
$user      = $cfg['EC2_USER']
$dir       = $cfg['REMOTE_DIR']
$container = $cfg['CONTAINER']
$image     = $cfg['IMAGE']
$vol       = $cfg['DB_VOLUME']
if (-not $Branch)  { $Branch  = $cfg['BRANCH'] }
if (-not $KeyPath) { $KeyPath = $cfg['SSH_KEY'] }

if (-not $KeyPath) {
  throw "No SSH key. Set `$env:ERP_SSH_KEY to your .pem path, or pass -KeyPath, or set SSH_KEY in deploy.env.local."
}
if (-not (Test-Path $KeyPath)) { throw "SSH key not found: $KeyPath" }

# --- remote script (runs on the box; `$HOME stays literal for remote bash) --
$remote = @"
set -euo pipefail
cd "`$HOME/$dir"
echo '==> git pull ($Branch)'
git fetch --all --quiet
git checkout "$Branch" --quiet
git pull --ff-only
echo '==> docker build'
docker build -f infra/qa/Dockerfile -t "$image" .
echo '==> restart container'
docker stop "$container" 2>/dev/null || true
docker rm "$container" 2>/dev/null || true
docker volume create "$vol" >/dev/null
ENV_ARG=""
if [ -f infra/qa/qa.env ]; then
  ENV_ARG="--env-file infra/qa/qa.env"
  echo '==> using qa.env (env-driven bootstrap)'
else
  echo 'WARNING: infra/qa/qa.env missing — app will start NOT bootstrapped'
fi
docker run -d --name "$container" -p 80:8081 -v "$vol":/var/lib/postgresql/data `$ENV_ARG --restart unless-stopped "$image"
docker image prune -f >/dev/null || true
echo '==> deployed'
"@

Write-Host "Deploying $Branch to $user@$ec2 ..." -ForegroundColor Cyan
ssh -i $KeyPath -o StrictHostKeyChecking=accept-new "$user@$ec2" $remote
if ($LASTEXITCODE -ne 0) { throw "Remote deploy failed (exit $LASTEXITCODE)" }
Write-Host "Done -> http://$ec2/" -ForegroundColor Green
