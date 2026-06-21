# ERP UAT — Executive Analysis & Defect Triage (2026-06-21)

Companion to the machine-generated **[UAT-RESULTS.md](UAT-RESULTS.md)** (every test, PASS/FAIL/BLOCKED with
data/expected/actual), **[UAT-RESULTS.csv](UAT-RESULTS.csv)** (Excel) and **[DEFECT-REGISTER.csv](DEFECT-REGISTER.csv)**.

## How this run was executed
- **Live, durable stack:** backend `:8081` (dev profile), Postgres `:5434` (durable — **never wiped**;
  902 products, 634 customers, 301 suppliers, 459 invoices, 617 POs, 130 employees pre-existing),
  web `:4200`.
- **40 automated QA agents** made **real HTTP calls** (no mocks), deriving "expected" from the actual
  controller/DTO/service source, and verifying side effects (GL postings, stock movements, balances)
  with follow-up reads and direct DB checks.
- **2,530 API/integration tests** + **134 Playwright UI smoke/a11y tests** = **2,664 total** (UI in §11).
- **Grading:** a correct *rejection* of bad input is a **PASS** (e.g. wrong password → 401 = PASS).
  A test is **FAIL** only when behaviour diverged from correct.

## Headline (all layers)
| Result | Count | % |
|---|---:|---:|
| Total tests | 2,664 | 100% |
| ✅ PASS | 2,569 | 96.4% |
| ❌ FAIL | 90 | 3.4% |
| ⏸ BLOCKED | 5 | 0.2% |

(API/integration: 2,530 tests — 2,439 PASS / 86 FAIL / 5 BLOCKED. Web UI: 134 — 130 PASS / 4 FAIL.)

Modules fully green (0 FAIL): **IAM auth/users (92), Parties-Customers (69), Cash & Bank (76),
Tax (70), Routes (62), cash/bank reconciliation flow (67)**.

The 86 FAILs are not 86 independent bugs — they collapse into **~10 themes**, several sharing a single
root cause. Triaged below by actionability.

---

## 1. 🔴 Systemic — "set-default / unique-flag" replacement fails with 409  (1 root cause, ~8 endpoints)
Every operation that **moves a unique flag from one row to another** fails with
`HTTP 409 — "A record with the same unique identifier already exists."`

| Defect | Operation |
|---|---|
| PARTIES-SUPPLIERS-054 | Set default supplier **bank account** |
| FX-CURRENCY-019 | Set company **default currency** |
| FX-CURRENCY-033 | Set branch **default currency** |
| FLOW-TAX-FX-052 | Set-default company currency (same) |
| STOCK-011 | Set default **stock location** |
| HR-PAY-033 | **Recalculate payroll** from CALCULATED/APPROVED |
| BUDGETING-040 | **Approve** budget version (supersede prior APPROVED) |

**Root cause:** the new row is written/flushed **before** the previous holder's flag is cleared, so the
partial-unique index (`is_default = true`) sees two `true` rows in the same flush. Fix once
(clear-old → `flush()` → set-new, or reorder the unit of work) and **all of these clear together**.
**Severity: High** (core master-data operations broken). High business value: one fix, broad win.

## 2. 🔴 Unhandled 500s where a 4xx is expected  (High)
A server error should never be the answer to bad input or a normal read.

| Defect | Endpoint | Cause |
|---|---|---|
| MANUFACTURING-024 / 022, FLOW-MANUFACTURE-018 | `GET /boms/cost-roll-up`, `/boms/explode?withCost` | Lazy-load `branch.getCompany()` + multi-location SOH → `IncorrectResultSizeException` |
| PROJECTS-040 | `GET` project **WIP report** | SQL failure (also 500 for bogus companyId) |
| REPORTING-BI-041 / 049 / 062 | BI dashboard / inventory / export with bad `companyId` | Unhandled NPE |
| FLOW-PROCURE-TO-PAY (M), PROCURE-RECEIVING (M) | Void DRAFT PO / GRN with **null reason** | NPE in `Map.of(...)` on null |

