# UI/UX Test Coverage — Gap Analysis (2026-06-19)

Scan of the new UI/UX suite (web/e2e/uiux-*.spec.ts) vs the full web UI surface vs the existing e2e safety net (routes-smoke / conventions / massive-data / smoke).

**Verdict:** No. The new UI/UX suite is a deep-on-a-narrow-sample suite, not whole-system coverage. Its 64 deep cases touch only ~6 of the 25 functional areas (Auth/Shell, Parties-Customers/Suppliers, Products, Sales-Orders, GL-Journals, plus RBAC nav), and within those it exercises real UX behavior — validation surfacing, empty/error/loading states, happy-path journeys, axe a11y, keyboard. The other ~19 areas — every money-movement and posting flow (AR receipts, AP payments/bills, Cash & Bank, GL trial-balance/year-end, Tax VAT/WHT, Fixed-Asset depreciation, Payroll), plus Purchasing P2P, Inventory transactions, Approvals, Manufacturing, CRM, Projects, Budgeting, FX, POS, Reporting — get only the BROAD-SHALLOW net: routes-smoke (page loads, no 5xx, no console errors on 128 routes), conventions (axe + no-raw-UID, list + first detail row), and massive-data (volume/pagination/search on 8 high-volume lists). So the honest framing: breadth is strong and shallow (load-without-crashing is well guarded everywhere), depth is strong but only on master-data/order-entry CRUD — the financial posting and multi-step transaction journeys that carry the highest correctness and money risk have essentially zero deep UX validation.

# UI/UX Test Coverage-Gap Analysis (pre-execution)

## 1. Verdict
**No — the UI/UX suite does not cover the whole system; it is deep-on-a-narrow-sample.** Its 64 cases exercise real UX behaviour (validation surfacing, empty/error/loading states, happy-path journeys, axe a11y, keyboard reachability) but only across ~6 of the 25 functional areas: Auth/Shell, Customers/Suppliers (Parties), Products, Sales-Orders, GL-Journals (post form only), and RBAC nav. The remaining ~19 areas — including **every money-movement and posting flow** (AR receipts, AP bills/payments, Cash & Bank, GL trial-balance/period/year-end, Tax VAT/WHT, Fixed-Asset depreciation, Payroll) plus Purchasing P2P, Inventory transactions, Approvals, Manufacturing, CRM, Projects, Budgeting, FX, POS, Reporting — receive only the **broad-shallow** net from `routes-smoke` (loads, no 5xx, no console error, no login-bounce), `conventions` (axe + no-raw-UID on lists + first detail row), and `massive-data` (volume/pagination/search on 8 lists). Net: *"does it load without crashing"* is well guarded everywhere; *"does the financial posting / multi-step journey actually work and fail gracefully"* is validated only for master-data and order entry. The highest-correctness, highest-money-risk flows have essentially zero deep UX coverage.

## 2. Coverage Matrix

Legend: ✅ deep/solid · ➖ partial/shallow · ❌ none. Columns 1-3 = broad shallow net (applies to all loadable routes); columns 4-7 = NEW deep UI/UX suite.

| Module-area | Route loads (routes-smoke) | axe+no-UID (conventions) | Volume/paginate (massive-data) | DEEP: validation | DEEP: empty/error states | DEEP: happy-path journey | a11y-deep |
|---|---|---|---|---|---|---|---|
| Administration & IAM | ✅ | ✅ | ➖ | ❌ | ❌ | ❌ | ➖ (home) |
| Parties – Customers | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Parties – Suppliers | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| Parties – Agents/Other/Routes | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Products & Catalog | ✅ | ✅ | ✅ | ✅ | ✅ | ➖ | ✅(create) |
| Sales – Orders | ✅ | ✅ | ✅ | ❌ | ✅ | ➖ | ✅(list) |
| Sales – Quote/Deliv/Invoice/Return/Blanket/Standing | ✅ | ✅ | ➖(SO only) | ❌ | ❌ | ❌ | ❌ |
| Inventory & Stock | ✅ | ✅ | ➖(valuation) | ❌ | ❌ | ❌ | ❌ |
| Purchasing (P2P) | ✅ | ✅ | ➖(PO list) | ❌ | ❌ | ❌ | ❌ |
| **GL (journals/TB/periods/year-end)** | ✅ | ✅ | ➖(journals,TB) | ➖(post form only) | ❌ | ➖(post→detail) | ❌ |
| **AR (receipts/statement/ageing)** | ✅ | ✅ | ✅(receipts) | ❌ | ❌ | ❌ | ❌ |
| **AP (bills/payments)** | ✅ | ✅ | ✅(bills) | ❌ | ❌ | ❌ | ❌ |
| **Cash & Bank** | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Tax (VAT/WHT)** | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Financial Reporting | ✅ | ✅ | ➖(TB,ageing) | ❌ | ❌ | ❌ | ❌ |
| Approvals & Workflow | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Documents & PDF | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Notifications | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Costing & Dimensions | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Fixed Assets & Depreciation** | ✅ | ✅ | ➖(valuation) | ❌ | ❌ | ❌ | ➖(smoke) |
| CRM | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **HR & Payroll** | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Projects | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Budgeting | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Manufacturing & BOM | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| FX & Multi-Currency | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Analytics/Dashboard | ✅ | ✅ | ✅(dashboard) | ❌ | ❌ | ❌ | ❌ |
| Point of Sale (POS) | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| RBAC / Permissions (cross-cut) | n/a | n/a | n/a | ➖(nav only) | n/a | ➖(forbidden→redirect) | n/a |

