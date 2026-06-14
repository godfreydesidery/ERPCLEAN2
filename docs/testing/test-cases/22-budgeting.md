# Budgeting & Management Accounting — Test Cases (BUD)

Scope: budget headers + version lifecycle (DRAFT → SUBMITTED → APPROVED / REJECTED / SUPERSEDED, plus
recall back to DRAFT), line entry in three modes (DIRECT, ANNUAL_SPREAD, SEED-from-prior-version),
new-version re-plan, and the two budget reports (budget-vs-actual variance + departmental actuals).
Budgets post NOTHING to GL (BR-BUD-01 / D-7); they are read against GL actuals only at report time.

## Modules / submodules covered (verified controllers + routes)

| Submodule | Frontend route | API base path | Controller |
|---|---|---|---|
| Budget list + create | `/admin/budgets` | `/api/v1/budgets` | `BudgetController` |
| Budget detail (header + all versions; lifecycle actions; re-plan) | `/admin/budgets/uid/:uid` | `GET /api/v1/budgets/uid/{uid}`, `POST /api/v1/budgets/uid/{uid}/versions` | `BudgetController` |
| Budget version detail (lines; DRAFT-only edit) | `/admin/budget-versions/uid/:uid` | `/api/v1/budget-versions/uid/{uid}` (+ `/lines`, `/submit`, `/recall`, `/approve`, `/reject`) | `BudgetVersionController` |
| Variance report | `/admin/budgeting/variance` | `GET /api/v1/budgeting/variance` | `BudgetReportController` |
| Departmental actuals | `/admin/budgeting/departmental-actuals` | `GET /api/v1/budgeting/departmental-actuals` | `BudgetReportController` |

Verified endpoint inventory (exhaustive — every action below has ≥1 case):

- `GET  /api/v1/budgets` — list (paged; filters `companyId`, `fiscalYearUid`, `costCentreValueUid`, `versionStatus`) — `BUDGETING.BUDGET.VIEW`
- `GET  /api/v1/budgets/uid/{uid}` — header + versions — `BUDGETING.BUDGET.VIEW` (scoped)
- `POST /api/v1/budgets` — create budget + v1 DRAFT (201) — `BUDGETING.BUDGET.MANAGE`
- `POST /api/v1/budgets/uid/{uid}/versions` — new version / re-plan (201) — `BUDGETING.BUDGET.MANAGE` (scoped)
- `GET  /api/v1/budget-versions/uid/{uid}` — version + lines — `BUDGETING.BUDGET.VIEW` (scoped)
- `PUT  /api/v1/budget-versions/uid/{uid}/lines` — upsert lines (DRAFT only) — `BUDGETING.BUDGET.MANAGE` (scoped)
- `POST /api/v1/budget-versions/uid/{uid}/submit` — DRAFT → SUBMITTED — `BUDGETING.BUDGET.SUBMIT` (scoped)
- `POST /api/v1/budget-versions/uid/{uid}/recall` — SUBMITTED → DRAFT — `BUDGETING.BUDGET.SUBMIT` (scoped)
- `POST /api/v1/budget-versions/uid/{uid}/approve` — SUBMITTED → APPROVED (supersedes prior) — `BUDGETING.BUDGET.APPROVE` (scoped)
- `POST /api/v1/budget-versions/uid/{uid}/reject` — SUBMITTED → REJECTED — `BUDGETING.BUDGET.APPROVE` (scoped)
- `GET  /api/v1/budgeting/variance` — variance report — `BUDGETING.REPORT.VIEW`
- `GET  /api/v1/budgeting/departmental-actuals` — departmental actuals — `BUDGETING.REPORT.VIEW`

## Permission codes in scope (verified — V70 migration + @PreAuthorize)

- `BUDGETING.BUDGET.VIEW` — list/view budgets, versions, lines
- `BUDGETING.BUDGET.MANAGE` — create budgets; create/edit versions & lines; seed/re-plan
- `BUDGETING.BUDGET.SUBMIT` — submit a version; recall a submitted version
- `BUDGETING.BUDGET.APPROVE` — approve or reject a submitted version
- `BUDGETING.REPORT.VIEW` — run variance + departmental-actuals reports
- `BUDGETING.REPORT.EXPORT` — seeded in V70 **but no controller endpoint exists** (export is backend-permission-only, NOT wired to any UI/endpoint at present). Noted for completeness; no functional TC.

Seeding note (V70): only the `ORG_ADMIN` role is granted the BUDGETING.* permissions in the seed. Any
other allowed role in cases below (e.g. ACCOUNTANT) must be granted via a CUSTOM role assignment or by
adding the permission to that role before the test. `rootadmin` bypasses all checks (use for setup/positive
sanity only, never for negative-auth assertions).

## Enum values in scope (verified)

- `BudgetVersionStatus` = **DRAFT, SUBMITTED, APPROVED, REJECTED, SUPERSEDED**
- `UpsertBudgetLineRequest.EntryMode` = **DIRECT, ANNUAL_SPREAD, SEED**
- `AccountType` (report filter) = **ASSET, LIABILITY, EQUITY, INCOME, EXPENSE**
- Currency on lines is fixed **TZS** (server constant `CURRENCY_TZS`; not user-settable)

## Legal vs illegal version-lifecycle transitions (verified in `BudgetServiceImpl`)

