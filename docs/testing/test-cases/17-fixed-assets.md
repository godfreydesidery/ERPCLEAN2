# Fixed Assets — Test Cases (Domain: FA)

Exhaustive, file-verified test cases for the Fixed Assets domain: asset categories, the
fixed-asset register (register / acquire-from-bill / place-in-service / transfer), depreciation
runs (preview / post), revaluation, disposal & write-off, and the FA→GL reconciliation report.
All endpoints, permission codes, enum values, request fields and frontend routes below were read
directly from the controllers / DTOs / enums / Angular routes named in the file citations.

## Modules / submodules covered

| Submodule | Frontend route | API base path | Controller |
|---|---|---|---|
| Asset categories — list + inline create | `/admin/asset-categories` | `/api/v1/fixed-assets/categories` | `AssetCategoryController` |
| Asset category detail — edit / archive | `/admin/asset-categories/uid/:uid` | `/api/v1/fixed-assets/categories/uid/{uid}` | `AssetCategoryController` |
| Fixed asset register — list + status filter | `/admin/fixed-assets` | `/api/v1/fixed-assets` | `FixedAssetController` |
| Register asset (create / DRAFT) | `/admin/fixed-assets/create` | `POST /api/v1/fixed-assets` | `FixedAssetController` |
| Fixed asset detail — edit, place-in-service, transfer, dispose, write-off, revalue, schedule, revaluations | `/admin/fixed-assets/uid/:uid` | `/api/v1/fixed-assets/uid/{uid}/*` | `FixedAssetController` |
| FA→GL reconciliation report | `/admin/fixed-assets/reconciliation` | `GET /api/v1/fixed-assets/reconciliation` | `FixedAssetController` |
| Depreciation runs — list | `/admin/depreciation-runs` | `GET /api/v1/fixed-assets/depreciation-runs` | `DepreciationRunController` |
| Run depreciation — preview + post | `/admin/depreciation-runs/post` | `POST .../preview`, `POST .../depreciation-runs` | `DepreciationRunController` |
| Depreciation run detail | `/admin/depreciation-runs/uid/:uid` | `GET .../depreciation-runs/uid/{uid}` | `DepreciationRunController` |

**Backend-only (no UI):** `POST /api/v1/fixed-assets/acquire-from-bill` exists on the controller and
in `fixed-assets.service.ts` (`acquireFromBill()`), but there is **no UI component or route** that
calls it (verified: no template/component references it). Cases for it are API-level (Manual/contract).

## Permission codes in scope (exact `@PreAuthorize` codes)

| Code | Guards |
|---|---|
| `FA.CATEGORY.VIEW` | category list (`@perm.has`), category get (`@perm.scoped … 'assetcategory'`) |
| `FA.CATEGORY.MANAGE` | category create, update, archive (`@perm.scoped 'assetcategory'`) |
| `FA.VIEW` | asset list, asset get, schedule, revaluations, reconciliation, depreciation-run list + get |
| `FA.REGISTER.MANAGE` | register, acquire-from-bill, asset update, place-in-service, transfer (`@perm.scoped 'fixedasset'`) |
| `FA.DISPOSE` | dispose, write-off, revalue (`@perm.scoped 'fixedasset'`) |
| `FA.DEPRECIATE` | depreciation preview, depreciation post |

Note: detail-level mutations use `@perm.scoped(#uid,'fixedasset'|'assetcategory',CODE)` — the permission
is checked **and** scope/tenant ownership of the uid is enforced in one annotation.

## Enums (exact values)

- `FixedAssetStatus` = `DRAFT`, `IN_SERVICE`, `DISPOSED`, `WRITTEN_OFF`
- `DepreciationMethod` = `STRAIGHT_LINE`, `REDUCING_BALANCE`
- `DepreciationRunStatus` = `POSTED` (single value; future `DRAFT` is additive)
- `AssetDisposalType` = `SALE`, `WRITE_OFF`
- `RevaluationDirection` = `UP`, `DOWN`
- `MasterStatus` (category) = `ACTIVE`, `INACTIVE`, `ARCHIVED`

### Verified lifecycle / transition rules (from service impls)

- **Asset:** `DRAFT → IN_SERVICE` (place-in-service, requires status==DRAFT). `IN_SERVICE → DISPOSED`
  (dispose/SALE), `IN_SERVICE → WRITTEN_OFF` (write-off). `DISPOSED`/`WRITTEN_OFF` are terminal.
- **Edit (`update`)** of non-financial fields (name/location/assetTag/costCentre) allowed **only while DRAFT**
  (`BR-FA-09`) — else `IllegalArgumentException`.
- **Transfer** allowed in DRAFT or IN_SERVICE; **rejected** for DISPOSED/WRITTEN_OFF.
- **Dispose / write-off** allowed **only from IN_SERVICE** (`BR-FA-03`); a second disposal is rejected
  ("Asset has already been disposed."). Disposal posts any unposted scheduled charges up to the disposal
  date FIRST (final-period depreciation, `BR-FA-10`) then computes NBV and gain/loss = proceeds − NBV.
- **Revalue** allowed only from IN_SERVICE. DOWN revaluation that would push carrying cost below
  accumulated depreciation is rejected. UP increases revaluation reserve. Remaining schedule is regenerated.
- **Depreciation run** is once per `(company, fiscalPeriod)` — a duplicate is rejected (idempotency, `D-4`).
  Posting requires an **OPEN** fiscal period containing `postingDate`, and at least one eligible schedule line.
  Status is always `POSTED`.

## Type / role variations exercised

| Dimension | Values exercised |
|---|---|
| User roles (allowed) | `rootadmin` (superuser bypass), `ACCOUNTANT` (assumed FA-permitted finance role), `ORG_ADMIN`, a CUSTOM role granted a single FA code |
| User roles (denied) | NO-PERMISSION user; SALES_REP / STOREKEEPER (no FA codes); CUSTOM role missing the specific code |
| `DepreciationMethod` | `STRAIGHT_LINE` (no reducingRate), `REDUCING_BALANCE` (reducingRate required) |
| `FixedAssetStatus` | every state + every legal & illegal transition |
| `AssetDisposalType` | `SALE` (dispose, proceeds ≥ 0), `WRITE_OFF` (proceeds = 0, loss = full NBV) |
| `RevaluationDirection` | `UP`, `DOWN` (incl. invalid DOWN below accum-dep) |
| `MasterStatus` (category) | `ACTIVE` (usable), `ARCHIVED` (soft-deleted, hidden from create pickers) |
| Branch / company | default vs non-default branch; single- vs multi-branch company; multi-company isolation; user acting in a non-assigned branch/company (denied) |