**Reading the matrix:** deep columns (4-7) are populated for only 4 areas (Customers fully; Products, Suppliers, Sales-Orders partially) plus the cross-cutting Auth/Shell/RBAC. Bolded rows are financial/posting flows where deep coverage is entirely ❌.

## 3. Ranked Gaps (by business/money risk)
1. **GL posting lifecycle** beyond the single post form — TB reconciliation, period open/close lock, year-end close wizard, journal detail/reversal. Risk: unbalanced/duplicate postings, period-lock bypass, year-end corruption.
2. **AR receipts** (record/allocate/over-apply) — load-only. Risk: mis-applied cash, over-allocation, silent posting 5xx.
3. **AP bills + payments + three-way match** — load-only. Risk: duplicate/overpayment, match bypass.
4. **Cash & Bank** transfers / entries / reconciliation — load-only. Risk: unbalanced transfers, reconciliation drift.
5. **Fixed-asset depreciation runs** (post/reversal/reconciliation) — load-only; reversal flagged design-heavy in memory. Risk: wrong/irreversible depreciation posting.
6. **Tax VAT returns + WHT** — load-only. Risk: incorrect statutory filing caught late.
7. **HR & Payroll run** — 13 routes load-only; payroll post is money-movement. Risk: calc/posting errors, leave/loan approval gaps.
8. **Purchasing P2P** (requisition→RFQ→PO→GRN→return→landed-cost) — create forms load-only. Risk: GRNI/landed-cost mis-distribution.
9. **Inventory transactions** (transfers, counts, opening valuation) — create forms load-only. Risk: negative/unbalanced stock, count variance errors.
10. **Approvals decision journey** — load-only; gates many posting flows. Risk: approval bypass / stuck requests.
11. **Sales O2C downstream** (quote conversion, deliveries, invoices, returns, blanket/standing) — only SO list/detail is deep. Risk: invoice state-transition + delivery/return validation gaps.
12. **Detail/edit-form validation depth + optimistic-lock 409 surfacing** — only D1 is deep across ~50 detail routes.
13. **RBAC action-level** (create/post buttons hidden, forbidden-API 403 surfacing) — only nav + one redirect tested.
14. **Long tail** (CRM, Projects, Budgeting, Manufacturing/BOM, FX, POS, Reporting, Documents, Notifications, Cost-centre) — load-only, no deep UX.

## 4. Recommendations

**Must-add before sign-off** (all cheaply cloned from existing `uiux-forms-validation` G1-G4 and `uiux-journeys` UX-J1 patterns):
- `uiux-ar-ap-posting.spec.ts` — AR record-receipt, AP record-payment, AP enter-bill: invalid/over-application validation, clean error strings (no 500/stack), success+detail nav, zero 5xx.
- `uiux-cash-bank.spec.ts` — record transfer, cash/bank entry, bank reconciliation: balance/required validation, must-balance UX.
- `uiux-gl-lifecycle.spec.ts` — journal detail, TB debits=credits, closed-period posting guard, year-end wizard happy/blocked.
- `uiux-depreciation-payroll.spec.ts` — depreciation run post + payroll run post: validation + happy path + detail.
- `uiux-purchasing-journey.spec.ts` — one P2P journey (requisition→PO or GRN create) + landed-cost distribution validation.
- `uiux-approvals.spec.ts` — inbox approve/reject decision journey, state change, no 5xx.
- Extend `uiux-forms-validation.spec.ts` — generalize D1 to detail/edit validation on the financial forms; assert optimistic-lock **409 surfaces as a clean conflict message**.
- Extend `uiux-rbac-nav.spec.ts` — action-level: narrow user sees no create/post buttons; forbidden write API surfaces 403 (not blank/500).