| From → To | Action | Legal? | Guard |
|---|---|---|---|
| (none) → DRAFT | create budget / create version | Legal | initial state |
| DRAFT → SUBMITTED | submit | Legal | requires ≥1 line (BR-BUD-11) |
| SUBMITTED → DRAFT | recall | Legal | only from SUBMITTED |
| SUBMITTED → APPROVED | approve | Legal | supersedes prior APPROVED in same (company,FY,costCentre) scope |
| SUBMITTED → REJECTED | reject | Legal | reason required |
| APPROVED → SUPERSEDED | (implicit) | Legal | only via approving a newer version |
| DRAFT → APPROVED / REJECTED | approve / reject | **Illegal** | "Only a SUBMITTED version can be …" |
| DRAFT → DRAFT (recall) | recall | **Illegal** | "Only a SUBMITTED version can be recalled" |
| SUBMITTED → SUBMITTED (submit) | submit | **Illegal** | "Only a DRAFT version can be submitted" |
| APPROVED / REJECTED / SUPERSEDED → anything | any lifecycle | **Illegal** | terminal/locked; ConflictException |
| edit lines when not DRAFT | upsert lines | **Illegal** | "lines are editable only in DRAFT status" |

## UI convention deviations found (assert these AS-IS — do not assume the ideal)

- **C1 partial deviation:** account / fiscal-period / seed-from-version selections use `<app-uid-picker>`
  (chosen by human NAME/label) — compliant. BUT the budget **create** form (`fFiscalYearUid`) and BOTH
  report forms (`fFiscalYearUid`, `fCostCentreUid` on variance) use **free-text UID `<input>`** fields,
  not pickers. Cases that exercise those screens must assert the field is free-text today (documented gap),
  while still asserting that no raw uid is shown in result tables/labels.
- **C4 partial deviation:** the budget-list screen has all four states (loading/error/forbidden/empty).
  The budget-**detail** and version-**detail** screens implement only loading/error/default (no in-page
  `forbidden` state) — RBAC there is enforced by the route guard `requirePermission('BUDGETING.BUDGET.VIEW')`,
  which blocks navigation entirely. Report screens have loading/error/forbidden/idle.

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User role | `rootadmin` (setup/bypass), `ORG_ADMIN` (all BUDGETING.*), a CUSTOM role with `BUDGETING.BUDGET.VIEW` only, a CUSTOM role with VIEW+MANAGE but NOT SUBMIT/APPROVE, a CUSTOM role with VIEW+SUBMIT, a CUSTOM role with VIEW+APPROVE, NO-PERMISSION user |
| Budget scope | company-wide (`costCentreValueUid = null`) vs cost-centre-specific (a dimension value) |
| Version status | DRAFT, SUBMITTED, APPROVED, REJECTED, SUPERSEDED |
| Line entry mode | DIRECT, ANNUAL_SPREAD, SEED |
| Account type (report) | ALL, ASSET, LIABILITY, EQUITY, INCOME, EXPENSE |
| Report scope | company-wide vs a single cost centre; full year (1–12) vs partial range |
| Tenancy | own company vs cross-company (must be denied / NotFound); branch context via `X-Branch-Uid` |
| Report state | with APPROVED budget vs `noApprovedBudget = true` (budget amounts all 0) |

---

# TEST CASES

## A. Budget list + create

### TC-BUD-001 — Budget list loads (paginated, four states)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Budget list (`/admin/budgets` · `GET /api/v1/budgets`)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → nav item hidden + route blocked
- **Preconditions / Seed:** ≥1 budget exists for the active company (seed via TC-BUD-010 or API `POST /api/v1/budgets`).
- **Steps:**
  1. Log in as ORG_ADMIN; navigate to `/admin/budgets`.
  2. Observe the company selector defaults to the first company; the list loads for it.
  3. Read the table rows (budget number, name, fiscal year, latest status badge).
- **Test Data:** active company with seeded budgets.
- **Expected Result:** table renders rows; status badge reflects `latestStatus` (APPROVED preferred, else latest version). Response is `ApiResponse<List<BudgetDto>>` with `meta {page,size,totalElements,totalPages,hasNext}`.
- **Convention Assertions:** C2 envelope+meta; C4 loading→idle states; C5 `<app-paginator>` present (hidden if 1 page); C6 axe clean; C7 only active-company budgets shown; C1 no raw uid in any cell (rows link by row, not by typed uid).
- **Negative / Edge:** NO-PERMISSION user → "Budgeting" nav group/item hidden and direct nav to `/admin/budgets` blocked by `requirePermission('BUDGETING.BUDGET.VIEW')`.

### TC-BUD-002 — Budget list empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Budget list (`/admin/budgets` · `GET /api/v1/budgets`)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a company with NO budgets (a freshly created company, or filter by a status with no matches).
- **Steps:**
  1. Navigate to `/admin/budgets`; select the empty company.
- **Expected Result:** distinct empty state (no rows; "no budgets" messaging), not an error.
- **Convention Assertions:** C4 empty ≠ error ≠ loading; C5 paginator self-hidden (0/1 page); C6 axe.
- **Negative / Edge:** switching back to a populated company re-loads rows.

### TC-BUD-003 — Budget list error state
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Budget list (`/admin/budgets` · `GET /api/v1/budgets`)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** force a 500 (network intercept) on `GET /api/v1/budgets`.
- **Steps:**
  1. Intercept the list call to fail; navigate to `/admin/budgets`.
- **Expected Result:** the list shows the `error` state (non-403). A 403 instead routes to the `forbidden` state.
- **Convention Assertions:** C4 error vs forbidden distinguished (component maps 403→forbidden, else→error); C6 axe.
- **Negative / Edge:** 403 intercept → forbidden state (separate from error).

