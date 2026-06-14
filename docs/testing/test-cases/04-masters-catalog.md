# Catalog Masters — Test Cases (Domain: CAT)

End-to-end and manual test cases for the **catalog master data** of the ERP: Products (incl. branches, barcodes, bulk packs, prices, components/recipe), Units of Measure, Price Lists, Currencies + FX rates, Tax Rates, and Distribution Routes (incl. customer/agent/branch assignment).
Scope is CRUD + list/search/filter, status lifecycle (ACTIVE/INACTIVE/ARCHIVED soft-delete), the **GOODS=stockable / SERVICE=non-stockable** rule, UoM conversions (bulk packs), price lists, multi-currency (base TZS + foreign FX rates), tax rates, and route assignment — all exercised through the deployed Angular admin UI per system conventions C1–C9.

## Modules / submodules covered (verified controllers · API base paths · frontend routes)

| Submodule | Controller (base path) | Frontend route(s) | List/detail components |
|---|---|---|---|
| Products | `ProductController` — `/api/v1/products` | `/admin/products`, `/admin/products/uid/:uid` | `product-list.component`, `product-detail.component` |
| Product → branches / barcodes / bulk-packs / prices / components | `ProductController` sub-resources under `/api/v1/products/uid/{uid}/...` | embedded panels in `/admin/products/uid/:uid` | `product-detail.component` |
| Barcode lookup (POS) | `ProductController` — `GET /api/v1/products/barcode-lookup` | barcode-scan box on `/admin/products` | `product-list.component` |
| Units of Measure | `UnitOfMeasureController` — `/api/v1/units` | `/admin/units` | `units-of-measure-list.component` |
| Price Lists | `PriceListController` — `/api/v1/price-lists` | `/admin/price-lists` | `price-list-list.component` |
| Currencies + FX rates | `CurrencyController` — `/api/v1/fx` (`/currencies`, `/rates`) | `/admin/fx/rates` | `fx-rate-list.component` |
| Tax Rates | `TaxRateController` — `/api/v1/tax-rates` | `/admin/tax-rates` | `tax-rate-list.component` |
| Routes | `RouteController` — `/api/v1/routes` | `/admin/routes`, `/admin/routes/uid/:uid` | `route-list.component`, `route-detail.component` |
| Route → customers / agents / branches | `RouteController` sub-resources under `/api/v1/routes/uid/{uid}/...` | embedded panels in `/admin/routes/uid/:uid` | `route-detail.component` |

Notes on the **as-built** surface (verified, do not assume otherwise):
- **Tax Rates** has **no create and no archive** — only `GET /api/v1/tax-rates` (list) and `PUT /api/v1/tax-rates/uid/{uid}` (update rate). The 3 VAT bands are seeded per company. Tax-rate list is **not paginated** (controller returns a `List`, not a `Page`).
- **Currencies** are global reference data: only `GET /currencies` and `GET /currencies/uid/{uid}` — **no create UI**. FX **rates** are append-only (no edit-in-place; a correction is a new effective-dated row) and the rates list **is** paginated.
- The Currency list endpoint returns a `List` (not paged); the FX rates list endpoint returns a `Page`.
- Product/UoM/PriceList/Route lists **are** paginated (`Page` + `PageMeta`).
- `RouteController.get` (detail) is gated `@perm.scoped(#uid,'route','ROUTE.VIEW')`; `RouteController.list` is gated `@perm.has('ROUTE.VIEW')`. `ProductController.get` and `list` are both `@perm.has('PRODUCT.VIEW')`.
- The catalog permissions are seeded and granted **only to `ORG_ADMIN`** (V3/V4/V5/V9/V77 migrations grant to `ORG_ADMIN`). For negative-auth tests use the **NO-PERMISSION user** or a **CUSTOM role** lacking the code; for positive tests run as **`ORG_ADMIN`** (or `rootadmin` to bypass, only where bypass is the point).

## Permission codes in scope (EXACT @PreAuthorize codes — verified in controllers + seed SQL)

- Products: `PRODUCT.VIEW` (read/list/get/sub-resource reads/barcode-lookup), `PRODUCT.MANAGE` (create/update/archive/restore + bulk-packs/barcodes/prices/components writes), `PRODUCT.BRANCH.ASSIGN` (assign/remove product↔branch), `BOM.MANAGE` (promote-recipe-to-bom).
- Units of Measure: `UOM.VIEW`, `UOM.MANAGE`.
- Price Lists: `PRICELIST.VIEW`, `PRICELIST.MANAGE`.
- Currencies/FX: `CURRENCY.VIEW`, `CURRENCY.MANAGE`.
- Tax Rates: `TAXRATE.VIEW`, `TAXRATE.MANAGE`.
- Routes: `ROUTE.VIEW`, `ROUTE.MANAGE` (CRUD + branch assoc), `ROUTE.ASSIGN` (customer/agent assoc + set primary agent).

## Type / role variations exercised

| Dimension | Variations exercised |
|---|---|
| User role (allowed) | `ORG_ADMIN` (holds all catalog perms); `rootadmin` (superuser bypass — cross-tenant/visibility only) |
| User role (denied) | NO-PERMISSION user (forbidden + hidden nav); CUSTOM role granted only `*.VIEW` (sees list, write actions hidden/403) |
| ProductType | `GOODS` (stockable allowed) vs `SERVICE` (stockable forced false — BR-PROD-01 / chk_product_service_stockable) |
| VatStatus | `STANDARD`, `ZERO_RATED`, `EXEMPT` (product attribute + tax-rate bands) |
| MasterStatus lifecycle | `ACTIVE` → `ARCHIVED` (archive) → `ACTIVE` (restore); `INACTIVE` rendered distinctly |
| Currency | base `TZS` vs foreign (e.g. `USD`, `EUR`, `KES`); FX rate direction from→to |
| AgentKind (route assign) | `EXTERNAL` (allowed) vs `INTERNAL` (rejected by backend, filtered from picker) |
| Code assignment | auto-assigned `PROD-####` (blank code) vs user-supplied code (trimmed/uppercased, unique per company) |
| Company/Branch scope | single-company vs multi-company org; default vs non-default branch; cross-tenant denial (C7) |
| Screen states | loading / empty / error / forbidden (C4) on every list/detail |