**Two sub-causes:** (a) genuine query bugs (BOM roll-up, WIP report); (b) missing null/not-found guards.
Add null-checks + a fallback `@ExceptionHandler` so unexpected paths return a safe 400/404, never 500.

## 3. 🟠 Missing state-transition / business-rule guards  (control gaps — High/Medium)
The system allows actions that should be blocked by status. Money/stock stays *internally* consistent in
most cases, but the controls are missing:

- **FLOW-ORDER-TO-CASH-027** — void a **fully-settled** invoice succeeds (should be 409).
- **FLOW-ORDER-TO-CASH-028** — cancel a **FULFILLED** sales order succeeds.
- **FLOW-TAX-FX-042** — **duplicate FX revaluation** run for the same period not blocked.
- **PURCHASES-058** — capture supplier quote against a **CANCELLED RFQ**.
- **PROJECTS-037/038 + (M)** — timesheet/task on **DRAFT / COMPLETED / CANCELLED** project (BR-PROJ-04).
- **HR-PEOPLE** — leave for **TERMINATED** employee / **INACTIVE** leave type; re-approve approved leave; re-terminate contract.
- **CRM** — re-qualify an already-QUALIFIED lead; `/win` with null `wonAt`.
- **POS (M)** — open a session on an **INACTIVE** till.
- **APPROVALS-047** — `PoApprovalGate.submit()` is **never invoked**: PO approval requests are not created
  (approval engine not wired into the PO submit path). *Architectural gap — verify intended design.*

## 4. 🟠 POS does not issue inventory  (High — retail-critical; DB-CONFIRMED)
**DEFECT-POS-001 / POS-HAPPY-010.** A finalised POS sale produces **no `SALE_ISSUE` stock movement**.
Verified directly in the DB: `stock_movements` for the POS invoice = **0 rows**; the sold product's
on-hand was unchanged; the only `SALE_ISSUE` movements in the window were sourced from **DELIVERY**.
Normal sales issue stock at *delivery*; a POS sale has no delivery step, so it must issue stock itself — it
doesn't. **Every POS sale overstates inventory.** (Money math, VAT, change, agent attribution, cash X-read
and session-close variance were all correct — only the stock leg is missing.)

## 5. 🟠 Validation bypass — `@Valid` / constraints not enforced on controller params  (Medium/Low)
The annotations exist on DTOs but aren't triggered because the controller param lacks `@Valid`, or no
constraint is present:
- LANDED COST empty `receiptUids` / `charges` accepted (PROCURE-RECEIVING-038/039).
- RFQ empty `supplierUids` (PURCHASES); settings **negative** approval threshold accepted.
- PAYE band set with **empty bands**; sales line with **both** discount amount *and* percent; HR PAYE.
- Fixed asset **negative cost** / REDUCING_BALANCE without rate (NPE).

## 6. 🟡 Security — stack-trace leakage on unmatched routes  (High — information disclosure)
**RBAC-SECURITY-NEGATIVE-017 / 046, IAM-ORG-060.** A 404 on an unknown `/api/**` path, or a 405 on a
read-only resource, returns Spring's **Whitelabel** error body with a full Java **`trace`** (15k+ chars) —
bypassing the `ApiResponse` envelope and the "user-safe errors only" invariant. *Permission gating itself
held: authenticated 401, unauthorized 403, and cross-branch isolation all passed.* Fix the error handler to
cover unmatched/405 paths and strip `trace` outside dev.

## 7. 🟡 Silent-ignore / phantom-data  (Medium)
Inputs accepted then ignored, or invalid queries returning a fake-success:
- `versionStatus` budget filter ignored; CRM `leadSource` change ignored on update.
- BI **Income Statement** with a bogus `companyId` → **200 with phantom "Company 9999" data**;
  reversed date range (`from > to`) → 200 silently.
