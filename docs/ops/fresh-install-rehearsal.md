# The fresh-install rehearsal (B1)

*First executed 2026-08-15. Four full stack builds against an empty database, on a throwaway
compose project. This document records what was run, what it produced, and what it does not prove.*

Kilimanjaro reached 1.8.3 by in-place `update` from 1.6.1. **Nobody has ever run `install.sh`
against an empty database at 1.8.x** — V1→V103 on an empty schema, `R__seed_permissions`,
`BootstrapRunner` (which fires only when `organisations.count() == 0`, so no test environment
reaches it), then `TenancyReconciler`. This runbook makes that path a ten-minute gate.

> **What this run proves, and what it does not.** The bundle rehearsed is
> `dist/release/orbixerp-1.8.3-amd64`, built at commit `d9d77baa`. `HEAD` is **12 commits ahead**,
> and three of them (`964e467b`, `13e721cb`, `0202d044`) rewrote `TenantProvisioningService`,
> `CompanyProvisioningServiceImpl` and added `CodeSequenceSeeder` — i.e. exactly the code this path
> executes. **This run validates the harness and the installer. It does not validate the release
> customer #2 will receive.** That release must be rebuilt and re-rehearsed from its own commit.

## Result

**One real failure, reproduced on every run** ([H3](#h3--the-first-real-user-cannot-be-given-a-branch)):
the first administrator can create a user but cannot assign it a branch. Everything else passed —
Flyway reached V103 on an empty schema, the application booted under the `prod` profile,
`BootstrapRunner` produced a complete tenant, login worked, and a real business action succeeded.

A second defect was found by construction rather than by luck: the installer's password generator
emits an admin password that the application then refuses, on **3.03% of installs (measured)**,
producing a 15-minute silent hang. See [the password lottery](#the-password-lottery).

## The harness

[`scripts/rehearse-fresh-install.sh`](../../scripts/rehearse-fresh-install.sh) — `up` / `verify` /
`reboot` / `down`.

It is hard-coded to a throwaway namespace (`orbix-b1r`, port `18080`, `/opt/orbix-b1r`), refuses to
adopt a real stack name, contains no `docker compose down -v` and no `prune`, and removes its
volume by literal name. `down` finishes by asserting that `erp-db-data` and `erp-rehearsal-data`
still exist.

### Run it inside WSL, onto native ext4

```bash
wsl -e bash -lc 'bash /mnt/d/My_Works/ERP/ERPCLEAN2/scripts/rehearse-fresh-install.sh up \
  /mnt/d/My_Works/ERP/ERPCLEAN2/dist/release/orbixerp-1.8.3-amd64'
wsl -e bash -lc 'bash /mnt/d/My_Works/ERP/ERPCLEAN2/scripts/rehearse-fresh-install.sh verify'
wsl -e bash -lc 'bash /mnt/d/My_Works/ERP/ERPCLEAN2/scripts/rehearse-fresh-install.sh reboot'
wsl -e bash -lc 'bash /mnt/d/My_Works/ERP/ERPCLEAN2/scripts/rehearse-fresh-install.sh down'
```

**Not Git Bash** — `install.sh` is the script customer #2 runs on Linux, so run it on Linux.
**Not `/mnt/c` or `/mnt/d`** — `install.sh:390-396` chowns the JWT private key to uid 10001 and
`docker-compose.yml:59` mounts it into a container running as that uid. `chown` is a no-op on a
DrvFs mount, so the key would be unreadable and the app would die for a reason that has nothing to
do with the release under test.

`verify` is **single-shot**: sections G and H write to the database, so a second run against the
same stack fails `C5` and `G2` legitimately. To re-run, go round `down` → `up` → `verify`.

### The only three deviations from a customer's defaults

Applied to `.env.example`, *not* to `.env`, so `create_env`'s real `cp template .env` + `chmod 600`
path (`install.sh:269-270`) still executes exactly as it does for a customer:

| Key | Value | Why |
|---|---|---|
| `ERP_STACK_NAME` | `orbix-b1r` | namespaces project, containers, network and volume |
| `ERP_HTTP_PORT` | `18080` | nothing on this machine has ever used it |
| `ERP_BIND_ADDR` | `127.0.0.1` | do not expose a rehearsal to the LAN |

Every other convenience edit removes a path from the gate. In particular **do not publish the
database port** — `docker-compose.db-docker.yml:31-32` keeps it unpublished by design; verification
goes through `docker exec orbix-b1r-db psql -U erp -d erp`.

## What actually happened

Three full `up` → `verify` cycles, plus one deliberate failure injection.

| Measurement | Value |
|---|---|
| `install.sh --defaults` wall clock | **372 s / 364 s / 309 s** (three runs) |
| Flyway V1→V103 + `R__seed_permissions` on an empty schema | **50 s** — "Successfully applied 104 migrations" |
| `Started ErpApplication` | **132.3 s** |
| `Bootstrap complete` | ~3 s after application start |
| Second boot (`orbixerp.sh restart`) | **163 s** |
| Healthcheck `start_period` (`docker-compose.yml:69`) | 180 s — **~40 s of margin over a measured ~140 s** |

All three installs exited 0.

### Flyway reached V103 on an empty schema — no failures

103 versioned migrations, no gaps, then the repeatable seed. `flyway_schema_history` held 104
successful rows and 0 failed. `R__seed_permissions.sql` applied cleanly, which is the check that
would fail first if `V102:39`'s `uq_role_code_global` and the seed's `ON CONFLICT` predicate ever
drift apart.

### The application booted under the `prod` profile

`The following 1 profile is active: "prod"`, actuator readiness `UP`, public `/api/v1/health` `UP`,
exactly one `Started ErpApplication` (no crash-loop), no startup exceptions. **This is gate 2 of
[release-staging-and-rollback.md](release-staging-and-rollback.md) executed against the shipped
image rather than the source tree** — the thing 1.8.2 needed and did not get.

### BootstrapRunner produced a complete tenant

1 organisation, 1 company, 1 branch, 1 user. Alias `my-organisation` set by provisioning (not by the
reconciler). Base currency `TZS`. `rootadmin` with `is_root = true` and a non-null `organisation_id`.
One `user_branch` row, flagged default.

The 23 company-scoped defaults all landed:

```
chart_of_accounts 48   gl_configs 45      tax_rates 3        units_of_measure 15
fiscal_years 1         fiscal_periods 12  cash_bank_accounts 1  document_branding 1
stock_locations 2      leave_types 6      notification_types 6  pipeline_stages 5
dimensions 2           sales_settings 1   company_currency 8    code_sequence 1
roles 13               permissions 252    petty_cash_funds 1
```

**And what a fresh tenant does not get**, which converts the readiness doc's B3/B4/B5 from inference
into measurement:

```
price_lists 0    pos_tills 0    customers WHERE customer_kind='CASH_WALK_IN' 0
```

### TenancyReconciler ran cleanly — but had nothing to do

`Tenancy reconcile: all users and customer roles are attributed.` No errors.

That is the point to be careful about. On a fresh database **every seeded role is global** (13
global, 0 tenant-scoped), so the reconciler's stamping loop had no rows to stamp and the V102/V103
trigger never fired. See [what this gate does not cover](#what-this-gate-does-not-cover).

### Login and a real business action both worked

Token issued, `isRoot` true, `activeBranchUid` non-null (so the default branch assignment landed).
`POST /api/v1/customers` returned **201** with code `CUST-0001`; the chart of accounts read back;
the Angular client is served from the same port.

One incidental confirmation: `party_code_sequence` is **not seeded on 1.8.3** (the fix, `13e721cb`,
post-dates the bundle) but the lazy path works — the row appeared on the first customer create.

## Findings

### H3 — the first real user cannot be given a branch

**Reproduced on every run.** Signed in as `rootadmin` on a fresh install:

```
POST /api/v1/users          -> 201   (user created)
user_company                -> 0     (no membership row)
POST /api/v1/user-branches  -> 409   "Assign this user to the company before assigning branches."
```

The cause is `UserServiceImpl.establishCreatorCompanyMembership`:

```java
if (principal == null || principal.root()) {
    return; // root / no principal → leave unassigned
}
```

Membership is established for users created by a *normal* administrator. **On a fresh install the
only account that exists is `rootadmin`, and root is precisely the case that skips it.** So every
user the first administrator creates starts with no company membership, and the branch and role
pickers stay empty until membership is assigned explicitly.

**It is not a dead end** — there is a working path, and the web UI supports it
(`user-detail.component.ts:30`: *"assign a company first, then its branches/roles become
available"*). Verified end-to-end on the final stack:

```
POST /api/v1/user-companies {userUid, companyUid}   -> 201
POST /api/v1/user-branches  {userUid, branchUid}    -> 201   (the call that was 409)
POST /api/v1/auth/login     as the new user         -> 200
```

**This belongs in the B6 onboarding runbook as an explicit ordered step.** Undocumented, it is a
day-one support call: the administrator creates a user, tries to give it a branch, and is refused.

### The password lottery

`install.sh:352-356` generates the first administrator's password:

```bash
docker run --rm --entrypoint openssl "$(image_ref)" rand -base64 64 \
  | tr -dc 'A-Za-z0-9' | cut -c1-"$len"
```

Twenty characters drawn from 52 letters + 10 digits, with **no guarantee of a digit**.
`PasswordPolicy.validate` rejects a password with no digit, and `BootstrapRunner:113` calls it.

Measured over 3,000 draws: **91 digit-free (3.03%)**, against a theoretical `(52/62)^20 = 2.97%`.
Roughly **one install in thirty-three**.

Reproduced by forcing a digit-free password onto an otherwise untouched first boot:

```
WeakPasswordException: Password must contain letters and at least one number
restarts    = 3 and climbing      organisations = 0
status      = running             health        = starting
flyway applied = 104              (the schema migrates fine; only the tenant is missing)
```

Two things make this worse than it looks:

1. **The installer cannot detect it.** `container_health()` (`orbixerp.sh:134-142`) reports
   `stopped` only when the container status is not `running`. A container crash-looping under
   `restart: unless-stopped` reads as `running`/`starting`, so `wait_healthy`'s fast-fail branch
   never triggers and the install **burns the full 900 s** before dying with *"The system did not
   become ready within 15 minutes"* — a message that names nothing.
2. **It is recoverable but not self-evident.** The schema is fully migrated and `organisations` is
   0, so `BootstrapRunner` will run again; setting a password containing a digit in `.env` and
   restarting fixes it. Nothing tells the customer that.

`install.ps1:275-282` (`New-Secret`) uses the identical alphabet, so Windows is equally exposed.

> **Triage rule.** If this rehearsal ever hangs for 15 minutes and dies, grep the API log for
> `Password must contain letters and at least one number` **before** suspecting a migration
> regression.

### The startup runners execute in the opposite order to their own documentation

Only two of the three bootstrap runners carry `@Order`:

| Class | Annotation | Effective order |
|---|---|---|
| `UserCompanyBackfill:39` | `@Order(20)` | second |
| `TenancyReconciler:53` | `@Order(30)` | third |
| `BootstrapRunner` | **none** | `LOWEST_PRECEDENCE` — **last** |

Both siblings' comments assert the opposite. `UserCompanyBackfill:35` says *"Runs after
BootstrapRunner (Order 20 vs 10)"* — **there is no `@Order(10)`**. `TenancyReconciler:53` says
*"after BootstrapRunner (unordered, runs last)"*, which is self-contradictory.

Measured consequence, via `reboot`:

```
boot 1:  rootadmin | user_company=0 | user_role=0 | user_branch=1
boot 2:  rootadmin | user_company=1 | user_role=0 | user_branch=1
         "UserCompanyBackfill: seeded 2 user_company rows from 2 (user, company) pairs."
```

On boot 1 both runners execute against an empty database and do nothing; only then does
`BootstrapRunner` create the tenant. **The tenant's own rows are first seen on the second boot.**
No failure was observed from this on 1.8.3, but it means a fresh installation spends its first boot
in a state the reconciler has never inspected.

Boot 2 also confirmed the restart is clean: no migrations re-applied (104 rows before and after),
`R__` checksum unchanged, one `Started ErpApplication`, healthy.

### Smaller observations

- **The bootstrap admin holds no role at all on 1.8.3.** `user_role = 0`; it works purely through
  the `is_root` short-circuit. The `ORG_ADMIN` grant (P4-3) post-dates the bundle and is present at
  `HEAD` — worth re-checking on the customer-#2 build, because under a shared instance the
  root/platform tier is the thing the readiness doc says to fix first.
- **Usernames are silently rewritten.** Asking for `b1.probe` stores `b1.probe@my-organisation`.
  The API response does return the stored name, so it is visible rather than hidden — but an
  administrator who hands out the name they typed will get *"Invalid username or password."*
  Confirmed: login as `b1.probe` → 401; as `b1.probe@my-organisation` → 200.
- **`docker-compose.yml:67` still says "93 migrations"**, and the 180 s `start_period` was sized
  against that number. There are now 103. Measured boot is ~140 s, so the margin is ~40 s — thin on
  hardware slower than a development laptop. Same stale comment in `dist/bundle/`.
- **`install.sh:222-224` swallows copy failures** (`cp -R ... 2>/dev/null || true`). Only reachable
  when `INSTALL_DIR != SCRIPT_DIR`, which is the default when a customer unpacks to Downloads and
  accepts `/opt/orbixerp`. Not exercised by this rehearsal, which installs in place.

### Reported, not fixed — `backend/src` is out of scope for this task

- `BootstrapRunner` has no `@Order`, and two sibling javadocs claim it does.
- `TenancyReconciler:226` calls `organisations.findById(u.getOrganisationId())` for every user whose
  username contains `@` (filtered at `:223`). Spring Data's `findById(null)` throws
  `IllegalArgumentException`, and the runner is `@Transactional` — so one user row with an `@`
  username and a null `organisation_id` would crash-loop the application at startup, the exact shape
  of the 1.8.0 outage. Not reachable through the API today, but reachable by direct SQL or **by
  restoring a backup from another estate** — which is what a shared instance and per-tenant restore
  create.

## What this gate does not cover

**It does not replace gate 3** (boot against a restored copy of the customer's database), and it
must never be read as retiring it.

The trigger that crash-looped the live client in 1.8.0 is **provably inert on an empty database**:
`V102:77` and `V103:38` both short-circuit on `IF NEW.organisation_id IS NOT NULL`, and every role
on a fresh install is global (measured: 13 global, 0 tenant-scoped), so the trigger never fires and
the reconciler has nothing to stamp. `V103`'s own header says this outright — the 1.8.0 defect was
missed *because the local rehearsal used a fresh database*. The two databases hide different
defects. Run both.

Also not covered:

- **The interactive installer.** `--defaults` never executes `configure_interactively`
  (`install.sh:303-347`), so the 1.8.x installer's questions have still never been answered by a
  human. To close that, drive it with a pty: `script -qec ./install.sh /dev/null < answers.txt`
  (`ask` reads `/dev/tty` at `:83`).
- **Host database mode.** `ERP_DB_MODE=host` and `check_host_database` (`install.sh:421-481`) are
  untested here.
- **Windows.** `install.ps1` has no `choose_install_dir` equivalent at all and creates only
  `secrets\jwt`, never `backups\` (`install.sh:230` creates both; the bundle ships a `backups/`
  directory, which masks it).
- **TLS**, the Caddy overlay, and `remote-setup-wizard.ps1`.
- **Whether the tenant can trade.** A green gate plus a working login is still a system with no
  price list, no till and no walk-in customer — the POS renders everything `NO_PRICE` and the till
  cannot open. *"Fresh install rehearsed and passing"* does not mean *"customer #2 can go live."*

**And under the shared-instance decision, this gate is not customer #2's onboarding path at all.**
With both customers in one database, customer #2 arrives through
`POST /api/v1/organisations` against a *populated* database — that is B6. What this gate covers is
the platform's own next installation and the disaster-recovery rebuild.

## Teardown

`down` tears down only what `up` built and then proves it:

```
Container orbix-b1r-api / orbix-b1r-db  Removed
Network orbix-b1r_default               Removed
Volume  orbix-b1r-db-data               Removed
PASS  erp-db-data still present
PASS  erp-rehearsal-data still present
PASS  erp-rehearsal-db still running
```

Loaded images (`orbixerp-api:1.8.3`, `postgres:15-alpine`, `caddy:2-alpine`) are left alone — they
are shared, and re-loading them is most of the install time.

> **Never** run `docker compose down -v`, `docker volume prune` or `docker system prune` on this
> machine. It hosts `erp-rehearsal-data` (a restored copy of the live customer's database) and
> `erp-db-data` (the local dev volume CLAUDE.md says to preserve), and a prune would also discard
> the buildx cache that keeps a bundle rebuild to minutes.

## Before customer #2: run this again, on their build

1. Cut the release commit (a task is concurrently widening `CreateTenantRequest` — pin it, do not
   build from a mid-flight tree).
2. `bash dist/build-release.sh --version <next> --arch amd64`. **Never reuse an existing version
   number** — `build_bundle` does `rm -rf` on the target directory first, and 1.8.3 is the artefact
   the live customer is running and the only local rollback copy.
3. `up` → `verify` → `reboot` → `down` against the new bundle.
4. Expect `user_role = 1` this time (P4-3) and `party_code_sequence > 0` (13e721cb). If either is
   still 0, the fix did not reach the bundle.