---

## TEST CASES

> Identity convention reminder applied throughout (C1): a `uid` appears **only** in the URL (`/admin/<res>/uid/:uid`); never shown in tables/labels; resources are chosen via a **search/select picker by human name/code**, never by typed uid. Money is rendered as a formatted string `CUR 1,234.56` (C8); dates ISO `yyyy-MM-dd` (C8).

### PRODUCTS — list, search, four states, pagination

### TC-CAT-001 — Product list loads (loading → idle) scoped to active company
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products` · `GET /api/v1/products`)
- **Permission / Role:** `PRODUCT.VIEW` — runs as `ORG_ADMIN`; also as NO-PERMISSION user → expect forbidden (see TC-CAT-006)
- **Variation:** company = default; product mix of GOODS + SERVICE
- **Preconditions / Seed:** active company with ≥1 product (seed via TC-CAT-010 or API `POST /api/v1/products`)
- **Steps:**
  1. Navigate to `/admin/products`.
  2. Observe the loading indicator, then the table rendering.
  3. Confirm the company selector shows the active company; rows are for that company only.
- **Test Data:** company "Acme Distributors (Default)"
- **Expected Result:** Table lists products with columns name, code (e.g. `PROD-0001`), type, status badge, base unit; envelope `ApiResponse<List<ProductDto>>` with `meta` populated; HTTP 200.
- **Convention Assertions:** C2 envelope+meta; C4 loading→idle; C5 paginator present (FIRST/PREV/numbers/NEXT/LAST); C7 only active-company rows; C1 no raw uid column; C6 axe scan clean.
- **Negative / Edge:** company with 0 products → empty state (TC-CAT-003).

### TC-CAT-002 — Product list pagination controls
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Products (`/admin/products` · `GET /api/v1/products?page=&size=`)
- **Permission / Role:** `PRODUCT.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** ≥21 products for the company (page size = 20)
- **Steps:**
  1. Navigate to `/admin/products`.
  2. Click NEXT; assert page 2 rows differ and `meta.page=1`.
  3. Click LAST, then FIRST; click a numbered page.
- **Test Data:** 25 seeded products
- **Expected Result:** Each click triggers `GET` with the correct `page`; rows update; current page highlighted.
- **Convention Assertions:** C5 all five controls; paginator self-hides when totalPages=1; C2 meta `{page,size,totalElements,totalPages,hasNext}`.
- **Negative / Edge:** single page (≤20 rows) → paginator hidden.

### TC-CAT-003 — Product list empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Products (`/admin/products` · `GET /api/v1/products`)
- **Permission / Role:** `PRODUCT.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** a company with no products (or search that returns none)
- **Steps:**
  1. Navigate to `/admin/products`; switch to the empty company.
- **Expected Result:** Distinct empty-state message (not a spinner, not an error); no table rows.
- **Convention Assertions:** C4 empty distinct from loading/error/forbidden; C6 axe clean.

### TC-CAT-004 — Product list error state
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Products (`/admin/products`)
- **Permission / Role:** `PRODUCT.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** simulate 5xx (network intercept) on `GET /api/v1/products`
- **Steps:**
  1. Navigate to `/admin/products` with the list call failing (non-403).
- **Expected Result:** Error state rendered (retry affordance), distinct from empty.
- **Convention Assertions:** C4 error state; state set to `error` (not `forbidden`) for non-403.

### TC-CAT-005 — Product search (debounced `q`)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products` · `GET /api/v1/products?q=`)
- **Permission / Role:** `PRODUCT.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** products incl. one named "Sugar 1kg"
- **Steps:**
  1. Navigate to `/admin/products`.
  2. Type "Sugar" into the search box; wait for the 300ms debounce.
  3. Assert results filter to matches; clear search resets to page 0.
- **Test Data:** query "Sugar"
- **Expected Result:** `GET ...?q=Sugar&page=0`; only matching rows shown.
- **Convention Assertions:** C2 meta; C5 search resets to first page.
- **Negative / Edge:** query with no matches → empty state; blank query → unfiltered list.

### TC-CAT-006 — Product list forbidden (no permission)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products` · `GET /api/v1/products`)
- **Permission / Role:** `PRODUCT.VIEW` — runs as NO-PERMISSION user → expect forbidden
- **Preconditions / Seed:** user with no catalog permissions
- **Steps:**
  1. Log in as NO-PERMISSION user.
  2. Confirm the "Products" nav item is hidden (shell gates by `PRODUCT.VIEW`).
  3. Directly navigate to `/admin/products`.
- **Expected Result:** Route guard `requirePermission('PRODUCT.VIEW')` redirects to admin home (not a permissioned screen); if API hit directly, returns 403.
- **Convention Assertions:** C3 RBAC by code; C4 forbidden state / hidden nav; C1 no uid leakage.

### PRODUCTS — create (GOODS / SERVICE stockable rule, code assignment, VAT)

### TC-CAT-010 — Create GOODS product, code auto-assigned (PROD-####)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products` · `POST /api/v1/products`)
- **Permission / Role:** `PRODUCT.MANAGE` (scoped to company) — runs as `ORG_ADMIN`; also as `*.VIEW`-only CUSTOM role → create form/button hidden, API 403
- **Variation:** product = GOODS; stockable = true; code = blank (auto)
- **Preconditions / Seed:** active company with ≥1 ACTIVE UoM (e.g. "EA — Each"); pick base unit by name
- **Steps:**
  1. Navigate to `/admin/products`; click "New product" to reveal the inline create form.
  2. Leave Code blank; enter Name "Sugar 1kg", Description optional.
  3. Type = GOODS; leave Stockable checked; Sellable checked.
  4. Select Base Unit from the unit dropdown **by its code/name** (e.g. "EA — Each").
  5. Cost amount 1000, currency TZS; VAT Status = STANDARD.
  6. Submit.
- **Test Data:** name "Sugar 1kg", baseUnit "EA", cost TZS 1000, VAT STANDARD
- **Expected Result:** HTTP 201; success toast "Product created"; new row with system code `PROD-####`; `stockable=true`; `type=GOODS`; envelope `ProductDto`.
- **Convention Assertions:** C1 base unit chosen via picker by name (no typed uid; company sent as resolved `companyUid` under the hood); C2 201 + envelope; C3 PRODUCT.MANAGE; C8 cost stored as Money string; C9 master created ACTIVE.
- **Negative / Edge:** missing name → "Company and name are required."; missing base unit → "Base unit is required." (no API call).

