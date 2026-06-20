# QA single-container deployment (ERPCLEAN2)

One Docker image runs everything: the Spring Boot API, PostgreSQL, and the
Angular bundle (served from the jar's `static/` path). Wrong shape for
production (coupled lifecycle, no scaling) — right shape for QA: one image,
one `docker run`, one volume.

Target box: **16.170.11.41** (`ubuntu@`, see `deploy.env`). This box previously
ran the Orbix QA image; ERPCLEAN2 **replaces** it (different app, Postgres not
MariaDB, different container/volume names).

## Deploy on demand (automated)

After the one-time bootstrap below, ship the latest commit to QA with one
command — it SSHes in, pulls the branch, rebuilds the image, restarts the
container:

```powershell
# Windows / PowerShell
$env:ERP_SSH_KEY = "C:\path\to\orbix-qa.pem"   # or set SSH_KEY in deploy.env.local
infra\qa\deploy.ps1                 # deploys the branch in deploy.env
infra\qa\deploy.ps1 -Branch main    # or another branch
```

```bash
# macOS / Linux / git-bash
export ERP_SSH_KEY=~/keys/orbix-qa.pem
infra/qa/deploy.sh
```

Target host/user/branch live in `deploy.env` (committed, non-secret). Your
`.pem` path and any PAT stay out of git — pass them via env or the gitignored
`deploy.env.local` (which is present locally in this repo for convenience).

## First-time bootstrap (once per instance)

The deploy script assumes Docker is installed and the ERPCLEAN2 repo is cloned
on the box. Since this box currently runs Orbix, also remove the old Orbix
container/volume so port 80 is free.

```bash
ssh -i orbix-qa.pem ubuntu@16.170.11.41

# (if not already) install Docker, then re-login so the docker group applies
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit
ssh -i orbix-qa.pem ubuntu@16.170.11.41

# Tear down the previous Orbix QA deployment (frees port 80; destroys its data)
docker stop orbix 2>/dev/null || true
docker rm   orbix 2>/dev/null || true
docker volume rm orbix-data 2>/dev/null || true

# Clone ERPCLEAN2 with a fine-grained PAT (repo: ERPCLEAN2, Contents: Read-only)
git clone https://oauth2:<PAT>@github.com/godfreydesidery/ERPCLEAN2.git erpclean2
cd erpclean2

# Put the bootstrap secrets on the box (copy qa.env.example -> qa.env, fill in)
cp infra/qa/qa.env.example infra/qa/qa.env
nano infra/qa/qa.env   # set ERP_BOOTSTRAP_ADMIN_PASSWORD + DB_PASSWORD
exit
```

The PAT stays in the box's git remote so the automated `deploy.*` `git pull`
works hands-off. Security group must allow inbound `22` and `80` from your IP.

Now run `deploy.ps1` / `deploy.sh` from your machine for every release.

## Credentials

Live, non-committed credentials are in `CREDENTIALS.local.md` (gitignored).
The deploy SSH key path + GitHub PAT are in `deploy.env.local` (gitignored).
The container bootstrap secrets are in `qa.env` (gitignored, lives on the box).

## Reset / wipe

> ⚠️ **QA data is permanent (since 2026-06-20).** A normal deploy keeps the volume — this is the
> default and what you want. The wipe below **drops all QA data** and re-bootstraps a fresh DB;
> it is **not** routine and should only be run on an explicit, deliberate decision (e.g. rebuilding
> the environment from scratch). Day-to-day releases must use the data-preserving `deploy.sh`.

```bash
ssh -i orbix-qa.pem ubuntu@16.170.11.41
# full reset (drops all QA data; next deploy re-bootstraps from qa.env) — DELIBERATE USE ONLY:
docker stop erpclean2 && docker rm erpclean2
docker volume rm erpclean2-data
# then run deploy.ps1 again
```
