# 15 — FX / Multi-Currency Test Cases

Exhaustive test cases for the FX / Multi-currency domain: currency master, effective-dated
exchange-rate maintenance, foreign-currency document posting (base ledger in TZS), realized FX on
settlement, and the period-end **unrealized** FX revaluation run (preview → post → reverse).
Scope is grounded only in endpoints, permission codes, enums, routes, and SQL constraints that were
read directly from the codebase; backend-only-with-UI and embedded-in-another-screen behaviours are
called out explicitly.

## Modules / submodules covered

- **Currency master + exchange rates** — `CurrencyController` (`@RequestMapping("/api/v1/fx")`):
  - `GET /api/v1/fx/currencies` — list active currencies (global reference, no `company_id`)
  - `GET /api/v1/fx/currencies/uid/{uid}` — single currency
  - `POST /api/v1/fx/rates` — add a new effective-dated rate row (no edit-in-place)
  - `GET /api/v1/fx/rates?companyId=&page=&size=` — paginated rate history (newest-first)
  - `GET /api/v1/fx/rates/uid/{uid}` — single rate
  - Frontend route: `/admin/fx/rates` → `FxRateListComponent`
    (`web/src/app/features/admin/fx/fx-rate-list.component.ts`), service `FxService`
    (`web/src/app/features/admin/fx/fx.service.ts`), nav item "Exchange Rates"
    (`shell.component.ts`, `permission: 'CURRENCY.VIEW'`).
- **FX revaluation run** — `FxRevaluationRunController`
  (`@RequestMapping("/api/v1/fx/revaluation-runs")`):
  - `GET /api/v1/fx/revaluation-runs/preview?companyId=&fiscalPeriodUid=&spotRateDate=` — dry-run, no GL
  - `POST /api/v1/fx/revaluation-runs` — post run (201 CREATED); persists run + GL journal + schedules reversal
  - `POST /api/v1/fx/revaluation-runs/uid/{uid}/reverse?reversalDate=` — explicit reversal
  - `GET /api/v1/fx/revaluation-runs?companyId=` — paginated run list
  - `GET /api/v1/fx/revaluation-runs/uid/{uid}` — single run (with lines)
  - Frontend route: `/admin/fx/revaluation-runs` → `FxRevaluationListComponent`
    (`web/src/app/features/admin/fx/fx-revaluation-list.component.ts`), nav item "Revaluation Runs"
    (`shell.component.ts`, `permission: 'FX.EXPOSURE.VIEW'`).
- **Cross-cutting conversion engine (no dedicated UI)** — `CurrencyConversionService` /
  `FxDocumentConverter` (`backend/src/main/java/com/erp/platform/common/money/`). This is the single
  foreign→base conversion chokepoint consumed by AR/AP/sales posting. There is **no FX screen** for
  conversion; it is exercised indirectly when posting a foreign-currency sales invoice / supplier bill.
- **Realized FX on settlement (embedded, no dedicated UI)** — computed inside
  `ArReceiptServiceImpl`, `ApPaymentServiceImpl`, `ArCreditNoteServiceImpl` (verified to reference
  `REALIZED_FX_*` / `UNREALIZED_FX_*` GL config). Realized FX gain/loss is posted when a foreign open
  item is settled; it surfaces only in GL (journal entries), not on an FX screen.

## Permission codes in scope (exact `@PreAuthorize` codes)

- `CURRENCY.VIEW` — read currencies and rates (gates `GET /fx/currencies*`, `GET /fx/rates*`).
- `CURRENCY.MANAGE` — add rate rows (gates `POST /fx/rates`).
- `FX.REVALUE` — preview, post, reverse a revaluation run (gates `/preview`, `POST`, `/reverse`).
- `FX.EXPOSURE.VIEW` — list + read revaluation runs (gates `GET` list + `GET /uid/{uid}`).

(Seeded in `V77__fx_currencies_and_rates.sql` and `V80__fx_revaluation_runs.sql`; all four granted to
`ORG_ADMIN`.)

## Enum / status values in scope (verified)

- `FxRevaluationRunStatus` = `PREVIEWED`, `POSTED`, `REVERSED`
  (`com.erp.modules.fx.domain.enums.FxRevaluationRunStatus`; DB CHECK
  `chk_fx_revaluation_run_status`).
- Rate `rateType` (DB CHECK `chk_currency_rate_type`) = `SPOT`, `CLOSING`, `AVERAGE`, `BUDGET`
  (service default `SPOT`; `rateOn` lookup uses `SPOT` only).
- Run line `source_type` (DB CHECK `chk_fx_revaluation_run_line_source`) = `AR`, `AP`, `CASH`.
- Currency `minor_units` (DB CHECK `chk_currency_minor_units`) = `0`, `2`, `3`
  (seeded: TZS=0; USD/EUR/KES/GBP=2). The `3` value is permitted by the CHECK but **no `minor_units=3`
  currency is seeded** (e.g. BHD would be 3 if added — see TC-FX-040 edge).
- Currency `status` (DB CHECK `chk_currency_status`) = `ACTIVE`, `INACTIVE`.
- Seeded currencies (`V77`): **TZS** (base), **USD**, **EUR**, **KES**, **GBP**.

> **Note on the brief's `RevaluationDirection`:** there is **no** `RevaluationDirection` enum in the FX
> module. That enum exists only in `fixedassets` (asset revaluation, out of scope). The FX run's
> direction is expressed by the **sign of `adjustmentAmount`** (`+` gain, `−` loss) and split into
> `totalGainAmount` / `totalLossAmount` / `netAdjustmentAmount`.

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User roles (allowed) | `rootadmin` (superuser bypass), `ORG_ADMIN` (has all four FX perms), `ACCOUNTANT` (verify each perm individually — see note) |
| User roles (denied) | NO-PERMISSION user; CUSTOM role with `CURRENCY.VIEW` only (read but not manage); CUSTOM role with `FX.EXPOSURE.VIEW` only (view runs but not revalue) |
| Currency master | base = TZS (minor_units 0); foreign = USD/EUR (minor_units 2); INACTIVE currency; unknown 3-letter code |
| Rate direction | `from` = foreign (USD), `to` = base (TZS) only (v1 rule: `to` must equal company base) |
| `rateType` | SPOT (default + lookup-relevant), CLOSING/AVERAGE/BUDGET (stored, not used by `rateOn`) |
| Document/source type in run | AR-only run, AP-only run, mixed AR+AP run, zero-exposure (single-currency) run |
| Run lifecycle | PREVIEWED→POSTED→REVERSED; auto-reverse at post (next period open) vs deferred reversal (next period closed); illegal transitions |
| Company/branch context | single-company; multi-company tenant isolation; rate scoped per company; user acting in a company they are NOT assigned to (denied) |
| Money / dates | money as string formatted `CUR 1,234.56`; dates ISO `yyyy-MM-dd`; spot-rate-date defaulting to period end |