### TC-CAT-011 — Create SERVICE product forces stockable = false (BR-PROD-01)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products` · `POST /api/v1/products`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** product = SERVICE; stockable must be forced false
- **Preconditions / Seed:** active company + ACTIVE UoM
- **Steps:**
  1. Open the product create form.
  2. Set Type = SERVICE.
  3. Observe the Stockable checkbox becomes **disabled** and unchecked (`stockableDisabled` computed true).
  4. Fill name "Delivery Service", base unit "EA"; submit.
- **Test Data:** name "Delivery Service", type SERVICE
- **Expected Result:** Created with `stockable=false`, `type=SERVICE`; UI never lets a SERVICE be stockable; backend persists stockable=false (DB CHECK `chk_product_service_stockable` is the backstop).
- **Convention Assertions:** C1 base unit via picker; C3 PRODUCT.MANAGE; entity rule honoured client- and server-side.
- **Negative / Edge:** attempt API `POST` with `type=SERVICE, stockable=true` directly → rejected by DB CHECK constraint (validation/400 or constraint error envelope). Verify the UI cannot produce this.

### TC-CAT-012 — Create product with user-supplied code (trimmed/uppercased, unique per company)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Products (`/admin/products` · `POST /api/v1/products`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** code = supplied "  sku-001 " → expected stored "SKU-001"
- **Preconditions / Seed:** active company; ensure code "SKU-001" not already used
- **Steps:**
  1. Open create form; enter Code "  sku-001 "; name "Widget"; base unit; submit.
- **Test Data:** code "  sku-001 "
- **Expected Result:** Stored code "SKU-001" (trimmed + uppercased); row shows "SKU-001".
- **Convention Assertions:** C2 201 envelope.
- **Negative / Edge:** create a second product with the same code "SKU-001" in the same company → duplicate rejected (uq_product_company_code; error message surfaced inline from envelope `errors[0]`). Same code in a **different** company → allowed (per-company uniqueness; ties to C7).

### TC-CAT-013 — Create product with VAT status variations
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Products (`/admin/products` · `POST /api/v1/products`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** VatStatus ∈ {STANDARD, ZERO_RATED, EXEMPT}
- **Preconditions / Seed:** active company + UoM
- **Steps:** Create three GOODS products, one per VAT status selected from the VAT dropdown.
- **Test Data:** names "Std Item"/"Zero Item"/"Exempt Item"
- **Expected Result:** Each persists the chosen `vatStatus`; default is STANDARD when left unset.
- **Convention Assertions:** C2 envelope; enum value round-trips.
- **Negative / Edge:** omit VAT status (null) → defaults to STANDARD.

### TC-CAT-014 — Create product blocked for CUSTOM/no-manage role
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`POST /api/v1/products`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as CUSTOM role with only `PRODUCT.VIEW` → expect create hidden + 403
- **Preconditions / Seed:** CUSTOM role granted `PRODUCT.VIEW` only
- **Steps:**
  1. Log in as the CUSTOM-role user; navigate to `/admin/products`.
  2. Confirm list renders but "New product" / create affordance is hidden (`canManage` false).
  3. Attempt `POST /api/v1/products` directly.
- **Expected Result:** No create UI; direct API call → 403.
- **Convention Assertions:** C3 RBAC by code (not role name); C1 no uid.

### PRODUCTS — detail, edit, archive/restore lifecycle

### TC-CAT-020 — Product detail loads with sub-panels (uid only in URL)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products/uid/:uid` · `GET /api/v1/products/uid/{uid}` + sub-resource GETs)
- **Permission / Role:** `PRODUCT.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** an existing product (from TC-CAT-010)
- **Steps:**
  1. From the list, click the product row to open `/admin/products/uid/:uid`.
  2. Confirm header (code, name, status badge) and panels: Barcodes, Bulk Packs, Prices, Components/Recipe, Branch associations all load (each shows loading→idle/empty).
- **Expected Result:** Detail renders; each panel independently loads via its sub-resource GET.
- **Convention Assertions:** C1 uid appears only in the URL, not in any visible label/table cell; C4 four states per panel; C6 axe clean.
- **Negative / Edge:** unknown uid → product error state.

### TC-CAT-021 — Edit product profile (name, description, type, sellable, base unit, cost, VAT)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products/uid/:uid` · `PUT /api/v1/products/uid/{uid}`)
- **Permission / Role:** `PRODUCT.MANAGE` (scoped to product) — runs as `ORG_ADMIN`; also `*.VIEW`-only → save hidden/403
- **Preconditions / Seed:** existing GOODS product
- **Steps:**
  1. Open detail; change name to "Sugar 1kg (Refined)"; change cost to TZS 1100; change base unit via picker; save.
- **Test Data:** new name, cost TZS 1100
- **Expected Result:** HTTP 200; success toast; header reflects updated values; code is NOT editable (immutable).
- **Convention Assertions:** C1 base unit via picker; C2 envelope; C8 money string; code/company immutability.
- **Negative / Edge:** clear name → "Name is required." (no API call); clear base unit → "Base unit is required."

### TC-CAT-022 — Edit GOODS→SERVICE forces stockable false
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`PUT /api/v1/products/uid/{uid}`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** type changed GOODS → SERVICE
- **Preconditions / Seed:** a stockable GOODS product
- **Steps:** Open detail; set Type = SERVICE; observe stockable checkbox disabled+forced off; save.
- **Expected Result:** Persists `type=SERVICE, stockable=false`; UI prevents stockable SERVICE; DB CHECK is backstop.
- **Convention Assertions:** entity rule client+server; C2 envelope.
- **Negative / Edge:** API `PUT type=SERVICE, stockable=true` → DB CHECK rejection.