> **C1 caveat (verified, flag for product):** The **Asset-Category create form** accepts **GL account IDs**
> (`assetAccountId`, `accumDepAccountId`, `depExpenseAccountId`) as hand-typed **numeric IDs**, not pickers
> (verified `asset-category-list.component.ts`: `newAssetAccountId` etc.). The **Register-Asset screen** selects
> **Category** via a `<select>` of human names and chooses **Branch** via `<app-uid-picker>` (by name, uid resolved
> to numeric `branchId` under the hood) — compliant — but takes **costCentreId** as a hand-typed numeric ID
> (verified `fixed-asset-create.component.ts`: `fCostCentreId`). The **Transfer form** (asset detail) takes
> `branchId`/`costCentreId` as typed numbers, not pickers (verified `fixed-asset-detail.component.ts`:
> `fTransferBranchId`/`fTransferCostCentreId`). Cases below assert the compliant picker where one exists and
> **flag the typed-ID fields as a C1 deviation** (negative/defect cases NEG where applicable).

---

## ASSET CATEGORIES

### TC-FA-001 — Category list loads, company-scoped, four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset Categories (`/admin/asset-categories` · `GET /api/v1/fixed-assets/categories`)
- **Permission / Role:** `FA.CATEGORY.VIEW` — runs as `ACCOUNTANT`; also as NO-PERMISSION user → nav item hidden + route guard blocks (forbidden)
- **Preconditions / Seed:** Company A has ≥1 category (`MACH` "Machinery"); a NO-PERMISSION user exists.
- **Steps:**
  1. Log in as `ACCOUNTANT`; navigate to `/admin/asset-categories`.
  2. Observe loading state, then the populated table (code, name, method, life, status badge).
  3. Switch the company selector to a company with no categories → observe empty state.
  4. (Forbidden) Log in as NO-PERMISSION user; confirm the "Asset Categories" nav item is hidden and direct navigation to the route is blocked.
- **Test Data:** Company A "ACME", category `MACH` / "Machinery" / STRAIGHT_LINE / 60.
- **Expected Result:** Envelope `ApiResponse<List<AssetCategoryDto>>` (non-paginated; no PageMeta). Populated rows show name/code; empty company shows the empty state; NO-PERMISSION user is forbidden.
- **Convention Assertions:** C2 envelope; C3 RBAC (`FA.CATEGORY.VIEW`); C4 loading/empty/error/forbidden; C6 axe scan; C7 only company-scoped categories shown.
- **Negative / Edge:** Company with no categories → empty (not error). API 403 for missing permission.

### TC-FA-002 — Create category, STRAIGHT_LINE (reducingRate omitted)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset Categories (`/admin/asset-categories` · `POST /api/v1/fixed-assets/categories`)
- **Permission / Role:** `FA.CATEGORY.MANAGE` — runs as `ACCOUNTANT`; also as a `FA.CATEGORY.VIEW`-only CUSTOM role → create button hidden + API 403
- **Variation:** method = `STRAIGHT_LINE`
- **Preconditions / Seed:** Company A; GL accounts exist (asset, accum-dep, dep-expense) — note their numeric IDs.
- **Steps:**
  1. As `ACCOUNTANT`, open `/admin/asset-categories`; click "New Category".
  2. Enter code `FURN`, name "Furniture", method = Straight Line, life periods = 60.
  3. Enter asset account ID, accumulated-dep account ID, dep-expense account ID (the seeded numeric IDs).
  4. Submit.
- **Test Data:** `{ code:"FURN", name:"Furniture", defaultMethod:"STRAIGHT_LINE", defaultLifePeriods:60, assetAccountId:<id>, accumDepAccountId:<id>, depExpenseAccountId:<id> }`
- **Expected Result:** 201 CREATED, `AssetCategoryDto` with `status=ACTIVE`, success toast, list refreshes showing "Furniture". `defaultReducingRate` null.
- **Convention Assertions:** C2 envelope; C3 RBAC (create hidden for view-only); C8 numbers as strings; C9 created as ACTIVE master.
- **Negative / Edge:** **C1 deviation** — assert account IDs are typed numeric IDs (not name-pickers): record as a known C1 gap. Missing required field (no code/name/accounts) → inline validation, no API call.

### TC-FA-003 — Create category, REDUCING_BALANCE requires reducingRate
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset Categories (`/admin/asset-categories` · `POST .../categories`)
- **Permission / Role:** `FA.CATEGORY.MANAGE` — runs as `ACCOUNTANT`
- **Variation:** method = `REDUCING_BALANCE`
- **Preconditions / Seed:** Company A; GL account IDs known.
- **Steps:**
  1. Open create form; choose method = Reducing Balance.
  2. Leave reducing rate blank → submit → expect inline error "Reducing rate is required for Reducing Balance method."
  3. Enter reducing rate `0.25`; submit.
- **Test Data:** `{ code:"VEH", name:"Vehicles", defaultMethod:"REDUCING_BALANCE", defaultLifePeriods:48, defaultReducingRate:"0.25", …accountIds }`
- **Expected Result:** First submit blocked client-side; second creates 201 with `defaultMethod=REDUCING_BALANCE`, `defaultReducingRate=0.25`.
- **Convention Assertions:** C2; C3; C4 (validation as a sub-state); C8.
- **Negative / Edge:** reducingRate blank rejected (client). Method-conditional required field.

### TC-FA-004 — Duplicate category code rejected (409 Conflict)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Asset Categories (`POST .../categories`)
- **Permission / Role:** `FA.CATEGORY.MANAGE` — `ACCOUNTANT`
- **Preconditions / Seed:** Category `MACH` already exists in Company A.
- **Steps:** Create another category with code `MACH` in the same company → submit.
- **Test Data:** `{ code:"MACH", name:"Machinery 2", … }`
- **Expected Result:** Backend `ConflictException` → HTTP 409, envelope `errors` contains "Asset category code 'MACH' already exists in this company."; the message surfaces in the form error.
- **Convention Assertions:** C2 envelope errors[]; C3.
- **Negative / Edge:** Same code in a **different** company must succeed (code is unique per company, verified `findByCompanyIdAndCode`).

