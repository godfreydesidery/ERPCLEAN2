# OrbixPOS — Flutter POS client

An external **Point-of-Sale till app** for the ERP. It owns no database and no
business logic; it runs a register entirely by calling the ERP REST API
(`/api/v1/...`). The **server is authoritative** for price, VAT, totals and cash
variance — everything the client shows in money is a *preview* until the ERP's
finalised `SalesInvoice` comes back.

- **Spec:** `docs/integration/pos/prd/` (the PRD) + the API reference
  (`docs/integration/pos/00`–`12`).
- **Visual design:** the `pos-ui-prototype/` OrbixPOS mock (indigo brand, green
  pay, slate ink, dense Excel-style register).
- **Targets:** Windows desktop (primary), Web, Android.

## What works today

Three register **verticals** sharing one payment / receipt / session spine —
only the register changes:

| Mode | Register |
|---|---|
| 🛒 **Supermarket** | 75% Excel-style line grid + 25% numpad; scanner-first add (exact code → barcode-lookup incl. embedded weight/price → search); inline qty/discount; void/remove columns; docked totals |
| 💊 **Pharmacy** | Rx/patient header (patient + prescriber + Rx#, carried on sale notes) + dispensing line table + side totals |
| 🍽 **Restaurant** | Floor/table picker + menu grid + order ticket + Send-to-kitchen (client-side) |

Shared flows: login + server setup, open shift (mode + ACTIVE-only till + float),
**multi-tender** checkout (cash / card / mobile money / cheque / split) with
change preview, **idempotent ring** (a durable client txn id is reused as the
`Idempotency-Key` **and** `X-Request-Id`, so an ambiguous attempt cannot
double-post), age-restriction gate, receipt from the finalised invoice with
reprint / gift / **whole-sale reverse**, and the session menu (X-read, cash
payout, today's sales reprint, close → variance, reconcile / Z-read, gated by
`POS.SESSION.RECONCILE`).

**Status:** supermarket vertical is complete and **live-verified end-to-end**
against a real backend (login → ring → idempotent replay → receipt → close →
reconcile). Pharmacy + restaurant are functional. Peripherals (printer / cash
drawer / scale) are stubbed behind the UI; the scanner is treated as a
keyboard-wedge. Offline queue-and-replay is not yet implemented (the resilient
idempotent ring is).

## Architecture

```
lib/
  core/        api client (Dio + bearer / X-Branch-Uid / X-Request-Id /
               Idempotency-Key, envelope unwrap, status-driven ApiException,
               single-flight token refresh + retry-on-401), money, config, store
  models/      DTOs (ids are JSON strings; money tolerant of string/number)
  services/    auth, context, catalogue, parties, till, session, sale
  state/       Riverpod Notifiers: app_controller (auth/context/shift),
               cart_controller, catalog_cache, providers
  features/    auth, shift, register/{supermarket,pharmacy,restaurant}, payment,
               receipt, session
  widgets/     OrbixPOS design-system kit
```

State is **Riverpod `Notifier`** (not the legacy `StateNotifier`). The token
store uses `shared_preferences` (toolchain-free; an OS keystore is the production
hardening path).

## Run it

1. **Configure the host.** On the login screen → *Server setup* → set the ERP
   host (default `http://localhost:8081`) and *Test connection*. `/api/v1` is
   added automatically.
2. **Run:** `flutter run -d windows` (or `-d chrome`, or an Android device).

### Local backend (for development)

```sh
docker compose up -d db                         # Postgres on :5434
cd backend && mvn -q -DskipTests spring-boot:run "-Dspring-boot.run.profiles=dev"
# → :8081, bootstraps rootadmin / RootPass12345, company C1, branch BR-01
```

Seed catalogue/parties/till (from repo root):

```sh
API_BASE=http://localhost:8081/api/v1 ROOT_USER=rootadmin ROOT_PASS=RootPass12345 \
  node e2e/full-coverage-drive.js
```

### Provision a cashier (required to ring sales)

A POS sale needs the **logged-in user to have an INTERNAL agent record linked to
their user id** (`BR-SALES-06`), and the **super-admin cannot be an agent**
(`BR-PARTY-10`). So sell as a real non-root cashier:

1. `POST /users` → `pos_cashier` / `Cashier12345`
2. `POST /user-branches` → assign branch BR-01 (`makeDefault: true`)
3. `POST /user-roles` → grant the seeded `ORG_ADMIN` role (scoped to the company)
4. `POST /agents` → `{ agentKind: "INTERNAL", appUserId: <cashier id> }`

## Test

```sh
flutter test test/widget_test.dart            # unit (money / enums)

# Live end-to-end against a running, seeded backend (skipped without the env var):
POS_LIVE_HOST=http://localhost:8081 POS_LIVE_USER=pos_cashier POS_LIVE_PASS=Cashier12345 \
  flutter test test/live_pos_smoke_test.dart
```

The live test drives the real service layer: login → numeric context resolution →
catalogue/units/prices → walk-in customer + agent → till → open session → ring
(server-priced, FINALISED) → idempotent replay (same invoice) → receipt → x-read
→ close → reconcile.

## Known API requirements / gaps

- **Cashier provisioning:** internal agent per user (above); a cashier without one
  gets a clear `BR-SALES-06` error at sale time.
- **branchId resolution:** there is no auth-only endpoint returning a branch's
  numeric id, so the client resolves it via `/branches?companyUid` (needs
  `BRANCH.VIEW`). A candidate backend follow-up is to add the numeric id to
  `/auth/my-branches`.
- **Windows build:** uses no native plugins that require the VS C++ ATL component
  (that's why the token store isn't `flutter_secure_storage`).

## Roadmap

Offline compose + queue-and-replay (SC-OFF), a local receipt journal for offline
reprint, real peripheral drivers, currency picker, and PLU/weighed-goods UI once
the matching backend ships.