### TC-BUD-004 — Filter budget list by version status
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Budget list (`/admin/budgets` · `GET /api/v1/budgets?versionStatus=`)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` — ORG_ADMIN
- **Variation:** versionStatus = each of DRAFT / SUBMITTED / APPROVED / REJECTED / SUPERSEDED, plus "All statuses".
- **Preconditions / Seed:** budgets with versions across multiple statuses.
- **Steps:**
  1. On `/admin/budgets`, choose each status from the status filter dropdown.
  2. Observe the list reloads (filter resets to page 0) and rows match.
- **Test Data:** the five status options + "All statuses".
- **Expected Result:** the `versionStatus` query param is sent; only matching budgets returned; "All statuses" clears the filter.
- **Convention Assertions:** C2 param round-trip; C4 each filter may yield empty state; C6 axe.
- **Negative / Edge:** a status with no matches → empty state, not error.

### TC-BUD-005 — Pagination controls on budget list
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Budget list (`/admin/budgets`)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** > 20 budgets (page size = 20) for one company.
- **Steps:**
  1. Navigate to `/admin/budgets`; use the paginator NEXT, then page-number, then PREVIOUS, FIRST, LAST.
- **Expected Result:** each click reloads the correct page; `meta.hasNext` drives the NEXT enablement; current page highlighted.
- **Convention Assertions:** C5 FIRST/PREV/numbers/NEXT/LAST present; C2 meta paging; C6 axe.
- **Negative / Edge:** ≤1 page → paginator hidden.

### TC-BUD-010 — Create budget (company-wide) → opens v1 DRAFT
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Budget create (`/admin/budgets` · `POST /api/v1/budgets`)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` — runs as ORG_ADMIN; also as a CUSTOM role with VIEW only → "New Budget" action hidden and `POST` returns 403
- **Variation:** budget scope = company-wide (`costCentreValueUid` omitted → null).
- **Preconditions / Seed:** an active fiscal year exists; note its `fiscalYearUid`.
- **Steps:**
  1. As ORG_ADMIN, open `/admin/budgets`; click to reveal the create form.
  2. Enter Name; enter Fiscal Year UID (free-text field — current UI); leave notes/initial-label optional; submit.
- **Test Data:** name = "FY2026 Operating Budget"; fiscalYearUid = `<seeded FY uid>`.
- **Expected Result:** 201 CREATED; success alert with the generated `budgetNumber`; list reloads showing the new budget with a DRAFT v1. Service auto-creates version 1 in DRAFT (`BudgetVersion(...,1,...)`).
- **Convention Assertions:** C2 201 + `BudgetDto`; C8 money n/a here but `budgetNumber` shown not raw id; **C1 NOTE deviation** — fiscal year is a free-text UID input today (no picker); assert no DB numeric id in URL; C9 soft-create (no GL posting — D-7); C3 MANAGE required.
- **Negative / Edge:** missing name → client "Budget name is required"; missing fiscalYearUid → "Fiscal Year UID is required"; unknown fiscalYearUid → server NotFound (FiscalYear); name > 160 chars → 400 validation; CUSTOM VIEW-only role → 403.

### TC-BUD-011 — Create budget scoped to a cost centre
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Budget create (`POST /api/v1/budgets`)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` — ORG_ADMIN
- **Variation:** `costCentreValueUid` = an ACTIVE COST_CENTRE dimension value.
- **Preconditions / Seed:** an active COST_CENTRE dimension value for the company.
- **Steps:**
  1. Create a budget supplying `costCentreValueUid` (via API since the create form does not expose a cost-centre picker today).
- **Test Data:** costCentreValueUid = `<active dimension value uid>`.
- **Expected Result:** 201; budget bound to that cost centre; uniqueness is per (company, fiscalYear, costCentre).
- **Convention Assertions:** C2 envelope; C7 dimension value must belong to same company (else NotFound); C9 no GL posting.
- **Negative / Edge:** INACTIVE cost-centre value → ConflictException "Cost centre is inactive"; cost-centre value from another company → NotFound (DimensionValue).

### TC-BUD-012 — Duplicate budget scope rejected
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Budget create (`POST /api/v1/budgets`)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` — ORG_ADMIN
- **Variation:** same (company, fiscalYear, costCentre) as an existing budget.
- **Preconditions / Seed:** TC-BUD-010 has created a company-wide budget for the FY.
- **Steps:**
  1. Attempt to create a second company-wide budget for the same fiscal year.
- **Expected Result:** ConflictException "A budget already exists for this fiscal year and cost centre scope"; surfaced as the form error from `errors[0]`.
- **Convention Assertions:** C2 errors array surfaced; C3 MANAGE.
- **Negative / Edge:** a different cost-centre value for the same FY is allowed (distinct scope).

### TC-BUD-013 — Create budget denied without MANAGE
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Budget create (`POST /api/v1/budgets`)
- **Permission / Role:** runs as CUSTOM role holding only `BUDGETING.BUDGET.VIEW`
- **Preconditions / Seed:** a CUSTOM role = {BUDGETING.BUDGET.VIEW} assigned to the test user.
- **Steps:**
  1. Log in as the VIEW-only user; open `/admin/budgets`.
  2. Confirm the create/"New Budget" affordance is not available (`canManage()` false).
  3. Attempt the `POST /api/v1/budgets` directly (API) → expect 403.
- **Expected Result:** UI hides create; API returns 403.
- **Convention Assertions:** C3 RBAC by permission code (not role name); C2 403.
- **Negative / Edge:** same user CAN still view the list (VIEW present).