### TC-FA-005 — Edit category (update accounts/method/life)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Category detail (`/admin/asset-categories/uid/:uid` · `PUT .../categories/uid/{uid}`)
- **Permission / Role:** `FA.CATEGORY.MANAGE` — `ACCOUNTANT`; also `FA.CATEGORY.VIEW`-only → edit blocked
- **Preconditions / Seed:** Category `FURN` exists.
- **Steps:**
  1. From the list, open `FURN`'s detail (navigate by route `/admin/asset-categories/uid/:uid` — uid only in URL).
  2. Change name to "Office Furniture", life periods 84; save.
- **Test Data:** `{ name:"Office Furniture", defaultMethod:"STRAIGHT_LINE", defaultLifePeriods:84, …accountIds }`
- **Expected Result:** `AssetCategoryDto` reflects the new values; note `code` is **not** editable (UpdateAssetCategoryRequest has no `code`).
- **Convention Assertions:** C1 uid only in URL, not shown in body labels as an entity reference; C2; C3.
- **Negative / Edge:** Attempt to change code → no field exists (verify). Blank name → 400.

### TC-FA-006 — Archive category (soft-delete) and absence from create picker
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Category detail (`DELETE .../categories/uid/{uid}`)
- **Permission / Role:** `FA.CATEGORY.MANAGE` — `ACCOUNTANT`
- **Preconditions / Seed:** An unused category `OLD`.
- **Steps:**
  1. Open `OLD` detail; archive it.
  2. Re-open list — `OLD` shows status badge `ARCHIVED`.
  3. Go to `/admin/fixed-assets/create` — confirm `OLD` is **not** offered in the Category dropdown (create filters `status==='ACTIVE'`).
- **Test Data:** category `OLD`.
- **Expected Result:** `archive()` returns the DTO with `status=ARCHIVED` (soft-delete, not hard-delete); register screen excludes archived categories.
- **Convention Assertions:** C9 soft-delete (MasterStatus.ARCHIVED, not removed); C3; C7.
- **Negative / Edge:** Archived category still visible in the **list** (status badge), only hidden from the create picker.

### TC-FA-007 — Category cross-tenant isolation
- **Type:** Automated (Playwright) + Manual (API)
- **Priority:** P1
- **Module / Submodule:** Asset Categories (`GET .../categories`, `…/uid/{uid}`)
- **Permission / Role:** `FA.CATEGORY.VIEW` — user belongs to Company A only
- **Preconditions / Seed:** Company A and Company B each have a category.
- **Steps:**
  1. As a Company-A user, list categories → only Company A's appear.
  2. (API) Call `GET /api/v1/fixed-assets/categories/uid/{B-category-uid}` → expect scope rejection.
- **Expected Result:** `scopeGuard.assertCanActIn` / `@perm.scoped(...,'assetcategory',...)` denies Company B's category; no leakage.
- **Convention Assertions:** C7 multi-tenancy; C1 uid only in URL; C3.
- **Negative / Edge:** Spoofing `companyId=B` as a Company-A user → denied.

---

## FIXED ASSET REGISTER

### TC-FA-010 — Asset register list: pagination + status filter + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Fixed asset list (`/admin/fixed-assets` · `GET /api/v1/fixed-assets`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`; also NO-PERMISSION → forbidden state (`err.status===403`)
- **Preconditions / Seed:** Company A has > 20 assets across DRAFT/IN_SERVICE/DISPOSED/WRITTEN_OFF.
- **Steps:**
  1. As `ACCOUNTANT`, open `/admin/fixed-assets`; observe loading→idle with first 20 rows.
  2. Use the shared `<app-paginator>`: FIRST/PREVIOUS/page-numbers/NEXT/LAST navigate (size=20).
  3. Set status filter = "In Service" → list reloads to page 0 with only IN_SERVICE.
  4. Set filter = "Disposed", "Written Off", "Draft" in turn; verify each.
  5. Force a 403 (NO-PERMISSION user) → assert the dedicated **forbidden** state (distinct from generic error).