### TC-CAT-023 — Archive product (ACTIVE → ARCHIVED), soft-delete
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`PUT /api/v1/products/uid/{uid}/archive`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** lifecycle ACTIVE → ARCHIVED
- **Preconditions / Seed:** ACTIVE product
- **Steps:** Open detail; click Archive.
- **Expected Result:** HTTP 204; status badge → ARCHIVED (secondary); record retained (no hard delete); audit `PRODUCT_ARCHIVE` with previous/new status.
- **Convention Assertions:** C9 soft-delete not hard delete; C2 204; C3 PRODUCT.MANAGE.
- **Negative / Edge:** archiving an already-ARCHIVED product is idempotent (stays ARCHIVED).

### TC-CAT-024 — Restore product (ARCHIVED → ACTIVE)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Products (`PUT /api/v1/products/uid/{uid}/restore`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** lifecycle ARCHIVED → ACTIVE
- **Preconditions / Seed:** ARCHIVED product (from TC-CAT-023)
- **Steps:** Open detail; click Restore.
- **Expected Result:** HTTP 204; status → ACTIVE; audit `PRODUCT_RESTORE`.
- **Convention Assertions:** C9; C2 204.
- **Negative / Edge:** archived product is excluded from the component picker (BR-PROD-05) and should not be selectable as a recipe component.

### PRODUCTS — branch associations (PRODUCT.BRANCH.ASSIGN)

### TC-CAT-030 — Assign product to a branch (chosen by name)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products → branches (`POST /api/v1/products/uid/{uid}/branches`)
- **Permission / Role:** `PRODUCT.BRANCH.ASSIGN` — runs as `ORG_ADMIN`; also `PRODUCT.MANAGE`-without-BRANCH.ASSIGN → assign hidden/403
- **Variation:** branch = non-default branch of a multi-branch company
- **Preconditions / Seed:** product + a company with ≥2 branches
- **Steps:**
  1. Open product detail → Branch associations panel.
  2. Select company (dropdown by name), then select branch (dropdown showing `code — name`).
  3. Click Assign.
- **Test Data:** branch "BR02 — Mwanza"
- **Expected Result:** HTTP 201; branch row appears (rendered as `code — name`); `ProductBranchDto` returned.
- **Convention Assertions:** C1 branch chosen via select by `code — name`, never a typed uid (uid stored under the hood, resolved from the loaded branch list); C3 PRODUCT.BRANCH.ASSIGN; C2 201.
- **Negative / Edge:** assign with no branch selected → "Select a branch to assign."; re-assigning the same branch → duplicate rejected (error surfaced).

### TC-CAT-031 — Remove product↔branch association
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Products → branches (`DELETE /api/v1/products/uid/{uid}/branches/{branchUid}`)
- **Permission / Role:** `PRODUCT.BRANCH.ASSIGN` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** product with ≥1 branch association
- **Steps:** In the Branch panel, click Remove on a branch row.
- **Expected Result:** HTTP 204; row removed; success toast names the branch.
- **Convention Assertions:** C2 204; C1 the branchUid used in the path is resolved from the loaded branch, not shown/typed.
- **Negative / Edge:** removing a branch not in the loaded list → guarded ("Branch not found in loaded list — refresh").

### TC-CAT-032 — Branch assign hidden without PRODUCT.BRANCH.ASSIGN
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Products → branches
- **Permission / Role:** `PRODUCT.BRANCH.ASSIGN` — runs as CUSTOM role with `PRODUCT.VIEW` (+ optionally `PRODUCT.MANAGE`) but NOT BRANCH.ASSIGN → expect forbidden
- **Steps:** Open detail; confirm assign/remove controls hidden (`canAssign` false); direct `POST` → 403.
- **Expected Result:** No branch-write affordance; API 403.
- **Convention Assertions:** C3 distinct permission per action.

### PRODUCTS — barcodes (incl. POS barcode lookup)

### TC-CAT-040 — Add barcode (primary flag)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Products → barcodes (`POST /api/v1/products/uid/{uid}/barcodes`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** existing product
- **Steps:** In Barcodes panel, enter "6001234567890", check Primary; Add.
- **Test Data:** barcode "6001234567890", primary=true
- **Expected Result:** HTTP 201; barcode row appears; `ProductBarcodeDto` returned.
- **Convention Assertions:** C2 201; C3 PRODUCT.MANAGE.
- **Negative / Edge:** blank barcode → "Barcode value is required."; duplicate barcode within company → rejected.