## B. Budget detail (header + versions) & re-plan

### TC-BUD-020 — Budget detail shows header + all versions
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Budget detail (`/admin/budgets/uid/:uid` · `GET /api/v1/budgets/uid/{uid}`)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` (scoped) — ORG_ADMIN; route guard blocks users without VIEW
- **Preconditions / Seed:** a budget with ≥2 versions of differing statuses.
- **Steps:**
  1. From the list, open a budget row → `/admin/budgets/uid/:uid`.
  2. Read the header (number, name) and the versions list (ordered version-no desc) with status badges.
- **Expected Result:** header + versions render; each version shows version-no, label, status badge, line count; action buttons appear conditionally by status + permission.
- **Convention Assertions:** C2 `BudgetDto` with nested `versions`; C1 uid only in URL, not shown as a label (versions are shown as "V{n}" not uid); C4 loading/error (no in-page forbidden — guard-enforced); C6 axe.
- **Negative / Edge:** unknown budget uid → server NotFound → detail `error` state; user without VIEW → route guard redirect (never reaches screen).

### TC-BUD-021 — Re-plan: create a new blank version
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Budget detail re-plan (`POST /api/v1/budgets/uid/{uid}/versions`)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` (scoped) — ORG_ADMIN; CUSTOM VIEW-only → action hidden + 403
- **Preconditions / Seed:** an existing budget (any version status).
- **Steps:**
  1. On budget detail, open "New version" form; leave label and seed empty; submit.
- **Test Data:** label = empty; seedFromVersionUid = empty.
- **Expected Result:** 201; new version created at `maxVersionNo + 1` in DRAFT with no lines; budget reloads.
- **Convention Assertions:** C2 201 `BudgetVersionDto`; C3 MANAGE; C9 append-only (a new version, prior versions untouched).
- **Negative / Edge:** VIEW-only role → 403.

### TC-BUD-022 — Re-plan: seed new version from a prior version (picker by name)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Budget detail re-plan (`POST /api/v1/budgets/uid/{uid}/versions` with `seedFromVersionUid`)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** seed from an APPROVED prior version.
- **Preconditions / Seed:** a budget whose v1 is APPROVED with several lines.
- **Steps:**
  1. On budget detail, open "New version" form.
  2. In the "Seed from version" `<app-uid-picker>`, choose "V1 — …" by its visible label/hint (status).
  3. Submit.
- **Test Data:** seedFromVersionUid chosen via picker (not typed).
- **Expected Result:** 201; new DRAFT version with all lines copied (`copiedLines` from source) — same accounts/periods/amounts/memos; `seededFromVersionId` set.
- **Convention Assertions:** **C1** — version chosen via picker by name ("V1 — label", hint = status), NOT a typed uid; the stored uid is hidden; C2 201; C9 copy is a new version (original unchanged).
- **Negative / Edge:** seedFromVersionUid of a version belonging to a DIFFERENT budget → server filters by budgetId → NotFound (BudgetVersion).

## C. Budget version lines (DIRECT / ANNUAL_SPREAD / SEED)

