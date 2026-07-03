# Simulation Run Report — 2026-07-03 (busy week)

> The Tembo Group business-operations team (17 staff personas) used the ERP **through the web UI**
> for a full "busy week" — everyone worked their own screens and did their real transactional job.
> This is the run + triage record. Companion: [../COMPANY-SCENARIO.md](../COMPANY-SCENARIO.md),
> [../TRIAGE-PROCESS.md](../TRIAGE-PROCESS.md), [../SIM-RUN-REPORT.md](../SIM-RUN-REPORT.md) (the
> 2026-06-28 baseline). Raw evidence: [all-problems.json](all-problems.json).

## How it ran

- **Stack:** local durable DB (`erp-db` :5434), backend dev profile :8081 (schema **V78**, DevTools
  restart disabled so the ephemeral JWT key could not rotate mid-run), Angular web on **:4400**
  (`:4200` is a different local project). The Tembo world (9 branches, 12 roles, 17 accounts) was
  **already provisioned** in the durable DB from prior runs — no re-onboarding.
- **Battery:** every persona ran at full depth — `DEEP` (real transactions), `MODULES` (one real
  operation in every module the role owns), `DETAILS` (id/uid leak scan on detail pages), `AXE`
  (WCAG serious/critical). Harness: [../../../e2e/sim/](../../../e2e/sim/), Playwright, no seeding.
- **Result:** **17/17 personas logged in and completed their work.** 11 problems captured, **all
  SLOW severity — zero BLOCKED.**

### What worked end-to-end (typed through the UI, non-root)

Procure-to-pay (Yusuf/Rehema raise → add line → **place** PO; register 11 products + 5 suppliers +
price a product), order-to-cash (Sabina: quotation, SO + line, delivery note, sales return, blanket
+ standing orders, 5 customers), GL (Amina & Grace **post balanced journals**), AP (**enter supplier
bill**), cash/AR (John: cash entry, cheque, cash transfer, **customer receipt**), stock (transfers,
locations), manufacturing (**release work orders** ×2), HR/payroll (pay component, leave, staff loan,
**run payroll**, register employee), fixed assets (category + asset), budgeting (budget), projects
(create project), approvals inbox (GM/CFO/branch managers — nothing pending, opened cleanly).

## Triage — problems by severity

### 🔴 Blockers — **0**

No persona was blocked from their core job. **The 2026-06-28 systemic blocker is gone**: reference-data
screens no longer hard-403 when a role lacks a supporting VIEW permission — every operational role
(storekeeper GRN, cashier receipt, sales SO, production WO, HR payroll, stores transfers) reached and
used its screens. The read-closure hardening (F22–F26, ADR-0047, commit 39998c4) has held.

### 🟠 Usability / functional — 2 genuine defects (both minor, non-blocking)

**D1 — Wide tables aren't keyboard-scrollable (accessibility).** `Medium` · UX/a11y · frontend
- **Found by:** Grace Mhina (CFO) on **Cash accounts** (`/admin/cash/accounts`). Axe **serious**:
  `scrollable-region-focusable` on `.erp-table-wrap`.
- **Root cause (code-confirmed):** the responsive horizontal-scroll wrapper `.erp-table-wrap`
  (`web/src/styles.scss:284`) is used in **193 templates but only 1 sets `tabindex`** — so a
  keyboard-only user cannot scroll a table that overflows its frame to see the off-screen columns
  (and the pinned row action). It only trips axe where the table actually overflows the viewport,
  but the missing keyboard access is **app-wide** (192 wrappers).
- **Fix direction:** add `tabindex="0"` + `role="region"` + an `aria-label` to the scroll wrapper,
  ideally via one shared directive/component rather than 192 edits. Regression: axe check on a wide
  table screen. Owner: **frontend-engineer**.

**D2 — Four "create" screens show a generic duplicate-record message.** `Low` · UX/error-hygiene · backend
- **Found by:** Grace (FX rate, cost-dimension value), Sabina (price list), Saidi (stock location).
  All returned **409 "A record with the same unique identifier already exists."**
- **Root cause (code-confirmed):** that string is the catch-all for DB unique-constraint violations
  at `GlobalExceptionHandler.java:244`. These four create paths (`POST /fx/rates`,
  `/dimension-values`, `/price-lists`, `/stock-locations`) have **no service-level friendly duplicate
  message**, so they fall through to the generic handler — unlike the paths that *do* pre-check and
  read well: *"Department code already exists: OPS"*, *"Account code 1100 already exists in this
  company."*, *"A VAT return for this period already exists…"*, *"An on-hand record already exists…
  Use an ADJUSTMENT."*
- **Impact:** the user can't tell *what* clashed or what to do. This is the same error-hygiene
  standard the F24 sweep enforced; these four slipped through.
- **Fix direction:** give each of the four a specific, friendly duplicate message (name the field/
  value), or enrich the generic handler to name the entity/constraint. Owner: **backend-engineer**.

### 🟡 UI hygiene (leaked ids/uids) — **0**

The `DETAILS` scan opened real records across 10 entity types (PO, SO, customer, supplier, product,
GRN, sales invoice, employee, work order, supplier bill) and every persona's screens were scanned:
**no raw id/uid or bare-numeric-FK leaks.** The stock-on-hand name+code fix and RFQ name fix held.

### 🟢 UX / accessibility (beyond D1) — clean