**Nice-to-have:** inventory-transactions, tax, sales-downstream, POS sell, CRM/Projects/Budgeting/Manufacturing/FX deep smoke; extend empty/error-state + a11y-deep coverage to AR/AP/cash/GL lists and financial entry forms (currently only 4 master-data areas).

**Cheaply extensible:** all "must-add" specs reuse the storageState auth, the alertdialog/success+list-persistence assertions, the "no raw 500/stack/NPE in body" guard, and the data-driven route loop already in the suite. Highest ROI per line of test code.

## 5. Cross-cutting UX dimensions only sampled
- **Loading states** — one assertion ("no stuck spinner") on 4 lists; skeleton/spinner-during-fetch UX is otherwise unverified. Worth folding into the new financial specs rather than a dedicated pass.
- **Keyboard nav** — only login + customer-create (KB-1/KB-2). Full-keyboard operability of data-entry grids (journal lines, order lines) is untested — **worth a dedicated pass** for the line-item entry forms.
- **Responsive/mobile** — **zero coverage**; no viewport variation anywhere. Worth a small dedicated pass on the top 5 flows if mobile is in scope.
- **Toast/confirm dialogs** — success alertdialog is asserted; destructive-action confirm dialogs and toast dismissal are not. Fold into journey specs.
- **Optimistic-lock 409 surfacing** — memory notes a 409 handler shipped, but **no UI test asserts it renders as a clean conflict message**. Add as an assertion in the detail/edit validation extension (listed in must-add). Worth explicit coverage given the posting flows.

Overall: run the existing suite + broad net as the breadth gate tomorrow, but treat the 8 "must-add" financial/posting specs as the real sign-off blocker — they close the money-risk gap the current deep suite leaves wide open.
---

## Findings surfaced by the UI/UX suite (first execution, 2026-06-19)

The gap-closing run (223 pass / 6 graceful-skip / 0 fail after triage) surfaced two **real app findings** (the rest of the 16 first-run failures were test-bugs: an over-broad `\b500\b` leak regex matching amounts like `-500.00`, auto-dismissing alerts, z-stacked backdrop clicks, and signal-backed inputs needing real keystrokes):

1. **A11Y — muted-text contrast below WCAG AA. ✅ FIXED (frontend-only).** App-wide `.text-muted` rendered Bootstrap's hardcoded `#6c757d` on the `#f4f6f9` canvas = **4.33:1** (< 4.5 AA). Fix in `web/src/styles.scss`: darkened the muted token `--erp-text-3` `#6b7280`→`#636b75` and mapped `.text-muted` to it (override beats Bootstrap's `!important`). Verified: `.text-muted` computed color is now `rgb(99,107,117)` = `#636b75` (~**4.98:1** on the canvas). axe `color-contrast` stays excluded from the automated gate (design-review territory + headless-variable), but the underlying defect is resolved.

2. **BUG — payroll-run create year field. ✅ FIXED (frontend-only).** Confirmed real: `/admin/hr/payroll-runs` create year is `<input type="number">`, so Angular's `NumberValueAccessor` set the (string-typed) `fPeriodYear` signal to a **number**; `create()` called `.trim()` on it → silent `TypeError` that aborted submit — no validation rendered, no run ever created (broken for every user). Fix in `payroll-run-list.component.ts`: `create()` now `String(...)`-coerces year (and month, defensively) before trimming. Verified: the spec was reverted from the signal-hack to real `fill()` and all 8 PR cases pass (incl. PR-6 valid-create-success + PR-3/4/5 validation messages).

3. **UX — "Paste fiscal year UID" raw-ULID inputs (Budgeting). ✅ FIXED (frontend-only).** Three Budgeting screens (Create Budget, Budget Variance Report, Departmental Actuals Report) forced the user to paste a raw fiscal-year ULID into a free-text input. **Fix:** replaced each `<input id="fFiscalYearUid">` with the shared `<app-uid-picker>` populated from `glService.listFiscalYears(companyId)` (label = `yearCode`, value = `uid`), matching how the module already picks fiscal *periods*. Label "Fiscal Year UID" → "Fiscal Year"; validation message updated. Regression test `BUD-FY-1` asserts the control is a selectable picker (≥1 option) and there is no "Paste fiscal year UID" input. Known minor follow-ups: the two *report* filters don't reload the picker on a company switch (pre-existing pattern), and the picker's `id` sits on the host (label `for` association) — both noted for the ui-kit owner.

