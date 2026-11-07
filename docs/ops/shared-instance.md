# The shared multi-tenant server

*MULTITENANCY-PLAN.md D-11 and item R-5. Written 2026-11-07. Stack files: [`infra/shared/`](../../infra/shared/).
Backups and point-in-time restore: [shared-instance-pitr.md](shared-instance-pitr.md).*

One server, one Postgres database, one API node, several customers separated by `organisation_id`.
It is a **new deployment for customer #2 onward** (D-1, D-11). Customer #1's existing install, the
`dist/` bundles, the LAN Windows-service jar, `infra/prod` and `infra/qa` are unchanged and keep
running as they are. The server runs the **same image and code** as every other install; there is no
tenancy-mode flag (plan section 0). Everything that differs is in `infra/shared/.env` and the compose file.

## Not supported

| Not supported | Why | What to do instead |
|---|---|---|
| **More than one API node** | The scheduled jobs (outbox dispatcher, midnight standing-order sweep, P8-6) take no locks. Two nodes would double-dispatch events and double-generate every tenant's standing orders. The compose file pins one replica and a fixed `container_name` so `--scale api=2` is refused. | Scale up (CPU/RAM, pool), not out. Multi-node needs scheduler locks first - an application change tracked as a plan follow-up. |
| **Per-tenant fiscalisation (TRA/VFD)** | `erp.fiscal.provider` is one JVM-wide property and only `none`/`simulated` exist. D-8: the shared server does not fiscalise until the real TRA adapter exists. The compose file pins `ERP_FISCAL_PROVIDER=none`. | A tenant that must fiscalise today needs its own single-tenant install. **R-1 is enforced by the server:** `POST /organisations` is refused (409) while a provider is enabled, and once more than one organisation exists no receipt is ever sent to a provider (it is recorded NOT_CONFIGURED). Keep `ERP_FISCAL_PROVIDER=none` anyway. |
| **Restoring one tenant by restoring the database** | A database restore is whole-instance. | The support procedure in [shared-instance-pitr.md](shared-instance-pitr.md) section 6. |
| **Per-tenant TLS names, rate limits** | One Caddy site per process. | - |
| **Per-tenant mail address** | One `spring.mail.*` block per process. With `ERP_MAIL_FROM` set (R-10), every tenant's mail leaves from that one address with the tenant's name as display name and the company's contact email as Reply-To. | Set `ERP_MAIL_FROM` to an address on a domain whose SPF/DKIM covers the SMTP relay. |

## 1 - Install (once)

On a Linux host with Docker Compose v2, from a checkout of the release you are deploying:

1. Create the environment file and fill in every placeholder:
   ```sh
   cp infra/shared/.env.example infra/shared/.env
   chmod 600 infra/shared/.env
   ```
   Generate passwords with `openssl rand -base64 24`. `ERP_BOOTSTRAP_ADMIN_PASSWORD` must be at least
   12 characters with a digit, or the app refuses to start.
2. Generate the RS256 signing key. It is mounted read-only; `ERP_JWT_SIGNING_MODE=file` is pinned in
   the compose file.
   ```sh
   mkdir -p infra/shared/jwt-keys
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out infra/shared/jwt-keys/private.pem
   openssl pkey -pubout -in infra/shared/jwt-keys/private.pem -out infra/shared/jwt-keys/public.pem
   chmod 600 infra/shared/jwt-keys/private.pem
   ```
   (`infra/prod/generate-jwt-keys.sh` writes the same pair under `infra/prod/`; use it if you prefer, then
   copy the files, or point `ERP_JWT_KEY_DIR` at them.) **On this server the private key signs tokens
   for every customer.** Back it up outside the repo and the server; custody and rotation are item R-4
   and [jwt-keys.md](jwt-keys.md). Do not go live with a tenant before R-4's custody decision is made.
3. Set `ERP_PUBLIC_HOST` (a real domain whose A record is this host) and, for a trusted certificate,
   `ERP_CADDY_TLS=you@example.com`. Leave it at `internal` for a self-signed certificate.
4. First boot, with bootstrap on:
   ```sh
   sed -i 's/^ERP_BOOTSTRAP_ENABLED=.*/ERP_BOOTSTRAP_ENABLED=true/' infra/shared/.env
   docker compose -f infra/shared/docker-compose.yml up -d --build
   docker compose -f infra/shared/docker-compose.yml logs -f api     # wait for "Started" + "Bootstrap complete"
   ```
   On an empty database the bootstrap creates the vendor's own organisation, company, branch and the
   user **`rootadmin`**. That account is the **platform operator** (`is_root`): the only account that
   holds `ORG.CREATE` / `ORG.SUSPEND`, because tenant administrators created through the API are **not**
   root and their role excludes the platform permissions (`TenantProvisioningService`, V102 seed). Name
   the bootstrap organisation after the vendor, not a customer (`ERP_BOOTSTRAP_ORG_NAME`).
