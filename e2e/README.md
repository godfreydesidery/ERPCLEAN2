# ERPCLEAN2 — End-to-End / Browser Test Harness

Reusable scripts for **manual, on-demand** end-to-end verification against a running stack
(throwaway, local-dev, or QA). These are **not** part of the Maven/CI build — they're operator
tools for: smoke-checking the SPA in a real browser, and exercising the system at scale
(bulk seed + the purchase→stock→sale→route loop) with assertions.

> The authoritative automated tests live in `backend/src/test` (JUnit/ArchUnit/Testcontainers)
> and `web/src/**/*.spec.ts` (Vitest). This harness complements them with full-stack, real-HTTP,
> real-browser checks that unit tests can't give (e.g. it caught the outbox dispatcher TX bug that
> all 371 unit tests missed — see `docs/decisions` / git log `fix(outbox)`).

## Scripts

| File | What it does | Runtime |
|---|---|---|
| `static-proxy-server.js` | Serves the built Angular SPA on a port and proxies `/api/*` to the API. Lets a browser hit one origin (no CORS). | Node (no deps) |
| `ui-smoke.js` | Playwright: logs in, screenshots every key screen, creates a Route + opens its detail (assignment panels). Reports console errors + API 5xx. Fast sanity that the SPA renders + core flow works. | Node + `playwright-core` |
| `seed-and-flow.js` | Large-scale: rootadmin bootstraps → creates an operator role + 100 users (branch-assigned, role-granted) → a **non-root operator** bulk-creates 1000 customers / 50 suppliers / 50 products / 20 agents / 10 routes → runs PO→goods-receipt (stock in) and finalised sales (stock out) → **asserts** counts, stock math, invoice-number uniqueness. Records every failure to `issues.json` (never aborts). | Node (no deps) |
| `qa-ui-drive.js` | **100% typed UI data entry** (no API seeding): logs in, then types ~50 customers / 10 suppliers / 10 products / 5 users / 3 routes / 1 price list into the real forms in a headless browser. Per-record: opens a fresh form, fills, submits, and **waits for the form to close** (the app's success signal) before the next — so it never double-submits or re-creates. Logs issues + screenshots, continues on failure. Counts are env-tunable (`N_CUSTOMERS`, …). | Node + `playwright-core` |

## Prerequisites

- **Node** (ships with the dev box). `seed-and-flow.js` and `static-proxy-server.js` use only Node built-ins.
- For `ui-smoke.js`: `npm i playwright-core` in a scratch dir, and a Chromium under
  `%LOCALAPPDATA%/ms-playwright/chromium-*` (the script auto-discovers `chrome-win[64]/chrome.exe`).
- A **running API** (default `http://127.0.0.1:8088`) and, for UI scripts, a **web origin**
  (default `http://127.0.0.1:4173` via the proxy server).

## How to stand up a throwaway stack (the safe default)

```bash
# 1. Fresh Postgres on a non-default port (never touches dev/QA)
docker run -d --name erp-verify-db -e POSTGRES_USER=erp -e POSTGRES_PASSWORD=erp \
  -e POSTGRES_DB=erp -p 5435:5432 postgres:15

# 2. Build the SPA into the API jar (so one jar serves API + UI), then the fat jar
cd web && npm run build && cd ..
rm -rf backend/src/main/resources/static && mkdir -p backend/src/main/resources/static
cp -r web/dist/web/browser/* backend/src/main/resources/static/
cd backend && mvn -q -DskipTests -Dmaven.test.skip=true clean package spring-boot:repackage && cd ..

# 3. Start the API on 8088 against the throwaway DB, bootstrap on
SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8088 \
  SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5435/erp" \
  SPRING_DATASOURCE_USERNAME=erp SPRING_DATASOURCE_PASSWORD=erp \
  ERP_BOOTSTRAP_ENABLED=true ERP_BOOTSTRAP_ADMIN_PASSWORD=RootPass12345 \
  java -jar backend/target/erp-api-0.0.1-SNAPSHOT.jar

# 4. (UI scripts only) serve the SPA + proxy on 4173
node e2e/static-proxy-server.js web/dist/web/browser 4173
```

## Run

```bash
# UI smoke (browser) — needs the proxy server (step 4) up
node e2e/ui-smoke.js                 # screenshots → %TEMP%/erp-verify-shots

# 100% typed UI data entry against a live site (e.g. QA). Needs playwright-core on NODE_PATH.
#   (the scripts in e2e/ have no node_modules of their own — point NODE_PATH at a scratch
#    dir where you `npm i playwright-core`, e.g. %TEMP%/erp-qa-e2e/node_modules)
NODE_PATH=%TEMP%/erp-qa-e2e/node_modules WEB_BASE=http://16.170.11.41 ROOT_PASS=... \
  node e2e/qa-ui-drive.js             # screenshots+json → %TEMP%/erp-qa-shots

# Large-scale seed + flow + assertions (talks to the API directly on 8088)
node e2e/seed-and-flow.js            # prints a per-severity issue summary; writes issues.json
```

### Config (env vars; sensible defaults)

| Var | Default | Used by |
|---|---|---|
| `API_BASE` | `http://127.0.0.1:8088/api/v1` | seed-and-flow |
| `WEB_BASE` | `http://127.0.0.1:4173` | ui-smoke |
| `API_PORT` | `8088` | static-proxy-server (proxy target) |
| `ROOT_USER` / `ROOT_PASS` | `rootadmin` / `RootPass12345` | both |
| `SHOTS_DIR` | `%TEMP%/erp-verify-shots` | ui-smoke |

## Teardown

```bash
docker rm -f erp-verify-db
# stop the java + node processes (kill by port 8088 / 4173)
rm -rf backend/src/main/resources/static   # the build-injected SPA copy (gitignored)
```

## Notes / gotchas (learned the hard way)

- **Always run on the throwaway stack** (port 5435/8088/4173) unless you explicitly intend to load
  QA/dev. `seed-and-flow.js` generates thousands of rows.
- `mvn package` alone produced a **thin jar** once (`no main manifest attribute`); always include
  `spring-boot:repackage` to get the executable fat jar. Confirm with
  `unzip -p target/*.jar META-INF/MANIFEST.MF | grep Start-Class`.
- A **running API holds a lock on the jar** on Windows → `mvn package` fails at `repackage`
  ("Unable to rename ... .jar.original"). Stop the API before rebuilding.
- The API DEBUG log is **flooded by the outbox poller** every ~1s; filter it out when hunting a
  stack: `grep -v "scheduling-1\|hibernate.SQL"`.
- Parties (`/customers`, `/suppliers`, `/agents`) create DTOs take **`companyId` (Long)**, while the
  newer masters (`/products`, `/price-lists`, `/routes`, `/units`) take **`companyUid` (String)** —
  an un-retrofitted inconsistency (logged as a known issue). The scripts handle both.
- `GET /companies` requires `?organisationUid=` — without it you (now) get a clean **400**.