### TC-BUD-030 — Version detail shows lines (read-only when not DRAFT)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Version detail (`/admin/budget-versions/uid/:uid` · `GET /api/v1/budget-versions/uid/{uid}`)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` (scoped) — ORG_ADMIN
- **Variation:** version = SUBMITTED (non-DRAFT).
- **Preconditions / Seed:** a SUBMITTED version with lines.
- **Steps:**
  1. Navigate to `/admin/budget-versions/uid/:uid`.
  2. Observe the lines table with caption, account code/name, period (P#), amount (TZS), memo, grand total.
  3. Confirm the "Edit Lines" button is absent (not DRAFT) and a read-only note "Recall to DRAFT to edit" is shown.
- **Expected Result:** lines render; no edit affordance; grand total = sum of amounts.
- **Convention Assertions:** C8 amount formatted `1.2-2` (TZS); C6 table caption + `scope="col"` + axe; C4 loading/error/default; C1 account/period shown by code/name + P#, not uid.
- **Negative / Edge:** DRAFT version → "Edit Lines (Replace All)" button present (when MANAGE held).

### TC-BUD-031 — Upsert lines DIRECT mode (pickers for account + period)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Version lines (`PUT /api/v1/budget-versions/uid/{uid}/lines`)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` (scoped) — ORG_ADMIN; CUSTOM VIEW-only → edit hidden + 403
- **Variation:** mode = DIRECT; version = DRAFT.
- **Preconditions / Seed:** DRAFT version; active GL accounts and 12 fiscal periods exist.
- **Steps:**
  1. Open the DRAFT version; click "Edit Lines (Replace All)"; mode defaults to DIRECT.
  2. Add a line; in the Account `<app-uid-picker>` choose an account by NAME (hint = account code); in the Period picker choose "P3 (…)" by label; enter amount; optional memo.
  3. Add a second line for a different period; submit "Replace Lines".
- **Test Data:** account by name; period P3; amount = 12000.50; memo = "Q1 marketing".
- **Expected Result:** 200; version returned with the two lines (amount stored scale 4, HALF_UP); table + grand total update; success alert "N line(s)".
- **Convention Assertions:** **C1** account & period via picker by name/label (no typed uid); C2 200 `BudgetVersionDto`; C8 TZS currency; C6 axe; C3 MANAGE.
- **Negative / Edge:** amount < 0 → ConflictException "amount must be >= 0 (BR-BUD-09)"; account inactive/other-company → "Account not found or inactive"; period not in this FY → "Period not found or not in this budget's fiscal year"; empty lines list (client) → "Add at least one line"; missing account/period/amount on a line (client) → "Each line needs account UID, period UID and amount".

### TC-BUD-032 — Upsert lines ANNUAL_SPREAD mode (even spread, remainder on last)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Version lines (`PUT …/lines` mode=ANNUAL_SPREAD)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** mode = ANNUAL_SPREAD; version = DRAFT; FY has exactly 12 periods.
- **Preconditions / Seed:** DRAFT version; active account; FY with 12 periods.
- **Steps:**
  1. Open Edit Lines; choose mode "Annual spread"; pick account via `<app-uid-picker>`; enter annual amount; submit.
- **Test Data:** annualAmount = 100000 → per-period 8333.3333 ×11, last period absorbs remainder so Σ = 100000.0000 exactly.
- **Expected Result:** 200; 12 lines, one per period; sum equals the annual amount exactly (HALF_UP, scale 4, last = remainder per `BudgetSpreadCalculator`).
- **Convention Assertions:** C8 amounts TZS; C2 200; **C1** account via picker; C6 axe.
- **Negative / Edge:** annualAmount missing OR account missing → "accountUid and annualAmount required for ANNUAL_SPREAD mode"; FY not exactly 12 periods → "Fiscal year must have 12 periods for annual spread"; negative annual → spread calculator rejects (amount >= 0); annual = 0 → all-zero lines allowed.

### TC-BUD-033 — Upsert lines SEED mode (re-seed from another version)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Version lines (`PUT …/lines` mode=SEED)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** mode = SEED; version = DRAFT.
- **Preconditions / Seed:** a DRAFT target version + another version (same budget) with lines.
- **Steps:**
  1. Open Edit Lines; choose mode "Seed from another version"; pick the source via `<app-uid-picker>` (label "V{n} — …", hint = status); submit.
- **Test Data:** seedFromVersionUid chosen via picker.
- **Expected Result:** 200; target version's lines REPLACED wholesale with copies from the source (`reseedLines` deletes then copies); table reflects copied lines.
- **Convention Assertions:** **C1** source version via picker by name; C2 200; C9 replace semantics (wholesale).
- **Negative / Edge:** SEED without seedFromVersionUid → "seedFromVersionUid required for SEED mode"; source version from a different budget → NotFound (BudgetVersion).

### TC-BUD-034 — Edit lines blocked when version not DRAFT
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Version lines (`PUT …/lines`)
- **Permission / Role:** `BUDGETING.BUDGET.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** version status ∈ {SUBMITTED, APPROVED, REJECTED, SUPERSEDED} (illegal-edit matrix).
- **Preconditions / Seed:** versions in each non-DRAFT status.
- **Steps:**
  1. For each non-DRAFT status, attempt `PUT …/lines` (UI hides the button; call the API directly to assert the guard).
- **Expected Result:** ConflictException "Budget version lines are editable only in DRAFT status; current: <status>"; UI never shows the edit button (`canEditLines = canManage && isDraft`).
- **Convention Assertions:** C2 conflict surfaced; C9 locked post-submit.
- **Negative / Edge:** recall a SUBMITTED version back to DRAFT (TC-BUD-042) then editing succeeds again.

### TC-BUD-035 — Edit lines denied without MANAGE
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Version lines (`PUT …/lines`)
- **Permission / Role:** runs as CUSTOM role with `BUDGETING.BUDGET.VIEW` only
- **Preconditions / Seed:** DRAFT version; VIEW-only user.
- **Steps:**
  1. Open the version as VIEW-only; confirm no "Edit Lines" button (`canManage()` false).
  2. Call `PUT …/lines` directly → 403.
- **Expected Result:** UI hides edit; API 403.
- **Convention Assertions:** C3 RBAC by permission code; C2 403.
- **Negative / Edge:** VIEW-only can still see lines read-only.

## D. Version lifecycle transitions

### TC-BUD-040 — Submit DRAFT → SUBMITTED (requires ≥1 line)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Lifecycle submit (`POST /api/v1/budget-versions/uid/{uid}/submit`)
- **Permission / Role:** `BUDGETING.BUDGET.SUBMIT` (scoped) — runs as ORG_ADMIN and as a CUSTOM role with VIEW+SUBMIT; also as VIEW+MANAGE-but-no-SUBMIT → 403
- **Variation:** version = DRAFT with ≥1 line.
- **Preconditions / Seed:** DRAFT version with lines (TC-BUD-031).
- **Steps:**
  1. On budget detail, click Submit on the DRAFT version.
- **Expected Result:** status → SUBMITTED; `submittedAt`/`submittedBy` stamped; success alert; lines now locked. Audit `BUDGET_VERSION_SUBMIT`.
- **Convention Assertions:** C2 `BudgetVersionDto` with SUBMITTED; C3 SUBMIT permission distinct from MANAGE; C9 lock-on-submit.
- **Negative / Edge:** submit a DRAFT with NO lines → ConflictException "must have at least one line before submission (BR-BUD-11)"; submit a non-DRAFT → "Only a DRAFT version can be submitted; current: <status>"; user with MANAGE but not SUBMIT → 403.

### TC-BUD-041 — Submit illegal from non-DRAFT (each status)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Lifecycle submit (`POST …/submit`)
- **Permission / Role:** `BUDGETING.BUDGET.SUBMIT` — ORG_ADMIN
- **Variation:** current status ∈ {SUBMITTED, APPROVED, REJECTED, SUPERSEDED}.
- **Steps:**
  1. For each, call submit.
- **Expected Result:** ConflictException "Only a DRAFT version can be submitted; current: <status>".
- **Convention Assertions:** C2 conflict; illegal-transition coverage.
- **Negative / Edge:** none beyond the four illegal sources.

### TC-BUD-042 — Recall SUBMITTED → DRAFT
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Lifecycle recall (`POST …/recall`)
- **Permission / Role:** `BUDGETING.BUDGET.SUBMIT` (scoped) — ORG_ADMIN / VIEW+SUBMIT
- **Variation:** version = SUBMITTED.
- **Preconditions / Seed:** SUBMITTED version (TC-BUD-040).
- **Steps:**
  1. On budget detail, click Recall on the SUBMITTED version.
- **Expected Result:** status → DRAFT; `submittedAt`/`submittedBy` cleared (null); lines editable again; alert "recalled to Draft". Audit `BUDGET_VERSION_RECALL`.
- **Convention Assertions:** C2 `BudgetVersionDto` DRAFT; C3 SUBMIT permission; C9 re-opens for edit.
- **Negative / Edge:** recall a DRAFT/APPROVED/REJECTED/SUPERSEDED → "Only a SUBMITTED version can be recalled; current: <status>"; recall without SUBMIT permission → 403.

### TC-BUD-043 — Approve SUBMITTED → APPROVED (supersedes prior APPROVED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Lifecycle approve (`POST …/approve`)
- **Permission / Role:** `BUDGETING.BUDGET.APPROVE` (scoped) — runs as ORG_ADMIN and CUSTOM VIEW+APPROVE; also as VIEW+SUBMIT-but-no-APPROVE → 403
- **Variation:** there IS a prior APPROVED version in the same (company, FY, costCentre) scope.
- **Preconditions / Seed:** v1 APPROVED; a new SUBMITTED version (v2) in the same scope.
- **Steps:**
  1. On budget detail, click Approve on v2; optionally enter an approve note; confirm.
- **Test Data:** note = "Approved by finance committee".
- **Expected Result:** v2 → APPROVED (`approvedAt`/`approvedBy`/`decisionReason` set); v1 → SUPERSEDED (`supersededAt` set) in the SAME transaction (PESSIMISTIC_WRITE serialises concurrent approves). Budget reloads to show both. Audit `BUDGET_VERSION_APPROVE` with `supersededVersionUid`.
- **Convention Assertions:** C2 `BudgetVersionDto` APPROVED; C3 APPROVE permission; C9 supersede-not-delete (prior retained as SUPERSEDED for history).
- **Negative / Edge:** approve a non-SUBMITTED → "Only a SUBMITTED version can be approved; current: <status>"; user without APPROVE → 403; note > 500 chars → 400.

### TC-BUD-044 — Approve with no prior approved version (no supersede)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Lifecycle approve (`POST …/approve`)
- **Permission / Role:** `BUDGETING.BUDGET.APPROVE` — ORG_ADMIN
- **Variation:** first-ever approval for the scope (no prior APPROVED).
- **Preconditions / Seed:** a single SUBMITTED version, no prior APPROVED.
- **Steps:**
  1. Approve it (no note).
- **Expected Result:** status → APPROVED; `decisionReason` null (no note); nothing superseded; audit detail empty supersede map.
- **Convention Assertions:** C2 envelope; approve note optional (`required=false` body).
- **Negative / Edge:** approving with empty body still works (request null-safe).

### TC-BUD-045 — Reject SUBMITTED → REJECTED (reason required)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Lifecycle reject (`POST …/reject`)
- **Permission / Role:** `BUDGETING.BUDGET.APPROVE` (scoped) — ORG_ADMIN / VIEW+APPROVE
- **Variation:** version = SUBMITTED.
- **Preconditions / Seed:** SUBMITTED version.
- **Steps:**
  1. On budget detail, click Reject; enter a reason; confirm.
- **Test Data:** reason = "Over budget ceiling; revise opex".
- **Expected Result:** status → REJECTED (`rejectedAt`/`rejectedBy`/`decisionReason`=reason); terminal — re-plan = new version. Audit `BUDGET_VERSION_REJECT` with reason.
- **Convention Assertions:** C2 `BudgetVersionDto` REJECTED; C3 APPROVE permission; C9 terminal (no edit; re-plan via new version).
- **Negative / Edge:** reject with EMPTY reason → client "Rejection reason is required" AND server `@NotBlank` 400; reject a non-SUBMITTED → "Only a SUBMITTED version can be rejected; current: <status>"; reason > 500 → 400; user without APPROVE → 403.

### TC-BUD-046 — Reject/Approve illegal from DRAFT and terminal states
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Lifecycle approve/reject (`POST …/approve`, `…/reject`)
- **Permission / Role:** `BUDGETING.BUDGET.APPROVE` — ORG_ADMIN
- **Variation:** current status ∈ {DRAFT, APPROVED, REJECTED, SUPERSEDED}.
- **Steps:**
  1. For each status, call approve and reject.
- **Expected Result:** ConflictException "Only a SUBMITTED version can be approved/rejected; current: <status>".
- **Convention Assertions:** C2 conflict; exhaustive illegal-transition coverage.
- **Negative / Edge:** confirms terminal states (REJECTED/SUPERSEDED) accept no lifecycle action.

### TC-BUD-047 — Lifecycle buttons gated by status + permission in UI
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Budget detail (`/admin/budgets/uid/:uid`)
- **Permission / Role:** runs as four CUSTOM roles: VIEW-only; VIEW+MANAGE; VIEW+SUBMIT; VIEW+APPROVE
- **Preconditions / Seed:** a budget with one DRAFT and one SUBMITTED version.
- **Steps:**
  1. For each role, open budget detail and inspect which action buttons render per version status.
- **Expected Result:** Submit/Recall appear only with SUBMIT (`canSubmit`); Approve/Reject only with APPROVE (`canApprove`); New version/Edit only with MANAGE (`canManage`); buttons appear only for the legal status (Submit on DRAFT, Recall/Approve/Reject on SUBMITTED).
- **Convention Assertions:** C3 every action gated by its exact permission code; C4 detail has loading/error only (RBAC by guard).
- **Negative / Edge:** VIEW-only sees no lifecycle buttons at all.

## E. Variance report

### TC-BUD-050 — Variance report runs (full year, company-wide)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Variance report (`/admin/budgeting/variance` · `GET /api/v1/budgeting/variance`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION → nav hidden + route blocked; 403 intercept → forbidden state
- **Variation:** fromPeriodNo=1, toPeriodNo=12, costCentre=null (company-wide), accountType=ALL.
- **Preconditions / Seed:** an APPROVED budget + posted GL actuals for the FY.
- **Steps:**
  1. Navigate to `/admin/budgeting/variance`; select company; enter Fiscal Year UID (free-text); leave from=1, to=12; account type "All"; Run.
- **Expected Result:** report renders header + rows (per account [× cost centre]) with budgetAmount, actualAmount, varianceAmount (= actual − budget), variancePct (null when budget=0), plus totals by AccountType; favourable/adverse label derived in UI (INCOME +ve favourable; EXPENSE −ve favourable; ASSET/LIAB/EQUITY neutral).
- **Convention Assertions:** C2 `VarianceReportDto`; C8 money formatted; C6 axe; **C1 NOTE** fiscal year + cost centre are free-text inputs (no picker today) — document gap; C7 scoped to company via `assertCanActIn`.
- **Negative / Edge:** missing company → client "Select a company"; missing FY UID → "Fiscal Year UID is required"; period out of 1–12 or from>to → client "Period range must be 1–12 and from ≤ to" (also server `VarianceQuery` throws IllegalArgumentException); NO-PERMISSION → 403/forbidden.

### TC-BUD-051 — Variance report with no APPROVED budget (noApprovedBudget flag)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Variance report (`GET /api/v1/budgeting/variance`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — ORG_ADMIN
- **Variation:** chosen scope has NO APPROVED version (only DRAFT/SUBMITTED/REJECTED).
- **Preconditions / Seed:** a FY/company with actuals but no APPROVED budget.
- **Steps:**
  1. Run the variance report for that scope.
- **Expected Result:** `header.noApprovedBudget = true`; all budget amounts 0; variance = actual; report is explicit (never silently wrong — BR-BUD-12/16); UI surfaces the "no approved budget" condition.
- **Convention Assertions:** C2 header flag honoured; C4 this is a valid populated state (not "empty"/"error").
- **Negative / Edge:** once a version is APPROVED for the scope, the flag flips to false and budget amounts populate.

### TC-BUD-052 — Variance report filtered by account type (each enum)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Variance report (`GET …/variance?accountType=`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — ORG_ADMIN
- **Variation:** accountType = each of ASSET / LIABILITY / EQUITY / INCOME / EXPENSE, plus ALL.
- **Steps:**
  1. Run the report once per account-type option; verify rows restricted to that type and totals map keyed by type.
- **Expected Result:** rows filtered to the chosen `AccountType`; INCOME/EXPENSE rows show favourable/adverse labels; ASSET/LIAB/EQUITY show neutral.
- **Convention Assertions:** C2 param round-trip; C6 axe.
- **Negative / Edge:** an account type with no data → empty rows for that type (valid).

### TC-BUD-053 — Variance report partial period range + cost-centre scope
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Variance report (`GET …/variance`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — ORG_ADMIN
- **Variation:** fromPeriodNo=4, toPeriodNo=6 (Q2); costCentreValueUid = a specific centre.
- **Preconditions / Seed:** budget + actuals across periods + cost centres; note an "Unallocated" (null cost-centre) bucket exists.
- **Steps:**
  1. Run with from=4,to=6 and a cost-centre UID (entered free-text today).
- **Expected Result:** rows limited to periods 4–6 for the chosen centre; when a centre is chosen, the Unallocated bucket (null cost_centre actuals) shows as a separate row (OQ-BUD-07).
- **Convention Assertions:** C2 envelope; C7 cost-centre scoping; **C1 NOTE** cost centre is free-text input.
- **Negative / Edge:** from>to or out of 1–12 → validation error (client + server).

## F. Departmental actuals report

### TC-BUD-060 — Departmental actuals report runs
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Departmental actuals (`/admin/budgeting/departmental-actuals` · `GET /api/v1/budgeting/departmental-actuals`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — runs as ORG_ADMIN; NO-PERMISSION → nav hidden + route blocked; 403 → forbidden state
- **Variation:** full year (1–12), company.
- **Preconditions / Seed:** posted GL actuals across cost centres + accounts.
- **Steps:**
  1. Navigate to `/admin/budgeting/departmental-actuals`; select company; enter Fiscal Year UID; from=1, to=12; Run.
- **Expected Result:** rows grouped by (cost_centre_value × account) with actualAmount and `fromDate`/`toDate`; null cost-centre = Unallocated bucket; NO budget join (actuals only).
- **Convention Assertions:** C2 `DepartmentalActualsDto`; C8 money + ISO dates; C6 axe; C7 company-scoped via `assertCanActIn`; **C1 NOTE** FY is free-text input.
- **Negative / Edge:** missing company → "Select a company"; missing FY → "Fiscal Year UID is required"; bad period range → "Period range must be 1–12 and from ≤ to".

### TC-BUD-061 — Departmental actuals empty / forbidden / error states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Departmental actuals (`GET …/departmental-actuals`)
- **Permission / Role:** `BUDGETING.REPORT.VIEW` — ORG_ADMIN (positive); NO-PERMISSION (forbidden)
- **Preconditions / Seed:** (a) a FY with no actuals; (b) intercept 500; (c) NO-PERMISSION user.
- **Steps:**
  1. Run for a FY with no postings → empty rows.
  2. Intercept the call to 500 → error state.
  3. As NO-PERMISSION user, 403 → forbidden state.
- **Expected Result:** the three states are distinct (idle-empty vs error vs forbidden).
- **Convention Assertions:** C4 four-state coverage (loading/error/forbidden/idle); C6 axe.
- **Negative / Edge:** partial period range returns only those periods' actuals.

## G. Cross-cutting: tenancy, scoping, audit, identity

### TC-BUD-070 — Cross-company isolation (cannot view another tenant's budget)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** All budgeting reads (`GET /api/v1/budgets…`, version, reports)
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` / `BUDGETING.REPORT.VIEW` — a user scoped to Company A
- **Variation:** target = a budget/version/FY belonging to Company B.
- **Preconditions / Seed:** budgets in two companies; user assigned only to Company A.
- **Steps:**
  1. As the Company-A user, attempt `GET /api/v1/budgets/uid/{B-uid}`, `GET /api/v1/budget-versions/uid/{B-uid}`, and `GET …/variance?companyId={B}`.
- **Expected Result:** `ScopeGuard.assertCanActIn` denies (403/forbidden) for cross-company; resources resolved with mismatched companyId yield NotFound (FY/DimensionValue filtered by company).
- **Convention Assertions:** C7 multi-tenant isolation enforced on every read path (NFR-BUD-01); C3 RBAC.
- **Negative / Edge:** list never returns Company B rows even without an explicit filter.

### TC-BUD-071 — Branch context (X-Branch-Uid) + acting in an unassigned branch
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** All budgeting endpoints
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` — user assigned to specific branch(es)
- **Variation:** user assigned to ONE branch vs acting under a branch they are NOT assigned to.
- **Preconditions / Seed:** multi-branch company; user assigned to branch X only.
- **Steps:**
  1. Call budgeting endpoints with `X-Branch-Uid` = branch X (assigned) → allowed.
  2. Repeat with `X-Branch-Uid` = branch Y (unassigned) → denied.
- **Expected Result:** acting in an unassigned branch is rejected by the scope guard; budgets are company-scoped (not branch-partitioned) but the acting context must still be a branch the user holds.
- **Convention Assertions:** C7 branch scoping; C3 RBAC.
- **Negative / Edge:** switching active branch to an assigned one restores access.

### TC-BUD-072 — Identity convention: uid only in URL, never typed/shown
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All budgeting screens
- **Permission / Role:** `BUDGETING.BUDGET.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a budget with versions + lines.
- **Steps:**
  1. Across list, detail, version-detail: assert no DB numeric id appears in any URL; uids appear only in `/uid/:uid` path segments.
  2. Assert versions are labelled "V{n}" / accounts by code+name / periods by "P{n}" — never raw uid text in tables.
  3. Assert all resource SELECTIONS (account, period, seed-version) go through `<app-uid-picker>` choosing by name.
- **Expected Result:** compliant for selections via pickers; DOCUMENTED EXCEPTIONS = the create-budget fiscal-year field and both report forms (fiscalYear/costCentre) are free-text UID inputs.
- **Convention Assertions:** **C1** (with the two documented free-text deviations explicitly asserted as the current state).
- **Negative / Edge:** n/a.

### TC-BUD-073 — Audit trail recorded for each budgeting action
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Budgeting service audit (`AuditService`)
- **Permission / Role:** ORG_ADMIN (full set)
- **Preconditions / Seed:** ability to inspect audit records.
- **Steps:**
  1. Perform create-budget, create-version, submit, recall, approve, reject; inspect the audit log.
- **Expected Result:** records `BUDGET_CREATE`, `BUDGET_VERSION_CREATE`, `BUDGET_VERSION_SUBMIT`, `BUDGET_VERSION_RECALL`, `BUDGET_VERSION_APPROVE` (with supersededVersionUid when applicable), `BUDGET_VERSION_REJECT` (with reason) — each with entity id + uid.
- **Convention Assertions:** C9 append-only audit; no GL postings created by any budgeting action (D-7).
- **Negative / Edge:** failed/illegal transitions do not produce a state-change audit record.

### TC-BUD-074 — No GL posting from budgeting (financial isolation)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Budgeting create/approve (whole module)
- **Permission / Role:** ORG_ADMIN
- **Preconditions / Seed:** a clean GL; create + approve a budget version.
- **Steps:**
  1. Create budget, add lines, submit, approve; then inspect GL journals.
- **Expected Result:** ZERO GL journals/entries created by any budgeting action (budgets are plan-only — BR-BUD-01 / D-7). GL actuals are read only at report time.
- **Convention Assertions:** C9 financial postings unaffected; budgeting never writes to GL.
- **Negative / Edge:** n/a.