5. Set `ERP_BOOTSTRAP_ENABLED=false` and recreate the api so the setting is not left on:
   ```sh
   sed -i 's/^ERP_BOOTSTRAP_ENABLED=.*/ERP_BOOTSTRAP_ENABLED=false/' infra/shared/.env
   docker compose -f infra/shared/docker-compose.yml up -d api
   ```
6. Check:
   ```sh
   curl -s http://127.0.0.1:9090/actuator/health/readiness        # {"status":"UP"}
   curl -sk "https://$ERP_PUBLIC_HOST/api/v1/auth/login" -H 'Content-Type: application/json' \
     -d '{"username":"rootadmin","password":"<ERP_BOOTSTRAP_ADMIN_PASSWORD>"}'
   ```
7. Schedule backups before any customer data exists: [shared-instance-pitr.md](shared-instance-pitr.md) section 2.
   Run the drill (section 7 there) once on this host.

Rollback of an install attempt: `docker compose -f infra/shared/docker-compose.yml down` (no `-v`).
A first boot that fails before any tenant exists can be repeated; if the database must be recreated that
is a deliberate decision with the owner, never routine.

## 2 - Capacity: connection arithmetic

All three knobs are env vars in `infra/shared/.env`, wired by the compose file. The defaults are for
roughly **5-20 small tenants** (a handful of tills and a few dozen users each) on a host with 4 vCPU and
8 GB RAM.