> **Seeding role permissions:** only `ORG_ADMIN` is granted the FX perms by migration. To exercise a
> single-permission positive/negative split (e.g. `FX.EXPOSURE.VIEW` without `FX.REVALUE`), seed a
> CUSTOM role with exactly that permission subset via IAM and assign it to a test user. `ACCOUNTANT`
> is **not** granted FX perms by seed — treat `ACCOUNTANT` as a denied role unless the perm is
> explicitly added to its role.

---

## TEST CASES

### TC-FX-001 — List active currencies (global reference) renders seeded set
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Currency master (`/admin/fx/rates` · `GET /api/v1/fx/currencies`)
- **Permission / Role:** `CURRENCY.VIEW` — runs as `ORG_ADMIN`; also as NO-PERMISSION user → expect forbidden / nav hidden
- **Variation:** base TZS + foreign USD/EUR/KES/GBP
- **Preconditions / Seed:** `V77` seed currencies present (TZS, USD, EUR, KES, GBP)
- **Steps:**
  1. Log in as `ORG_ADMIN`; navigate to `/admin/fx/rates`.
  2. The from/to currency pickers are populated from `listCurrencies()`.
  3. Open the from-currency picker and read the option list.
- **Test Data:** none
- **Expected Result:** the picker lists TZS, USD, EUR, KES, GBP (active only); the API returns
  `ApiResponse<CurrencyDto[]>` with `code`, `name`, `symbol`, `minorUnits`, `active=true`, `status="ACTIVE"`.
- **Convention Assertions:** C2 envelope; C3 RBAC (NO-PERM user → 403 / hidden nav); C6 axe scan clean;
  C7 currencies are global (no company filter); C8 codes shown by human code/name, not uid.
- **Negative / Edge:** seeding an INACTIVE currency → it is **excluded** (service uses
  `findByActiveTrueOrderByCodeAsc`).

### TC-FX-002 — Get single currency by uid (uid only in URL, never on screen)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Currency master (`GET /api/v1/fx/currencies/uid/{uid}`)
- **Permission / Role:** `CURRENCY.VIEW` — `ORG_ADMIN`; NO-PERM → 403
- **Preconditions / Seed:** seeded USD currency
- **Steps:**
  1. Resolve the USD currency uid from the list response (machine id, not shown to user).
  2. `GET /api/v1/fx/currencies/uid/{uid}`.
- **Test Data:** USD uid (e.g. `CUR000002USD` form per V77 uid pattern)
- **Expected Result:** returns the USD `CurrencyDto`; 200 / envelope.
- **Convention Assertions:** C1 uid appears ONLY in URL path, never rendered in a table/label; C2 envelope.
- **Negative / Edge:** unknown uid → `NotFoundException` (404 / errors populated).