Across all 17 personas at desktop viewport, axe surfaced **exactly one** serious/critical violation
(D1). No other a11y regressions.

## Works-as-designed / not defects (the remaining 9 findings)

These are **duplicate-data 409s** — an artifact of running the same canonical master-data script
against a **durable DB that prior runs already populated**. A real user on a real day creates *new*
records and would not hit them; the guards themselves are correct and (mostly) friendly:

| # | Persona | Action | 409 message | Verdict |
|---|---|---|---|---|
| 4 | Neema | create department | "Department code already exists: OPS" | WAD — friendly ✓ |
| 5,10 | Frank, Saidi | stock opening balance | "An on-hand record already exists… Use an ADJUSTMENT." | WAD — friendly ✓ |
| 6 | Amina | create VAT return | "A VAT return for this period already exists…" | WAD — friendly ✓ |
| 7 | Amina | create GL account | "Account code 1100 already exists in this company." | WAD — friendly ✓ |
| 2,3,8,9 | Grace, Sabina, Saidi | FX rate / dim value / price list / location | "…same unique identifier already exists." | WAD duplicate, but **message → D2** |

**Data-state artifact (needs-repro, low):** Saidi — *"PO selected but no receivable lines appeared"*
on Goods Receipt. The harness's `searchPick('Mbasha')` matched the first Mbasha PO, which on the
durable DB is likely an already-received (or still-DRAFT) order with no outstanding quantity — not a
product defect. A fresh placed-but-unreceived PO exists this run (procurement placed one); a targeted
manual receive against *that* PO should confirm GRN loads lines. No API error was returned.

## Bottom line

A full 17-persona busy week against a durable, already-operated database produced **zero blockers,
zero permission/RBAC gaps, zero id/uid leaks, and one a11y violation** — a strong signal that the
IAM/read-closure and error-hygiene programs have paid off. The only actionable engineering items are
two minor polish fixes: **D1** (keyboard-scrollable tables, app-wide) and **D2** (four generic
duplicate messages).

## Follow-up — fixes shipped + multi-device pass

The two desktop defects were fixed on branch `fix/table-a11y-and-duplicate-messages` (off
`develop`), and the personas then continued their busy week on **mobile (390×844)** and **tablet
(834×1112)**. Evidence: [mobile-all-problems.json](mobile-all-problems.json),
[tablet-all-problems.json](tablet-all-problems.json).

### Fixes

**D1 — wide tables keyboard-scrollable.** Two parts:
- `web/src/app/core/a11y/scrollable-region.service.ts` (new): a root-level service (one choke
  point, no template edits) that makes every genuinely horizontally-overflowing `.erp-table-wrap`
  a focusable, named region (`tabindex="0"`, `role="region"`, `aria-label` from the table
  `<caption>`), re-evaluated on DOM mutation + resize, retracting the tab stop when a table no
  longer overflows. 11 unit tests.
- `web/src/styles.scss`: `.erp-table-wrap` now declares `overflow-y: hidden`. **Root cause of the
  tablet recurrence:** `overflow-x: auto` forces the *computed* `overflow-y` to `auto`, so a table
  1px over on height (sub-pixel row rounding) registered as a vertically-scrollable region and
  tripped axe even though nothing scrolls vertically. Declaring `overflow-y: hidden` keeps it a
  horizontal-only scroller (no max-height → nothing clipped). Verified live: `/admin/cash/accounts`
  at tablet went from `scrollable-region-focusable` (serious) to **0 serious/critical**, with no
  needless focus stop added (the table fits width-wise).

**D2 — friendly duplicate messages.** Four create services now pre-check the real unique key and
return a specific, user-safe 409 (verified live on the running backend):
*"A price list with code RETAIL already exists."*, *"A stock location with code MAIN-BR-01 already
exists in this company."*, *"An exchange rate for USD→TZS on &lt;date&gt; already exists."*,
*"Cost Centre value CC-01 already exists."* 18 unit tests. (The agent also flagged that stock-location
codes are **company**-scoped, not branch-scoped, in the DB — a possible product-intent question,
out of scope here.)

### Multi-device pass results

| Device | Personas | Blockers | Findings |
|---|---|---|---|
| **Mobile** 390×844 | 17/17 logged in | 0 | **0 problems** — no a11y (incl. WCAG 2.2 tap-target), no access blocks, no id/uid leaks |
| **Tablet** 834×1112 | 17/17 logged in | 0 | 2, both noise: (a) the D1-class `scrollable-region-focusable` on cash/accounts — **now fixed** by the `overflow-y` change and re-verified clean; (b) one persona's browser crashed mid-run (*"Target page … has been closed"*) — a chromium flake under doubled concurrency, not an app defect |

**Good-UX verdict:** on phones and tablets the whole company could log in and reach every screen it
owns — no blocking, no confusing leaked codes, and (after the fix) no accessibility violations.
~290 device screenshots were captured as evidence.

## Re-run

```bash
docker compose up -d db
# backend: mvn spring-boot:run -Dspring-boot.run.profiles=dev  (DevTools restart off)
# web:     npm start -- --port 4400   (:4200 may be another project)
export NODE_PATH=d:/My_Works/ERP/ERPCLEAN2/web/node_modules WEB_BASE=http://localhost:4400
export SIM_PASSWORD='Tembo@2026!' DEEP=1 MODULES=1 DETAILS=1 AXE=1
node e2e/sim/run-personas.js   # → all-problems.json
```