4. **a11y — `app-uid-picker` `<label for>` didn't associate with the real control. ✅ FIXED (shared component, frontend-only).** The consumer-supplied `id` landed on the custom-element host, so `<label for="X">` pointed at the host, not the inner `<select>` the user operates (WCAG 1.3.1/4.1.2). **Fix (one file, `uid-picker.component.ts`):** added an `id` signal input forwarded to the inner `<select>` via `[attr.id]`, nulled the host id (`host: { '[attr.id]': 'null' }`), and gave the large-list filter `<input>` a derived `X-filter` id; `name` stays on the host so NgForm registration is unchanged. Fixes all **72** consumers that pair `id` + `<label for>` with **zero consumer churn**. One regression the adversarial review caught and we fixed: `stock-transfer-create` used `[attr.id]="'lineProd'+i"` (a host-attr binding the null stripped) → switched to `[id]=` so it routes through the new input. Verified: full uiux suite **292 / 0 failed**; tsc clean. **Backlog:** (a) ✅ FIXED — `aria-labelledby` now forwarded to the inner `<select>` (see #5). (b) `budget-version-detail` has `<label [for]>` with no `id` on the picker (dangling). (c) `pricing-rules` pickers have `id` but no `name` (NgForm registration). (d) drop the now-redundant `<select>` `aria-label` when a label is associated (Label-in-Name).

5. **a11y — `app-uid-picker` `aria-labelledby` now forwarded to the inner `<select>`. ✅ FIXED (frontend-only).** Follow-up to #4 backlog (a). Added an `ariaLabelledby` signal input bound to `[attr.aria-labelledby]` on the inner `<select>` (keeping `aria-label` as a fallback so a select never loses its accessible name); host `aria-labelledby` is nulled so it never lingers on the wrapper. Consumers bind via the input `[ariaLabelledby]="'someId'"` — a bare `aria-labelledby="…"` attribute on the host is intentionally stripped. **Normalised all picker labelledby consumers** (the workflow survey initially missed `stock-transfer-create`'s 4 header pickers — caught by adversarial review): `enter-bill` (×2), `approval-policy-detail`/`-list` (moved off the wrapper `<div>`), `loan-list`, `leave-request-list`, `contract-list`, `stock-transfer-create` (×4). Also removed `aria-hidden="true"` from 3 label-target spans (a labelledby target must be in the a11y tree) and gave `contract-list`'s nameless picker a `name`. Pitfall fixed during integration: the input was first aliased `'aria-labelledby'` while consumers used `[ariaLabelledby]` → Angular `NG8002` (plain `tsc` doesn't run template type-checking, so it slipped past the agent's check); resolved by dropping the alias so the camelCase input binds. Verified: full uiux suite **286 passed / 0 failed** (6 env-skips, 1 cold-start-flake-passed-on-retry); ng template compile clean.

6. **a11y — remaining `app-uid-picker` backlog (b)/(c)/(d) cleared. ✅ FIXED (frontend-only).** (b) `budget-version-detail` dangling `<label for>`: added `[id]` to the DIRECT-mode pickers (accUid/perUid) and converted the ANNUAL_SPREAD/SEED pickers from a **fake `inputId=` attribute** (not a real input — silently dropped, so the label never associated) to `[id]="'fSpreadAccUid'"`/`[id]="'fSeedUid'"`. (c) `pricing-rules`: added `name` to the 3 filter pickers (NgForm hygiene). (d) **Label-in-Name** (WCAG 2.5.3): the inner `<select>`'s fallback `aria-label` is now suppressed when a real label is wired — `[attr.aria-label]="(id() || ariaLabelledby()) ? null : (placeholder() || 'Select a resource')"` — so the visible `<label for>`/`aria-labelledby` wins; label-less pickers keep `aria-label`, so none is ever nameless. The large-list filter `<input>` keeps its search `aria-label` unconditionally. **Empirically settled an open question** the reviewers disputed: a static `id="x"` on `<app-uid-picker>` **does** forward to the inner `<select>` (Angular binds the static attr to the signal input; host copy is nulled) — proven by a new permanent BUD-FY-1 assertion (`app-uid-picker[id=…]` count 0, `select#…` count 1). So (d) is safe for static-id consumers too. Verified: full uiux suite **293 passed / 0 failed / 0 skipped**. **`requisition-create` per-line labels — ✅ FIXED.** Added an `ariaLabel` signal input to the picker, forwarded to the inner `<select>` (`[attr.aria-label]="ariaLabel() ?? ((id() || ariaLabelledby()) ? null : placeholder…)"`) and to the large-list filter `<input>`; host `aria-label` nulled. Converted `requisition-create`'s 2 line pickers (×N rows) from `[attr.aria-label]` (host) to `[ariaLabel]`. Runtime-confirmed: each `<select>` now reads "Product for line N" / "Unit for line N" and the host carries no `aria-label`. **The app-uid-picker a11y initiative is now complete** — every labelling path (`<label for>` via `[id]`, `[ariaLabelledby]`, explicit `[ariaLabel]`) forwards to the real `<select>`, with `placeholder` as the only fallback.