| Setting | Env var | Default | Where it applies |
|---|---|---|---|
| Postgres `max_connections` | `PG_MAX_CONNECTIONS` | 100 | `db` command line |
| Postgres `shared_buffers` | `PG_SHARED_BUFFERS` | 256MB | `db` command line (about a quarter of RAM reserved for Postgres on a shared host) |
| Hikari `maximumPoolSize` | `ERP_DB_POOL_MAX` | 20 | `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` |
| Hikari `minimumIdle` | `ERP_DB_POOL_MIN_IDLE` | 5 | `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` |
| Scheduler threads | `ERP_SCHEDULER_POOL_SIZE` | 4 | `spring.task.scheduling.pool.size` (the prod profile's own default is also 4) |

The rule is:

```
ERP_DB_POOL_MAX  +  3 (superuser_reserved_connections)  +  10 (operator psql sessions, restores, ad-hoc)  <=  PG_MAX_CONNECTIONS
        20       +  3                                   +  10                                            =   33  <=  100
```

- **One node only**, so one pool: there is no "pools x nodes" multiplication. If multi-node is ever
  supported, the pool per node must be divided accordingly.
- `pg_basebackup` uses a WAL-sender slot (`max_wal_senders=4`), which is separate from `max_connections`.
- The **scheduler threads borrow from the same Hikari pool**. Worst case all 4 are in a database call,
  leaving 16 connections for web requests. If the outbox backlog metric climbs while request latency is
  fine, raise `ERP_SCHEDULER_POOL_SIZE` and `ERP_DB_POOL_MAX` together.
- The default pool is deliberately well under `max_connections`: past about `2 x cores + 1` per database,
  more connections add contention, not throughput. Raise the pool only on evidence (Hikari
  `hikaricp_connections_pending` > 0 for sustained periods in `/actuator/prometheus`).
- Growing beyond about 20 active tenants: move to `ERP_DB_POOL_MAX=30`, `PG_MAX_CONNECTIONS=150`,
  `PG_SHARED_BUFFERS=1GB` on 16 GB RAM, then re-check the rule. Each Postgres backend costs roughly
  10 MB plus `work_mem`; 150 connections is about 1.5 GB before caches.
- `max_connections` is read at start-up: changing it is a `docker compose up -d db` (a brief outage). A
  restore scratch instance reads the value from the backup's control file, so changing it does not
  break restores of older backups.

## 3 - Metrics, per tenant

The API serves actuator on **management port 9090**, never on the public 8081. In this stack:

- the compose file publishes it on **`127.0.0.1:9090` only** (`ERP_MGMT_HOST_PORT` to change the host port);
- Caddy proxies only `api:8081` and answers 404 for `/actuator*` regardless;
- `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus` is set in the compose file because
  `application-prod.yml` exposes only `health,info` and would otherwise hide `/actuator/prometheus`.
- **Never** change the port mapping to `0.0.0.0` or add a Caddy route for it. If you need remote
  scraping, use an SSH tunnel or a Prometheus on the same host / compose network.

```sh
curl -s http://127.0.0.1:9090/actuator/prometheus | grep -E '^erp_outbox'
# erp_outbox_failed{...} 0.0                                  total FAILED events, all tenants
# erp_outbox_failed_by_tenant{organisation="2"} 3.0           FAILED events per organisation id (P8-4)
# erp_outbox_pending_oldest_age_seconds{...} 0.0              dispatch backlog age
```

`erp_outbox_failed_by_tenant` is the Prometheus form of `erp.outbox.failed.by.tenant`. The tag value is the
**organisation id** (map it with `SELECT id, name, alias FROM organisations`), and `unattributed` marks an
event with no owning company. Only organisations with failed events appear: an absent series means zero.
Suggested alerts: `erp_outbox_failed_by_tenant > 0` for 10 minutes, and
`erp_outbox_pending_oldest_age_seconds > 300`. A minimal scrape config on the host:

```yaml
scrape_configs:
  - job_name: erp-shared
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['127.0.0.1:9090']
```

## 4 - Create a tenant

Only the platform operator (`rootadmin`) may do this: `POST /api/v1/organisations` is gated on
`ORG.CREATE`. A platform web console (R-2) is coming; until it ships this is an HTTP call. The full
worked example, with measured responses and the verification checklist, is
[tenant-onboarding.md](tenant-onboarding.md) - read its section 3 (alias, collisions) first. Two parts of
that document predate the platform tier and are superseded here: tenant administrators are no longer
root, and `rootadmin` is the platform operator.

Before each tenant: **take a base backup** (`sh infra/shared/pitr/base-backup.sh`) and note its name. The
call writes 120+ rows in one transaction; a success with wrong values (a bad alias, a wrong base currency)
is not undone by the transaction rolling back.

1. Log in as the platform operator and keep the token out of shell history and process lists:
   ```sh
   TOKEN=$(curl -sk "https://$ERP_PUBLIC_HOST/api/v1/auth/login" -H 'Content-Type: application/json' \
     -d @login.json | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
   ```
   (`login.json` holds `{"username":"rootadmin","password":"..."}`; delete it afterwards.)
2. Prepare `tenant.json` (fields from `CreateTenantRequest`):

   | Field | Required | Notes |
   |---|---|---|
   | `organisationName` | yes, <= 160 | The **alias is derived from it** (lower-case, hyphenated, truncated to 20 chars) and is **immutable** (D-7b). Shorten the name before the call if the slug truncates badly. |
   | `timeZone` | no, <= 64 | Falls back to the deployment default. |
   | `companyCode` | yes, <= 20 | |
   | `companyName` | yes, <= 160 | Trading name on documents. |
   | `companyLegalName` | no, <= 200 | Blank = not recorded. |
   | `companyTaxId` | no, <= 60 | TIN. Blank = no TIN on printed documents; supply it. |
   | `companyVrn` | no, <= 40 | Reaches the Company screen and report headers, not printed invoices. |
   | `branchCode`, `branchName` | yes, <= 20 / <= 160 | The default branch. |
   | `adminUsername` | yes, <= 60 | **No `@`**; the alias is appended for you. |
   | `adminPassword` | yes, 12-200 | Hand over out of band; never returned or logged. |
   | `adminDisplayName` | no, <= 160 | |
   | `baseCurrency`, `defaultCurrency` | no, 3 chars | Default `TZS`. Not a label to fix later. |
   | `enabledCurrencies` | no | List of ISO-3 codes. |
   | `priceListCode` + `priceListName` | both or neither | Blank = no price list (POS shows items unpriced). |
   | `priceListIncludesVat` | no | Ask the customer. Omitted = VAT-exclusive. |
   | `walkInCustomerName` | no, <= 200 | Blank = none created. |
   | `posTillName` | no, <= 60 | Unique across the company: "HQ Till 1", not "Till 1". |

3. Create:
   ```sh
   curl -sk -X POST "https://$ERP_PUBLIC_HOST/api/v1/organisations" \
     -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d @tenant.json
   ```
   `201` returns `{"data":{"id":"2","uid":"...","name":"...","status":"ACTIVE","alias":"jambo-bora-traders"}}`.
   Every failure rolls the whole transaction back (`409 A record with the same unique identifier already
   exists.` is almost always an alias collision).
4. **The composed username is `<adminUsername>@<alias>`**, e.g. `admin@jambo-bora-traders`. Copy the alias
   from the response, never from what you typed. Every user that tenant creates later gets the same
   `@alias` suffix. Only `rootadmin` (and other legacy names) stay bare.
5. Verify with tenant-onboarding.md section 7 (login as the new admin, `GET /organisations/current`
   names the new organisation, the till exists, a test sale posts, the invoice prints a TIN) and section 6.1
   (a tenant's staff need company membership and a branch before a role sticks).
6. Check the server log once for the tenant's seeders and that the outbox gauge for the new organisation
   id is absent (zero failures).
7. **Fiscalisation:** do not enable a provider for this tenant or any other. A tenant that needs TRA/VFD
   today cannot be hosted here (R-1: the server refuses tenant creation while a provider is on).
8. Take another base backup so the new tenant's creation is inside a restorable window, then hand over
   credentials.

## 5 - Suspend and resume

Both are platform-operator calls (`ORG.SUSPEND`), `uid` from the create response or `GET /api/v1/organisations`:

```sh
curl -sk -X POST "https://$ERP_PUBLIC_HOST/api/v1/organisations/uid/<uid>/suspend" -H "Authorization: Bearer $TOKEN"
curl -sk -X POST "https://$ERP_PUBLIC_HOST/api/v1/organisations/uid/<uid>/resume"  -H "Authorization: Bearer $TOKEN"
```

Suspension bites **at sign-in and at the next token refresh**, not per request: staff cannot sign in
(`401`, "This account is not available at the moment"), and a till or browser that is already signed in
is refused at its next refresh. An access token already issued keeps working until it expires (at most
15 minutes). Verified by `AuthServiceImplIT.refresh_afterTheTenantIsSuspended_isRefused`. Nothing is deleted. Use it for non-payment, an incident, or the
grace period before departure (R-8). Resume restores sign-in immediately.

## 6 - Upgrade

A release changes every tenant at once: one Flyway run, no canary, no per-tenant rollback. The five gates
in [release-staging-and-rollback.md](release-staging-and-rollback.md) apply in full; none may be skipped
on the strength of "no migration":

1. Fast suite plus `TwoOrganisationIsolationIT` (`mvn -B clean test`, then the IT).
2. Boot under the `prod` profile (`mvn -o -B spring-boot:run -Dspring-boot.run.profiles=prod`).
3. Boot against a restored copy of **this server's** database - a scratch restore from
   [shared-instance-pitr.md](shared-instance-pitr.md) section 4 is exactly that - and, for 3b, an empty
   database through the installer.
4. Boot the shipped image: `docker build -f infra/prod/Dockerfile -t erpclean2:<version> .`, run it
   with `SPRING_PROFILES_ACTIVE=prod` against the scratch database.
5. QA, then a person using the product; verify the SHA on the box.

Then on the server (announce a window to all tenants first - the API is down for the Flyway run):

```sh
cd <checkout>; git fetch && git checkout <release-tag>
sh infra/shared/pitr/base-backup.sh                      # the safety backup; note its name
ERP_IMAGE_TAG=<version> docker compose -f infra/shared/docker-compose.yml up -d --build api
docker compose -f infra/shared/docker-compose.yml logs -f api   # Flyway validated/migrated, "Started"
curl -s http://127.0.0.1:9090/actuator/health/readiness
```

**Rollback.** No migration in the release: `git checkout <previous-tag>` and the same `up -d --build api`
(the old code runs on the unchanged schema). With a migration: Flyway refuses an older jar, so rollback is
a **whole-instance restore** to the moment just before the upgrade, which costs every tenant the trading
done since: [shared-instance-pitr.md](shared-instance-pitr.md) section 5, target time = the timestamp of the
safety backup. Note any one-way migration in the release notes before shipping.

## 7 - Day-to-day

| Task | Command |
|---|---|
| Status | `docker compose -f infra/shared/docker-compose.yml ps` |
| API log | `docker compose -f infra/shared/docker-compose.yml logs -f api` |
| psql | `docker compose -f infra/shared/docker-compose.yml exec db psql -U "$POSTGRES_USER" "$POSTGRES_DB"` |
| Restart API (logs nobody out: the key is stable) | `docker compose -f infra/shared/docker-compose.yml restart api` |
| Stop everything | `docker compose -f infra/shared/docker-compose.yml down` - **never `-v`**: that deletes the database and the backups |

Do not `docker compose down -v` this stack, ever. The database, the WAL archive and the base backups are
named volumes; all three are the customers' data.