### TC-FX-003 — Add a new effective-dated SPOT rate (USD→TZS) succeeds
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Exchange rates (`/admin/fx/rates` · `POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.MANAGE` — runs as `ORG_ADMIN`; also as CUSTOM role with `CURRENCY.VIEW` only → "New rate" hidden + API 403
- **Variation:** from=USD (foreign), to=TZS (base), rateType=SPOT
- **Preconditions / Seed:** company with `baseCurrency = TZS`; USD + TZS active
- **Steps:**
  1. As `ORG_ADMIN`, go to `/admin/fx/rates`; select the company (company chosen from the company picker by name).
  2. Click "New rate"; choose **from = USD** and **to = TZS** in the pickers (by code/name).
  3. Enter rate `2500.00000000`, effective date `2026-01-01`, rateType `SPOT`, source `manual`.
  4. Submit.
- **Test Data:** `{ fromCurrency: "USD", toCurrency: "TZS", rate: "2500", effectiveDate: "2026-01-01", rateType: "SPOT", source: "manual" }`
- **Expected Result:** success toast "Rate added — USD/TZS @ 2500…"; new row appears at the **top**
  of the newest-first list; persisted `CurrencyRate` with `rate` scale 8, `active=true`. HTTP 200, envelope.
- **Convention Assertions:** C1 currencies chosen via picker by code, no uid typed; C2 envelope; C3 RBAC
  (view-only role: button hidden, direct POST → 403); C8 date ISO `yyyy-MM-dd`, rate displayed to 6 dp
  by `fmtRate`; C9 append-only (no edit-in-place — a correction is a new row).
- **Negative / Edge:** covered in TC-FX-004..009.

### TC-FX-004 — Add rate rejected when `to_currency` ≠ company base (v1 rule)
- **Type:** Automated (Playwright) + Manual
- **Priority:** P1
- **Module / Submodule:** Exchange rates (`POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.MANAGE` — `ORG_ADMIN`
- **Variation:** from=EUR, to=USD (neither is base TZS)
- **Preconditions / Seed:** company base = TZS
- **Steps:**
  1. Attempt to add a rate with from=EUR, to=USD.
- **Test Data:** `{ fromCurrency:"EUR", toCurrency:"USD", rate:"1.1", effectiveDate:"2026-01-01" }`
- **Expected Result:** rejected with message `to_currency 'USD' must equal company base currency 'TZS' in v1.`
  (HTTP 400 / `errors[0]`). No row created.
- **Convention Assertions:** C2 envelope `errors`; C8 message surfaced verbatim in form error.
- **Negative / Edge:** boundary — to=TZS (base) accepted; this is the success path (TC-FX-003).

### TC-FX-005 — Add rate rejected for unknown / inactive currency code
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Exchange rates (`POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.MANAGE` — `ORG_ADMIN`
- **Preconditions / Seed:** seed an INACTIVE currency (e.g. set GBP `active=false`); base TZS
- **Steps:**
  1. Add rate from=`ZZZ` (unknown) to=TZS → expect `Unknown from-currency: ZZZ`.
  2. Add rate from=`GBP` (inactive) to=TZS → expect `Currency is not active: GBP`.
- **Test Data:** as above; rate `1000`, effectiveDate `2026-01-01`
- **Expected Result:** both rejected (HTTP 400 / `errors[0]`); no rate row created.
- **Convention Assertions:** C2 envelope errors.
- **Negative / Edge:** to-currency inactive likewise rejected (`Currency is not active`).

### TC-FX-006 — Add rate validation: rate must be > 0, codes must be 3 chars
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Exchange rates (`POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.MANAGE` — `ORG_ADMIN`
- **Preconditions / Seed:** base TZS, USD active
- **Steps:**
  1. Client-side: enter from=`US` (2 chars) → form error "From-currency must be a 3-letter ISO code."
  2. Client-side: from=USD, to=USD (equal) → "From and To currencies must differ."
  3. Client-side: rate `0` → "Rate must be a positive number."
  4. Bypass client (direct API): rate `-5` → `@Positive` bean-validation 400; rate `0` → service `Rate must be greater than zero.`
- **Test Data:** boundary rates `0`, `-5`, `0.00000001`
- **Expected Result:** invalid inputs blocked at form; server enforces `@Positive` + `@Size(min=3,max=3)`
  + service double-check. Smallest positive (`0.00000001`) accepted (scale 8).
- **Convention Assertions:** C2 envelope; required-field/boundary validation.
- **Negative / Edge:** missing `effectiveDate` → "Effective date is required." (client) / `@NotNull` (server).

### TC-FX-007 — Correction is a NEW effective-dated row, not an edit (append-only)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Exchange rates (`POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.MANAGE` — `ORG_ADMIN`
- **Variation:** two rows, same (company, USD, TZS, effectiveDate, SPOT) **must collide**; different effectiveDate must coexist
- **Preconditions / Seed:** existing USD→TZS @2500 effective 2026-01-01
- **Steps:**
  1. Add USD→TZS @2550 effective `2026-02-01` (a later correction).
  2. Confirm both rows exist; the screen shows no "edit" affordance on existing rows.
  3. Attempt to add USD→TZS @2600 effective `2026-01-01` SPOT again (same composite key).
- **Test Data:** rates 2500/2550/2600
- **Expected Result:** step 1 succeeds (new row); step 3 violates
  `uq_currency_rate (company_id, from_currency, to_currency, effective_date, rate_type)` → DB
  unique-constraint error surfaced as a 4xx; no in-place mutation ever occurs (columns are
  `updatable=false`).
- **Convention Assertions:** C9 append-only / no edit-in-place; C5 list newest-first; C2 envelope.
- **Negative / Edge:** same date but different `rate_type` (e.g. CLOSING) is allowed (key differs).

### TC-FX-008 — Rate list is paginated newest-first with full paginator
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Exchange rates (`/admin/fx/rates` · `GET /api/v1/fx/rates?companyId=&page=&size=`)
- **Permission / Role:** `CURRENCY.VIEW` — `ORG_ADMIN`
- **Preconditions / Seed:** ≥ 51 rate rows for the company (page size default 50) across multiple dates
- **Steps:**
  1. Navigate to `/admin/fx/rates`; default page 0 shows 50 rows ordered effectiveDate DESC, id DESC.
  2. Click NEXT, page-number, LAST, PREVIOUS, FIRST on `<app-paginator>`.
- **Test Data:** 51 rows
- **Expected Result:** ordering newest-first; paginator exposes FIRST/PREVIOUS/page-numbers/NEXT/LAST;
  `meta` carries `{page,size,totalElements,totalPages,hasNext}`. With ≤ 1 page the paginator self-hides.
- **Convention Assertions:** C2 envelope `meta`; C5 paginator complete + self-hide; C4 four states.
- **Negative / Edge:** company with zero rates → empty state shown distinctly (not error).

### TC-FX-009 — Rate list four-state handling (loading / empty / error / forbidden)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Exchange rates (`/admin/fx/rates`)
- **Permission / Role:** `CURRENCY.VIEW` — `ORG_ADMIN` (states) + NO-PERM user (forbidden)
- **Preconditions / Seed:** a company with 0 rates; a way to force a 5xx (or block the request)
- **Steps:**
  1. Observe the loading state while the list request is in flight.
  2. Select the zero-rate company → empty state.
  3. Force a server error → error state.
  4. As NO-PERM user (or one whose token lacks `CURRENCY.VIEW`) → the component maps 403 to `forbidden`.
- **Expected Result:** each of loading/empty/error/forbidden renders a distinct UI; the component sets
  `state` to `'forbidden'` only on HTTP 403.
- **Convention Assertions:** C4 four states distinct; C3 RBAC 403 → forbidden; C6 axe scan clean.
- **Negative / Edge:** 403 vs 500 differentiated (only 403 → forbidden, else error).

### TC-FX-010 — Get single rate by uid is company-scoped
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Exchange rates (`GET /api/v1/fx/rates/uid/{uid}`)
- **Permission / Role:** `CURRENCY.VIEW` — `ORG_ADMIN` of company A; also as a user of company B (cross-tenant)
- **Variation:** rate belongs to company A
- **Preconditions / Seed:** a USD→TZS rate row in company A
- **Steps:**
  1. As company-A user, `GET /fx/rates/uid/{uidA}` → 200.
  2. As company-B user (not assigned to A), `GET /fx/rates/uid/{uidA}` → denied by `assertCanActIn`.
- **Expected Result:** company-A user succeeds; company-B user gets 403 (scope guard).
- **Convention Assertions:** C1 uid only in URL; C7 tenant isolation enforced on read.
- **Negative / Edge:** unknown uid → `NotFoundException`.

### TC-FX-011 — Rate write endpoint forbidden for view-only role (RBAC split)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Exchange rates (`POST /api/v1/fx/rates` vs `GET /fx/rates`)
- **Permission / Role:** CUSTOM role with `CURRENCY.VIEW` only — can list; `POST /fx/rates` → 403
- **Preconditions / Seed:** CUSTOM role = {`CURRENCY.VIEW`}; user assigned to it
- **Steps:**
  1. As the view-only user, open `/admin/fx/rates` — list loads; the "New rate" button is hidden
     (`canManage` is false).
  2. Issue a direct `POST /api/v1/fx/rates` → 403.
- **Expected Result:** read allowed, write denied at both UI (button hidden) and API (403).
- **Convention Assertions:** C3 RBAC per-permission gating; button visibility tied to `CURRENCY.MANAGE`.
- **Negative / Edge:** user with neither perm → list itself 403 → forbidden state.

### TC-FX-012 — Rate `companyId` scope is enforced even though endpoint gate is `@perm.has`
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Exchange rates (`POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.MANAGE` user assigned ONLY to company A
- **Variation:** request body carries `companyId` = company B
- **Preconditions / Seed:** user holds `CURRENCY.MANAGE` but is assigned to company A only
- **Steps:**
  1. POST a rate with `companyId` = B (a company the user cannot act in).
- **Expected Result:** denied — the controller gate is `@perm.has('CURRENCY.MANAGE')` (deliberately not
  `@perm.scoped`, see controller comment), but `FxRateServiceImpl.addRate` calls
  `scopeGuard.assertCanActIn(req.companyId())` → 403. Confirms the documented numeric-id scope check is
  the real guard.
- **Convention Assertions:** C7 cross-company write blocked at the service layer.
- **Negative / Edge:** same user with `companyId` = A → succeeds.

### TC-FX-013 — Effective-dated rate lookup (`rateOn`) picks newest ≤ asOf, SPOT only
- **Type:** Manual (backend behaviour; surfaced via document posting / revaluation)
- **Priority:** P1
- **Module / Submodule:** Conversion engine — `FxRateServiceImpl.rateOn` (no dedicated UI)
- **Permission / Role:** invoked under the caller's permission (e.g. `FX.REVALUE` during a run)
- **Variation:** two SPOT rows (2026-01-01 @2500, 2026-02-01 @2550); a CLOSING row on 2026-02-15
- **Preconditions / Seed:** rates as above for USD→TZS
- **Steps:**
  1. Resolve rate as-of `2026-01-15` → expects 2500 (newest SPOT ≤ date).
  2. Resolve as-of `2026-02-20` → expects 2550 (CLOSING row ignored; only SPOT used).
  3. Resolve as-of `2025-12-31` (before any rate) → `FxRateNotFoundException`.
- **Expected Result:** lookup honours effective-dated newest-≤-asOf and `rateType='SPOT'` filter; missing
  rate throws `FxRateNotFoundException`.
- **Convention Assertions:** money/rate direction `base = face × rate`; C8.
- **Negative / Edge:** CLOSING/AVERAGE/BUDGET rows never satisfy a SPOT lookup.

### TC-FX-014 — Foreign-currency document posts base ledger in TZS via conversion chokepoint
- **Type:** Both (UI to create a foreign invoice; assert GL in base)
- **Priority:** P1
- **Module / Submodule:** Conversion engine — `FxDocumentConverter` / `CurrencyConversionService`
  (embedded in sales/AR posting; **no dedicated FX screen**)
- **Permission / Role:** sales/AR posting permission (out of FX scope) to create the document; assert GL as `GL.*` viewer
- **Variation:** invoice currency USD; base TZS; USD→TZS @2500
- **Preconditions / Seed:** USD→TZS SPOT rate effective on/before invoice date
- **Steps:**
  1. Create/post a foreign-currency sales invoice for USD 100 (via the sales module UI).
  2. Inspect the resulting GL journal.
- **Test Data:** face USD 100, rate 2500
- **Expected Result:** the GL journal is in **base TZS**: receivable leg = round(100 × 2500, base minor
  units) = TZS 250,000; entry balances (Σdebit==Σcredit). The control leg is the balancing plug
  absorbing any rounding residual (D-3).
- **Convention Assertions:** C8 base money string `TZS 250,000`; rate direction `base = face × rate`;
  postings append-only.
- **Negative / Edge:** invoice currency == base (TZS) → identity short-circuit, rate=1, no rate-table
  lookup, byte-identical to single-currency path; no rate row required.

### TC-FX-015 — Foreign document posting fails loudly when no rate resolves
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Conversion engine (embedded)
- **Permission / Role:** sales/AR posting permission
- **Variation:** EUR invoice but no EUR→TZS rate seeded
- **Preconditions / Seed:** no EUR rate row
- **Steps:**
  1. Attempt to post a EUR-denominated document.
- **Expected Result:** `FxRateNotFoundException` (foreign with no resolvable rate); document not posted.
- **Convention Assertions:** C2 envelope errors.
- **Negative / Edge:** identity case (base currency) never triggers the lookup.

### TC-FX-016 — Realized FX on AR receipt settlement (embedded, GL-only)
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Realized FX — `ArReceiptServiceImpl` (no FX screen; surfaces in GL)
- **Permission / Role:** AR receipt permission; assert via GL viewer
- **Variation:** foreign open item invoiced at USD→TZS @2500, settled when rate @2600 (gain) and @2400 (loss)
- **Preconditions / Seed:** posted foreign AR open item (USD) with stamped invoice fxRate/baseOriginalAmount;
  GL config `REALIZED_FX_GAIN` / `REALIZED_FX_LOSS` set
- **Steps:**
  1. Receipt the USD open item in full when the settlement-date rate is **2600** → realized **gain**.
  2. (Separate invoice) settle when rate is **2400** → realized **loss**.
- **Test Data:** face USD 100; invoice rate 2500; settlement rates 2600 / 2400
- **Expected Result:** AR is relieved at the **original invoice base** (100 × 2500 = TZS 250,000); the
  base/foreign difference at settlement posts to **REALIZED_FX_GAIN** (settle@2600: +TZS 10,000) or
  **REALIZED_FX_LOSS** (settle@2400: −TZS 10,000). Journal balances.
- **Convention Assertions:** C8 base money; C9 postings append-only (no edit); rate direction.
- **Negative / Edge:** base-currency (TZS) receipt → no realized FX leg.

### TC-FX-017 — Realized FX on AP payment settlement (embedded, GL-only)
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Realized FX — `ApPaymentServiceImpl` (no FX screen)
- **Permission / Role:** AP payment permission; assert via GL viewer
- **Variation:** foreign supplier bill USD→TZS @2500; pay-single at @2600 / @2400
- **Preconditions / Seed:** posted foreign AP bill (USD); `REALIZED_FX_*` GL config set
- **Steps:**
  1. Pay the USD bill in full (paySingle) when rate is **2600** → liability rose ⇒ realized **loss**.
  2. (Separate bill) pay when rate **2400** → liability fell ⇒ realized **gain**.
- **Test Data:** face USD 100; bill rate 2500; settlement rates 2600/2400
- **Expected Result:** AP relieved at original base (TZS 250,000); difference posts to realized FX
  (pay@2600 ⇒ loss TZS 10,000; pay@2400 ⇒ gain TZS 10,000). Journal balances.
- **Convention Assertions:** C8; C9 append-only.
- **Negative / Edge:** **paymentRun across MIXED currencies** is a known sensitive path (per FX recon
  findings: non-first-currency bills can be valued at the wrong settlement rate). Add an explicit case
  driving `paymentRun` over two different foreign currencies and assert the cash base value + realized-FX
  plug per bill are economically correct (regression guard).

### TC-FX-018 — Realized FX on AR credit note against a foreign open item (embedded)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Realized FX — `ArCreditNoteServiceImpl` (no FX screen)
- **Permission / Role:** AR credit-note permission; assert via GL viewer
- **Variation:** credit note applied to a foreign USD open item at a different rate
- **Preconditions / Seed:** foreign AR open item; credit-note rate seeded
- **Steps:**
  1. Issue an AR credit note that relieves part of the foreign open item.
- **Expected Result:** the credit note converts at document currency (not base-as-face), relieves
  `base_outstanding` correctly, and books any realized FX difference. (Regression guard for the recon
  finding that the CN previously posted foreign-face as base.)
- **Convention Assertions:** C8; rate direction; C9.
- **Negative / Edge:** base-currency CN → no FX leg.

---

### Revaluation run — preview

### TC-FX-019 — Preview revaluation: AR-only gain run (dry run, no GL)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`/admin/fx/revaluation-runs` · `GET /preview`)
- **Permission / Role:** `FX.REVALUE` — runs as `ORG_ADMIN`; also as CUSTOM role with `FX.EXPOSURE.VIEW` only → Preview action 403
- **Variation:** one open foreign AR invoice (USD), period-end spot > invoice rate ⇒ gain
- **Preconditions / Seed:** OPEN foreign AR invoice USD 100, carrying base TZS 250,000 (rate 2500);
  USD→TZS SPOT @2600 effective on the period end date; an OPEN `FiscalPeriod`
- **Steps:**
  1. As `ORG_ADMIN`, go to `/admin/fx/revaluation-runs`; select the company (by name).
  2. Click "Preview"; choose the fiscal period via the period picker (by period no / dates); leave
     spot-rate-date blank (defaults to period end).
  3. Run preview.
- **Test Data:** USD 100; carrying TZS 250,000; spot 2600
- **Expected Result:** preview shows one AR/USD line: outstandingTxn 100, carryingBase 250,000,
  spotRate 2600, revaluedBase 260,000, adjustment **+10,000**; header `totalGain=10,000`,
  `totalLoss=0`, `netAdjustment=+10,000`. **No run row is created** (dry run); the run list is unchanged.
- **Convention Assertions:** C1 period chosen via picker (no uid typed); C2 envelope
  (`ApiResponse<FxRevaluationPreviewDto>`); C3 RBAC (`FX.EXPOSURE.VIEW`-only → Preview button gated by
  `canRevalue`, API 403); C8 amounts `TZS 10,000.00`, dates ISO.
- **Negative / Edge:** missing period selection → client "Select a fiscal period."

### TC-FX-020 — Preview revaluation: AR-only loss run (spot < invoice rate)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`GET /preview`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** open USD AR invoice; period-end spot 2400 (< 2500) ⇒ loss
- **Preconditions / Seed:** USD 100 AR open item, carrying TZS 250,000; SPOT @2400
- **Steps:** preview as in TC-FX-019.
- **Test Data:** spot 2400
- **Expected Result:** AR/USD line adjustment **−10,000**; header `totalGain=0`, `totalLoss=10,000`,
  `netAdjustment=−10,000`. Loss styled distinctly (`deltaClass` → danger).
- **Convention Assertions:** C8 negative formatting; C2 envelope.
- **Negative / Edge:** spot == invoice rate ⇒ adjustment 0, net 0 (see TC-FX-027 no-GL behaviour on post).

### TC-FX-021 — Preview revaluation: AP-only run (sign convention: liability fell ⇒ gain)
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`GET /preview`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** open foreign AP supplier bill USD; AP uses `carrying − revalued` sign so adj>0 = gain
- **Preconditions / Seed:** open foreign AP bill USD 100 carrying TZS 250,000; SPOT @2400 (gain) and a
  second scenario @2600 (loss)
- **Steps:**
  1. Preview with spot **2400** → AP/USD line: revaluedBase 240,000, adjustment = carrying−revalued =
     **+10,000** (gain — we owe less in base).
  2. Preview with spot **2600** → adjustment = **−10,000** (loss — liability rose).
- **Expected Result:** AP line uses the documented `carrying − revalued` convention; header gain/loss
  totals reflect the sign. (Regression guard for the AP-imbalance finding: the run journal must still
  balance per-line on post — see TC-FX-024.)
- **Convention Assertions:** C8; sign convention asserted.
- **Negative / Edge:** AP at spot==rate ⇒ adjustment 0.

### TC-FX-022 — Preview revaluation: MIXED AR + AP, multiple currencies
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`GET /preview`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** open USD AR (gain) + open EUR AP (loss) in the same period
- **Preconditions / Seed:** USD AR + EUR AP open items; SPOT for both currencies on period end
- **Steps:** preview the period.
- **Expected Result:** preview returns one line **per (sourceType, currency)** (grouped: AR/USD, AP/EUR);
  header totals aggregate `totalGain`, `totalLoss`, `netAdjustment` across all lines.
- **Convention Assertions:** C2 envelope; grouping by currency verified.
- **Negative / Edge:** this mixed case is the exact scenario the per-line FX-complement poster must
  balance — pair with TC-FX-024 (post) to assert balanced GL.

### TC-FX-023 — Preview with zero foreign exposure (single-currency company) returns empty lines
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Revaluation run (`GET /preview`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** all open items in base TZS only
- **Preconditions / Seed:** only base-currency open items
- **Steps:** preview the period.
- **Expected Result:** `lines=[]`, all totals 0; the preview panel shows a zero/empty result. (D-8: a
  single-currency company behaves identically to pre-FX.)
- **Convention Assertions:** C4 empty rendered distinctly; C2 envelope.
- **Negative / Edge:** posting this (TC-FX-027) creates a run with **no GL entry**.

### TC-FX-024 — Post revaluation run posts a BALANCED base journal + schedules reversal (next period open)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`/admin/fx/revaluation-runs` · `POST /api/v1/fx/revaluation-runs`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`; also as `FX.EXPOSURE.VIEW`-only → Post 403
- **Variation:** mixed AR+AP run (from TC-FX-022); **next fiscal period is OPEN**
- **Preconditions / Seed:** open foreign AR + AP; GL config `UNREALIZED_FX_GAIN` / `UNREALIZED_FX_LOSS`
  + `ACCOUNTS_RECEIVABLE` / `ACCOUNTS_PAYABLE` set; current period + next period both OPEN
- **Steps:**
  1. Preview (TC-FX-022), then enter posting date and click "Post".
- **Test Data:** posting date = period end; net adjustment ≠ 0
- **Expected Result:** HTTP **201 CREATED**; a run row with `runNumber` (FXR-NNNNN), status... → because
  the next period is open the auto-reversal fires immediately, so final status = **REVERSED** with both
  `glEntryUid` and `reversalGlEntryUid` populated. The GL journal is **balanced** (per-line FX-complement:
  each control+FX pair self-balances; Σdebit==Σcredit asserted before posting). Success toast with run number; list refreshes.
- **Convention Assertions:** C1 period via picker; C2 envelope (201); C3 RBAC (view-only → 403); C8 totals
  as base money; C9 reversal = new entry, never an edit.
- **Negative / Edge:** if GL is unbalanced before posting (programming regression) the service throws
  `… journal is unbalanced before posting …` (fail-loud) — assert no partial run is committed.

### TC-FX-025 — Post revaluation: reversal DEFERRED when next period not yet open (status stays POSTED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`POST` + later `/reverse`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** next fiscal period is NOT open at post time
- **Preconditions / Seed:** open foreign exposure; next period closed/not created
- **Steps:**
  1. Post the run for the current period.
- **Expected Result:** HTTP 201; status = **POSTED**, `glEntryUid` set, `reversalGlEntryUid = null`
  (OQ-FX-04 "record intent + post on open"). The safe-invoker (`postReversalInNewTx`) returns null and
  must NOT poison the outer transaction — the run is committed.
- **Convention Assertions:** C2 envelope; C9.
- **Negative / Edge:** the run row now shows a "Reverse" affordance (TC-FX-029) so reversal can be
  triggered once the next period opens.

### TC-FX-026 — Post revaluation idempotency: one run per (company, fiscal_period)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`POST`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** post the same (company, period) twice
- **Preconditions / Seed:** a posted run already exists for the period
- **Steps:**
  1. Post the run for period P (succeeds).
  2. Post again for the same period P.
- **Expected Result:** the second post is a **true idempotent no-op** — returns the existing run DTO
  (status POSTED/REVERSED), does NOT create a second run or a second GL entry. Backed by
  `uq_fx_revaluation_run_company_period` and the service's existing-run short-circuit.
- **Convention Assertions:** C2 envelope; C9 no duplicate postings.
- **Negative / Edge:** a stale `PREVIEWED` slot for the same (company, period) is reused (lines+header
  deleted, then re-inserted cleanly) rather than colliding on the unique constraint.

### TC-FX-027 — Post revaluation with zero net adjustment posts NO GL entry
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Revaluation run (`POST`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** no foreign exposure, OR exposure with net adjustment exactly 0
- **Preconditions / Seed:** single-currency company (TC-FX-023) OR offsetting lines netting to 0
- **Steps:** post the run.
- **Expected Result:** run is created with totals 0 and **no GL journal** (`glEntryUid = null`); no
  reversal needed. Identical to pre-FX behaviour for single-currency.
- **Convention Assertions:** C2 envelope; C9.
- **Negative / Edge:** distinguish empty-exposure (no lines) from net-zero (offsetting lines) — both
  produce no GL entry.

### TC-FX-028 — Post revaluation fails (no partial commit) when FX GL config missing
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`POST`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** `UNREALIZED_FX_GAIN`/`UNREALIZED_FX_LOSS` not configured but net adjustment ≠ 0
- **Preconditions / Seed:** open foreign exposure; FX gain/loss accounts NOT in `gl_config`
- **Steps:** attempt to post.
- **Expected Result:** `IllegalStateException` "FX revaluation GL post failed … GL config missing or
  period closed. Run was NOT committed." — the orphan run header + lines are deleted so a retry after
  fixing config re-inserts cleanly. No run row persists.
- **Convention Assertions:** C2 envelope errors; C9 no half-written run.
- **Negative / Edge:** closed posting period produces the same not-committed outcome.

### TC-FX-029 — Explicit reverse of a POSTED run (next period now open)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`/admin/fx/revaluation-runs` · `POST /uid/{uid}/reverse?reversalDate=`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`; also as `FX.EXPOSURE.VIEW`-only → Reverse 403
- **Variation:** a run in status POSTED with `reversalGlEntryUid = null` (deferred from TC-FX-025)
- **Preconditions / Seed:** a POSTED run; next period now open
- **Steps:**
  1. In the run list, expand the POSTED run; click "Reverse"; enter reversal date (e.g. first day of next period).
  2. Confirm.
- **Test Data:** reversalDate `2026-04-01`
- **Expected Result:** posts the reversal journal; status → **REVERSED**, `reversalGlEntryUid` set; the
  list row updates in place; success toast.
- **Convention Assertions:** C1 reversal date ISO; C2 envelope; C3 RBAC; C9 reversal is a new GL entry.
- **Negative / Edge:** reversal date required (client "Reversal date is required.").

### TC-FX-030 — Reverse illegal-transition guards (PREVIEWED, already-REVERSED, no GL entry)
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`POST /uid/{uid}/reverse`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** illegal lifecycle transitions
- **Preconditions / Seed:** runs in each state
- **Steps / Expected Result:**
  1. Reverse a **PREVIEWED** run → `IllegalStateException` "Run … has not been posted yet." (rejected).
  2. Reverse an **already-REVERSED** run → idempotent no-op, returns the run unchanged.
  3. Reverse a POSTED run whose `glEntryUid == null` → `IllegalStateException` "… has no GL entry to
     reverse …".
  4. Reverse when the GL entry already has a reversal externally (`existsByReversalOfId`) → status set to
     REVERSED, no duplicate reversal posted.
- **Convention Assertions:** C2 envelope errors; C9 no double-reversal (idempotency via
  `existsByReversalOfId`).
- **Negative / Edge:** these are the explicit illegal-transition rejections for the lifecycle.

### TC-FX-031 — Revaluation run list paginated + four-state + scoped
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Revaluation run (`/admin/fx/revaluation-runs` · `GET ?companyId=`)
- **Permission / Role:** `FX.EXPOSURE.VIEW` — `ORG_ADMIN`; NO-PERM / `FX.REVALUE`-only-without-view → forbidden
- **Preconditions / Seed:** ≥ 51 runs for the company (or seed enough to paginate); a zero-run company
- **Steps:**
  1. Navigate to `/admin/fx/revaluation-runs`; observe loading → list.
  2. Exercise paginator FIRST/PREVIOUS/numbers/NEXT/LAST.
  3. Select the zero-run company → empty state; force a 5xx → error state; lacking `FX.EXPOSURE.VIEW` → forbidden.
- **Expected Result:** list paginated with `meta`; four states distinct; data scoped to the selected
  company (`assertCanActIn`).
- **Convention Assertions:** C2 envelope `meta`; C4 four states; C5 paginator complete; C6 axe clean;
  C7 tenant scoping.
- **Negative / Edge:** ≤ 1 page → paginator self-hides.

### TC-FX-032 — Get single revaluation run by uid includes lines; uid never shown on screen
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Revaluation run (`GET /uid/{uid}`)
- **Permission / Role:** `FX.EXPOSURE.VIEW` — `ORG_ADMIN`; cross-tenant user → 403
- **Preconditions / Seed:** a posted run with ≥ 1 line in company A
- **Steps:**
  1. Expand the run row in the list (detail is rendered inline from the list payload).
  2. Confirm the detail shows runNumber, status badge, totals, and the per-currency lines (sourceType,
     currency, outstanding, carrying, spot, revalued, adjustment).
  3. As a company-B user, `GET /uid/{uidA}` → 403.
- **Expected Result:** detail includes `lines[]`; the run **uid is never displayed** (only the
  human-readable `runNumber` and currencies/period are shown); cross-tenant read denied.
- **Convention Assertions:** C1 uid not shown (runNumber is the human handle); C7 scoping; C2 envelope.
- **Negative / Edge:** unknown uid → `NotFoundException`.

### TC-FX-033 — Run number uniqueness + amounts non-negative (DB invariants)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Revaluation run (persistence invariants)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Preconditions / Seed:** posted runs
- **Steps / Expected Result:**
  1. `run_number` is unique per company (`uq_fx_revaluation_run_number`).
  2. `total_gain_amount >= 0 AND total_loss_amount >= 0` (`chk_fx_revaluation_run_amounts`) — gains and
     losses are stored as non-negative magnitudes; direction lives in the signed `net_adjustment_amount`
     and per-line `adjustment_amount`.
  3. Line `spot_rate > 0` (`chk_fx_revaluation_run_line_spot`); `source_type IN ('AR','AP','CASH')`.
- **Convention Assertions:** invariants enforced at DB level.
- **Negative / Edge:** attempting to persist a negative gain/loss magnitude violates the CHECK.

### TC-FX-034 — Nav visibility and route guards reflect FX permissions
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Both FX routes (`/admin/fx/rates`, `/admin/fx/revaluation-runs`)
- **Permission / Role:** matrix across roles
- **Preconditions / Seed:** users for each permission subset
- **Steps / Expected Result:**
  1. `CURRENCY.VIEW` present → "Exchange Rates" nav item visible; route `/admin/fx/rates` activates
     (`requirePermission('CURRENCY.VIEW')`).
  2. `FX.EXPOSURE.VIEW` present → "Revaluation Runs" nav item visible; route activates
     (`requirePermission('FX.EXPOSURE.VIEW')`).
  3. NO-PERMISSION user → neither nav item shown; deep-linking either route → guard blocks (forbidden/redirect).
- **Convention Assertions:** C3 RBAC nav + route guard; C4 forbidden state on deep-link.
- **Negative / Edge:** a user with `FX.REVALUE` but **not** `FX.EXPOSURE.VIEW` — the revaluation route
  is guarded by `FX.EXPOSURE.VIEW`, so the screen is not reachable via guard even though they could
  preview/post via API; assert the route guard denies and the nav item is hidden.

### TC-FX-035 — Multi-tenant isolation: company A cannot see/seed company B FX data
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** All FX endpoints (scoped: rates list/get, run list/get/preview/post)
- **Permission / Role:** `ORG_ADMIN` of company A vs company B
- **Variation:** rates + runs exist in both companies
- **Preconditions / Seed:** USD rate + a posted run in each of company A and company B
- **Steps:**
  1. As company-A admin, list rates / runs with `companyId = A` → only A's data.
  2. Request `companyId = B` (preview/list/post) → `assertCanActIn` denies (403).
  3. Currencies (global) are visible to both (no tenant filter) — assert this is the one intentional
     exception.
- **Expected Result:** rate rows, run rows, previews, and posts are all company-scoped; currencies are
  global reference data shared across tenants.
- **Convention Assertions:** C7 tenant isolation on every per-company endpoint; the currency master is
  the documented global exception (no `company_id`).
- **Negative / Edge:** a user assigned to a branch but acting against another company's `companyId` is denied.

### TC-FX-036 — rootadmin superuser bypass sees all FX data cross-tenant
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** All FX endpoints
- **Permission / Role:** `rootadmin` (bypasses permission + scope)
- **Preconditions / Seed:** rates + runs across multiple companies
- **Steps:** as `rootadmin`, list rates/runs for any company.
- **Expected Result:** rootadmin can read/act across all companies and holds all FX permissions
  implicitly (bypass). (Use rootadmin only for this positive sanity case — never for negative-auth tests.)
- **Convention Assertions:** C3 bypass behaviour confirmed.
- **Negative / Edge:** none (superuser).

### TC-FX-037 — Spot-rate-date override vs default (period end) on preview/post
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Revaluation run (`GET /preview`, `POST`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** spotRateDate provided vs omitted
- **Preconditions / Seed:** USD→TZS rates effective at two dates (period end @2600; an earlier date @2500)
- **Steps:**
  1. Preview leaving spot-rate-date blank → engine uses **period end date** for the rate lookup → 2600.
  2. Preview with spot-rate-date = the earlier date → uses 2500.
- **Expected Result:** the chosen spot date drives `rateOn`; preview header `spotRateDate` echoes the
  resolved date; the post persists `spot_rate_date` accordingly.
- **Convention Assertions:** C8 date ISO; rate-date selection asserted.
- **Negative / Edge:** spot-rate-date with no resolvable rate on/before it → `FxRateNotFoundException`
  during compute (preview/post fails).

### TC-FX-038 — Preview/post against a non-existent fiscal period is rejected
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Revaluation run (`GET /preview`, `POST`)
- **Permission / Role:** `FX.REVALUE` — `ORG_ADMIN`
- **Variation:** bogus `fiscalPeriodUid`
- **Preconditions / Seed:** none
- **Steps:** call preview/post with an unknown `fiscalPeriodUid`.
- **Expected Result:** `NotFoundException("FiscalPeriod", uid)` → 404 / envelope errors; no run created.
- **Convention Assertions:** C1 period chosen via picker in the UI (so this is an API-level negative);
  C2 envelope.
- **Negative / Edge:** missing required `companyId` / `fiscalPeriodUid` / `postingDate` → bean-validation 400.

### TC-FX-039 — Status badge + gain/loss colour styling reflect run state (UI)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Revaluation run list (`/admin/fx/revaluation-runs`)
- **Permission / Role:** `FX.EXPOSURE.VIEW` — `ORG_ADMIN`
- **Preconditions / Seed:** runs in PREVIEWED (if surfaced), POSTED, REVERSED; a gain run and a loss run
- **Steps:**
  1. Inspect status badges: PREVIEWED → warning, POSTED → success, REVERSED → secondary
     (`statusBadgeClass`).
  2. Inspect net-adjustment colour: positive → success, negative → danger (`netAdjClass`/`deltaClass`).
- **Expected Result:** badges + amount colours match the verified class mapping; amounts formatted to 2 dp
  (`fmtAmt`).
- **Convention Assertions:** C6 colour is not the only signal (text/state present); C8 amount formatting.
- **Negative / Edge:** net 0 → muted/no colour.

### TC-FX-040 — Currency minor-units drive base rounding (TZS=0, USD=2)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Conversion engine + revaluation compute (rounding)
- **Permission / Role:** `FX.REVALUE` (via run) — `ORG_ADMIN`
- **Variation:** base TZS (minor_units 0) vs a base with minor_units 2
- **Preconditions / Seed:** company base TZS; foreign USD with a rate producing a fractional base amount
- **Steps:**
  1. Compute revalued base for USD 100 × 2500.123 with base TZS → HALF_UP to **0** decimals.
  2. (Comparison) with a 2-minor-unit base currency → HALF_UP to **2** decimals.
- **Expected Result:** `revaluedBase` and `adjustment` are scaled to the **base** currency minor units
  resolved from the `currencies` master (`resolveBaseMinorUnits`), HALF_UP; default 2 if base not seeded.
- **Convention Assertions:** C8 rounding to currency minor units; rate scale 8 preserved on input.
- **Negative / Edge:** a 3-minor-unit base (e.g. BHD, if seeded) rounds to 3 dp.

---

## Coverage map (endpoint / lifecycle → cases)

| Target | Cases |
|---|---|
| `GET /fx/currencies` (+ INACTIVE exclusion) | TC-FX-001 |
| `GET /fx/currencies/uid/{uid}` | TC-FX-002 |
| `POST /fx/rates` (success) | TC-FX-003 |
| `POST /fx/rates` (validation: base rule, unknown/inactive, positivity/required, append-only dup) | TC-FX-004, 005, 006, 007 |
| `GET /fx/rates` (pagination, four-state, scope) | TC-FX-008, 009, 010 |
| `GET /fx/rates/uid/{uid}` | TC-FX-010 |
| Rate RBAC (view vs manage; service scope guard) | TC-FX-011, 012 |
| `rateOn` effective-dated + SPOT-only lookup | TC-FX-013 |
| Foreign document posting → base ledger / no-rate failure | TC-FX-014, 015 |
| Realized FX (AR receipt / AP payment / AR credit note; mixed paymentRun regression) | TC-FX-016, 017, 018 |
| `GET /preview` (AR gain, AR loss, AP, mixed, zero) | TC-FX-019, 020, 021, 022, 023 |
| `POST` (balanced post + auto-reverse / deferred / idempotent / zero-GL / fail-no-commit) | TC-FX-024, 025, 026, 027, 028 |
| `POST /uid/{uid}/reverse` (success + illegal transitions) | TC-FX-029, 030 |
| `GET` list runs (pagination/four-state/scope) | TC-FX-031 |
| `GET /uid/{uid}` run (lines, uid hidden, scope) | TC-FX-032 |
| DB invariants (run number, amounts, spot, source_type) | TC-FX-033 |
| Nav + route guards | TC-FX-034 |
| Multi-tenant isolation + rootadmin bypass | TC-FX-035, 036 |
| Spot-date override; bad period; required fields | TC-FX-037, 038 |
| UI status/colour formatting | TC-FX-039 |
| Minor-unit rounding | TC-FX-040 |

## Lifecycle transition coverage (`FxRevaluationRunStatus`)

| Transition | Legal? | Case |
|---|---|---|
| (none) → PREVIEWED (dry run, no persistence) | n/a (preview creates no row) | TC-FX-019..023 |
| → POSTED (post, next period closed) | legal | TC-FX-025 |
| → REVERSED (post auto-reverses, next period open) | legal | TC-FX-024 |
| POSTED → REVERSED (explicit reverse) | legal | TC-FX-029 |
| PREVIEWED → REVERSED (reverse before post) | ILLEGAL → rejected | TC-FX-030(1) |
| REVERSED → REVERSED (reverse again) | idempotent no-op | TC-FX-030(2) |
| POSTED(no GL) → REVERSED | ILLEGAL → rejected | TC-FX-030(3) |
| double-post same (company, period) | idempotent no-op | TC-FX-026 |