- **Test Data:** mixed-status seed; filter values map to `status` query param `IN_SERVICE` etc.
- **Expected Result:** Envelope keeps `meta {page,size,totalElements,totalPages,hasNext}` (list uses SKIP_UNWRAP). Filter sends `?status=`; paginator hides itself when 1 page.
- **Convention Assertions:** C2 meta retained; C3; C4 loading/empty/error/**forbidden** all distinct; C5 paginator FIRST/PREV/pages/NEXT/LAST; C6 axe; C7 company-scoped.
- **Negative / Edge:** Empty result for a filter (e.g. no WRITTEN_OFF) → empty state. 403 → forbidden state. `companyId` of another tenant → scope denied.

### TC-FA-011 — Register asset (DRAFT), STRAIGHT_LINE, branch via picker
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Register asset (`/admin/fixed-assets/create` · `POST /api/v1/fixed-assets`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`; also `FA.VIEW`-only → "Register Asset" nav hidden + route guard blocks + API 403
- **Variation:** method = `STRAIGHT_LINE`; branch = default; ProductType context N/A
- **Preconditions / Seed:** Company A; ACTIVE category `MACH`; ≥1 ACTIVE branch.
- **Steps:**
  1. As `ACCOUNTANT`, open `/admin/fixed-assets/create`.
  2. Select company; select Category "Machinery" from the dropdown (by name).
  3. Choose **Branch** via `<app-uid-picker>` by branch **name** (uid stored under the hood; the form converts the picked branch uid → numeric `branchId`).
  4. Enter name "Lathe #1", acquisition cost `5,000,000`, salvage `500,000`, method Straight Line, life periods 60, acquisition date `2026-01-15`, depreciation start `2026-02-01`.
  5. Submit.
- **Test Data:** `{ companyId, branchId(derived from picker), categoryId, name:"Lathe #1", acquisitionCost:"5000000", salvageValue:"500000", depreciationMethod:"STRAIGHT_LINE", lifePeriods:60, acquisitionDate:"2026-01-15", depreciationStartDate:"2026-02-01" }`
- **Expected Result:** 201 CREATED, asset created with `status=DRAFT`, an `assetNumber` generated, navigates to `/admin/fixed-assets/uid/:uid`. No GL posting yet (capitalisation happens at place-in-service).
- **Convention Assertions:** **C1 branch chosen via picker by name** — assert no raw uid is typed and none visible on the create screen; C2; C3 (button/nav hidden for view-only); C6 axe; C8 money as strings.
- **Negative / Edge:** **C1 deviation** — costCentreId is a typed numeric field (flag). Missing required fields (category/branch/name/cost/life/dates) → inline errors, no API call. branchId fails to resolve from picker → "Could not resolve branch ID" guard.

### TC-FA-012 — Register asset, REDUCING_BALANCE requires reducingRate
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Register asset (`POST /api/v1/fixed-assets`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`
- **Variation:** method = `REDUCING_BALANCE`
- **Steps:**
  1. Open create; pick method = Reducing Balance; leave reducing rate blank → submit → inline error "Reducing rate is required for Reducing Balance method."
  2. Enter reducing rate `0.20`; submit.
- **Test Data:** `{ …, depreciationMethod:"REDUCING_BALANCE", reducingRate:"0.20", lifePeriods:48 }`
- **Expected Result:** First blocked client-side; second creates DRAFT with `reducingRate=0.20`.
- **Convention Assertions:** C2; C3; C4 validation; C8.
- **Negative / Edge:** `lifePeriods` 0 or blank → "Life periods must be at least 1" (matches `@Min(1)`). Non-numeric acquisition cost → blocked.

### TC-FA-013 — Required-field validation on register (server `@NotNull`/`@NotBlank`/`@Min`)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Register asset (`POST /api/v1/fixed-assets`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`
- **Preconditions / Seed:** none.
- **Steps:** (API) POST with each of `companyId`, `branchId`, `categoryId`, `name`, `acquisitionCost`, `depreciationMethod`, `acquisitionDate`, `depreciationStartDate` omitted in turn; and `lifePeriods=0`.
- **Test Data:** systematic omission per field.
- **Expected Result:** HTTP 400 with validation `errors`. `name` blank → `@NotBlank`; `lifePeriods<1` → `@Min(1)`.
- **Convention Assertions:** C2 errors[]; C8 dates ISO `yyyy-MM-dd`.
- **Negative / Edge:** category belonging to a different company → `IllegalArgumentException` "Category does not belong to the specified company." (verified `requireCategory`).

### TC-FA-014 — Edit non-financial fields allowed only in DRAFT (BR-FA-09)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset detail edit (`/admin/fixed-assets/uid/:uid` · `PUT .../uid/{uid}`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`; also `FA.VIEW`-only → edit controls hidden + API 403
- **Variation:** status = DRAFT (allowed) vs IN_SERVICE (rejected)
- **Preconditions / Seed:** one DRAFT asset, one IN_SERVICE asset.
- **Steps:**
  1. Open the DRAFT asset detail; edit name → "Lathe #1 (rev)", location, asset tag; save → success.
  2. Open the IN_SERVICE asset; attempt the same edit (via API if UI hides it).
- **Test Data:** `{ name:"Lathe #1 (rev)", location:"Bay 3", assetTag:"TAG-001" }`
- **Expected Result:** DRAFT edit succeeds. IN_SERVICE edit → `IllegalArgumentException` "Non-financial fields can only be updated while DRAFT (BR-FA-09)." (HTTP 400 in envelope errors).
- **Convention Assertions:** C1 uid only in URL; C2; C3; C9 (financial fields never edited — only name/location/tag/costCentre).
- **Negative / Edge:** blank name → `@NotBlank` 400. Edit on DISPOSED/WRITTEN_OFF → same DRAFT-only rejection.

### TC-FA-015 — Asset detail screen does not expose raw uid; shows assetNumber
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Asset detail (`/admin/fixed-assets/uid/:uid`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`
- **Preconditions / Seed:** one asset.
- **Steps:** Open the detail; inspect the header and the "Asset Number" field; scan all visible labels.
- **Expected Result:** The human-readable `assetNumber` is shown (monospace); the machine `uid` appears only in the URL, never as on-screen text/label.
- **Convention Assertions:** **C1** uid not shown anywhere on screen; assetNumber is the human identifier.
- **Negative / Edge:** Confirm child tables (schedule, revaluations) track rows by `uid` internally but display human columns (period seq, dates, amounts) — no uid column rendered.

---

## PLACE IN SERVICE

### TC-FA-020 — Place DRAFT asset in service (DRAFT → IN_SERVICE, capitalisation GL)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset detail (`POST .../uid/{uid}/place-in-service`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`; also `FA.VIEW`-only → action hidden + API 403
- **Variation:** status DRAFT → IN_SERVICE
- **Preconditions / Seed:** a DRAFT asset under category with valid GL accounts; an OPEN fiscal period covering postingDate.
- **Steps:**
  1. Open the DRAFT asset; click "Place in Service"; enter posting date `2026-02-01`; confirm.
  2. Observe status flips to IN_SERVICE; schedule + revaluations sections load.
- **Test Data:** `{ postingDate:"2026-02-01" }`
- **Expected Result:** `status=IN_SERVICE`, `capitalisedGlEntryUid` populated, a depreciation schedule generated (visible in the Schedule child table). Success toast with assetNumber.
- **Convention Assertions:** C1; C2; C3; C8 dates ISO; C9 (GL posting append-only — capitalisation journal).
- **Negative / Edge:** missing posting date → inline "Posting date is required." Posting into a closed/non-existent period → period-gate error from GL.

### TC-FA-021 — Place-in-service rejected when not DRAFT (illegal transition)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Asset detail (`POST .../uid/{uid}/place-in-service`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`
- **Variation:** status = IN_SERVICE / DISPOSED / WRITTEN_OFF
- **Preconditions / Seed:** assets in each non-DRAFT state.
- **Steps:** (API) POST place-in-service on an IN_SERVICE asset; repeat for DISPOSED and WRITTEN_OFF.
- **Expected Result:** `IllegalArgumentException` "Asset must be DRAFT to place in service." (HTTP 400). No status change.
- **Convention Assertions:** C2 errors[]; C3.
- **Negative / Edge:** Each non-DRAFT origin must be rejected (illegal transition per state).

---

## TRANSFER (between branches/locations)

### TC-FA-022 — Transfer asset to a non-default branch via picker (no GL effect)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset detail transfer (`POST .../uid/{uid}/transfer`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`; also `FA.VIEW`-only → transfer hidden + API 403
- **Variation:** multi-branch company; target = non-default branch; status IN_SERVICE (no status change)
- **Preconditions / Seed:** an IN_SERVICE asset in default branch; a second (non-default) ACTIVE branch.
- **Steps:**
  1. Open the asset; open the Transfer form.
  2. Enter the target branch and (optionally) new location/cost-centre; confirm.
  3. Verify the asset's branch/location updated; status unchanged (still IN_SERVICE).
- **Test Data:** `{ branchId:<non-default>, location:"Branch-2 Store", costCentreId:<id|null> }`
- **Expected Result:** `TransferAssetRequest` applied; only provided fields change (`if (req.branchId()!=null)…`). **No GL journal** (verified comment "no GL effect"). Status preserved.
- **Convention Assertions:** C3; C7 branch scoping; C8.
- **Negative / Edge:** **C1 deviation** — transfer form takes branchId/costCentreId as **typed numeric IDs**, not pickers (flag vs the create screen which uses a branch picker). All-null transfer body is a no-op (no field changes).

### TC-FA-023 — Transfer rejected for disposed/written-off asset
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Asset detail transfer (`POST .../uid/{uid}/transfer`)
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`
- **Variation:** status = DISPOSED / WRITTEN_OFF
- **Preconditions / Seed:** a DISPOSED and a WRITTEN_OFF asset.
- **Steps:** (API) POST transfer on each → expect rejection.
- **Expected Result:** `IllegalArgumentException` "Cannot transfer a disposed or written-off asset." (HTTP 400).
- **Convention Assertions:** C2; C3.
- **Negative / Edge:** Transfer is allowed in DRAFT (positive control — confirm DRAFT transfer succeeds without status change).

### TC-FA-024 — User acting in a non-assigned branch/company is denied (scope guard)
- **Type:** Manual (API) + Automated
- **Priority:** P1
- **Module / Submodule:** Asset endpoints (scope-guarded throughout)
- **Permission / Role:** `FA.REGISTER.MANAGE` held, but user NOT assigned to the target company/branch
- **Preconditions / Seed:** user assigned to Company A / Branch 1 only; an asset in Company B.
- **Steps:**
  1. Set X-Branch-Uid to a branch the user is not assigned to; attempt list/get/transfer.
  2. Attempt `GET /api/v1/fixed-assets/uid/{B-asset-uid}`.
- **Expected Result:** `@perm.scoped(...,'fixedasset',...)` and `scopeGuard.assertCanActIn` deny → 403; cross-tenant asset not returned.
- **Convention Assertions:** C7 multi-tenancy/branch scoping; C3.
- **Negative / Edge:** Even with the correct permission code, wrong-branch/company access is denied.

---

## ACQUIRE FROM BILL (backend-only — no UI)

### TC-FA-030 — Acquire asset from a MATCHED AP bill line (capitalise + auto place-in-service)
- **Type:** Manual (API / contract)
- **Priority:** P2
- **Module / Submodule:** `POST /api/v1/fixed-assets/acquire-from-bill` (**no frontend route/component**; service method `acquireFromBill()` exists but is not wired to any UI)
- **Permission / Role:** `FA.REGISTER.MANAGE` — runs as `ACCOUNTANT`/`PURCHASE_OFFICER` with the code; denied for users lacking it (403)
- **Variation:** bill status = `MATCHED` (required)
- **Preconditions / Seed:** a Supplier Bill in status `MATCHED` for Company A with at least one line (note bill uid + line uid).
- **Steps:**
  1. POST `acquire-from-bill` with the bill uid, bill line uid, category, name, method, life, dates.
- **Test Data:** `{ companyId, branchId, billUid, billLineUid, categoryId, name:"Server Rack", depreciationMethod:"STRAIGHT_LINE", lifePeriods:36, acquisitionDate, depreciationStartDate }`
- **Expected Result:** 201 CREATED. Acquisition cost = bill **line net amount** (VAT excluded, `BR-FA-07`). Asset is **placed in service in the same transaction** → returns `status=IN_SERVICE`, with `supplierId`, `sourceBillUid` set and a capitalisation GL entry.
- **Convention Assertions:** C2; C3; C7 (bill must belong to the same company — verified). Document that **no UI exists** for this path.
- **Negative / Edge:** bill not `MATCHED` → "Bill must be MATCHED to capitalise an asset from it." Bill company ≠ request company → "Bill does not belong to the specified company." Unknown bill line uid → `NotFoundException`.

---

## DEPRECIATION RUN (preview / post)

### TC-FA-040 — Preview depreciation for a period (read-only, nothing posted)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Run depreciation (`/admin/depreciation-runs/post` · `POST .../depreciation-runs/preview`)
- **Permission / Role:** `FA.DEPRECIATE` — `ACCOUNTANT`; also `FA.VIEW`-only → "Run Depreciation" nav hidden + route guard blocks + API 403
- **Preconditions / Seed:** Company A with IN_SERVICE assets that have an eligible schedule line for the chosen open fiscal period.
- **Steps:**
  1. As `ACCOUNTANT`, open `/admin/depreciation-runs/post`.
  2. Select company; enter the fiscal period UID; click Preview.
  3. Review the preview table (asset number, name, period seq, planned charge) + totals (`assetCount`, `totalChargeAmount`).
- **Test Data:** `companyId`, `fiscalPeriodUid` (an OPEN period).
- **Expected Result:** `DepreciationRunPreviewDto` returned; **nothing persisted** (no run created, schedule lines untouched). Totals match the sum of preview lines.
- **Convention Assertions:** C2; C3 (preview gated by `FA.DEPRECIATE`, not `FA.VIEW`); C6 axe; C8 amounts as strings.
- **Negative / Edge:** unknown `fiscalPeriodUid` → `NotFound(FiscalPeriod)`. No eligible assets → empty preview (assetCount 0, total 0).

### TC-FA-041 — Post depreciation run (creates POSTED run, one GL journal)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Run depreciation (`/admin/depreciation-runs/post` · `POST .../depreciation-runs`)
- **Permission / Role:** `FA.DEPRECIATE` — `ACCOUNTANT`; also `FA.VIEW`-only → forbidden
- **Preconditions / Seed:** as TC-FA-040, fiscal period OPEN and containing the posting date; no prior run for that (company, period).
- **Steps:**
  1. After previewing, enter posting date; click Post.
  2. Observe success toast with `runNumber`; navigate to the run detail.
- **Test Data:** `{ companyId, fiscalPeriodUid, postingDate:"2026-02-28" }`
- **Expected Result:** 201 CREATED `DepreciationRunDto` with `status=POSTED`, `runNumber`, `totalChargeAmount`, `assetCount`, `glEntryUid`, `lines[]`. Each eligible schedule line marked posted; each asset's `accumulatedDepreciation` increased; a `DEPRECIATION.RUN.EXECUTED` event emitted.
- **Convention Assertions:** C2; C3; C8 money strings, ISO dates; C9 append-only GL.
- **Negative / Edge:** posting date in a closed period → period-gate error. No eligible assets → "No eligible assets found for depreciation run in period …" (HTTP 400/409).

### TC-FA-042 — Duplicate run for same (company, period) rejected (idempotency D-4)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Run depreciation (`POST .../depreciation-runs`)
- **Permission / Role:** `FA.DEPRECIATE` — `ACCOUNTANT`
- **Preconditions / Seed:** a run already POSTED for Company A, period P.
- **Steps:** Attempt to post depreciation again for the same company + period P.
- **Expected Result:** `IllegalStateException` "Depreciation run already posted for company=… period=… (run=…). Duplicate runs are not allowed (ADR-0030 D-4)." (HTTP 409). No second run, no double GL.
- **Convention Assertions:** C2 errors[]; C3.
- **Negative / Edge:** A different period for the same company must succeed (control).

### TC-FA-043 — Depreciation run list (paginated) + four states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Depreciation runs list (`/admin/depreciation-runs` · `GET .../depreciation-runs`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** Company A with > 20 runs (or seed enough to page).
- **Steps:**
  1. Open `/admin/depreciation-runs`; observe rows (runNumber, posting date, status POSTED, total charge, asset count).
  2. Exercise the paginator (FIRST/PREV/pages/NEXT/LAST).
- **Expected Result:** Envelope retains `meta`; list company-scoped. All runs show `status=POSTED`.
- **Convention Assertions:** C2 meta; C4 four states; C5 paginator; C6 axe; C7.
- **Negative / Edge:** company with no runs → empty state.

### TC-FA-044 — Depreciation run detail shows lines (per-asset charge/accum/NBV)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Run detail (`/admin/depreciation-runs/uid/:uid` · `GET .../depreciation-runs/uid/{uid}`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`
- **Preconditions / Seed:** a posted run with multiple lines.
- **Steps:** From the list, open a run detail; review header (runNumber, totals, glEntryUid) and the lines table (`chargeAmount`, `accumDepAfter`, `nbvAfter`).
- **Expected Result:** `DepreciationRunDto` with `lines[]` matching `DepreciationRunLineDto`.
- **Convention Assertions:** C1 uid only in URL (runNumber shown to user); C2; C3; C8.
- **Negative / Edge:** unknown uid → `NotFound(DepreciationRun)`; cross-tenant uid → scope denied.

---

## REVALUATION

### TC-FA-050 — Revalue UP (increases carrying cost + revaluation reserve, regenerates schedule)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset detail revalue (`POST .../uid/{uid}/revalue`)
- **Permission / Role:** `FA.DISPOSE` — runs as the role holding `FA.DISPOSE` (e.g. `ACCOUNTANT`); also a user lacking `FA.DISPOSE` → action hidden + API 403
- **Variation:** direction = `UP`; status IN_SERVICE
- **Preconditions / Seed:** an IN_SERVICE asset; OPEN fiscal period for the revaluation date.
- **Steps:**
  1. Open the asset; open the Revalue form; direction = UP; delta `1,000,000`; date `2026-03-31`; reason "market appraisal"; confirm.
  2. Observe carrying cost increases by delta; revaluation reserve increases; schedule regenerated; revaluation appears in the Revaluations child table.
- **Test Data:** `{ direction:"UP", deltaAmount:"1000000", revaluationDate:"2026-03-31", reason:"market appraisal" }`
- **Expected Result:** `AssetRevaluationDto` with `direction=UP`, `carryingAfter = carryingBefore + delta`; `revaluationReserveBalance` increased on the asset; a revaluation GL journal posted; remaining schedule regenerated.
- **Convention Assertions:** C1 (revalue action gated by permission, not visible to others); C2; C3; C8 money strings.
- **Negative / Edge:** delta ≤ 0 or non-numeric → inline "Delta amount must be a positive number." (also `@Positive` server-side). Missing date → inline error.

### TC-FA-051 — Revalue DOWN valid (carrying stays ≥ accumulated depreciation)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Asset detail revalue (`POST .../uid/{uid}/revalue`)
- **Permission / Role:** `FA.DISPOSE` — holder role
- **Variation:** direction = `DOWN` (within bounds)
- **Preconditions / Seed:** IN_SERVICE asset with carrying cost comfortably above accumulated depreciation.
- **Steps:** Revalue DOWN by a delta small enough that carryingAfter ≥ accumulatedDepreciation; confirm.
- **Test Data:** `{ direction:"DOWN", deltaAmount:"200000", revaluationDate:"2026-03-31" }`
- **Expected Result:** `carryingAfter = carryingBefore − delta`; success; schedule regenerated.
- **Convention Assertions:** C2; C3; C8.
- **Negative / Edge:** see TC-FA-052 for the invalid DOWN boundary.

### TC-FA-052 — Revalue DOWN below accumulated depreciation rejected (boundary)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Asset detail revalue (`POST .../uid/{uid}/revalue`)
- **Permission / Role:** `FA.DISPOSE` — holder role
- **Variation:** direction = `DOWN` (delta too large)
- **Preconditions / Seed:** IN_SERVICE asset with known carrying cost and accumulated depreciation.
- **Steps:** (API) Revalue DOWN by a delta such that `carryingAfter < accumulatedDepreciation`.
- **Expected Result:** `IllegalArgumentException` "Down-revaluation would reduce carrying cost below accumulated depreciation." (HTTP 400). No change.
- **Convention Assertions:** C2 errors[]; C3.
- **Negative / Edge:** delta exactly to the boundary (carryingAfter == accumDep) is allowed (only strictly-below is rejected).

### TC-FA-053 — Revalue rejected when not IN_SERVICE (illegal state)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Asset detail revalue (`POST .../uid/{uid}/revalue`)
- **Permission / Role:** `FA.DISPOSE` — holder role
- **Variation:** status = DRAFT / DISPOSED / WRITTEN_OFF
- **Steps:** (API) Revalue a DRAFT asset, a DISPOSED asset, a WRITTEN_OFF asset.
- **Expected Result:** `IllegalStateException` "Only IN_SERVICE assets can be revalued." for each (HTTP 409/400).
- **Convention Assertions:** C2; C3.
- **Negative / Edge:** every non-IN_SERVICE origin rejected.

### TC-FA-054 — Revaluation history child list on the asset detail
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Asset detail revaluations (`GET .../uid/{uid}/revaluations`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`
- **Preconditions / Seed:** an IN_SERVICE asset with ≥1 revaluation.
- **Steps:** Open the asset detail; the Revaluations section loads (only when IN_SERVICE) showing direction, delta, carrying before/after, date.
- **Expected Result:** ordered by revaluation date asc; rows tracked by uid internally; no uid column shown.
- **Convention Assertions:** C1 uid not displayed; C2; C4 (loading/empty/error for the child list).
- **Negative / Edge:** asset with no revaluations → empty child state; section is not loaded for DRAFT assets (verified: only loaded when `status==='IN_SERVICE'`).

---

## DISPOSAL & WRITE-OFF

### TC-FA-060 — Dispose by SALE (IN_SERVICE → DISPOSED, gain/loss = proceeds − NBV)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset detail dispose (`POST .../uid/{uid}/dispose`)
- **Permission / Role:** `FA.DISPOSE` — holder role; also a user lacking `FA.DISPOSE` → action hidden + API 403
- **Variation:** disposalType = `SALE`; status IN_SERVICE → DISPOSED
- **Preconditions / Seed:** IN_SERVICE asset with a known NBV; OPEN fiscal period for the disposal date.
- **Steps:**
  1. Open the asset; open the Dispose form; disposal date `2026-04-30`; proceeds `3,000,000`; reason "sold to vendor"; confirm.
  2. Observe success; asset reloads as DISPOSED; `disposedAt` set.
- **Test Data:** `{ disposalDate:"2026-04-30", proceedsAmount:"3000000", reason:"sold to vendor" }`
- **Expected Result:** `AssetDisposalDto` with `disposalType=SALE`, `nbvAtDisposal`, `gainLossAmount = proceeds − NBV`. **Final-period depreciation** is posted first (any unposted schedule lines up to the disposal date are charged, accumulatedDepreciation updated) before NBV/gain-loss is computed (`BR-FA-10`). Disposal GL journal posted; status → DISPOSED.
- **Convention Assertions:** C1 uid only in URL; C2; C3; C8 money strings; C9 append-only GL.
- **Negative / Edge:** proceeds blank/non-numeric → inline "Proceeds amount is required (enter 0 for none)." Negative proceeds → server `@PositiveOrZero` 400. Disposal into closed period → period-gate error.

### TC-FA-061 — Dispose with zero proceeds (full loss = NBV)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Asset detail dispose (`POST .../uid/{uid}/dispose`)
- **Permission / Role:** `FA.DISPOSE` — holder role
- **Variation:** disposalType = `SALE`, proceeds = 0
- **Preconditions / Seed:** IN_SERVICE asset.
- **Steps:** Dispose with proceeds `0`.
- **Test Data:** `{ disposalDate:"2026-04-30", proceedsAmount:"0" }`
- **Expected Result:** `gainLossAmount = 0 − NBV` (a loss equal to NBV); status → DISPOSED (note: still `SALE` type — distinct from write-off which sets WRITTEN_OFF).
- **Convention Assertions:** C2; C3; C8.
- **Negative / Edge:** `@PositiveOrZero` allows 0; this confirms 0 is the boundary (not rejected).

### TC-FA-062 — Write-off (IN_SERVICE → WRITTEN_OFF, proceeds forced 0)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Asset detail write-off (`POST .../uid/{uid}/write-off`)
- **Permission / Role:** `FA.DISPOSE` — holder role; also lacking → hidden + 403
- **Variation:** disposalType = `WRITE_OFF`; status IN_SERVICE → WRITTEN_OFF
- **Preconditions / Seed:** IN_SERVICE asset; OPEN period.
- **Steps:** Open the Write-off form; date `2026-04-30`; reason "obsolete"; confirm.
- **Test Data:** `{ disposalDate:"2026-04-30", reason:"obsolete" }`
- **Expected Result:** `AssetDisposalDto` with `disposalType=WRITE_OFF`, `proceedsAmount=0`, `gainLossAmount = −NBV` (loss = full NBV). Final-period depreciation posted first. Status → WRITTEN_OFF. Write-off GL journal posted.
- **Convention Assertions:** C2; C3; C8; C9.
- **Negative / Edge:** missing date → inline "Write-off date is required." Write-off has no proceeds field (forced 0 by service — verify the form has none).

### TC-FA-063 — Dispose/write-off rejected when not IN_SERVICE (illegal state)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** dispose / write-off (`POST .../uid/{uid}/dispose|write-off`)
- **Permission / Role:** `FA.DISPOSE` — holder role
- **Variation:** status = DRAFT / DISPOSED / WRITTEN_OFF
- **Steps:** (API) Dispose a DRAFT asset; write-off a DRAFT asset; dispose an already-DISPOSED asset.
- **Expected Result:** Non-IN_SERVICE origin → `IllegalStateException` "Only IN_SERVICE assets can be disposed (BR-FA-03)." Already disposed → "Asset has already been disposed." (HTTP 409/400). No change.
- **Convention Assertions:** C2 errors[]; C3.
- **Negative / Edge:** Double-dispose guard (`disposals.findByFixedAssetId(...).isPresent()`) verified separately in TC-FA-064.

### TC-FA-064 — Cannot dispose an asset twice (single-disposal guard)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** dispose (`POST .../uid/{uid}/dispose`)
- **Permission / Role:** `FA.DISPOSE` — holder role
- **Preconditions / Seed:** an asset already DISPOSED with a disposal record.
- **Steps:** Re-POST dispose on the same asset.
- **Expected Result:** Blocked twice over — first by status (not IN_SERVICE) and by the explicit "Asset has already been disposed." guard. Exactly one disposal record persists.
- **Convention Assertions:** C2; C9 (no duplicate financial posting).
- **Negative / Edge:** confirms idempotency of disposal at the asset level.

---

## FA → GL RECONCILIATION REPORT

### TC-FA-070 — Reconciliation bars (cost ties + accum-dep ties)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** FA Reconciliation (`/admin/fixed-assets/reconciliation` · `GET /api/v1/fixed-assets/reconciliation`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** Company A with IN_SERVICE assets and posted depreciation/capitalisation so the register and GL tie.
- **Steps:**
  1. Open `/admin/fixed-assets/reconciliation`; select company.
  2. Read the cost bar (`registerCostSum` vs `glCostBalance`, `costTies`) and the accumulated-dep bar (`registerAccumDepSum` vs `glAccumDepBalance`, `accumDepTies`).
- **Expected Result:** `FixedAssetReconciliationDto` returned; both `costTies` and `accumDepTies` true (within rounding); the screen renders matching/“ties” indicators.
- **Convention Assertions:** C2; C3; C6 axe; C7 company-scoped; C8 money formatting.
- **Negative / Edge:** Seed a known mismatch (e.g. manual GL entry) → `costTies=false`; the bar shows a "does not tie" state. Company with no FA activity → zero bars that tie.

### TC-FA-071 — Reconciliation cross-tenant + permission gating
- **Type:** Manual (API) + Automated
- **Priority:** P2
- **Module / Submodule:** FA Reconciliation (`GET /api/v1/fixed-assets/reconciliation`)
- **Permission / Role:** `FA.VIEW` — Company-A user
- **Steps:**
  1. As a Company-A user, request reconciliation for `companyId=B` → scope denied.
  2. As a user lacking `FA.VIEW`, navigate to the route → forbidden + API 403.
- **Expected Result:** `scopeGuard.assertCanActIn` denies foreign company; permission guard hides nav / blocks route.
- **Convention Assertions:** C3; C7.
- **Negative / Edge:** spoofed companyId rejected.

---

## DEPRECIATION SCHEDULE (child of asset detail)

### TC-FA-080 — Schedule child table populated after place-in-service
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Asset detail schedule (`GET .../uid/{uid}/schedule`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`
- **Preconditions / Seed:** an IN_SERVICE asset (schedule generated at place-in-service).
- **Steps:** Open the asset detail; the Schedule section loads (only when IN_SERVICE) showing period seq, period date, planned charge, accumulated-after, NBV-after, posted flag.
- **Expected Result:** `DepreciationScheduleLineDto[]` rendered; posted lines flagged; rows tracked by uid (no uid column).
- **Convention Assertions:** C1 uid not displayed; C2; C4 child loading/empty/error.
- **Negative / Edge:** DRAFT asset → schedule not loaded (verified gate). After a revaluation the schedule is **regenerated** (later versions; verify `scheduleVersion` increments — confirm the latest version is shown).

### TC-FA-081 — STRAIGHT_LINE vs REDUCING_BALANCE produce different schedules
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Asset detail schedule (`GET .../uid/{uid}/schedule`)
- **Permission / Role:** `FA.VIEW` — `ACCOUNTANT`
- **Variation:** method enum drives the planned-charge curve
- **Preconditions / Seed:** two IN_SERVICE assets — one STRAIGHT_LINE, one REDUCING_BALANCE — same cost/life.
- **Steps:** Compare the two schedules.
- **Expected Result:** STRAIGHT_LINE planned charges are even across periods; REDUCING_BALANCE charges decline by `reducingRate`. (Exact figures per the schedule generator.)
- **Convention Assertions:** C8 amounts as strings; C2.
- **Negative / Edge:** REDUCING_BALANCE asset with a missing reducingRate would have been blocked at creation (see TC-FA-012).

---

## CROSS-CUTTING / RBAC MATRIX

### TC-FA-090 — Permission matrix across all FA actions (allowed vs denied)
- **Type:** Manual (API) + Automated nav check
- **Priority:** P1
- **Module / Submodule:** all FA endpoints
- **Permission / Role:** each FA code in isolation via a CUSTOM role
- **Preconditions / Seed:** CUSTOM roles each granting exactly one of `FA.CATEGORY.VIEW`, `FA.CATEGORY.MANAGE`, `FA.VIEW`, `FA.REGISTER.MANAGE`, `FA.DISPOSE`, `FA.DEPRECIATE`; plus a NO-PERMISSION user; plus `rootadmin`.
- **Steps:** For each role, attempt every endpoint and observe nav visibility.
- **Test Data / expected map:**
  - `FA.CATEGORY.VIEW`: can list/get categories; cannot create/update/archive (403).
  - `FA.CATEGORY.MANAGE`: can create/update/archive categories (manage implies the action codes only — verify it does **not** auto-grant `FA.CATEGORY.VIEW` unless seeded together).
  - `FA.VIEW`: can list/get assets, schedule, revaluations, reconciliation, run list/detail; cannot register/dispose/depreciate (403).
  - `FA.REGISTER.MANAGE`: register, acquire-from-bill, update, place-in-service, transfer (403 on view-only endpoints if `FA.VIEW` not also granted — verify get uses `@perm.scoped(...,'fixedasset','FA.VIEW')`).
  - `FA.DISPOSE`: dispose, write-off, revalue.
  - `FA.DEPRECIATE`: preview + post depreciation.
  - NO-PERMISSION: every FA nav item hidden; every endpoint 403.
  - `rootadmin`: bypasses all checks and sees all companies (do NOT use for negative-auth cases).
- **Expected Result:** Each action returns 200/201 only with its exact code; 403 otherwise; nav items reflect the codes.
- **Convention Assertions:** C3 RBAC by permission code (never role name); C4 forbidden state; C7.
- **Negative / Edge:** Verify a code grants only its action(s) — e.g. having `FA.REGISTER.MANAGE` but not `FA.VIEW` is tested against the GET endpoints (which require `FA.VIEW`).

### TC-FA-091 — Money & date conventions across FA screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** all FA screens
- **Permission / Role:** `FA.VIEW` (+ relevant action codes) — `ACCOUNTANT`
- **Steps:** Across list/detail/run/reconciliation, inspect rendered amounts and dates.
- **Expected Result:** Money rendered as strings on the wire and formatted for display; dates ISO `yyyy-MM-dd` in inputs/payloads (acquisitionDate, depreciationStartDate, postingDate, disposalDate, revaluationDate).
- **Convention Assertions:** C8 money string / ISO dates; C6 axe.
- **Negative / Edge:** locale-independent ISO date inputs.

### TC-FA-092 — Single-branch vs multi-branch company behaviour
- **Type:** Manual + Automated
- **Priority:** P3
- **Module / Submodule:** register + transfer
- **Permission / Role:** `FA.REGISTER.MANAGE` — `ACCOUNTANT`
- **Variation:** single-branch company (only default branch) vs multi-branch
- **Preconditions / Seed:** Company S (one branch), Company M (≥2 branches).
- **Steps:**
  1. In Company S, register an asset — branch picker shows only the default branch.
  2. In Company M, register against a non-default branch, then transfer it to another branch.
- **Expected Result:** Branch picker lists only ACTIVE branches of the selected company; transfer moves the asset between branches of the same company; cross-company branch not offered.
- **Convention Assertions:** C1 branch via picker (register); C7 branch scoping; C3.
- **Negative / Edge:** Selecting a branch from another company is impossible via the picker; an API call with a foreign branchId is scope-denied.
