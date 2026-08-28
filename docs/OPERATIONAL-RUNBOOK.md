# OrbixERP — Operational Runbook

**How to deploy, run, verify, back up and recover every OrbixERP installation.**

Companion document: [TECHNICAL-RUNBOOK.md](TECHNICAL-RUNBOOK.md) — how the system is built and
tested. This document is for whoever is operating the estate. Every command needed to complete a
procedure is here in full, with the machine it runs on.

*Written 2026-08-28 against the shipped scripts. Hosts, keys and versions change — §2.5 tells you how
to confirm what is actually true before you act on it.*

---

## Contents

1. [Ground rules](#1--ground-rules)
2. [The estate](#2--the-estate)
3. [Day-to-day operations](#3--day-to-day-operations)
4. [Release process and gates](#4--release-process-and-gates)
5. [Deploying to QA](#5--deploying-to-qa)
6. [Deploying to production (self-hosted EC2)](#6--deploying-to-production-self-hosted-ec2)
7. [Deploying to a customer](#7--deploying-to-a-customer)
8. [Backup, restore and disaster recovery](#8--backup-restore-and-disaster-recovery)
9. [Incident response](#9--incident-response)
10. [User and access administration](#10--user-and-access-administration)
11. [Mobile and till app delivery](#11--mobile-and-till-app-delivery)
12. [Operational troubleshooting](#12--operational-troubleshooting)
13. [Quick reference](#13--quick-reference)
14. [Where to go deeper](#14--where-to-go-deeper)

---

## 1 · Ground rules

Each of these exists because breaking it has cost something real.

1. **The database is durable in every environment** — local, QA, production, customer. Never
   `docker compose down -v`. Never `docker volume rm` a data volume. Never `flyway clean` (it is
   disabled in configuration, deliberately).
2. **Migrations are additive and immutable, and cannot be unapplied.** "Rollback" of a schema change
   means "restore a dump". Therefore: **back up before the deploy, never after.**
3. **Never commit or push to `main`.** `develop` is the integration branch; branch off it, open a PR,
   the owner merges to `main`.
4. **Never auto-deploy.** Merge to `develop`, then stop and ask for review before QA. Production and
   customer deploys are always explicitly authorised, each time.
5. **Verify on the box, not by exit code.** A deploy script has shipped the wrong branch and exited
   `0`. Check the commit SHA and probe an authenticated route.
6. **A release goes out when someone is available to watch it and roll it back** — not at the end of
   a session, and never unattended. There is one production installation per customer, so there is no
   canary and no staged rollout: the customer's working day is the detection mechanism.
7. **No secret on a command line.** It is visible in `ps` to any other user on the box. Use stdin or
   a mode-600 file that is removed afterwards.
8. **Never `StrictHostKeyChecking=no`.** Confirm the fingerprint on first connect and let it pin; a
   changed key later must be an error you investigate.

---

## 2 · The estate

### 2.1 Environments

| Environment | Host | Reached at | Topology | Data |
|---|---|---|---|---|
| **Local dev** | your machine | `http://localhost:4200` (SPA), `:8081` (API) | Postgres in Docker on host port **5434**; API and SPA native | volume `erp-db-data` — do not wipe |
| **QA** | `16.170.11.41` (Ubuntu, `ubuntu@`) | `http://16.170.11.41/` | **single container**: API + Angular + Postgres + supervisord | volume `erpclean2-data` — **permanent since 2026-06-20** |
| **Production (self-hosted)** | `16.192.117.45` (Amazon Linux 2023, `ec2-user@`) | `https://ec2-16-192-117-45.eu-north-1.compute.amazonaws.com/` | API + Caddy in Docker, `network_mode: host`; **PostgreSQL 15 native on the host** at `127.0.0.1:5432` | native Postgres data directory |
| **Customer — Kilimanjaro** | `51.21.23.170`, install dir `/opt/orbixerp` | `http://51.21.23.170` | offline bundle driven by `orbixerp.sh` | Docker volume namespaced by `ERP_STACK_NAME` |
| **Rehearsal / throwaway** | your machine | ports per drill (18080, 5440, 5445) | whatever the drill needs | deliberately disposable |

### 2.2 Access

| Environment | User | Key | Where the key lives |
|---|---|---|---|
| QA | `ubuntu` | `orbix-qa.pem` | operator machine; path passed as `ERP_SSH_KEY` / `SSH_KEY` or set in the gitignored `infra/qa/deploy.env.local` |
| Production | `ec2-user` | `SAM-ELECTRONIX-NEW.pem` | `C:\Users\Godfrey\.ssh\` |
| Kilimanjaro | `ubuntu` | `KILIMANJAROSUPERMARKET.pem` | `~/.ssh/` |

```bash
ssh -i ~/keys/orbix-qa.pem ubuntu@16.170.11.41                              # QA
ssh -i "C:/Users/Godfrey/.ssh/SAM-ELECTRONIX-NEW.pem" ec2-user@16.192.117.45 # production
ssh -i ~/.ssh/KILIMANJAROSUPERMARKET.pem ubuntu@51.21.23.170                 # Kilimanjaro
```

Keys must be mode `600` (or the Windows ACL equivalent) or OpenSSH refuses them.

**Use `ssh -tt` when the remote command needs a terminal.** `orbixerp.sh restore` reads its
confirmation from `/dev/tty` and treats "no terminal" as a refusal — a plain non-interactive SSH
restore silently cancels every time.

**To run a script remotely, `scp` it — do not pipe it.** Piping into `ssh "bash -s"` from PowerShell
injects a UTF-8 BOM and CRLF endings, giving `set: command not found` and carriage-return errors on
the remote bash. `scp` transfers bytes verbatim.

```bash
scp -i <key> ./do-thing.sh user@host:~/do-thing.sh
ssh -i <key> user@host 'bash ~/do-thing.sh'
```

### 2.3 Where the secrets live (never in git)

| Secret | Location |
|---|---|
| QA bootstrap admin + DB passwords | `~/erpclean2/infra/qa/qa.env` **on the QA box** |
| QA live credential summary | `infra/qa/CREDENTIALS.local.md` (gitignored, local) |
| QA deploy key path + GitHub PAT | `infra/qa/deploy.env.local` (gitignored, local) |
| Production DB + bootstrap secrets | `~/erpclean2/infra/prod/.env` **on the box** |
| Production JWT keys | `~/erpclean2/infra/prod/jwt-keys/{private,public}.pem` **on the box** |
| Customer secrets | `<install-dir>/.env` **on the customer machine** |
| Customer JWT keys | `<install-dir>/secrets/jwt/` **on the customer machine** |

> **`infra/prod/.env` contains spaces in values** (`ERP_BOOTSTRAP_ORG_NAME=My Organisation`).
> **Do not `source` it.** Grep what you need:
> ```bash
> PGPASSWORD=$(grep -E '^POSTGRES_PASSWORD=' infra/prod/.env | cut -d= -f2-)
> ```
> On the QA box, `set -a; . infra/qa/qa.env` prints harmless "command not found" noise for the
> spaced values; the variables still load.

### 2.4 Ports and URLs

| Port | Serves | Exposure |
|---|---|---|
| `4200` | Angular dev server | local only |
| `8081` | **API + SPA, one origin** (`ERP_API_PORT`) | local; behind Caddy in production; inside the QA container |
| `9090` | **actuator / health / Prometheus** (`ERP_MANAGEMENT_PORT`) | never public — **this is where health lives, not 8081** |
| `5434` | local dev Postgres (host side) | local only |
| `5432` | Postgres in the QA container; native Postgres on production | loopback only |
| `80` | QA published port; Caddy HTTP→HTTPS redirect in production | public |
| `443` | Caddy HTTPS (production, optional client overlay) | public |
| `8080` | default client-bundle port (`ERP_HTTP_PORT`) | customer LAN |

| Environment | URL | Certificate |
|---|---|---|
| QA | `http://16.170.11.41/` | none — plain HTTP |
| Production | `https://ec2-16-192-117-45.eu-north-1.compute.amazonaws.com/` | **Caddy self-signed** (`tls internal`) — browsers warn |
| Kilimanjaro | `http://51.21.23.170` | none |

**HTTPS on the bare production IP fails** (curl returns `000`). Caddy binds the site to
`ERP_PUBLIC_HOST`, which is the EC2 hostname — verify through the `ec2-...amazonaws.com` URL. HTTP on
port 80 against the IP returns 200 through the redirect.

**To get a trusted certificate:** point a domain's A record at the box, set `ERP_PUBLIC_HOST` to that
domain, delete the `tls internal` line from `infra/prod/Caddyfile`, bring the stack up again. Caddy
auto-issues and renews Let's Encrypt from then on.

**Sub-path hosting (`https://client.com/orbix-erp`) is not supported** — the SPA is pinned to the
origin root by `<base href="/">`, an absolute `apiBaseUrl`, and the absence of a servlet context
path. **The answer is a subdomain**, `https://erp.client.com`, which works today and costs one DNS
entry.

### 2.5 Verifying what is actually running

Do this after **every** deploy.

```bash
# Which commit is checked out? (QA and production, on the box)
cd ~/erpclean2 && git rev-parse --abbrev-ref HEAD && git log --oneline -1

# Is it actually up? Health is on 9090.
docker exec erpclean2 wget -qO- http://127.0.0.1:9090/actuator/health   # QA
wget -qO- http://127.0.0.1:9090/actuator/health                          # production
cd /opt/orbixerp && ./orbixerp.sh status && ./orbixerp.sh version        # customer

# What schema version? Read the BOOT LOG, not SQL.
docker logs erpclean2 2>&1 | grep -i "Current version of schema"
docker logs erpclean2 2>&1 | grep -i "Successfully applied\|No migration necessary"
docker logs erpclean2 2>&1 | grep -i "Started ErpApplication"
```

**Four traps:**

1. **`max(version)` on `flyway_schema_history` is a LEXICAL string maximum** — `"9" > "78"`. It lies.
   Read *"Current version of schema"* from the boot log.
2. **An unauthenticated probe cannot tell 404 from 200.** Spring Security returns `401` *before*
   routing, so a nonexistent path answers exactly like a real one. To prove a route exists you must
   log in — and with a **non-root** account, because root bypasses permissions.
3. **`unzip` is not on the QA box**, so jar-listing checks silently return 0. **`python3` is in the
   container** — use it. The Angular bundle lives *inside* the jar at `BOOT-INF/classes/static/`, so
   grepping the container filesystem finds nothing either way, and shared components land in a lazy
   `chunk-*.js` rather than `main-*.js`, so fetching `/` and grepping `main` is a false negative.
   ```bash
   docker exec erpclean2 python3 -c "import zipfile; z=zipfile.ZipFile('/opt/erpclean2/app.jar'); print(len([n for n in z.namelist() if n.startswith('BOOT-INF/classes/static/')]),'static entries')"
   ```
4. **A `(unhealthy)` container badge on production is usually cosmetic** — the old healthcheck probed
   port 8081 where actuator does not live. Verify on **9090**.

---

## 3 · Day-to-day operations

### 3.1 QA

```bash
ssh -i <key> ubuntu@16.170.11.41
docker ps                                   # is erpclean2 up?
docker logs -f erpclean2                    # follow the log
docker restart erpclean2                    # restart (data is kept)
docker exec -it erpclean2 psql -U erp -d erp
docker exec erpclean2 wget -qO- http://127.0.0.1:9090/actuator/health
```

### 3.2 Production

```bash
ssh -i <key> ec2-user@16.192.117.45
cd ~/erpclean2
docker ps
docker logs -f erp-prod-api
docker logs -f erp-prod-caddy
docker compose -f infra/prod/docker-compose.hostdb.yml restart api
psql -h 127.0.0.1 -U erp -d erp
wget -qO- http://127.0.0.1:9090/actuator/health
```

### 3.3 A customer installation

`orbixerp.sh` (Linux/macOS) and `orbixerp.ps1` (Windows) are the entire day-2 surface. They work out
the database mode from `.env` and assemble the right Compose command, so nobody types a `docker`
command.

```bash
cd /opt/orbixerp

./orbixerp.sh start            # start the system
./orbixerp.sh stop             # stop it (data is kept — never uses -v)
./orbixerp.sh restart          # apply changes made to .env
./orbixerp.sh status           # is it running and healthy?
./orbixerp.sh logs -f          # application logs
./orbixerp.sh backup           # write a backup into backups/
./orbixerp.sh restore <file>   # REPLACE the database from a backup
./orbixerp.sh update <dir>     # upgrade to a newer release bundle (a PATH, not a version)
./orbixerp.sh version          # what is installed
./orbixerp.sh config           # the RESOLVED docker compose config (NOT .env)
./orbixerp.sh help
```

On Windows the same commands are available by double-clicking `OrbixERP.cmd`, which opens a
start/stop/backup menu.

**Three integration traps if you drive these from a script or a GUI:**

1. **`restore` reads its confirmation from `/dev/tty`.** Allocate a TTY (`ssh -tt`) and send
   `RESTORE`, or pass `--yes`.
2. **`update` takes a PATH to an unpacked release bundle directory** (it checks for a `VERSION`
   file), not a version number. Upload first, then pass the path.
3. **`config` runs `docker compose config`** — the resolved compose, not `.env`. An `.env` viewer
   must read the real file.

---

## 4 · Release process and gates

### 4.1 Branch workflow

`develop` is the integration branch; `main` is release. **Never commit to or push `main`.** Branch
off `develop` (or `feat/**`), commit with Conventional Commits, open a PR — the owner merges.
CI runs on `main`, `develop` and `feat/**`.

### 4.2 Versioning

- **Never `latest`.** Both release build scripts reject it. A moving tag makes it impossible to know
  what a customer is running when they call for support.
- Version numbers **must move forward and be unique**. The customer's installer compares the bundle's
  `VERSION` against their `.env`; a matching version reports "nothing to update", and a fresh install
  over a working system is always refused.
- The pom stays `0.0.1-SNAPSHOT`; the release version is a build argument.
- `CHECKSUMS.txt` records SHA-256 for every file in `sha256sum -c` format. `VERSION` carries the
  version, architecture, build date and git commit.

### 4.3 Why CI is not enough

CI is thorough — unit tests, ArchUnit gates, a two-organisation isolation harness — and on
**2026-08-15 it passed, twice, on releases that would not start.** The reason is structural:

| Path | Why no test environment exercises it |
|---|---|
| `BootstrapRunner` | runs only when `organisations.count() == 0`. QA has an organisation, so it never executes there |
| `TenancyReconciler`'s stamping loop | QA's roles were already stamped, so the loop had no work and its trigger never fired |
| `application-prod.yml` | loaded by the `prod` profile only. Local uses `dev`, QA uses `qa` — **the customer is the first to parse it** |

**The two failures, concretely.** *1.8.0 — a migration:* `V102` added a `BEFORE UPDATE` trigger that
matched the row it was validating, and `TenancyReconciler` performs exactly that update on every
boot. The application crash-looped twelve times. *1.8.2 — one line of config, no migration:* a second
top-level `spring:` key was appended to `application-prod.yml`; duplicate keys are a hard parse error
and the application died before reading a single property.

**The second is the more instructive: "no migration" is not the same as "low risk".**

### 4.4 The gates, in order

**Gate 1 — fast suite plus the two-organisation harness.**

```bash
cd backend && mvn -B clean test
mvn -Dit.test=TwoOrganisationIsolationIT verify
```

Necessary, not sufficient.

**Gate 2 — boot under the `prod` profile.**

```bash
cd backend && mvn -o -B spring-boot:run -Dspring-boot.run.profiles=prod
```

Under a minute, and it catches every `application-prod.yml` fault outright — property resolution and
bean wiring, not just the parse.

**Gate 3 — boot against a restored copy of the customer's database.** Not a fresh one: **theirs**. A
fresh database has nothing for the reconciler to stamp, no legacy roles, no unattributed rows, and
none of the shape that has actually broken. Running SQL against the copy is **not enough** —
1.8.0's defect was in what the *application* does at startup, and the DDL rehearsal passed.

**Gate 3b — boot against an EMPTY database, through the shipped installer.** Gates 3 and 3b hide
**different** defects. A populated database has legacy roles for the reconciler and a trigger that
fires; an empty one has neither — but only the empty path runs `BootstrapRunner` and first-install
provisioning at all.

```bash
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh up dist/release/orbixerp-<version>-amd64'
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh verify'
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh down'
```

Ten minutes on a throwaway stack. Run it in **WSL, not Git Bash**, and install to **native ext4**
(`/opt/...`) — `chown` is a no-op on `/mnt/c` and the app then cannot read its own signing key.

**Gate 4 — boot the shipped artefact.** Load the image from the bundle and run *that*, not the source
tree. Ninety seconds, and it is the last thing between a bundle and a customer.

```bash
docker load -i images/orbixerp-api-<version>-amd64.tar.gz
docker run --rm -e SPRING_PROFILES_ACTIVE=prod -e SPRING_DATASOURCE_URL=... orbixerp-api:<version>
```

**Gate 5 — QA, then a human.** Deploy `develop` to QA, **verify the SHA on the box**, then have
someone open the product and click. Two of three releases on 2026-08-15 shipped defects that every
automated check passed; the thing that caught both was a person using the software.

### 4.5 Release checklist

1. `mvn -B clean test` and `npm test -- --watch=false` green on the release commit.
2. Gates 2, 3, 3b, 4 above, as applicable to what the release touches.
3. Bump the version; write `RELEASE-NOTES.md` (the build writes a stub).
4. Build the bundle (§7.1).
5. **Install the bundle on a clean machine and complete a first run** before sending it — Docker's
   own errors for a bad bundle are unhelpful, and the installer's are only useful once exercised.
6. Verify `CHECKSUMS.txt` after transfer.
7. Record which customer received which version and architecture.

**Before shipping, ask: which paths does this change touch that QA cannot reach?** Bootstrap, the
reconciler, the `prod` profile, a first-use path, a migration against existing data. Then go and run
that one deliberately.

### 4.6 Rollback — decide before you ship

| The release contains | Rollback is |
|---|---|
| **No migration** | Reinstall the previous bundle. The schema is untouched, so the older application runs against it unchanged |
| **A migration** | **Restore the safety backup that `update` prints.** Reinstalling the old version will **not** work — Flyway finds applied migrations it does not ship and refuses to start |
| **A one-way migration** (a dropped constraint, a replaced unique index) | Restore only. **Say so in the release notes *before* shipping** |

```bash
ssh -tt -i <key> ubuntu@<host>          # -tt: restore reads /dev/tty and hangs without it
cd /opt/orbixerp && ./orbixerp.sh restore backups/<file>
```

`main` is **no longer a pre-tenancy rollback artefact** — the tenancy work merged into it on
2026-08-15. Rolling back past `V99` now means restoring a database backup taken before 1.8.0.

---

## 5 · Deploying to QA

**Target:** `16.170.11.41` · container `erpclean2` · image `erpclean2:qa` · volume `erpclean2-data` ·
repo on the box at `~/erpclean2`.

### 5.1 Before you start

- The change is merged to `develop` and **the owner has reviewed and approved the deploy**. Never
  auto-deploy.
- You know whether the release contains a migration (§4.6).
- **QA data is permanent.** A normal deploy keeps the volume. This is the default and what you want.

### 5.2 The deploy

```powershell
# Windows / PowerShell
$env:ERP_SSH_KEY = "C:\path\to\orbix-qa.pem"
infra\qa\deploy.ps1                     # deploys the branch in deploy.env
infra\qa\deploy.ps1 -Branch develop     # or another branch
```

```bash
# macOS / Linux / git-bash
export ERP_SSH_KEY=~/keys/orbix-qa.pem
bash infra/qa/deploy.sh                 # deploys the branch in deploy.env (currently main)
BRANCH=develop bash infra/qa/deploy.sh  # override
```

### 5.3 What the script does, in order

1. **Backs up the QA database first** — before the pull, before the build, long before Flyway runs.
   The dump is pulled to **your** machine (`$HOME/qa-backups/qa-<stamp>-pre-deploy.sql`, override with
   `ERP_QA_BACKUP_DIR`), because a backup that only exists on the box is not a backup of the box.
   **If the backup fails the deploy aborts** — an unbackupable database is a reason to stop and look,
   and a zero-byte file next to a broken database is worse than no file. To proceed anyway, accepting
   there is no rollback point: `ERP_SKIP_BACKUP=1 BRANCH=<b> bash infra/qa/deploy.sh`.
   The backup is taken deliberately **before** the build, because a build takes minutes and a backup
   taken after it is a backup of a database somebody may have used meanwhile.
2. SSHes in, `git fetch` / `checkout <branch>` / `pull --ff-only`, and **echoes the branch and SHA it
   landed on**.
3. `docker build -f infra/qa/Dockerfile -t erpclean2:qa .`
4. Stops and removes the container, ensures the volume exists, and `docker run -d --name erpclean2
   -p 80:8081 -v erpclean2-data:/var/lib/postgresql/data --env-file infra/qa/qa.env --restart
   unless-stopped erpclean2:qa`.
5. Prunes dangling images.

If `infra/qa/qa.env` is missing on the box the script warns and the app starts **not bootstrapped**.

### 5.4 The branch-override trap — read this once

`deploy.sh` used to `source deploy.env` *after* the caller's environment, so
`BRANCH=develop ./deploy.sh` — the override the README documents — was overwritten by
`deploy.env`'s `BRANCH=main`. The box rebuilt the branch it already had, Docker reported every layer
`CACHED`, and the script printed `==> deployed` and **exited 0**.

**A deploy that ships nothing is indistinguishable from a successful one.**

Fixed: precedence is now environment > `deploy.env.local` > `deploy.env`, and the remote block echoes
`==> now at <branch> <sha>`. `deploy.ps1` never had the bug — its `-Branch` parameter takes
precedence.

**The lesson stands regardless: verify on the box.**

### 5.5 Verify

```bash
ssh -i <key> ubuntu@16.170.11.41

cd ~/erpclean2
git rev-parse --abbrev-ref HEAD && git log --oneline -1     # the branch and SHA you meant

docker ps --filter name=erpclean2
docker exec erpclean2 wget -qO- http://127.0.0.1:9090/actuator/health
docker logs erpclean2 2>&1 | grep -i "Current version of schema"
docker logs erpclean2 2>&1 | grep -iE "Successfully applied|No migration necessary"
docker logs erpclean2 2>&1 | grep -i "Started ErpApplication"
docker logs erpclean2 2>&1 | grep -iE "ERROR|Exception" | head
```

Then prove the release actually landed in the running jar. Log in — with the **non-root** account
`AMIR` / `amir2026`, because root bypasses permissions — and exercise a route the release added.
For a UI change, search the static entries inside the jar with `python3` (§2.5 trap 3).

Note when calling the API by hand: `/api/v1/companies` needs `?organisationUid=`, obtained from
`/api/v1/organisations/current`, and ids come back as JSON **strings**.

Finally: open `http://16.170.11.41/` in a browser and click through the change.

### 5.6 First-time bootstrap of a QA box (once per instance)

```bash
ssh -i orbix-qa.pem ubuntu@16.170.11.41

# Docker, then re-login so the docker group applies
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
exit
ssh -i orbix-qa.pem ubuntu@16.170.11.41

# Clone with a fine-grained PAT (repo: ERPCLEAN2, Contents: Read-only).
# The PAT stays in the box's git remote so the automated deploy's git pull is hands-off.
git clone https://oauth2:<PAT>@github.com/godfreydesidery/ERPCLEAN2.git erpclean2
cd erpclean2

# Bootstrap secrets on the box
cp infra/qa/qa.env.example infra/qa/qa.env
nano infra/qa/qa.env      # set ERP_BOOTSTRAP_ADMIN_PASSWORD (>=12 chars) and DB_PASSWORD
exit
```

The security group must allow inbound `22` and `80` from your IP. Then run `deploy.ps1` /
`deploy.sh` from your machine for every release.

### 5.7 Resetting QA — deliberate use only

> ⚠ **This drops all QA data.** It is not routine. Day-to-day releases use the data-preserving
> deploy above.

```bash
ssh -i <key> ubuntu@16.170.11.41
docker stop erpclean2 && docker rm erpclean2
docker volume rm erpclean2-data
# then run deploy.ps1 / deploy.sh again — it re-bootstraps from qa.env
```

### 5.8 Rolling QA back

```bash
# 1. Redeploy the previous commit
BRANCH=<previous-branch-or-sha> bash infra/qa/deploy.sh

# 2. If the bad release applied a migration, restore the pre-deploy dump the deploy took
cat ~/qa-backups/qa-<stamp>-pre-deploy.sql | \
  ssh -i <key> ubuntu@16.170.11.41 "docker exec -i erpclean2 psql -U erp -d erp"
```

### 5.9 Facts about the QA data worth knowing

QA holds **four companies in one organisation** (SAM Electoronix QA, Otapp Agency, G&G, KILIMANJARO)
and 13 users. `rootadmin` and `AMIR` both sit in company 4.
**The QA `rootadmin` password is shared with another person — never rotate it** (owner, 2026-08-15).
QA has only one organisation, so **cross-tenant denial cannot be proven end-to-end there** — that is
covered by unit tests and `TwoOrganisationIsolationIT`.

---

## 6 · Deploying to production (self-hosted EC2)

**Target:** `16.192.117.45`, Amazon Linux 2023, `ec2-user@`, repo at `~/erpclean2` on `main`,
host-DB topology (`infra/prod/docker-compose.hostdb.yml`), PostgreSQL 15 **native on the host**.

### 6.1 Pre-flight

- The change is on `main` and the deploy is explicitly authorised.
- You know whether it contains a migration, and whether any migration is one-way (§4.6).
- Someone is available to watch and to roll back.

### 6.2 The deploy, step by step (run on the box)

```bash
ssh -i "C:/Users/Godfrey/.ssh/SAM-ELECTRONIX-NEW.pem" ec2-user@16.192.117.45
cd ~/erpclean2

# 1. BACK UP FIRST. Grep the credentials — do not source .env (it has spaces in values).
mkdir -p ~/db-backups
PGPASSWORD=$(grep -E '^POSTGRES_PASSWORD=' infra/prod/.env | cut -d= -f2-) \
PGUSER=$(grep -E '^POSTGRES_USER=' infra/prod/.env | cut -d= -f2-) \
PGDATABASE=$(grep -E '^POSTGRES_DB=' infra/prod/.env | cut -d= -f2-) \
  pg_dump -h 127.0.0.1 -Fc -Z9 \
    -f ~/db-backups/erpclean2_predeploy_$(date +%Y%m%d_%H%M%S).dump
ls -lh ~/db-backups | tail -3          # confirm it is non-empty

# 2. Pull
git pull --ff-only origin main
git log --oneline -1

# 3. Build and swap. The build is heavy on a 2-vCPU / 3.7 GB box — run it DETACHED so an
#    SSH drop does not kill it. Compose only swaps the container after a successful build,
#    so a failed build means no downtime.
nohup docker compose -f infra/prod/docker-compose.hostdb.yml up -d --build \
  > ~/deploy.log 2>&1 &
tail -f ~/deploy.log
```

Flyway applies additive `V<n>` migrations on boot (`clean-disabled`, `validate-on-migrate` — it never
wipes). **Never `down -v`.**

### 6.3 Verify

```bash
# Real health — port 9090, not the container badge
wget -qO- http://127.0.0.1:9090/actuator/health          # expect UP

docker logs erp-prod-api 2>&1 | grep -i "Current version of schema"
docker logs erp-prod-api 2>&1 | grep -iE "Successfully applied|No migration necessary"
docker logs erp-prod-api 2>&1 | grep -i "Started ErpApplication"

# External, through Caddy — use the HOSTNAME, not the IP
curl -k -o /dev/null -w '%{http_code}\n' \
  https://ec2-16-192-117-45.eu-north-1.compute.amazonaws.com/     # expect 200 (the SPA)
curl -k -o /dev/null -w '%{http_code}\n' \
  https://ec2-16-192-117-45.eu-north-1.compute.amazonaws.com/api/v1/companies   # expect 401 unauth
```

The container badge may read `(unhealthy)` — cosmetic; see §2.5.

### 6.4 Rolling production back

| Situation | Action |
|---|---|
| No migration in the release | `git checkout <previous-sha>` and rebuild with the same compose command |
| A migration ran | Stop the API, restore the pre-deploy dump, then start the previous code |

```bash
docker compose -f infra/prod/docker-compose.hostdb.yml stop api
PGPASSWORD=... pg_restore -h 127.0.0.1 -U erp -d erp --clean --if-exists --no-password \
  ~/db-backups/erpclean2_predeploy_<stamp>.dump
git checkout <previous-sha>
docker compose -f infra/prod/docker-compose.hostdb.yml up -d --build
```

### 6.5 First-time production setup (once per host)

```bash
# 1. Native PostgreSQL 15: install, init, create the erp role + database.
#    It must accept scram-sha-256 password auth from 127.0.0.1 and never listen off-box.

# 2. Configuration
cp infra/prod/.env.example infra/prod/.env
nano infra/prod/.env
#    - POSTGRES_PASSWORD must EQUAL the password of the native erp role
#    - ERP_PUBLIC_HOST must be set, or HTTPS has no name to issue a cert for
#    - ERP_BOOTSTRAP_ENABLED=true for the very first boot only, with a >=12-char admin password

# 3. Stable RS256 signing keys
bash infra/prod/generate-jwt-keys.sh      # -> infra/prod/jwt-keys/{private,public}.pem

# 4. Bring it up
docker compose -f infra/prod/docker-compose.hostdb.yml up -d --build

# 5. After the first successful bootstrap, set ERP_BOOTSTRAP_ENABLED=false and redeploy
```

`infra/prod/docker-compose.yml` (Postgres in a container) is **reference only** — a starting point
and a way to smoke-test the prod image locally.

---

## 7 · Deploying to a customer

Customers receive **compose files, two scripts, a set of guides and offline Docker image tarballs** —
no `backend/`, no `web/`, no `pom.xml`, no source. Delivery is offline by decision: no registry, no
`docker login`, no per-client credential to issue, rotate or leak; it works on sites with poor or
absent connectivity; and the bytes a customer runs are exactly the bytes handed over.

### 7.1 Cut the bundle

```powershell
.\dist\build-release.ps1 -Version 1.9.4                # amd64 + arm64
.\dist\build-release.ps1 -Version 1.9.4 -Arch amd64
```

```bash
bash dist/build-release.sh --version 1.9.4
bash dist/build-release.sh --version 1.9.4 --arch amd64
bash dist/build-release.sh --refresh-docs              # after editing a guide, then commit both .md and .txt
```

Output in `dist/release/`:

```
orbixerp-1.9.4-amd64/        the bundle
orbixerp-1.9.4-amd64.zip     hand this over
orbixerp-1.9.4-arm64/  ...
```

Send **one** archive — the one matching the customer's processor. A release **refuses to build** if a
committed `docs/*.txt` no longer matches its `.md`.

**Check the architecture before sending.** `uname -m` on their box: `x86_64` needs the amd64 bundle.
`orbixerp.sh update` refuses a mismatched bundle outright, which is the right failure but a wasted
transfer.

### 7.2 What is in the bundle

```
orbixerp-<version>-<arch>/
├── Setup.cmd + setup-wizard.ps1    Windows: double-click for the graphical wizard
├── Install.cmd                     Windows: same install, plain text window
├── OrbixERP.cmd                    Windows: double-click for a start/stop/backup menu
├── install.sh / install.ps1        one-shot installer
├── orbixerp.sh / orbixerp.ps1      day-2 commands
├── .env.example                    every setting, commented; the installer copies it to .env
├── docker-compose.yml              base stack: the API only, database-agnostic
├── docker-compose.db-docker.yml    overlay: we provide PostgreSQL
├── docker-compose.db-host.yml      overlay: the customer's own PostgreSQL
├── docker-compose.tls.yml          optional overlay: HTTPS via Caddy
├── Caddyfile
├── images/                         orbixerp-api, postgres, caddy — as .tar.gz
├── docs/                           INSTALL, HOST-DB-SETUP, OPERATIONS, TROUBLESHOOTING,
│                                   REMOTE-INSTALL, REMOTE-CONTROL-PANEL — each .md AND .txt
├── backups/  secrets/jwt/          empty, created by the build
└── VERSION  CHECKSUMS.txt  RELEASE-NOTES.md  LICENSE.txt
```

The Windows `.cmd` files exist because Windows blocks double-clicked `.ps1` outright (execution
policy) while `.cmd` is unrestricted; each starts PowerShell with the block lifted for that one
script. All three must be **CRLF and ASCII** — `.gitattributes` pins the first and the release build
enforces both.

> ⚠ **`LICENSE.txt` is an unreviewed template.** It is the *actual* protection for the compiled code
> (an image is packaging, not protection). **Have a lawyer review it before the first handover.**

### 7.3 First install — the customer's side

**Windows (the normal path).** Unzip, **double-click `Setup.cmd`**. The graphical wizard (WinForms,
because .NET ships inside Windows — nothing to install first, nothing downloaded, works with no
internet at all) creates the install folder, copies the bundle in (excluding `images/`, which are
dead weight once loaded into Docker), writes `.env`, generates the admin and database passwords with
the system RNG, generates the RS256 keypair, loads the images, starts the stack, waits for health,
optionally drops a desktop shortcut, and flips `ERP_BOOTSTRAP_ENABLED` to false once the system is
genuinely up.

`Install.cmd` does the same install in a plain text window — for remote or scripted installs.

**Install location follows the Bitnami convention:** a dedicated top-level folder (`C:\OrbixERP`),
**never Program Files**. The wizard refuses Program Files, Program Files (x86), `C:\Windows` and bare
drive roots outright, with no "install anyway as administrator" escape hatch — taking it yields a
system needing admin rights forever after, for updates and even for backups. Downloads, Desktop and
Temp are warned about but permitted, since the install folder holds the signing keys and the backups.
The payoff is that install, update and backup all work as an ordinary user, and the whole deployment
is one copyable tree.

**Linux / macOS:**

```bash
./install.sh                      # guided — asks a few questions
./install.sh --defaults           # unattended, using the values already in .env
./install.sh --backup-time 03:30  # nightly backup time (default 02:00)
./install.sh --no-schedule        # do not schedule the nightly backup
```

Safe to run more than once: an existing `.env` is never overwritten, existing signing keys are never
regenerated, and an existing database is never touched. In order it: checks the bundle matches the
machine and Docker is usable → loads the images from `images/` (no internet needed) → creates `.env`
and generates passwords and keys → checks the port is free and, in `host` mode, that the database is
reachable, the credentials work and it is safe to install into → starts everything and waits until
genuinely ready → schedules a nightly backup via **cron** (deliberately not a systemd timer, which
would need root and defeat the ordinary-user contract).

**Choosing the database mode.** `docker` (recommended) means we ship and manage PostgreSQL. `host`
means the customer's own PostgreSQL — in which case **the database must be empty and dedicated**; the
installer verifies this and refuses otherwise, recognising an existing installation by
`flyway_schema_history` and allowing an in-place upgrade.

**First boot is slow** — the full migration chain against an empty database. The healthcheck's
`start_period` is 180 s and `orbixerp.sh start` waits up to 15 minutes.

### 7.4 Updating a customer installation

**Releasing an update is the same command as a first release** — there is no separate "update build".
Hand over the new archive; the customer double-clicks `Setup.cmd`, points it at their existing
installation, and the wizard recognises the version already there and switches to update mode. All
setup questions are skipped — their database, organisation and network settings are already
configured. The wizard does not reimplement any of this: it calls `orbixerp.ps1 update`, so the
graphical and typed routes cannot drift.

From a terminal:

```bash
# 1. Upload the unpacked bundle to the customer's box
scp -i <key> -r dist/release/orbixerp-1.9.4-amd64 ubuntu@<host>:~/orbixerp-1.9.4-amd64

# 2. Update — the argument is a PATH to the bundle directory, not a version
ssh -i <key> ubuntu@<host>
cd /opt/orbixerp
./orbixerp.sh update ~/orbixerp-1.9.4-amd64
```

**What `update` does, in order:**

1. Refuses a bundle built for the wrong architecture.
2. Checks the installation is writable.
3. **Takes a safety backup and stops if it fails.** Labelled `orbixerp-preupdate_<stamp>_<from>-to-<to>.dump`
   so housekeeping keeps it for `ERP_BACKUP_PREUPDATE_RETAIN_DAYS` (90 days) rather than the 14 a
   nightly backup gets — the file able to undo a release used to expire in a fortnight.
   **It prints the filename. Write it down.**
4. Loads the new images from the bundle's `images/`.
5. Replaces the compose files, `Caddyfile`, `.env.example` and the guides. **`.env`, `secrets/` and
   `backups/` are the customer's and are never touched.**
6. Sets `ERP_VERSION` and forces `ERP_BOOTSTRAP_ENABLED=false`.
7. Starts the new version and waits for health.
8. Replaces the control scripts last (safe mid-run: bash parses the whole file before executing).
9. Copies `VERSION` **last, once the new release is genuinely up** — copied early, a half-finished
   update left the file naming a version that was not running, so the obvious "what is installed?"
   check confirmed a success that had not happened.

Then verify:

```bash
./orbixerp.sh version
./orbixerp.sh status
./orbixerp.sh logs | tail -50
```

**Rollback is §4.6.** For a release with a migration, restoring the printed safety backup is the
**only** route back.

### 7.5 Installing or managing over SSH from Windows

`Remote-Setup.cmd` / `remote-setup-wizard.ps1` is a Windows GUI that SSHes to a Linux box to install
and manage OrbixERP, so nobody has to open a terminal. It uploads the bundle over SSH (so it works
air-gapped, with no hosting), installs Docker if missing (Ubuntu/Debian, Amazon Linux, RHEL/Rocky),
and then drives `orbixerp.sh` for day-2 operations — view/edit `.env`, start/stop/restart, logs,
backups, updates, status.

Transport is Windows' built-in OpenSSH (`ssh.exe` / `scp.exe`) — no PuTTY, no WSL, no extra module.
**Key-file auth is the only path**: OpenSSH cannot take a password non-interactively without a helper
binary, so do not try to script one.

Rules baked in, which must not be relaxed: **never `StrictHostKeyChecking=no`** (show the fingerprint,
have the operator confirm, pin it; a later change is an error), and **no secret on a command line**
(`ps` exposes it) — secrets go via stdin or a mode-600 file that is removed afterwards.

Guides: `docs/REMOTE-INSTALL.md` and `docs/REMOTE-CONTROL-PANEL.md` in the bundle.

### 7.6 Editing a customer's `.env` — footguns

- **`ERP_DB_PASSWORD` is the dangerous one.** Changing it in `.env` does **not** change the password
  inside an existing database volume; it only breaks the application's ability to connect.
- **`ERP_PUBLIC_HOST`** affects Caddy and TLS — certificates may need reissuing.
- Some values need a restart to take effect. Say so, rather than silently doing nothing.
- **Always back up the previous `.env`** before writing a new one.
- `./orbixerp.sh config` shows the **resolved compose**, not `.env`. To see `.env`, read the file.

### 7.7 A second instance (training / demo) on the same machine

Unzip the bundle into a second folder and change two settings: `ERP_STACK_NAME` (which namespaces the
compose project, containers, network and volume) and `ERP_HTTP_PORT`. That is the whole procedure.

---

## 8 · Backup, restore and disaster recovery

### 8.1 Which tool is on which box — check before quoting a filename

| Estate | Script | Writes | Retention variable |
|---|---|---|---|
| **Customer installation** | `orbixerp.sh backup` / `orbixerp.ps1` | `<install-dir>/backups/orbixerp_<stamp>.dump` (and `orbixerp-preupdate_<stamp>_<label>.dump`) | `ERP_BACKUP_RETAIN_DAYS` (14), `ERP_BACKUP_PREUPDATE_RETAIN_DAYS` (90) |
| **Self-hosted production** | `infra/prod/backup.sh` | `${BACKUP_DIR}/erpclean2_<YYYYMMDD_HHMMSS>.dump` | `BACKUP_RETAIN_DAYS` (14) |
| **QA** | the deploy script, automatically | `$HOME/qa-backups/qa-<stamp>-pre-deploy.sql` **on the operator's machine** | none — prune by hand |

Different name, different directory, different variable. **Check which box you are on.**

### 8.2 Customer installation

```bash
cd /opt/orbixerp
./orbixerp.sh backup                    # into backups/
ls -lh backups/ | tail
cat backups/backup.log                  # the nightly schedule's own log
crontab -l                              # confirm the nightly job exists
```

The installer schedules a nightly backup (default 02:00) via cron, so the customer should not have to
type `backup` at all. **Checking it is running is a monthly job** — the guide `docs/OPERATIONS.md`
walks the customer through it.

**Getting backups off the machine is still nobody's job automatically.** A backup that lives only on
the machine it protects is not a backup of that machine. Arrange a copy — external drive, network
share, cloud sync — and keep **three things together**: the `.dump` file, the `.env` (which holds the
database password the dump needs to be restored under) and the `secrets/jwt/` keys.

### 8.3 Self-hosted production

```bash
PGHOST=127.0.0.1 PGPORT=5432 PGDATABASE=erp PGUSER=erp PGPASSWORD=<pw> \
  BACKUP_DIR=/backups BACKUP_RETAIN_DAYS=14 \
  sh infra/prod/backup.sh
```

Writes `erpclean2_<stamp>.dump` in `pg_dump -Fc --compress=9` format and prunes archives older than
`BACKUP_RETAIN_DAYS`. `PGPASSWORD` is required and has no default.

Ad-hoc, before any risky operation:

```bash
PGPASSWORD=$(grep -E '^POSTGRES_PASSWORD=' infra/prod/.env | cut -d= -f2-) \
  pg_dump -h 127.0.0.1 -U erp -d erp -Fc -Z9 \
  -f ~/db-backups/erpclean2_$(date +%Y%m%d_%H%M%S).dump
```

### 8.4 Restoring

> **A restore is a whole-database operation. There is no tenant filter.** If the database holds more
> than one organisation, **every** organisation is taken back to the moment of the dump — they lose a
> trading day and re-issue invoice numbers already used. On a shared instance this needs named vendor
> sign-off and both customers told first. `infra/prod/restore.sh` prints this warning and waits five
> seconds before proceeding.

**Stop the API before restoring** — otherwise Flyway and Hibernate fight the restore.

**Customer installation:**

```bash
ssh -tt -i <key> ubuntu@<host>        # -tt: restore reads /dev/tty
cd /opt/orbixerp
./orbixerp.sh restore backups/orbixerp_20260828_020000.dump
# type RESTORE when prompted, or pass --yes for a scripted drill
```

**Self-hosted production:**

```bash
docker compose -f infra/prod/docker-compose.hostdb.yml stop api

PGHOST=127.0.0.1 PGDATABASE=erp PGUSER=erp PGPASSWORD=<pw> \
  DUMP_FILE=/backups/erpclean2_20260828_120000.dump \
  sh infra/prod/restore.sh

docker compose -f infra/prod/docker-compose.hostdb.yml start api
# Flyway runs `validate` on start — read the boot log
```

`restore.sh` uses `pg_restore --clean --if-exists`, which **drops and recreates every object in the
target database** before restoring. Confirm the dump file is the one you mean.

**QA:**

```bash
cat ~/qa-backups/qa-<stamp>-pre-deploy.sql | \
  ssh -i <key> ubuntu@16.170.11.41 "docker exec -i erpclean2 psql -U erp -d erp"
```

### 8.5 A restore that must not lose the schema history

A restored `pg_dump` of one of our own databases **carries `flyway_schema_history` with it**, which is
why `baseline-on-migrate` is `false` and why a real restore needs no baseline. If you ever restore a
schema-only or history-less dump, see [TECHNICAL-RUNBOOK.md](TECHNICAL-RUNBOOK.md) §7.8 before
starting the application against it.

### 8.6 The recovery drill

Run the drill rather than assuming the backups work. Full procedure, timings and traps:
[ops/restore-drill.md](ops/restore-drill.md). It covers in-place recovery (timed), bare-metal
recovery, and **testing that the schedule actually fires** — which is a separate question from
whether a restore works.

Related deep references: [ops/backup-restore.md](ops/backup-restore.md) (both tool families and the
shared-instance implications) and [ops/rehearsal-stack.md](ops/rehearsal-stack.md).

### 8.7 Housekeeping

The customer's backup housekeeping is bounded on four axes at once: age
(`ERP_BACKUP_RETAIN_DAYS`), a floor and ceiling on file count (`ERP_BACKUP_KEEP_MIN` 7,
`ERP_BACKUP_KEEP_MAX` 90), and total directory size (`ERP_BACKUP_DIR_MAX_MB` 2048). Pre-update
backups are exempt from the short retention and kept for 90 days.

---

## 9 · Incident response

### 9.1 First five minutes

```bash
# 1. Is the process alive, and is it healthy? (health is on 9090)
docker ps
docker exec <container> wget -qO- http://127.0.0.1:9090/actuator/health

# 2. Is it crash-looping? Look at restart count and the tail of the log.
docker ps --format '{{.Names}}\t{{.Status}}'
docker logs --tail 200 <container>

# 3. Capture evidence BEFORE changing anything
docker logs <container> > ~/incident-$(date +%Y%m%d-%H%M%S).log 2>&1
```

Record: what changed and when, the version before and after, whether the release contained a
migration, and the exact first error line.

### 9.2 Crash-loop triage

| First error looks like | Cause | Action |
|---|---|---|
| YAML parse / duplicate key | a `prod`-profile-only config fault | roll back to the previous version; reproduce locally with `-Dspring-boot.run.profiles=prod` |
| Flyway checksum mismatch | an applied migration was edited | restore the original migration file; ship the correction as a new `V<n>` |
| Flyway duplicate version | two branches used the same `V<n>` | renumber and re-release |
| A migration error repeated every boot | the migration fails against *this* data | see §9.3 |
| `NullPointerException` in a startup runner | a bootstrap/reconciler path no test environment reaches | roll back, then reproduce against a restored copy of that database |
| Cannot connect to the database | credentials changed in `.env` but not in the volume, or Postgres is down | check Postgres first; **never** "fix" it by changing the password in `.env` |

**Rolling back is the first move, not the last.** Diagnose from a copy afterwards.

### 9.3 A migration has half-applied

```sql
SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 20;
```

1. **Back up first**, even now — especially now.
2. If the release can be abandoned: restore the pre-deploy dump and redeploy the previous version.
3. If it must go forward: fix the data or author a new `V<n>`, run `flyway repair` to clear the
   failed row, restart.

**A migration cannot be unapplied.** Reinstalling the old application against an upgraded schema will
not start — Flyway finds applied migrations it does not ship.

### 9.4 The lesson under all of it

Every production failure so far has had the same shape: **the thing that broke was the thing no
environment except the customer's had ever executed.** Before shipping, name the paths this change
touches that QA cannot reach — bootstrap, the reconciler, the `prod` profile, a first-use path, a
migration against existing data — and go and run that one deliberately (§4.4).

Guards that now exist because of specific incidents: `ApplicationYamlParsesTest`,
`CodeSequenceSeederCoversAllKindsTest`, `OrganisationWriteMappingsAreAllowlistedTest`,
`StandingOrderSweepPrincipalTest`.

### 9.5 Communication

There is one production installation per customer, so there is no canary: the customer's working day
is the detection mechanism. If an incident affects a live installation, tell the customer what
happened, what you have done, and what they should expect — before they ask. If a restore is
involved, they need to know they may have lost a period of work and may need to re-issue documents.

---

## 10 · User and access administration

### 10.1 The model

- **Permissions** are the atomic unit — dot-separated codes (`SALES_INVOICE.POST`, `USER.MANAGE`).
- **Roles** are bundles of permissions.
- **`app_user.is_root`** short-circuits every check and is always audited. Root exists for recovery,
  not for daily work.
- A user may be assigned to **many branches**, with exactly **one default**. Login lands them in the
  default branch; switching branch is context-only (an `X-Branch-Uid` header), with no DB write and
  no re-login.
- **Membership first:** grants and branch assignments require prior company membership
  (`user_company`).

### 10.2 Common tasks

| Task | How |
|---|---|
| Create a user | Admin → Users → New. Then assign at least one branch (`user-branches`, one marked default) and grant a role |
| Give a user access to a branch | Admin → Users → the user → Branches |
| Change what a role can do | Admin → Roles. A role can only be granted permissions the granting admin themselves holds (the authority ceiling) |
| Add a **new** permission code | It must be seeded — `R__seed_permissions.sql`, with owner approval. See [TECHNICAL-RUNBOOK.md](TECHNICAL-RUNBOOK.md) §7.5 |
| Unlock an account | 5 failed attempts locks for 15 minutes; it clears itself |
| Reset a password | Admin → Users → Reset password |
| Recover a lost `rootadmin` password | Only via the database. Take a backup, then update the hash directly. Bootstrap will **not** re-run — it fires only when there are no organisations |

### 10.3 Testing access changes

**Always verify as a non-root user.** Root bypasses every permission check, so a missing grant is
invisible when testing as root. On QA use `AMIR` / `amir2026`.

Three failure shapes to check for:

1. A permission gated in code but never seeded — every non-root user is blocked, and CI will not see
   it unless a test covers the code.
2. An Angular route guard whose code does not match the backend endpoint's — the user sees "can't
   open this screen" instead of a meaningful 403.
3. A role bundle that looks complete but lacks one code the screen needs — e.g. a finance role
   holding `REPORT.EXPORT` but not `INVENTORY.VALUATION.VIEW` cannot open the valuation report at
   all.

### 10.4 JWT key rotation

```bash
bash infra/prod/generate-jwt-keys.sh     # warns and waits 5s if keys already exist
# then redeploy
```

**Rotation logs every user out at the moment of deploy.** Do it deliberately, outside business hours.
Back the private key up **outside the repo** — it can forge a token for any user on that deployment.

---

## 11 · Mobile and till app delivery

Build commands and verification are in [TECHNICAL-RUNBOOK.md](TECHNICAL-RUNBOOK.md) §12. The
operational rules:

**Check the server before shipping a client build.** The app is only as new as the API behind it — a
screen calling an endpoint the customer's server does not have fails in their hands, not in testing.
This **cannot be probed**: the API answers 401 for a nonexistent route exactly as it does for one
needing a login. The evidence is the git dates of the endpoints the build depends on, checked against
the version the customer is actually running.

**Verify the baked-in host actually landed**, and that no *other* customer's host is present — a
build pointed at one customer that still carries another's address is worse than one pointed at
localhost. Command in §12 of the technical runbook.

**Current builds are debug-signed.** Android warns on install; they are not distribution builds. A
real release keystore is required before an app goes to a customer or a store.

**Naming and delivery convention:** `dist/orbixhq/OrbixHQ-<version>-<target>.apk` and
`dist/OrbixPOS-<version>-windows.zip`, with a README table recording which build carries which server
address. Keep that table current — it is how support answers "what is this phone pointed at?".

**Support:** the OrbixHQ server address is behind a seven-tap gesture on the footer line, and is
always readable under **Settings → About**. Ask a user to read it back from there.

---

## 12 · Operational troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Deploy exits 0 but nothing changed | the wrong branch was deployed; every Docker layer `CACHED` | §5.4 — verify the branch and SHA on the box |
| Deploy aborts: "could not back up the QA database" | the container is not running, or `pg_dump` failed | fix that first. Override only knowingly: `ERP_SKIP_BACKUP=1` |
| Container badge `(unhealthy)`, app works fine | healthcheck probing 8081 instead of 9090 | verify on 9090 |
| HTTPS on the production IP returns 000 | Caddy binds the site to `ERP_PUBLIC_HOST` | use the `ec2-...amazonaws.com` hostname |
| Browser warns the certificate is not trusted | Caddy self-signed (`tls internal`) — expected without a domain | §2.4 to move to Let's Encrypt |
| Customer: "nothing to update" | the bundle's version equals the installed `ERP_VERSION` | versions must move forward and be unique |
| Customer: update refused, wrong architecture | amd64 bundle on an arm64 box or vice versa | check `uname -m`; send the matching bundle |
| Customer: fresh install refused | there is a working installation already | use `update`, not `install` |
| Customer: `host` DB mode refused at install | the target database is not empty and dedicated | give it an empty, dedicated database, or use `docker` mode |
| Customer: cannot connect after an `.env` edit | `ERP_DB_PASSWORD` changed; the volume's password did not | restore the previous `.env` |
| A scripted restore silently does nothing | `restore` reads `/dev/tty` and treats no-terminal as refusal | `ssh -tt`, or pass `--yes` |
| `./orbixerp.sh update 1.9.4` fails | `update` takes a **path**, not a version | pass the unpacked bundle directory |
| `.env` viewer shows the wrong thing | `config` prints the resolved compose, not `.env` | read the file |
| `source infra/prod/.env` errors | values contain spaces | grep the keys you need |
| Remote bash: `set: command not found` | a script was piped through `ssh` from PowerShell (BOM + CRLF) | `scp` the file, then `ssh bash ~/file` |
| First boot takes many minutes | the full migration chain against an empty database | expected — `start_period` is 180 s and `start` waits up to 15 minutes |
| Reported schema version looks wrong | `max(version)` is a lexical string max | read "Current version of schema" from the boot log |
| A route "does not exist" but returns 401 | Spring Security 401s before routing | log in (non-root) and probe again |
| POS tills all stop trusting the server | the `erp-prod-caddy-data` volume was lost and Caddy minted a new root | fetch the new root and drop it into the tills' `certs/` (technical runbook §12.1) |

---

## 13 · Quick reference

### 13.1 QA

```bash
export ERP_SSH_KEY=~/keys/orbix-qa.pem
BRANCH=develop bash infra/qa/deploy.sh            # deploy (backs up first, to YOUR machine)

ssh -i $ERP_SSH_KEY ubuntu@16.170.11.41
cd ~/erpclean2 && git rev-parse --abbrev-ref HEAD && git log --oneline -1
docker exec erpclean2 wget -qO- http://127.0.0.1:9090/actuator/health
docker logs erpclean2 2>&1 | grep -i "Current version of schema"
docker restart erpclean2
docker exec -it erpclean2 psql -U erp -d erp
```

### 13.2 Production

```bash
ssh -i "C:/Users/Godfrey/.ssh/SAM-ELECTRONIX-NEW.pem" ec2-user@16.192.117.45
cd ~/erpclean2

PGPASSWORD=$(grep -E '^POSTGRES_PASSWORD=' infra/prod/.env | cut -d= -f2-) \
  pg_dump -h 127.0.0.1 -U erp -d erp -Fc -Z9 -f ~/db-backups/pre_$(date +%Y%m%d_%H%M%S).dump

git pull --ff-only origin main
nohup docker compose -f infra/prod/docker-compose.hostdb.yml up -d --build > ~/deploy.log 2>&1 &
tail -f ~/deploy.log

wget -qO- http://127.0.0.1:9090/actuator/health
docker logs erp-prod-api 2>&1 | grep -i "Current version of schema"
```

### 13.3 A customer installation

```bash
cd /opt/orbixerp
./orbixerp.sh status
./orbixerp.sh version
./orbixerp.sh logs -f
./orbixerp.sh backup
./orbixerp.sh update ~/orbixerp-<version>-amd64
./orbixerp.sh restore backups/<file>      # needs ssh -tt, or --yes
```

### 13.4 Cutting and shipping a release

```bash
cd backend && mvn -B clean test
cd ../web && npm test -- --watch=false && npm run build
cd ../backend && mvn -o -B spring-boot:run -Dspring-boot.run.profiles=prod   # gate 2
cd .. && bash dist/build-release.sh --version 1.9.4 --arch amd64
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh up dist/release/orbixerp-1.9.4-amd64'
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh verify'
wsl -e bash -lc 'bash scripts/rehearse-fresh-install.sh down'
# then QA, then a human, then hand over the archive + verify CHECKSUMS.txt
```

### 13.5 Checklists

**Before any deploy**

- [ ] Change is merged to the right branch and the deploy is explicitly authorised
- [ ] I know whether this release contains a migration, and whether it is one-way
- [ ] A backup exists, taken **before** this deploy, and I know its filename
- [ ] Someone is available to watch and roll back

**After any deploy**

- [ ] Branch and SHA on the box are what I intended
- [ ] Health on **9090** is UP
- [ ] The boot log shows the expected schema version and no errors
- [ ] I logged in as a **non-root** user and exercised the change
- [ ] A human opened the product and clicked through it

**Before handing a bundle to a customer**

- [ ] All release gates passed for what this release touches
- [ ] Architecture matches their machine
- [ ] Release notes written, and any one-way migration called out
- [ ] Installed on a clean machine and first run completed
- [ ] `CHECKSUMS.txt` verified after transfer
- [ ] Recorded which customer got which version and architecture

---

## 14 · Where to go deeper

| Document | Adds |
|---|---|
| [TECHNICAL-RUNBOOK.md](TECHNICAL-RUNBOOK.md) | architecture, build, test, configuration reference, app builds |
| [ops/backup-restore.md](ops/backup-restore.md) | both backup tool families; shared-instance implications |
| [ops/restore-drill.md](ops/restore-drill.md) | the timed recovery drill and its measured results |
| [ops/release-staging-and-rollback.md](ops/release-staging-and-rollback.md) | the narrative behind the gates, written after two outages in one day |
| [ops/fresh-install-rehearsal.md](ops/fresh-install-rehearsal.md) | the empty-database rehearsal and what it found |
| [ops/tenant-onboarding.md](ops/tenant-onboarding.md) | onboarding an additional organisation onto an instance |
| [ops/migrations-and-seeding.md](ops/migrations-and-seeding.md) | migration authoring against a durable database |
| [ops/jwt-keys.md](ops/jwt-keys.md) · [ops/security-sweep.md](ops/security-sweep.md) | key material; the periodic security review |
| [../infra/qa/README.md](../infra/qa/README.md) | the QA single-container deployment in the maintainer's own words |
| [../dist/README.md](../dist/README.md) | maintainer rationale for the client distribution package |
| `<bundle>/docs/INSTALL.md`, `OPERATIONS.md`, `TROUBLESHOOTING.md`, `HOST-DB-SETUP.md`, `REMOTE-INSTALL.md`, `REMOTE-CONTROL-PANEL.md` | the customer-facing guides — read these before answering a customer question |