### TC-CAT-041 — Remove barcode
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Products → barcodes (`DELETE /api/v1/products/uid/{uid}/barcodes/{barcodeUid}`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** Click Remove on a barcode row.
- **Expected Result:** HTTP 204; row removed.
- **Convention Assertions:** C2 204; C1 barcodeUid resolved from the row, not typed.

### TC-CAT-042 — POS barcode lookup resolves to product (company-scoped)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products (`/admin/products` barcode box · `GET /api/v1/products/barcode-lookup?companyId=&barcode=`)
- **Permission / Role:** `PRODUCT.VIEW` — runs as `ORG_ADMIN`
- **Variation:** valid barcode within active company; cross-tenant barcode (must not resolve)
- **Preconditions / Seed:** product with barcode "6001234567890" in company A
- **Steps:**
  1. On `/admin/products`, type the barcode into the barcode-scan box; trigger lookup.
- **Test Data:** "6001234567890"
- **Expected Result:** Resolves to the owning product (name/code shown); `ProductBarcodeDto`.
- **Convention Assertions:** C7 lookup scoped by `companyId` — a barcode from company B does not resolve in company A; C2 envelope.
- **Negative / Edge:** unknown barcode → 404 → "not found" state; another company's barcode → not found (no cross-tenant leak).

### PRODUCTS — bulk packs (UoM conversion factor)

### TC-CAT-050 — Add bulk pack with conversion factor (factorToBase > 0)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products → bulk packs (`POST /api/v1/products/uid/{uid}/bulk-packs`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** unit = "CTN — Carton", factorToBase = 24 (24 EA per carton)
- **Preconditions / Seed:** product with base unit EA; an ACTIVE UoM "CTN" in the same company
- **Steps:**
  1. In Bulk Packs panel, select Unit "CTN — Carton" from the unit dropdown; enter factor 24; Add.
- **Test Data:** unit CTN, factor 24
- **Expected Result:** HTTP 201; bulk-pack row with unit + factor; `ProductBulkPackDto`.
- **Convention Assertions:** C1 unit chosen via select by code/name (uid under the hood); C2 201; C3 PRODUCT.MANAGE.
- **Negative / Edge:** factor ≤ 0 (e.g. 0 or negative) → `@DecimalMin 0.000001` validation → "factorToBase must be greater than zero (BR-PROD-03)"; missing unit/factor → "Unit and factor are required." (no call).

### TC-CAT-051 — Remove bulk pack
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Products → bulk packs (`DELETE /api/v1/products/uid/{uid}/bulk-packs/{bulkPackUid}`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** Click Remove on a bulk-pack row.
- **Expected Result:** HTTP 204; row removed.
- **Convention Assertions:** C2 204; C1 bulkPackUid from row.

### PRODUCTS — prices on price lists

### TC-CAT-060 — Set product price on a price list (Money)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products → prices (`POST /api/v1/products/uid/{uid}/prices`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** price list = "RETAIL — Retail" (selected by code/name); price TZS 1500
- **Preconditions / Seed:** product + an ACTIVE price list in the same company (TC-CAT-110)
- **Steps:**
  1. In Prices panel, select Price List "RETAIL — Retail"; enter amount 1500, currency TZS; Set price.
- **Test Data:** price list RETAIL, TZS 1500
- **Expected Result:** HTTP 201; price row shows the enriched price-list `code — name` + formatted money; `ProductPriceDto`.
- **Convention Assertions:** C1 price list via picker by code/name (priceListUid under the hood); C8 money string; C2 201.
- **Negative / Edge:** missing price list or amount → "Price list and amount are required."; re-setting the same price list = upsert (overwrites, not duplicates).

### TC-CAT-061 — Remove product price
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Products → prices (`DELETE /api/v1/products/uid/{uid}/prices/{priceListUid}`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** Click Remove on a price row.
- **Expected Result:** HTTP 204; row removed.
- **Convention Assertions:** C2 204; C1 priceListUid from the row.

### PRODUCTS — components / recipe (composed products)

### TC-CAT-070 — Add component to a product (picker by name; excludes self + archived)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Products → components (`POST /api/v1/products/uid/{uid}/components`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** component product chosen via debounced search picker; quantity 2
- **Preconditions / Seed:** a parent product + ≥1 other ACTIVE product in the same company
- **Steps:**
  1. In Components/Recipe panel, type a component name into the search box (debounced 300ms).
  2. Select a result from the dropdown (label `code — name`).
  3. Enter quantity 2; Add component.
- **Test Data:** component "Sugar 1kg", qty 2
- **Expected Result:** HTTP 201; component row added; `ProductComponentDto`.
- **Convention Assertions:** C1 component chosen via search picker by code/name (componentProductUid stored under the hood; no typed uid); C2 201.
- **Negative / Edge:** quantity ≤ 0 → `@DecimalMin 0.000001` → "quantity must be greater than zero (BR-PROD-05/qty)"; the **parent product itself** and **ARCHIVED** products are excluded from the picker (BR-PROD-05 self-exclusion); no selection → "Select a component product and enter a quantity."

### TC-CAT-071 — Remove component
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Products → components (`DELETE /api/v1/products/uid/{uid}/components/{componentUid}`)
- **Permission / Role:** `PRODUCT.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** Click Remove on a component row.
- **Expected Result:** HTTP 204; row removed.
- **Convention Assertions:** C2 204.

### TC-CAT-072 — Promote recipe to BOM draft (BOM.MANAGE)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Products → BOM (`POST /api/v1/products/uid/{uid}/promote-recipe-to-bom?companyId=`)
- **Permission / Role:** `BOM.MANAGE` (scoped to product) — runs as `ORG_ADMIN`; user with `PRODUCT.MANAGE` but NOT `BOM.MANAGE` → 403
- **Preconditions / Seed:** product with ≥1 component (legacy recipe)
- **Steps:** Invoke promote-recipe-to-bom (UI affordance if present, else API).
- **Expected Result:** HTTP 201; a DRAFT BOM is created from the recipe; `BomDto` returned.
- **Convention Assertions:** C3 distinct `BOM.MANAGE` code (NOT PRODUCT.MANAGE); C2 201.
- **Negative / Edge:** missing `BOM.MANAGE` → 403; note this is the boundary between catalog and the BOM module.

### UNITS OF MEASURE

### TC-CAT-100 — UoM list (four states) + management gating
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Units of Measure (`/admin/units` · `GET /api/v1/units`)
- **Permission / Role:** `UOM.VIEW` — runs as `ORG_ADMIN`; NO-PERMISSION → nav hidden + guard redirect; `UOM.VIEW`-only CUSTOM → list visible, create/edit/archive hidden
- **Preconditions / Seed:** company with seeded units (e.g. EA, KG)
- **Steps:** Navigate to `/admin/units`; observe loading→idle; switch to an empty company → empty state.
- **Expected Result:** Units listed with code, name, status badge; management actions visible only with `UOM.MANAGE` (`canManage`).
- **Convention Assertions:** C2 envelope+meta; C4 four states; C3 RBAC; C6 axe clean. (Note: list is paged via `GET /api/v1/units` `Page`.)
- **Negative / Edge:** error intercept → error state distinct from forbidden.

### TC-CAT-101 — Create UoM (inline code + name)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Units of Measure (`POST /api/v1/units`)
- **Permission / Role:** `UOM.MANAGE` (scoped to company) — runs as `ORG_ADMIN`; `UOM.VIEW`-only → create hidden/403
- **Preconditions / Seed:** active company
- **Steps:** Click "New unit"; enter Code "CTN", Name "Carton"; submit.
- **Test Data:** code CTN, name Carton
- **Expected Result:** HTTP 201; new unit row ACTIVE; `companyUid` resolved from selected company under the hood.
- **Convention Assertions:** C1 company resolved (no typed uid); C2 201; C9 created ACTIVE.
- **Negative / Edge:** missing code or name → "Code and name are required."; duplicate code per company → rejected.

### TC-CAT-102 — Edit UoM name (inline; code immutable)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Units of Measure (`PUT /api/v1/units/uid/{uid}`)
- **Permission / Role:** `UOM.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** Click Edit on a unit row; change name; Save.
- **Expected Result:** HTTP 200; name updated in-row; code unchanged (not editable).
- **Convention Assertions:** C2 envelope; code/company immutability.
- **Negative / Edge:** blank name → "Name is required."

### TC-CAT-103 — Archive then restore UoM (lifecycle)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Units of Measure (`PUT /api/v1/units/uid/{uid}/archive`, `.../restore`)
- **Permission / Role:** `UOM.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** ACTIVE → ARCHIVED → ACTIVE
- **Steps:** Archive a unit (badge → ARCHIVED); Restore it (badge → ACTIVE).
- **Expected Result:** HTTP 204 each; soft-delete preserved.
- **Convention Assertions:** C9 soft-delete; C2 204.
- **Negative / Edge:** archived units are filtered out of product base-unit / bulk-pack dropdowns (only ACTIVE units offered).

### PRICE LISTS

### TC-CAT-110 — Price list: list, create, edit, archive/restore
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Price Lists (`/admin/price-lists` · `GET/POST/PUT /api/v1/price-lists`, `.../archive`, `.../restore`)
- **Permission / Role:** `PRICELIST.VIEW` (read), `PRICELIST.MANAGE` (writes) — runs as `ORG_ADMIN`; `PRICELIST.VIEW`-only → writes hidden/403
- **Preconditions / Seed:** active company
- **Steps:**
  1. Navigate to `/admin/price-lists`; observe loading→idle/empty.
  2. Click "New price list"; enter Code "RETAIL", Name "Retail"; submit (201).
  3. Edit the name inline; save (200).
  4. Archive (204) → badge ARCHIVED; Restore (204) → ACTIVE.
- **Test Data:** code RETAIL, name Retail
- **Expected Result:** Full CRUD + lifecycle works; `PriceListDto` envelopes.
- **Convention Assertions:** C1 company resolved (no typed uid); C2 envelopes + status codes; C4 four states; C9 soft-delete; C3 RBAC.
- **Negative / Edge:** missing code/name → "Code and name are required."; duplicate code per company → rejected; forbidden for NO-PERMISSION user (nav hidden + guard).

### CURRENCIES + FX RATES

### TC-CAT-120 — Currency list (global reference, base TZS + foreign)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Currencies (`GET /api/v1/fx/currencies`) — surfaced as from/to pickers on `/admin/fx/rates`
- **Permission / Role:** `CURRENCY.VIEW` — runs as `ORG_ADMIN`; NO-PERMISSION → FX nav hidden + guard
- **Preconditions / Seed:** seeded currencies incl. base TZS and ≥1 foreign (USD/EUR/KES)
- **Steps:** Navigate to `/admin/fx/rates`; open the From/To currency pickers and confirm seeded currencies appear.
- **Expected Result:** Active currencies listed (`CurrencyDto` with code/name/symbol/minorUnits); base TZS present. There is **no currency-create UI** (global reference data).
- **Convention Assertions:** C2 envelope (list, not paged for currencies); C3 CURRENCY.VIEW; C8 base currency TZS.
- **Negative / Edge:** none for create (no create endpoint).

### TC-CAT-121 — Add FX rate (effective-dated, append-only)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** FX rates (`/admin/fx/rates` · `POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.MANAGE` — runs as `ORG_ADMIN`; `CURRENCY.VIEW`-only → "New rate" hidden/403
- **Variation:** from = USD (foreign), to = TZS (base), rate 2600.000000, effectiveDate today
- **Preconditions / Seed:** company selected; USD + TZS active currencies
- **Steps:**
  1. Click "New rate"; select From USD, To TZS; rate 2600; effective date today; rateType SPOT; source MANUAL; submit.
- **Test Data:** USD→TZS @ 2600.000000, effectiveDate `yyyy-MM-dd`
- **Expected Result:** HTTP 201; new rate row appears newest-first; `CurrencyRateDto`. Scope enforced server-side by numeric `companyId` (the controller gate is `CURRENCY.MANAGE` only by design — see controller note).
- **Convention Assertions:** C8 date ISO; C2 201; C9 append-only (no edit-in-place — a correction is a NEW effective-dated row); C7 company-scoped.
- **Negative / Edge:** From == To → "From and To currencies must differ." (client); rate ≤ 0 → "Rate must be a positive number." / backend `@Positive`; non-3-letter code → client validation; missing effective date → "Effective date is required."

### TC-CAT-122 — FX rates list paginated, newest-first
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** FX rates (`GET /api/v1/fx/rates?companyId=&page=` sort effectiveDate)
- **Permission / Role:** `CURRENCY.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** >50 rate rows (default page size 50) for the company
- **Steps:** Navigate to `/admin/fx/rates`; verify ordering newest-first; page via paginator.
- **Expected Result:** Rates paginated (page size 50); `meta` populated; sorted by effectiveDate.
- **Convention Assertions:** C5 paginator; C2 meta; C4 four states (incl. forbidden when no `CURRENCY.VIEW`).
- **Negative / Edge:** company with no rates → empty state.

### TC-CAT-123 — FX rate company scoping (cross-tenant isolation)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** FX rates (`GET/POST /api/v1/fx/rates`)
- **Permission / Role:** `CURRENCY.VIEW` / `CURRENCY.MANAGE` — runs as `ORG_ADMIN` of company A
- **Variation:** attempt to read/post rates for company B
- **Preconditions / Seed:** two companies A and B with rates
- **Steps:** As an A-scoped user, switch the company selector to B (if visible) or call the API with B's `companyId`.
- **Expected Result:** Only A's rates are visible; posting for B → denied (ScopeGuard `assertCanActIn`).
- **Convention Assertions:** C7 multi-tenancy enforced by numeric company scope; 403 on cross-company write.

### TAX RATES

### TC-CAT-130 — Tax rate list (3 seeded VAT bands, not paginated)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Tax Rates (`/admin/tax-rates` · `GET /api/v1/tax-rates?companyId=`)
- **Permission / Role:** `TAXRATE.VIEW` — runs as `ORG_ADMIN`; NO-PERMISSION → nav hidden + guard
- **Preconditions / Seed:** company with seeded tax rates (STANDARD, ZERO_RATED, EXEMPT)
- **Steps:** Navigate to `/admin/tax-rates`; confirm three VAT-band rows with label + rate + status.
- **Expected Result:** Three rows: STANDARD, ZERO_RATED, EXEMPT, each with its rate; `TaxRateDto` list (not paged — no paginator expected here).
- **Convention Assertions:** C2 envelope (list); C4 loading/idle/empty/error/forbidden; C3 TAXRATE.VIEW; C6 axe clean.
- **Negative / Edge:** company without seeded rates → empty state; error intercept → error state.

### TC-CAT-131 — Edit tax rate value (inline) — valid range [0, 1)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Tax Rates (`PUT /api/v1/tax-rates/uid/{uid}`)
- **Permission / Role:** `TAXRATE.MANAGE` (scoped to taxrate) — runs as `ORG_ADMIN`; `TAXRATE.VIEW`-only → edit hidden/403
- **Variation:** edit STANDARD band to 0.18 (18%)
- **Preconditions / Seed:** STANDARD tax rate exists
- **Steps:** Click Edit on the STANDARD row; set rate 0.18; Save.
- **Test Data:** rate 0.18
- **Expected Result:** HTTP 200; row shows updated rate; `TaxRateDto`. There is **no create/archive** for tax rates — only edit.
- **Convention Assertions:** C2 envelope; C3 distinct TAXRATE.MANAGE; C9 (rate is mutable in place — bands are fixed reference rows).
- **Negative / Edge:** rate < 0 or ≥ 1 → `@DecimalMin 0.0000`/`@DecimalMax 0.9999` + DB CHECK `chk_tax_rate_value` rejection; non-numeric → "A valid numeric rate is required." (client, no call); blank → required.

### ROUTES — CRUD, lifecycle, four states, search

### TC-CAT-140 — Route list (loading/empty/error/forbidden) + search
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Routes (`/admin/routes` · `GET /api/v1/routes?companyId=&q=`)
- **Permission / Role:** `ROUTE.VIEW` — runs as `ORG_ADMIN`; NO-PERMISSION → nav hidden + guard redirect
- **Preconditions / Seed:** company with ≥1 route
- **Steps:** Navigate to `/admin/routes`; observe loading→idle; type a query to filter; switch to empty company → empty state.
- **Expected Result:** Routes listed with code, name, location identifier, status; search filters; `ApiResponse<List<RouteDto>>` + meta.
- **Convention Assertions:** C2 envelope+meta; C4 four states; C5 paginator; C3 ROUTE.VIEW; C6 axe clean.
- **Negative / Edge:** error intercept → error; no matches → empty.

### TC-CAT-141 — Create route (company resolved; code auto)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Routes (`POST /api/v1/routes`)
- **Permission / Role:** `ROUTE.MANAGE` (scoped to company) — runs as `ORG_ADMIN`; `ROUTE.VIEW`-only → create hidden/403
- **Preconditions / Seed:** active company
- **Steps:** Create a route with Name "North Zone", Location Identifier "NZ-01".
- **Test Data:** name "North Zone", locationIdentifier "NZ-01"
- **Expected Result:** HTTP 201; route row with system code; `RouteDto`; `companyUid` resolved from selected company.
- **Convention Assertions:** C1 company resolved (no typed uid); C2 201; C9 created ACTIVE.
- **Negative / Edge:** blank name → "Name is required."

### TC-CAT-142 — Edit route (name + location; code/company immutable)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Routes (`/admin/routes/uid/:uid` · `PUT /api/v1/routes/uid/{uid}`)
- **Permission / Role:** `ROUTE.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** Open route detail; change name + location; Save.
- **Expected Result:** HTTP 200; header updated; code unchanged.
- **Convention Assertions:** C2 envelope; C1 uid only in URL; immutability of code/company.
- **Negative / Edge:** blank name → "Name is required."

### TC-CAT-143 — Archive then restore route (lifecycle)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Routes (`PUT /api/v1/routes/uid/{uid}/archive`, `.../restore`)
- **Permission / Role:** `ROUTE.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** ACTIVE → ARCHIVED → ACTIVE
- **Steps:** On route detail, Archive (badge → ARCHIVED), then Restore (badge → ACTIVE).
- **Expected Result:** HTTP 204 each; soft-delete preserved.
- **Convention Assertions:** C9 soft-delete; C2 204.
- **Negative / Edge:** idempotent archive of an already-archived route.

### TC-CAT-144 — Route detail get is scoped (ROUTE.VIEW scoped to route)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Routes (`GET /api/v1/routes/uid/{uid}` — `@perm.scoped(#uid,'route','ROUTE.VIEW')`)
- **Permission / Role:** `ROUTE.VIEW` — runs as `ORG_ADMIN` of company A; attempt to open a route belonging to company B
- **Preconditions / Seed:** route in company B; user scoped to company A
- **Steps:** Navigate directly to `/admin/routes/uid/<companyB-route-uid>`.
- **Expected Result:** 403 (route get is scope-checked to the route's company); detail shows forbidden/error.
- **Convention Assertions:** C7 cross-tenant denied; C3 scoped permission.

### ROUTES — customer / agent / branch assignment (ROUTE.ASSIGN, ROUTE.MANAGE)

### TC-CAT-150 — Assign customer to route (picker by code/name; scoped to route company)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Routes → customers (`POST /api/v1/routes/uid/{uid}/customers`)
- **Permission / Role:** `ROUTE.ASSIGN` — runs as `ORG_ADMIN`; `ROUTE.VIEW`-only → assign hidden/403
- **Preconditions / Seed:** route + ≥1 ACTIVE customer in the route's company
- **Steps:**
  1. Open route detail → Customers panel; type a customer name (debounced); pick a result (`code — displayName`); Assign.
- **Test Data:** customer "CUST-001 — Acme Stores"
- **Expected Result:** HTTP 201; customer row added; `RouteCustomerDto`.
- **Convention Assertions:** C1 customer chosen via search picker by code/name (customerUid under the hood); C2 201; C3 ROUTE.ASSIGN; the picker is scoped to the route's company (C7).
- **Negative / Edge:** no selection → "Select a customer to assign."; duplicate assignment → rejected; only ACTIVE customers appear in picker.

### TC-CAT-151 — Unassign customer from route
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Routes → customers (`DELETE /api/v1/routes/uid/{uid}/customers/{customerUid}`)
- **Permission / Role:** `ROUTE.ASSIGN` — runs as `ORG_ADMIN`
- **Steps:** Click Remove on a customer row.
- **Expected Result:** HTTP 204; row removed; toast names the customer.
- **Convention Assertions:** C2 204; C1 customerUid resolved from row.

### TC-CAT-152 — Assign EXTERNAL agent to route, set primary
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Routes → agents (`POST /api/v1/routes/uid/{uid}/agents`)
- **Permission / Role:** `ROUTE.ASSIGN` — runs as `ORG_ADMIN`
- **Variation:** agentKind = EXTERNAL; isPrimary = true
- **Preconditions / Seed:** route + ≥1 ACTIVE EXTERNAL agent in the company
- **Steps:**
  1. Open Agents panel; type an agent name (debounced; picker shows EXTERNAL only); pick; check Primary; Assign.
- **Test Data:** agent "AG-002 — Field Rep" (EXTERNAL), primary=true
- **Expected Result:** HTTP 201; agent row with Primary badge; `RouteAgentDto`.
- **Convention Assertions:** C1 agent via search picker by code/name; C2 201; C3 ROUTE.ASSIGN.
- **Negative / Edge:** **INTERNAL agents do not appear in the picker** and are **rejected by the backend** (error "Only EXTERNAL agents may be assigned to a route."); no selection → "Select an agent to assign."

### TC-CAT-153 — Unassign agent from route
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Routes → agents (`DELETE /api/v1/routes/uid/{uid}/agents/{agentUid}`)
- **Permission / Role:** `ROUTE.ASSIGN` — runs as `ORG_ADMIN`
- **Steps:** Click Remove on an agent row.
- **Expected Result:** HTTP 204; row removed.
- **Convention Assertions:** C2 204; C1 agentUid from row.

### TC-CAT-154 — Assign branch to route (ROUTE.MANAGE, not ROUTE.ASSIGN)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Routes → branches (`POST /api/v1/routes/uid/{uid}/branches`)
- **Permission / Role:** `ROUTE.MANAGE` — runs as `ORG_ADMIN`; user with `ROUTE.ASSIGN` but NOT `ROUTE.MANAGE` → branch-assign 403
- **Variation:** branch = non-default branch
- **Preconditions / Seed:** route + a company with ≥2 branches
- **Steps:** Open Branches panel; select company (by name), then branch (`code — name`); Assign.
- **Test Data:** branch "BR02 — Mwanza"
- **Expected Result:** HTTP 201; branch row added; `RouteBranchDto`.
- **Convention Assertions:** C1 branch via select by code/name; C3 **branch assoc requires ROUTE.MANAGE** (distinct from customer/agent which use ROUTE.ASSIGN) — verify the gating difference; C2 201.
- **Negative / Edge:** no branch selected → "Select a branch to assign."; duplicate → rejected.

### TC-CAT-155 — Unassign branch from route
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Routes → branches (`DELETE /api/v1/routes/uid/{uid}/branches/{branchUid}`)
- **Permission / Role:** `ROUTE.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** Click Remove on a branch row.
- **Expected Result:** HTTP 204; row removed.
- **Convention Assertions:** C2 204; C1 branchUid from row.

### TC-CAT-156 — Route assignment panels: list four states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Routes → customers/agents/branches (`GET .../customers|agents|branches`)
- **Permission / Role:** `ROUTE.VIEW` (scoped) — runs as `ORG_ADMIN`
- **Preconditions / Seed:** route with no assignments
- **Steps:** Open route detail; observe each assignment panel shows loading→empty distinctly; force error on one panel → that panel shows error.
- **Expected Result:** Each panel independently handles loading/empty/error.
- **Convention Assertions:** C4 four states per panel; C6 axe clean.

### CROSS-CUTTING — multi-tenancy, identity, accessibility

### TC-CAT-160 — Cross-tenant isolation across all catalog lists
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** All catalog lists (products/units/price-lists/routes/fx-rates/tax-rates)
- **Permission / Role:** respective `*.VIEW` — runs as `ORG_ADMIN` of company A
- **Variation:** company A vs company B data
- **Preconditions / Seed:** two companies each with distinct catalog data
- **Steps:** As an A-scoped user, view each catalog list; attempt company B `companyId` via API.
- **Expected Result:** Only company A rows visible in the UI; B's data never appears; cross-company API → denied by ScopeGuard.
- **Convention Assertions:** C7 company scoping on every list; C3 no leakage.

### TC-CAT-161 — uid never displayed; resources always chosen via picker (identity convention sweep)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All catalog detail/assignment screens
- **Permission / Role:** respective `*.VIEW`/`*.MANAGE` — runs as `ORG_ADMIN`
- **Steps:** On product detail, route detail, and each assignment panel, scan the visible text for any 26/36-char uid token; confirm none appear in tables/labels; confirm every cross-resource reference (base unit, price list, branch, customer, agent, component, currency) is selected via a named dropdown/search picker.
- **Expected Result:** No raw uid visible anywhere on screen; uid present only in the URL path; all references chosen by human name/code.
- **Convention Assertions:** C1 (the central catalog assertion); C8 money/date formatting where shown.

### TC-CAT-162 — Accessibility (axe) clean on all catalog screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** `/admin/products`, `/admin/products/uid/:uid`, `/admin/units`, `/admin/price-lists`, `/admin/routes`, `/admin/routes/uid/:uid`, `/admin/fx/rates`, `/admin/tax-rates`
- **Permission / Role:** respective `*.VIEW` — runs as `ORG_ADMIN`
- **Steps:** Run an axe scan on each catalog screen in its idle state (and empty state where reachable).
- **Expected Result:** No critical/serious axe violations; tables have captions/scope; controls are keyboard-operable with aria labels.
- **Convention Assertions:** C6 WCAG 2.1 AA across the catalog domain.

### TC-CAT-163 — rootadmin superuser bypass (visibility sanity, not a negative test)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** All catalog modules
- **Permission / Role:** superuser bypass — runs as `rootadmin`
- **Steps:** Log in as `rootadmin`; confirm all catalog nav items visible and all lists across companies accessible.
- **Expected Result:** `rootadmin` sees everything and bypasses permission checks (do NOT use rootadmin for any forbidden/negative-auth assertion).
- **Convention Assertions:** C3 (documents the bypass boundary); C7 cross-tenant visibility for the superuser only.