- Notifications: orphan preference row created for a non-existent `typeKey`.

## 8. 🔵 Response-DTO mapper gaps — `uid` fields null  (Low/Medium — breaks uid-in-body convention)
DTOs return numeric `id` but `null` for the `uid`/derived fields clients are supposed to use:
- Budgeting (`budgetUid`, `fiscalYearUid`, `costCentreValueUid`, line `accountUid`…),
  AP/AR allocation (`supplierBillUid`/`arInvoiceUid`), **GL JournalEntry `totalDebit`/`totalCredit` null**,
  `ProductBranchDto` missing `branchUid`/`branchName`.

## 9. 🔵 "Correct rejection, imprecise message/status"  (Low — UX, not functional)
The system **does** reject bad input but with a generic *"data constraint"* message or a 404/409 where a
400 would be clearer (products self-component, service+stockable, agent country >2 chars, null
companyUid/amount, GRN without PO, etc.) and a couple of REST nits (POST → 200 not 201; cancel → 200 not
204). These passed the *control* but read poorly to an API consumer.

## 10. ⚪ Data observation — NOT a code defect (reclassified from Critical)
**AP-029 / AP-052** flagged the AP sub-ledger vs GL control difference ≠ 0 (≈1.99M). The reconciliation
endpoint **worked** (computed and reported the variance). The imbalance is almost certainly **accumulated
durable-DB data** from prior seeding/partial transactions, not a code bug — but it is worth an owner
investigation to confirm AP postings always hit control account 2100.

---

## Severity rollup (after triage)
| Bucket | Count (approx) | Notes |
|---|---:|---|
| Real, high-value (themes 1–4, 6) | ~25 | One shared fix clears ~8 of them (theme 1) |
| Medium control/validation gaps (3,5,7) | ~30 | Add status guards + `@Valid` |
| Low UX/mapper/REST nits (8,9) | ~25 | Polish; some are "correct-reject, poor message" |
| Data artifact / design-to-confirm | ~6 | AP recon (data); agent-mandatory & approvals-wiring (design) |

## 5 BLOCKED
3 in `pos-deep` (happy-path sale/stock/void — later unblocked by `pos-happy` via a provisioned cashier),
1 in `pos` module, 1 in `flow-gl-integrity`.

## 11. 🟢 Web UI smoke + accessibility  (Playwright)
- **130 / 130 admin route screens loaded cleanly** — every module's list/create/detail screen renders
  without an error overlay or console error (IAM, sales, purchases, stock, GL, AR/AP, cash, tax, fixed
  assets, HR, manufacturing, projects, CRM, budgeting, FX, **POS**, documents, notifications, approvals,
  reporting, routes, dimensions). This is strong end-to-end UI confirmation.
- **Login-page accessibility (axe) clean** — no violations.
- **4 FAIL — brittle test, not an app bug.** All 4 are inside `smoke.spec.ts`'s own interactive-login
  block (login→home + 2 dependent navigations + 1 admin-home axe). The **identical login in `auth.setup`
  passed**, and all 130 authenticated route loads passed, so the application's login works; the spec's
  assertion is flaky on first-hit lazy route compilation (the long-standing "login-flake"). Severity Low.
- Not run this pass: deeper `uiux-*` interaction specs and full axe-across-routes (route-load smoke +
  login a11y were covered). Worth a dedicated UI a11y run later.

## Recommended fix order
1. **Theme 1** (set-default 409) — one root cause, ~8 endpoints. Biggest ratio of value to effort.
2. **Theme 4** (POS stock issue) — retail-critical inventory integrity.
3. **Theme 2** (500→4xx) — add guards + global fallback handler; fix BOM roll-up & WIP queries.
4. **Theme 6** (stack-trace leak) — security.
5. **Theme 3** (status guards) — breadth of control gaps.
6. Themes 5/7/8/9 — validation, silent-ignore, mapper, UX polish.
