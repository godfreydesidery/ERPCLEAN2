# 09 — Inventory (INV) Test Cases

Exhaustive, accuracy-checked test cases for the **Inventory** domain: stock on-hand (list / by-location / by-product / movements / adjustments / opening balance / reorder level), stock locations CRUD, inter-location transfers, physical/cycle counts, batches/lots, serial numbers, and inventory valuation + GL reconciliation.
All endpoints, permission codes, enum values and routes below were verified by reading the actual controllers, DTOs, enums, services, Angular components, routes and Flyway seed migrations. Where a code/route is missing or mismatched in the shipped source, it is called out explicitly as a defect/negative target rather than assumed working.

## Modules / submodules covered

| Submodule | Controller (API base path) | Frontend route(s) |
|---|---|---|
| Stock on-hand + movements + adjustments + opening + reorder + by-location/by-product | `StockController` (`/api/v1/stock`) | `/admin/stock` (`stock-list.component`) |
| Stock locations (CRUD + default + activate/deactivate) | `StockLocationController` (`/api/v1/stock-locations`) | `/admin/stock/locations` (`stock-location-list.component`) |
| Inter-location stock transfers | `StockTransferController` (`/api/v1/stock-transfers`) | `/admin/stock-transfers`, `/admin/stock-transfers/create`, `/admin/stock-transfers/uid/:uid` |
| Stock counts (physical / cycle) | `StockCountController` (`/api/v1/stock-counts`) | `/admin/stock-counts`, `/admin/stock-counts/create`, `/admin/stock-counts/uid/:uid` |
| Stock batches / lots + expiry report | `StockBatchController` (`/api/v1/stock-batches`) | `/admin/stock/batches` (`stock-batch-list.component`) |
| Stock serial numbers | `StockSerialController` (`/api/v1/stock-serials`) | `/admin/stock/serials` (`stock-serial-list.component`) |
| Inventory valuation report + opening valuation | `StockValuationController` (`/api/v1/stock/valuation`) | `/admin/stock/valuation` (`stock-valuation-report.component`), `/admin/stock/valuation/opening` (`opening-valuation.component`) |

Nav: all the above appear under the **Inventory** group in `shell.component.ts` (lines 149-160), each nav item gated by the permission below.

## Permission codes in scope (EXACT, from `@PreAuthorize` + Flyway seed)

| Code | Used by | Seeded in migration? |
|---|---|---|
| `STOCK.VIEW` | on-hand list, movements, by-location, by-product | V7 ✓ |
| `STOCK.ADJUST` | manual adjustment, set reorder level | V7 ✓ |
| `STOCK.OPENING` | opening-balance movement | V7 ✓ |
| `STOCK.LOCATION.VIEW` | location read / list / active | V41 ✓ |
| `STOCK.LOCATION.MANAGE` | location create / update / deactivate / reactivate / set-default | V41 ✓ |
| `STOCK.TRANSFER.VIEW` | transfer list / get | V41 ✓ |
| `STOCK.TRANSFER.CREATE` | transfer create / dispatch / complete-instant / cancel | V41 ✓ |
| `STOCK.TRANSFER.RECEIVE` | transfer receive | V41 ✓ |
| `STOCK.COUNT.VIEW` | count list / get | V41 ✓ |
| `STOCK.COUNT.CREATE` | count create / enter / cancel | V41 ✓ |
| `STOCK.COUNT.POST` | count post (variance GL) | V41 ✓ |
| `STOCK.BATCH.VIEW` | batch get / list (`@PreAuthorize` in `StockBatchController`); FE route + nav | **NOT seeded** — see TC-INV-901 |
| `INVENTORY.EXPIRY.VIEW` | batch expiring report | V41 ✓ |
| `STOCK.SERIAL.VIEW` | serial get / lookup / list / by-product (`@PreAuthorize` in `StockSerialController`); FE route + nav | **NOT seeded** — see TC-INV-901 |
| `INVENTORY.VALUATION.VIEW` | valuation report (`hasAuthority(...)`) | V17 ✓ |
| `INVENTORY.OPENING.SET` | set opening valuation (`hasAuthority(...)`) | V17 ✓ |

> **Accuracy flag (verified):** `StockBatchController` and `StockSerialController` gate reads on `STOCK.BATCH.VIEW` / `STOCK.SERIAL.VIEW`, and the FE routes (`admin.routes.ts` lines 239-251) + nav (`shell.component.ts` lines 155-156) reference those same `STOCK.*` codes. **However the only seed migration (V41) inserts the codes `INVENTORY.BATCH.VIEW` and `INVENTORY.SERIAL.VIEW`** and grants only those to `ORG_ADMIN`. No migration creates `STOCK.BATCH.VIEW` / `STOCK.SERIAL.VIEW`. Consequently, on seeded data, ALL non-superuser roles (including ORG_ADMIN) are denied batch/serial reads (403), and the nav items are hidden, because the gating code does not exist as a grantable permission. `rootadmin` still works (bypasses permission checks). This is a real latent defect — captured as TC-INV-901 / TC-INV-902.

> **Enum vocabulary note (verified):** `MovementType` declares many values (GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL, ADJUSTMENT, OPENING_BALANCE, plus reserved TRANSFER_OUT/TRANSFER_IN, PURCHASE_RETURN, ISSUE_TO_PROJECT, PRODUCTION_*). The only types this domain's **own UI/endpoints** create are `ADJUSTMENT` and `OPENING_BALANCE`; the others arrive via event-driven flows (purchases/sales/transfer/count). There is **no REST endpoint** for event-driven movements (documented in `StockController` Javadoc, FR-STOCK-15). Transfer movements are published as events; counts post `ADJUSTMENT` movements.

## Type / role variations exercised

| Dimension | Values exercised |
|---|---|
| User roles (allowed) | `rootadmin` (superuser bypass), `ORG_ADMIN`, `STOREKEEPER`, `ACCOUNTANT`, a CUSTOM role (subset), and a NO-PERMISSION user |
| RBAC mapping under test | `STOCK.VIEW`/`STOCK.ADJUST`/`STOCK.OPENING` (storekeeper-style); `STOCK.COUNT.POST` (accountant/variance authority); `STOCK.TRANSFER.RECEIVE` (destination operator); `INVENTORY.VALUATION.VIEW`/`INVENTORY.OPENING.SET` (finance) |
| `LocationType` | WAREHOUSE, STORE, VAN, QUARANTINE, OTHER |
| Location default | default (`isDefault=true`) vs non-default; switching the default |
| `StockTransferStatus` | DRAFT → DISPATCHED → RECEIVED (IN_TRANSIT); DRAFT → COMPLETED (INSTANT); DRAFT → CANCELLED; + illegal transitions |
| Transfer mode | INSTANT (same-branch) vs IN_TRANSIT (cross-branch) |
| `StockCountStatus` | DRAFT/COUNTING → POSTED; DRAFT/COUNTING → CANCELLED; + illegal (re-post, post-when-counted-empty) |
| Count type | FULL vs CYCLE (productUids subset) |
| `SerialStatus` | IN_STOCK, ISSUED, RETURNED (filter + history) |
| `AdjustmentReason` | COUNT_CORRECTION, DAMAGE, SHRINKAGE, EXPIRY, RECEIPT_CORRECTION, OTHER |
| `MasterStatus` (location) | ACTIVE, INACTIVE, ARCHIVED |
| Branch / company scope | single-branch vs multi-branch; default vs non-default branch; user assigned to one vs many; acting in a non-assigned branch (denied); cross-tenant isolation |
| Money / qty wire format | BigDecimal-as-string; money displayed `CUR 1,234.56`; dates ISO `yyyy-MM-dd` |

---

# TEST CASES

## A. Stock On-Hand list, search, pagination, four states

### TC-INV-001 — On-hand list loads with company/branch scope and pagination
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Stock On-Hand (`/admin/stock` · `GET /api/v1/stock/on-hand`)
- **Permission / Role:** `STOCK.VIEW` — runs as `ORG_ADMIN` (or any role with STOCK.VIEW); also as NO-PERMISSION user → expect forbidden
- **Variation:** company = default; branch = default
- **Preconditions / Seed:** at least 25 stockable (`ProductType=GOODS`) products with on-hand rows at the active branch (seed via opening-balances or goods receipts) so >1 page exists.
- **Steps:**
  1. Login, navigate to `/admin/stock`.
  2. Confirm the company selector defaults to the first company; the on-hand table renders rows.
  3. Read the `<app-paginator>`: FIRST, PREVIOUS, page numbers, NEXT, LAST controls present.
  4. Click NEXT; confirm page 2 loads (`page=1&size=20`).
  5. Click FIRST; confirm return to page 1.
- **Test Data:** company "Acme HQ"; default branch.
- **Expected Result:** Table shows quantity (3-dp), reorder level, negative/low flags. Envelope `ApiResponse<List<StockOnHandDto>>` with `meta {page,size,totalElements,totalPages,hasNext}`; UI uses meta for paginator.
- **Convention Assertions:** C2 envelope+meta; C4 four states; C5 paginator (all 5 controls, hidden when 1 page); C7 scope; C8 qty/money formatting; C1 no raw uid shown in table; C6 axe scan clean.
- **Negative / Edge:** NO-PERMISSION user → nav item hidden + route guard `requirePermission('STOCK.VIEW')` blocks → forbidden; on backend, `GET /on-hand` → 403.

### TC-INV-002 — On-hand search filter (debounced) resets to page 0
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Stock On-Hand (`/admin/stock` · `GET /api/v1/stock/on-hand?q=`)
- **Permission / Role:** `STOCK.VIEW` — runs as `STOREKEEPER`
- **Preconditions / Seed:** products including one named/coded "WIDGET-100".
- **Steps:**
  1. Navigate to `/admin/stock`, go to page 2.
  2. Type "WIDGET" into the search box (getByPlaceholder/getByLabel).
  3. Wait for the 300ms debounce; confirm the request fires with `q=WIDGET&page=0`.
- **Test Data:** `q = "WIDGET"`.
- **Expected Result:** Results filtered; current page resets to 0; matching rows shown.
- **Convention Assertions:** C4 loading→idle transition; C5 paginator recalculated; C2 meta.
- **Negative / Edge:** `q` matching nothing → empty state (distinct from error/loading).

### TC-INV-003 — On-hand empty / loading / error / forbidden states
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Stock On-Hand (`/admin/stock`)
- **Permission / Role:** `STOCK.VIEW` — runs as a CUSTOM role granted only `STOCK.VIEW`
- **Steps:**
  1. **Empty:** select a company/branch with no on-hand rows → distinct empty message (not a spinner, not error).
  2. **Loading:** throttle network; confirm loading indicator while fetch in flight.
  3. **Error:** force a 500 (e.g. invalid company) → error state with retry affordance.
  4. **Forbidden:** as NO-PERMISSION user navigate to `/admin/stock` → forbidden state / nav hidden.
- **Expected Result:** Four visually distinct states (`state` signal: loading|idle|error|forbidden).
- **Convention Assertions:** C4 four-state; C3 RBAC 403→forbidden (component maps HTTP 403 to `'forbidden'`).
- **Negative / Edge:** 403 vs 500 must render differently (forbidden vs error).

### TC-INV-004 — On-hand negative + low-stock derived flags
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Stock On-Hand (`/admin/stock`)
- **Permission / Role:** `STOCK.VIEW` — runs as `STOREKEEPER`
- **Preconditions / Seed:** one product oversold to negative on-hand; one product with `reorderLevel` set and `quantity <= reorderLevel`.
- **Steps:** open `/admin/stock`; locate the two rows.
- **Expected Result:** `negative=true` row visually flagged (overselling indicator, FR-STOCK-04); `low=true` row flagged (reorder indicator). Both flags are server-derived in `StockOnHandDto.from()`, not stored.
- **Convention Assertions:** C8 qty formatting; C2 envelope.
- **Negative / Edge:** product with `reorderLevel=null` is never `low`.

## B. Stock On-Hand: by-location & by-product views

### TC-INV-010 — Switch to "By Location" view (paged per-location rows)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** By-location (`/admin/stock` view toggle · `GET /api/v1/stock/on-hand/by-location?companyId=&branchId=`)
- **Permission / Role:** `STOCK.VIEW` — runs as `ORG_ADMIN`; also NO-PERMISSION → forbidden
- **Variation:** branch must be selected (required `branchId`)
- **Preconditions / Seed:** multiple stock locations at the active branch with on-hand across them.
- **Steps:**
  1. Navigate to `/admin/stock`; select a branch in the branch selector.
  2. Toggle view mode to "By Location".
  3. Confirm rows show location code+name and product code+name, quantity, onHandValue, avgCost, currency.
- **Test Data:** branch = "Main Branch".
- **Expected Result:** `LocationOnHandRowDto` rows; paged; money formatted with currency.
- **Convention Assertions:** C1 location/product shown by NAME (uid only in path param of by-product endpoint, never typed); C5 paginator; C7 branch scope (companyId+branchId on wire); C8 money.
- **Negative / Edge:** no branch selected → component returns early (no fetch); user acting in a non-assigned branch → backend scope denies / empty.

### TC-INV-011 — "By Product" view selects product via picker, lists all locations
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** By-product (`/admin/stock` · `GET /api/v1/stock/on-hand/by-product/uid/{productUid}?companyId=`)
- **Permission / Role:** `STOCK.VIEW` — runs as `STOREKEEPER`
- **Preconditions / Seed:** a GOODS product held at ≥2 locations.
- **Steps:**
  1. Toggle to "By Product"; type product name in the search; pick a result by NAME (`code — name`).
  2. Confirm the per-location breakdown loads (plain list, no pagination per controller signature).
- **Test Data:** product "WIDGET-100".
- **Expected Result:** All locations holding the product, with quantity/value. Product chosen by name; `productUid` placed in URL path under the hood.
- **Convention Assertions:** C1 picker-by-name, uid in path only, no raw uid typed/shown; C4 empty when product held nowhere; C6 axe.
- **Negative / Edge:** SERVICE product (non-stockable) → no on-hand → empty result.

## C. Stock On-Hand: manual adjustment

### TC-INV-020 — Manual adjustment (positive) with reason COUNT_CORRECTION
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Adjustment (`/admin/stock` adjust form · `POST /api/v1/stock/adjustments`)
- **Permission / Role:** `STOCK.ADJUST` — runs as `STOREKEEPER`; also as a role lacking `STOCK.ADJUST` (e.g. STOCK.VIEW-only) → adjust action hidden + API 403
- **Variation:** product = GOODS; reason = COUNT_CORRECTION; positive delta
- **Preconditions / Seed:** an on-hand row for a GOODS product at the active branch.
- **Steps:**
  1. Open `/admin/stock`; on a row click "Adjust".
  2. Confirm the product is pre-selected by name (picker); reason dropdown lists exactly the 6 `AdjustmentReason` values.
  3. Enter quantity `+10`, reason COUNT_CORRECTION, note "cycle count fix".
  4. Submit.
- **Test Data:** quantity `10`, reason `COUNT_CORRECTION`.
- **Expected Result:** HTTP 201; returns `StockMovementDto` (`movementType=ADJUSTMENT`, `direction=IN`, signed quantity). On-hand increases by 10; success toast; list reloads.
- **Convention Assertions:** C1 product via picker by name; C3 RBAC `@perm.scoped(productUid,'product','STOCK.ADJUST')`; C8 qty string on wire; C9 append-only (a new movement row, not an edit).
- **Negative / Edge:** missing reason → 400 (`@NotNull AdjustmentReason`); quantity = 0 → FE blocks ("non-zero"); role without STOCK.ADJUST → 403.

### TC-INV-021 — Manual adjustment (negative) reason DAMAGE drives stock down
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Adjustment (`POST /api/v1/stock/adjustments`)
- **Permission / Role:** `STOCK.ADJUST` — runs as `STOREKEEPER`
- **Variation:** reason = DAMAGE; negative delta
- **Steps:** adjust a row by `-3`, reason DAMAGE; submit.
- **Test Data:** quantity `-3`, reason `DAMAGE`.
- **Expected Result:** 201; `direction=OUT`; on-hand decreases by 3.
- **Convention Assertions:** C8 signed qty; C9 append-only movement.
- **Negative / Edge:** negative beyond on-hand may produce a negative on-hand (allowed/flagged per BR — overselling indicator), confirm `negative` flag set, not a hard reject.

### TC-INV-022 — Adjustment reason variations (each AdjustmentReason)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Adjustment (`POST /api/v1/stock/adjustments`)
- **Permission / Role:** `STOCK.ADJUST` — runs as `STOREKEEPER`
- **Variation:** iterate reason ∈ {COUNT_CORRECTION, DAMAGE, SHRINKAGE, EXPIRY, RECEIPT_CORRECTION, OTHER}
- **Steps:** for each reason submit a ±1 adjustment; for `OTHER` include a free-text note (recommended per enum doc).
- **Expected Result:** all 6 accepted (201); movement records carry the `reasonCode`.
- **Convention Assertions:** C2 envelope.
- **Negative / Edge:** an invalid/unknown reason string → 400 (service-layer enum gate).

### TC-INV-023 — Adjustment denied across tenant / non-assigned branch scope
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Adjustment (`POST /api/v1/stock/adjustments`)
- **Permission / Role:** `STOCK.ADJUST` — runs as a tenant-B user who HAS STOCK.ADJUST in tenant B
- **Variation:** target product belongs to tenant A
- **Steps:** as tenant-B user, attempt to adjust a tenant-A product uid.
- **Expected Result:** denied — `@perm.scoped` resolves product by uid within caller scope; cross-tenant uid → 403/404 (not found in scope).
- **Convention Assertions:** C7 multi-tenancy isolation; C3 scoped permission.
- **Negative / Edge:** user acting in a branch they are not assigned to (X-Branch-Uid for a non-assigned branch) → denied.

## D. Stock On-Hand: opening balance + reorder level

### TC-INV-030 — Opening balance for a never-tracked product
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opening balance (`/admin/stock` inline form · `POST /api/v1/stock/opening-balances`)
- **Permission / Role:** `STOCK.OPENING` — runs as `STOREKEEPER` (or role with STOCK.OPENING); also a role lacking it → form hidden + 403
- **Variation:** product = GOODS, never had a movement at this branch
- **Preconditions / Seed:** a GOODS product with NO prior stock movement at the active branch.
- **Steps:**
  1. Open `/admin/stock`; click "Opening balance".
  2. Pick the product by name (picker); enter quantity `100`, note "go-live".
  3. Submit.
- **Test Data:** quantity `100`.
- **Expected Result:** 201; `StockMovementDto` (`movementType=OPENING_BALANCE`, `direction=IN`); on-hand becomes 100.
- **Convention Assertions:** C1 product via picker; C3 `@perm.scoped(productUid,'product','STOCK.OPENING')`; C8 positive qty string.
- **Negative / Edge:** quantity ≤ 0 → FE blocks + backend `@Positive` 400; product with ANY prior movement → backend rejects ("opening rejected if prior movement exists", D-6) → error toast.

### TC-INV-031 — Opening balance rejected when stock already tracked
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Opening balance (`POST /api/v1/stock/opening-balances`)
- **Permission / Role:** `STOCK.OPENING` — runs as `STOREKEEPER`
- **Preconditions / Seed:** a product that already has an opening balance / prior movement.
- **Steps:** attempt a second opening balance for that product at the same branch.
- **Expected Result:** backend rejects (400/409); inline error from `errors[]`; on-hand unchanged.
- **Convention Assertions:** C2 envelope errors surfaced; C9 append-only not bypassed.
- **Negative / Edge:** negative quantity → `@Positive` 400.

### TC-INV-032 — Set reorder level (and clear by blank)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Reorder level (`/admin/stock` inline edit · `PUT /api/v1/stock/on-hand/uid/{uid}/reorder-level`)
- **Permission / Role:** `STOCK.ADJUST` — runs as `STOREKEEPER`; also a STOCK.VIEW-only role → edit hidden + 403
- **Steps:**
  1. On a row click reorder-level edit; enter `5`; save → confirm row now flagged low if `quantity<=5`.
  2. Edit again; clear the field (blank) and save → reorder cleared; `low=false`.
- **Test Data:** reorderLevel `5`, then blank (null).
- **Expected Result:** returns updated `StockOnHandDto`; `low` recomputed; success toast.
- **Convention Assertions:** C1 uid only in URL path (`/on-hand/uid/{uid}`), never shown in the table; C3 `@perm.scoped(uid,'stockonhand','STOCK.ADJUST')`; C8 string on wire.
- **Negative / Edge:** negative reorder level → FE blocks + backend `chk_stock_on_hand_reorder` → friendly 400.

## E. Stock On-Hand: movement ledger

### TC-INV-040 — Movements ledger drawer (chronological, paged)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Movements (`/admin/stock` drawer · `GET /api/v1/stock/products/uid/{productUid}/movements`)
- **Permission / Role:** `STOCK.VIEW` — runs as `STOREKEEPER`
- **Preconditions / Seed:** a product with mixed movements: OPENING_BALANCE, ADJUSTMENT(+/-), and at least one event-driven movement (GOODS_RECEIPT or SALE_ISSUE seeded via purchase/sale flows).
- **Steps:**
  1. On a row open "Movements".
  2. Confirm rows show movementType, signed quantity, direction (IN/OUT badge), occurredAt date, reasonCode/note where present, source document type.
  3. Page through with the drawer paginator.
- **Test Data:** product with ≥21 movements (multi-page).
- **Expected Result:** `StockMovementDto` rows in time order; direction derived from sign; envelope+meta.
- **Convention Assertions:** C2 meta; C5 paginator in drawer; C8 ISO dates + signed qty; C9 each movement append-only (reversals appear as separate SALE_REVERSAL/GOODS_RECEIPT_REVERSAL rows, never edits).
- **Negative / Edge:** product with no movements → empty drawer; productUid not resolvable → error state.

## F. Stock Locations CRUD

### TC-INV-050 — Create location (each LocationType) on a branch via picker
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Locations (`/admin/stock/locations` · `POST /api/v1/stock-locations`)
- **Permission / Role:** `STOCK.LOCATION.MANAGE` — runs as `ORG_ADMIN`; also a `STOCK.LOCATION.VIEW`-only role → create button hidden + 403
- **Variation:** locationType iterated WAREHOUSE / STORE / VAN / QUARANTINE / OTHER; branch chosen via `<app-uid-picker>`
- **Preconditions / Seed:** at least one branch.
- **Steps:**
  1. Open `/admin/stock/locations`; click "Create".
  2. Enter code `WH-01`, name `Main Warehouse`, pick locationType, pick a branch by NAME via the uid picker.
  3. Submit; repeat per locationType.
- **Test Data:** code `WH-01`..`OT-05`, names per type.
- **Expected Result:** 201; `StockLocationDto` (`status=ACTIVE`, `isDefault=false` unless makeDefault). New row in list.
- **Convention Assertions:** C1 branch via picker by name, branch uid stored under the hood, no raw uid typed/shown; C3 `STOCK.LOCATION.MANAGE`; C4 list states.
- **Negative / Edge:** blank code/name → FE blocks + backend `@NotBlank` 400; code > 30 chars or name > 120 → `@Size` 400; missing locationType → 400; duplicate code within branch → backend unique-constraint 400/409.

### TC-INV-051 — Create location with makeDefault=true clears prior default
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Locations (`POST /api/v1/stock-locations`)
- **Permission / Role:** `STOCK.LOCATION.MANAGE` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** branch already has a default location L1.
- **Steps:** create L2 with the "make default" checkbox ticked.
- **Expected Result:** L2 `isDefault=true`; L1 `isDefault=false` after reload (single default per branch invariant).
- **Convention Assertions:** C7 branch-scoped default; C2 envelope.
- **Negative / Edge:** verify only one location per branch carries `isDefault=true`.

### TC-INV-052 — Edit location name + type
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Locations (`/admin/stock/locations` inline edit · `PUT /api/v1/stock-locations/uid/{uid}`)
- **Permission / Role:** `STOCK.LOCATION.MANAGE` — runs as `ORG_ADMIN`; VIEW-only role → edit hidden + 403
- **Steps:** on a row click edit; change name + locationType; save.
- **Test Data:** name `Cold Store`, type `QUARANTINE`.
- **Expected Result:** updated `StockLocationDto`; row updated in place. (Code is NOT editable — `UpdateStockLocationRequest` has only name + locationType.)
- **Convention Assertions:** C1 uid in URL path only; C3 `@perm.scoped(uid,'stocklocation','STOCK.LOCATION.MANAGE')`.
- **Negative / Edge:** blank name → `@NotBlank` 400; missing locationType → `@NotNull` 400; attempting to change code → not exposed by the request DTO.

### TC-INV-053 — Set a location as the branch default
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Locations (`PATCH /api/v1/stock-locations/uid/{uid}/default`)
- **Permission / Role:** `STOCK.LOCATION.MANAGE` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** ≥2 active locations on a branch.
- **Steps:** on a non-default location click "Set default"; confirm reload.
- **Expected Result:** target `isDefault=true`; previous default cleared.
- **Convention Assertions:** C1 uid in path; C7 per-branch default.
- **Negative / Edge:** set-default on an INACTIVE location — confirm backend behaviour (reject or auto-handle); document actual.

### TC-INV-054 — Deactivate then reactivate a location (soft lifecycle)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Locations (`DELETE .../uid/{uid}` deactivate · `PATCH .../uid/{uid}/reactivate`)
- **Permission / Role:** `STOCK.LOCATION.MANAGE` — runs as `ORG_ADMIN`
- **Steps:**
  1. On an ACTIVE location click "Deactivate" → `DELETE` returns 204; row shows `INACTIVE`.
  2. Click "Reactivate" → row returns to `ACTIVE`.
- **Expected Result:** `MasterStatus` transitions ACTIVE→INACTIVE→ACTIVE; never hard-deleted.
- **Convention Assertions:** C9 soft-delete (deactivate, not destroy); C1 uid path-only; C3 manage gate.
- **Negative / Edge:** deactivate the branch DEFAULT location — confirm behaviour (reject vs allow & clear default); document actual. Deactivate a location holding stock — confirm behaviour.

### TC-INV-055 — Location list four states + pagination + RBAC
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Locations (`/admin/stock/locations` · `GET /api/v1/stock-locations`)
- **Permission / Role:** `STOCK.LOCATION.VIEW` — runs as a CUSTOM role with VIEW only; NO-PERMISSION → forbidden
- **Steps:** exercise loading / empty / error / forbidden; page through when >20 locations.
- **Expected Result:** four distinct states; paginator; VIEW-only sees list but no manage actions.
- **Convention Assertions:** C4; C5; C3 (manage buttons hidden for VIEW-only); C6 axe.
- **Negative / Edge:** route guard `requirePermission('STOCK.LOCATION.VIEW')` blocks NO-PERMISSION user.

### TC-INV-056 — Active-locations endpoint feeds pickers (by branch)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Locations (`GET /api/v1/stock-locations/active?branchUid=`)
- **Permission / Role:** `STOCK.LOCATION.VIEW` — runs as `STOREKEEPER`
- **Steps:** in the transfer-create and count-create screens, select a branch; confirm the location picker is populated only with ACTIVE locations for that branch (uses `activeForBranch(branchUid)`).
- **Expected Result:** only ACTIVE locations returned; INACTIVE/ARCHIVED excluded.
- **Convention Assertions:** C1 picker by name; C7 branch scope.
- **Negative / Edge:** branch with no active locations → empty picker.

## G. Stock Transfers — lifecycle

### TC-INV-060 — Create IN_TRANSIT transfer (DRAFT) with two locations + lines
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Transfers (`/admin/stock-transfers/create` · `POST /api/v1/stock-transfers`)
- **Permission / Role:** `STOCK.TRANSFER.CREATE` — runs as `STOREKEEPER`; also a TRANSFER.VIEW-only role → Create blocked + 403
- **Variation:** transferMode = IN_TRANSIT; cross-branch (source branch ≠ dest branch)
- **Preconditions / Seed:** ≥2 branches, each with an active location; stockable products with on-hand at source.
- **Steps:**
  1. Navigate to `/admin/stock-transfers/create`.
  2. Pick source branch (picker) → pick source location (picker); pick dest branch → dest location.
  3. Set transferDate (ISO); transferMode = IN_TRANSIT; add a line: product (picker) + qty `5`; add a second line.
  4. Submit.
- **Test Data:** transferDate `2026-06-14`, line1 qty `5`, line2 qty `2`.
- **Expected Result:** 201; `StockTransferDto` `status=DRAFT`, `transferMode=IN_TRANSIT`, lines persisted, `transferNumber` assigned; redirect to detail.
- **Convention Assertions:** C1 all of source/dest/product chosen via pickers by name, no uid typed/shown; C3 `STOCK.TRANSFER.CREATE`; C8 ISO date, positive qty strings.
- **Negative / Edge:** source==dest location → FE blocks ("must be different"); empty lines → `@NotEmpty` 400; line qty ≤ 0 → `@Positive` 400; missing transferDate → 400; blank transferMode → `@NotBlank` 400.

### TC-INV-061 — IN_TRANSIT happy path: DRAFT → DISPATCHED → RECEIVED
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Transfers (detail · `PATCH .../uid/{uid}/dispatch`, `PATCH .../uid/{uid}/receive`)
- **Permission / Role:** dispatch=`STOCK.TRANSFER.CREATE` (runs as `STOREKEEPER`); receive=`STOCK.TRANSFER.RECEIVE` (runs as a destination operator with RECEIVE)
- **Variation:** transferMode = IN_TRANSIT
- **Preconditions / Seed:** a DRAFT IN_TRANSIT transfer from TC-INV-060.
- **Steps:**
  1. On detail, with DRAFT + IN_TRANSIT, click "Dispatch" → status DISPATCHED, `dispatchedAt` set. Source stock decreases (TRANSFER_OUT event).
  2. As the RECEIVE-holder, click "Receive" → status RECEIVED, `receivedAt` set. Dest stock increases (TRANSFER_IN event).
- **Expected Result:** statuses transition exactly DRAFT→DISPATCHED→RECEIVED; on-hand moves between locations; events published (`STOCK.TRANSFER.DISPATCHED`, `STOCK.TRANSFER.RECEIVED`).
- **Convention Assertions:** C3 split permissions (dispatch vs receive); C8 stock deltas; C9 movements append-only.
- **Negative / Edge:** Dispatch button hidden unless DRAFT+IN_TRANSIT (detail `canDispatch`); Receive button hidden unless DISPATCHED (`canReceiveTransfer`); user without RECEIVE → 403 on receive.

### TC-INV-062 — INSTANT same-branch transfer: DRAFT → COMPLETED in one step
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Transfers (`PATCH .../uid/{uid}/complete-instant`)
- **Permission / Role:** `STOCK.TRANSFER.CREATE` — runs as `STOREKEEPER`
- **Variation:** transferMode = INSTANT; same branch, two locations
- **Preconditions / Seed:** two active locations in the SAME branch; stock at source location.
- **Steps:**
  1. Create a transfer with both locations in the same branch, transferMode = INSTANT.
  2. On detail (DRAFT+INSTANT) click "Complete instant".
- **Expected Result:** status DRAFT→COMPLETED in one TX (no in-transit); stock moves immediately between locations.
- **Convention Assertions:** C3 CREATE gate (`@perm.scoped(uid,'stocktransfer','STOCK.TRANSFER.CREATE')`); C9 movements.
- **Negative / Edge:** "Complete instant" hidden unless DRAFT+INSTANT (`canCompleteInstant`); complete-instant on an IN_TRANSIT transfer → backend rejects.

### TC-INV-063 — Cancel a DRAFT transfer
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Transfers (`DELETE .../uid/{uid}`)
- **Permission / Role:** `STOCK.TRANSFER.CREATE` — runs as `STOREKEEPER`
- **Steps:** on a DRAFT transfer detail click "Cancel" → 204; reload shows CANCELLED.
- **Expected Result:** status DRAFT→CANCELLED; no stock movement occurred.
- **Convention Assertions:** C9 soft lifecycle (cancel, not delete record); C3 CREATE gate.
- **Negative / Edge:** Cancel button hidden unless DRAFT (`canCancel`).

### TC-INV-064 — Illegal transfer transitions are rejected
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Transfers (lifecycle endpoints)
- **Permission / Role:** appropriate transfer permissions — runs as `STOREKEEPER` / RECEIVE-holder
- **Steps & Expected (each must be rejected by backend, with FE buttons hidden):**
  1. Receive a DRAFT (not dispatched) → reject (must be DISPATCHED).
  2. Dispatch a DISPATCHED/RECEIVED/COMPLETED/CANCELLED → reject.
  3. Complete-instant a DISPATCHED/RECEIVED transfer → reject.
  4. Cancel a DISPATCHED/RECEIVED/COMPLETED transfer → reject (only DRAFT cancellable).
  5. Dispatch an INSTANT-mode transfer → reject (dispatch is IN_TRANSIT-only).
  6. Receive an INSTANT-mode transfer → reject.
- **Expected Result:** backend returns a 400/409 illegal-transition error for each; FE never offers the action for the wrong state/mode.
- **Convention Assertions:** C2 error envelope; status-machine integrity per `StockTransferStatus` doc (IN_TRANSIT: DRAFT→DISPATCHED→RECEIVED; INSTANT: DRAFT→COMPLETED; both: DRAFT→CANCELLED).
- **Negative / Edge:** double-dispatch (idempotency) — second call rejected.

### TC-INV-065 — Transfer list four states + pagination + detail by uid
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Transfers (`/admin/stock-transfers` · `GET /api/v1/stock-transfers`; `/admin/stock-transfers/uid/:uid` · `GET .../uid/{uid}`)
- **Permission / Role:** `STOCK.TRANSFER.VIEW` — runs as a VIEW-only CUSTOM role; NO-PERMISSION → forbidden
- **Steps:** list page (loading/empty/error/forbidden + paginator); open a row → detail loads by uid; status badge reflects status.
- **Expected Result:** four states; paginator; detail `getByUid` scoped (`@perm.scoped(uid,'stocktransfer','STOCK.TRANSFER.VIEW')`).
- **Convention Assertions:** C1 uid only in route, transfer referenced by transferNumber in UI; C4; C5; C3.
- **Negative / Edge:** open a transfer uid from another tenant → 403/404 (scope).

### TC-INV-066 — Transfer create RBAC: VIEW-only cannot create
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Transfers (`/admin/stock-transfers/create`)
- **Permission / Role:** `STOCK.TRANSFER.CREATE` — runs as a `STOCK.TRANSFER.VIEW`-only user → expect forbidden
- **Steps:** as VIEW-only user navigate to `/admin/stock-transfers/create`.
- **Expected Result:** route guard `requirePermission('STOCK.TRANSFER.CREATE')` blocks; nav "create" affordance hidden; direct POST → 403.
- **Convention Assertions:** C3 RBAC.

## H. Stock Counts — lifecycle

### TC-INV-070 — Create FULL count → snapshot system_qty → COUNTING
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Counts (`/admin/stock-counts/create` · `POST /api/v1/stock-counts`)
- **Permission / Role:** `STOCK.COUNT.CREATE` — runs as `STOREKEEPER`; also a COUNT.VIEW-only role → Create blocked + 403
- **Variation:** countType = FULL
- **Preconditions / Seed:** an active location with on-hand across several GOODS products.
- **Steps:**
  1. Navigate to `/admin/stock-counts/create`.
  2. Select company → branch; pick a location by NAME (uid picker); countDate defaults to today; countType = FULL; submit.
- **Test Data:** countDate `2026-06-14`, countType `FULL`.
- **Expected Result:** 201; `StockCountDto` with `status=COUNTING` (created DRAFT then frozen to COUNTING per service doc), `frozenAt` set, `countNumber` assigned, one line per on-hand product with `systemQty` snapshot; redirect to detail.
- **Convention Assertions:** C1 location via picker by name; C3 `STOCK.COUNT.CREATE`; C8 ISO date; C9 immutable snapshot.
- **Negative / Edge:** no location selected → FE blocks; missing countDate → 400; blank countType → `@NotBlank` 400.

### TC-INV-071 — Create CYCLE count limited to a product subset
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Counts (`POST /api/v1/stock-counts`)
- **Permission / Role:** `STOCK.COUNT.CREATE` — runs as `STOREKEEPER`
- **Variation:** countType = CYCLE with `productUids` subset
- **Steps:** create a count with countType CYCLE and a subset of product uids (chosen via pickers, where the UI supports subset selection).
- **Expected Result:** 201; count lines only for the chosen subset (empty/null subset = FULL location scope per DTO doc).
- **Convention Assertions:** C1 products via picker; C2 envelope.
- **Negative / Edge:** productUids list > 500 → `@Size(max=500)` 400.

### TC-INV-072 — Enter counted quantities (COUNTING) and compute variance
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Counts (`/admin/stock-counts/uid/:uid` · `PATCH .../uid/{uid}/enter`)
- **Permission / Role:** `STOCK.COUNT.CREATE` — runs as `STOREKEEPER`
- **Preconditions / Seed:** a COUNTING count from TC-INV-070.
- **Steps:**
  1. Open the count detail; for several lines type a `countedQty` (and optional reasonCode).
  2. Click "Enter / Save".
- **Test Data:** line A counted `12` vs system `10` (variance +2); line B counted `7` vs system `9` (variance −2, reason SHRINKAGE).
- **Expected Result:** lines persist `countedQty`; `varianceQty = countedQty − systemQty` shown with directional colour; document stays COUNTING.
- **Convention Assertions:** C8 qty strings; C2 envelope.
- **Negative / Edge:** empty entry (no qty on any line) → FE blocks ("enter at least one"); `@NotEmpty` lines → 400; non-numeric qty filtered out by FE.

### TC-INV-073 — Post a count → ADJUSTMENT movements + one GL variance journal
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Counts (`PATCH .../uid/{uid}/post?postingDate=`)
- **Permission / Role:** `STOCK.COUNT.POST` — runs as `ACCOUNTANT` (variance-posting authority); also a COUNT.CREATE-only user → Post hidden + 403
- **Preconditions / Seed:** a COUNTING count with at least one variance entered.
- **Steps:**
  1. On detail click "Post"; supply postingDate (ISO); confirm.
- **Test Data:** postingDate `2026-06-14`.
- **Expected Result:** status COUNTING→POSTED; `postedAt` set; per varying line a `movementUid` (ADJUSTMENT movement) and `varianceValue`; one `varianceGlEntryUid` on the document; POSTED is read-only.
- **Convention Assertions:** C3 separate POST authority (`@perm.scoped(uid,'stockcount','STOCK.COUNT.POST')`); C8 ISO postingDate + money variance; C9 append-only postings.
- **Negative / Edge:** post with no variance lines / nothing counted → backend behaviour (reject or no-op journal) — document actual; missing/invalid postingDate format → 400.

### TC-INV-074 — Cancel a count (DRAFT/COUNTING → CANCELLED)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Counts (`DELETE .../uid/{uid}`)
- **Permission / Role:** `STOCK.COUNT.CREATE` — runs as `STOREKEEPER`
- **Steps:** on a COUNTING count detail click "Cancel" → 204; reload shows CANCELLED (read-only).
- **Expected Result:** status →CANCELLED; no GL/movements posted.
- **Convention Assertions:** C9 soft lifecycle; C3 CREATE gate.
- **Negative / Edge:** cancel a POSTED count → reject (POSTED immutable, FR-INVD-15).

### TC-INV-075 — Illegal count transitions rejected
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Counts (lifecycle endpoints)
- **Permission / Role:** appropriate count permissions — runs as `STOREKEEPER` / `ACCOUNTANT`
- **Steps & Expected (each rejected):**
  1. Post an already-POSTED count → reject (immutable; corrections require a new count).
  2. Enter quantities on a POSTED or CANCELLED count → reject.
  3. Cancel a POSTED count → reject.
  4. Post a CANCELLED count → reject.
- **Expected Result:** backend 400/409 per illegal transition; FE shows read-only (no action buttons) for POSTED/CANCELLED (`isPosted`/`isCancelled` derived signals).
- **Convention Assertions:** state-machine integrity per `StockCountStatus` (DRAFT→COUNTING→POSTED; DRAFT/COUNTING→CANCELLED); C2 errors.
- **Negative / Edge:** enter on a count not yet COUNTING.

### TC-INV-076 — Count list four states + pagination + RBAC
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Counts (`/admin/stock-counts` · `GET /api/v1/stock-counts`)
- **Permission / Role:** `STOCK.COUNT.VIEW` — runs as a VIEW-only CUSTOM role; NO-PERMISSION → forbidden
- **Steps:** exercise loading/empty/error/forbidden; paginate; open a count by uid (detail `getByUid` scoped).
- **Expected Result:** four states; paginator; counts referenced by countNumber, not uid, in the table.
- **Convention Assertions:** C1 uid only in route; C4; C5; C3; C6 axe.
- **Negative / Edge:** cross-tenant count uid → 403/404.

## I. Stock Batches / Lots

### TC-INV-080 — Batch list by location + product (read-only) via pickers
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Batches (`/admin/stock/batches` · `GET /api/v1/stock-batches?companyId=&locationId=&productId=`)
- **Permission / Role:** `STOCK.BATCH.VIEW` (per controller @PreAuthorize) — runs as `rootadmin` (see caveat); also any non-superuser → forbidden today (TC-INV-901)
- **Preconditions / Seed:** batch records created internally on receipt of a lot-tracked product into a location.
- **Steps:**
  1. Navigate to `/admin/stock/batches`.
  2. Pick a location by NAME (uid picker → resolves to numeric `locationId` param under the hood); pick a product by NAME (→ `productId`).
  3. Confirm batch rows show lotNumber, manufactureDate, expiryDate, qtyOnHand, expired flag.
- **Test Data:** lotNumber `LOT-2026-A`, expiryDate near future.
- **Expected Result:** `StockBatchDto` rows paged; uid→id resolution happens in the component (picker selects uid, computed `selectedLocationId`/`selectedProductId` supply the API id params).
- **Convention Assertions:** C1 location/product via picker by NAME, uid never typed; numeric id is a hidden query param derived from the picked uid (still no uid/id shown on screen); C5 paginator; C4 states; C6 axe.
- **Negative / Edge:** missing location or product → no fetch (component guards); non-superuser → 403 due to ungranted `STOCK.BATCH.VIEW` (TC-INV-901).

### TC-INV-081 — Expiring batches report (horizon date)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Batches (`/admin/stock/batches` Expiring tab · `GET /api/v1/stock-batches/expiring?companyId=&horizon=YYYY-MM-DD`)
- **Permission / Role:** `INVENTORY.EXPIRY.VIEW` — runs as `ORG_ADMIN` (seeded); also a user without it → forbidden/empty
- **Preconditions / Seed:** batches with qty>0 expiring before and after a horizon.
- **Steps:**
  1. Open `/admin/stock/batches`; switch to "Expiring Soon".
  2. Default horizon = today+30; confirm batches expiring on/before horizon (qty>0) are listed; already-expired rows flagged red, near-expiry warned.
  3. Change the horizon date; confirm the result set changes.
- **Test Data:** horizon `2026-07-14`.
- **Expected Result:** paged `StockBatchDto` expiring set; expired vs near-expiry styling differs.
- **Convention Assertions:** C8 ISO horizon date; C5 paginator; C2 meta.
- **Negative / Edge:** horizon with no expiring batches → empty state; the expiry tab is gated by `INVENTORY.EXPIRY.VIEW` separately from `STOCK.BATCH.VIEW` (a user may see expiry but not by-location, or vice versa) — assert both gates independently.

### TC-INV-082 — Batch detail by uid (scoped)
- **Type:** Manual
- **Priority:** P3
- **Module / Submodule:** Batches (`GET /api/v1/stock-batches/uid/{uid}`)
- **Permission / Role:** `STOCK.BATCH.VIEW` — runs as `rootadmin` (see TC-INV-901)
- **Steps:** fetch a batch by uid via API/deep link.
- **Expected Result:** single `StockBatchDto`; `@perm.scoped(uid,'stockbatch','STOCK.BATCH.VIEW')`.
- **Convention Assertions:** C1 uid only in path; C7 scope.
- **Negative / Edge:** cross-tenant batch uid → 403/404.

## J. Stock Serial Numbers

### TC-INV-090 — Serial list by location + product, with status filter
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Serials (`/admin/stock/serials` By-Location · `GET /api/v1/stock-serials?companyId=&locationId=&productId=[&status=]`)
- **Permission / Role:** `STOCK.SERIAL.VIEW` (per controller) — runs as `rootadmin` (see TC-INV-901); non-superuser → forbidden today
- **Variation:** status filter ∈ {∅, IN_STOCK, ISSUED, RETURNED}
- **Preconditions / Seed:** serial-tracked product with serials in each status at a location.
- **Steps:**
  1. Navigate to `/admin/stock/serials` (By Location mode).
  2. Pick location + product via pickers; leave status blank → all serials.
  3. Set status = IN_STOCK → only in-stock serials; repeat ISSUED, RETURNED.
- **Test Data:** serialNumber e.g. `SN-0001`.
- **Expected Result:** `StockSerialDto` rows show serialNumber, serialStatus badge, location, received/issued document uids (truncated via SlicePipe); status filter narrows correctly.
- **Convention Assertions:** C1 location/product via picker by name; status by enum dropdown; C5 paginator; C4 states; C6 axe.
- **Negative / Edge:** missing location/product → no fetch; non-superuser → 403 (TC-INV-901).

### TC-INV-091 — Serial history By Product (full lifecycle)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Serials (`/admin/stock/serials` By-Product · `GET /api/v1/stock-serials/product/uid/{productUid}?companyId=`)
- **Permission / Role:** `STOCK.SERIAL.VIEW` — runs as `rootadmin` (see caveat)
- **Preconditions / Seed:** a serial that went IN_STOCK → ISSUED → RETURNED → IN_STOCK.
- **Steps:** switch to "By Product"; pick the product by name; confirm full history paged (FR-INVD-27).
- **Expected Result:** all serials for the product across statuses; transitions reflected by current `serialStatus`.
- **Convention Assertions:** C1 productUid in path only; C5 paginator.
- **Negative / Edge:** product with no serials → empty.

### TC-INV-092 — Serial lookup by serial number
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Serials (`/admin/stock/serials` lookup · `GET /api/v1/stock-serials/lookup?companyId=&productId=&serialNumber=`)
- **Permission / Role:** `STOCK.SERIAL.VIEW` — runs as `rootadmin` (see caveat)
- **Preconditions / Seed:** a known serial `SN-0001` for a product.
- **Steps:**
  1. Pick a product (to supply `productId`); type `SN-0001`; click "Look up".
  2. Confirm the matched serial card shows its current status/location.
  3. Look up a non-existent serial.
- **Test Data:** serialNumber `SN-0001`; then `SN-XXXX`.
- **Expected Result:** found → single `StockSerialDto` displayed; not found → 404 mapped to a distinct "not-found" UI state (component handles 404 → `'not-found'`).
- **Convention Assertions:** C1 serial referenced by its human serial number; C4 distinct not-found vs error.
- **Negative / Edge:** lookup without a product selected → no fetch; cross-product serial → not found.

### TC-INV-093 — SerialStatus enum coverage in filter dropdown
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Serials (`/admin/stock/serials`)
- **Permission / Role:** `STOCK.SERIAL.VIEW` — runs as `rootadmin`
- **Steps:** confirm the status dropdown lists exactly: (blank/All), IN_STOCK, ISSUED, RETURNED — matching `SerialStatus`.
- **Expected Result:** exactly those four options; each badge colour distinct.
- **Convention Assertions:** enum fidelity.
- **Negative / Edge:** no extra/invented statuses.

## K. Inventory Valuation & GL reconciliation

### TC-INV-100 — Valuation report renders rows + total + GL recon (ties=true)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Valuation (`/admin/stock/valuation` · `GET /api/v1/stock/valuation/report`)
- **Permission / Role:** `INVENTORY.VALUATION.VIEW` (`hasAuthority(...)`) — runs as `ACCOUNTANT`/finance role with the perm; also a user without it → forbidden
- **Preconditions / Seed:** valued on-hand whose Σ on_hand_value equals the GL 1300 Inventory balance.
- **Steps:**
  1. Navigate to `/admin/stock/valuation`.
  2. Confirm per-product rows (productCode/name, quantity, avgCost, inventory value), a total row, and currency.
  3. Confirm the reconciliation bar shows GREEN "Reconciled to GL" when `recon.ties=true`.
- **Test Data:** total value e.g. `5,000,000.00 TZS`.
- **Expected Result:** `StockValuationReportDto` with `rows`, `totalValue`, `recon {label, computed, expected, difference, ties}`, `currency`. Company derived from JWT principal (no companyId param from UI).
- **Convention Assertions:** C8 money formatted `CUR 1,234.56`; C7 company-scoped via principal; C4 states; C6 axe.
- **Negative / Edge:** user without `INVENTORY.VALUATION.VIEW` → forbidden (component `canView` false → no load; backend 403).

### TC-INV-101 — Valuation recon mismatch (ties=false) shows finance alarm
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Valuation (`GET /api/v1/stock/valuation/report`)
- **Permission / Role:** `INVENTORY.VALUATION.VIEW` — runs as `ACCOUNTANT`
- **Preconditions / Seed:** force a stock-ledger vs GL drift so `Σ on_hand_value ≠ GL 1300`.
- **Steps:** open the report; inspect the recon bar.
- **Expected Result:** RED finance-grade alarm; `difference = computed − expected` displayed; `ties=false` (BR-INV-06 defect surfaced).
- **Convention Assertions:** C8 money/difference; recon semantics per `StockValuationReconDto`.
- **Negative / Edge:** difference sign (over/under) rendered correctly.

### TC-INV-102 — Valuation rows for unvalued (quantity-only) products
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Valuation (`GET /api/v1/stock/valuation/report`)
- **Permission / Role:** `INVENTORY.VALUATION.VIEW` — runs as `ACCOUNTANT`
- **Preconditions / Seed:** an on-hand row with quantity but `avgCost` null/0 (never valued).
- **Steps:** open the report; locate the unvalued row.
- **Expected Result:** row flagged unvalued (`isUnvalued` → avgCost null or 0); value contributes 0 to total until opening valuation set.
- **Convention Assertions:** C8 zero-value formatting.
- **Negative / Edge:** all-unvalued company → total 0 but rows still listed.

### TC-INV-103 — Set opening valuation for an unvalued row → DR 1300 / CR 3100
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opening valuation (`/admin/stock/valuation/opening` · `POST /api/v1/stock/valuation/opening`)
- **Permission / Role:** `INVENTORY.OPENING.SET` (`hasAuthority(...)`) — runs as `ACCOUNTANT`/finance; also a user without it → forbidden
- **Preconditions / Seed:** an unvalued on-hand row (quantity>0, avgCost null) — surfaced from TC-INV-102.
- **Steps:**
  1. Navigate to `/admin/stock/valuation/opening`.
  2. The screen lists unvalued rows (report rows cross-referenced to `stockOnHandUid` via the on-hand list). Select one by product label.
  3. Enter openingCost `12.50`; submit.
- **Test Data:** openingCost `12.50`, quantity `100` → openingValue `1,250.00`.
- **Expected Result:** 201; `OpeningValuationResultDto` (openingValue + currency); GL posts DR INVENTORY (1300) / CR OPENING_BALANCE_EQUITY (3100); the row disappears from the unvalued list (once-per-row affordance).
- **Convention Assertions:** C1 row selected by product NAME/label, `stockOnHandUid` carried under the hood (never typed); C3 `INVENTORY.OPENING.SET`; C8 money formatting; C9 append-only GL posting.
- **Negative / Edge:** openingCost < 0 → FE blocks + backend `@DecimalMin("0.0000")` 400; missing stockOnHandUid → `@NotNull` 400.

### TC-INV-104 — Opening valuation rejected when row already valued (409)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Opening valuation (`POST /api/v1/stock/valuation/opening`)
- **Permission / Role:** `INVENTORY.OPENING.SET` — runs as `ACCOUNTANT`
- **Preconditions / Seed:** an on-hand row already valued (avgCost set / on_hand_value ≠ 0).
- **Steps:** attempt to set opening valuation again on that row (e.g. via direct call / stale list).
- **Expected Result:** backend 409; FE shows "already has an opening valuation set (one-time per on-hand row)".
- **Convention Assertions:** C2 error mapping; one-time invariant.
- **Negative / Edge:** openingCost `0` on an unvalued row — confirm whether allowed (DTO permits `>= 0`); document actual.

### TC-INV-105 — Opening valuation: empty state when nothing unvalued
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Opening valuation (`/admin/stock/valuation/opening`)
- **Permission / Role:** `INVENTORY.OPENING.SET` — runs as `ACCOUNTANT`
- **Preconditions / Seed:** all on-hand rows valued.
- **Steps:** open the screen.
- **Expected Result:** "no unvalued rows" empty state; submit disabled.
- **Convention Assertions:** C4 empty vs loading vs error vs forbidden.
- **Negative / Edge:** forbidden when lacking the perm (component `canSet` false / 403).

## L. Cross-cutting: RBAC matrix, scope, conventions, known defects

### TC-INV-200 — Inventory nav visibility matches granted permissions
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Shell nav (Inventory group, `shell.component.ts` 149-160)
- **Permission / Role:** all inventory perms — runs as NO-PERMISSION user, then a CUSTOM role with a subset
- **Steps:**
  1. As NO-PERMISSION user, confirm NONE of the Inventory nav items render.
  2. As a CUSTOM role with only `STOCK.VIEW` + `STOCK.LOCATION.VIEW`, confirm only "Stock On-Hand" and "Stock Locations" render; others hidden.
- **Expected Result:** each nav item visible iff its `permission` is held; routes also guarded by `requirePermission(...)`.
- **Convention Assertions:** C3 RBAC nav + route guard parity.
- **Negative / Edge:** deep-linking a route without the perm → forbidden (not a silent render).

### TC-INV-201 — Multi-tenancy isolation across all inventory reads
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** all inventory list/detail endpoints
- **Permission / Role:** full inventory perms in tenant B — runs as a tenant-B user
- **Steps:** as tenant-B user, attempt to read tenant-A on-hand, locations, transfers, counts, batches, serials, valuation (by deep-link uid where applicable).
- **Expected Result:** only tenant-B data returned; tenant-A uids → 403/404; valuation report scoped to tenant-B company (principal-derived).
- **Convention Assertions:** C7 company scope; C1 uids never leak across tenants.
- **Negative / Edge:** branch-level scope — a user assigned to branch X cannot see branch Y on-hand even within the same company.

### TC-INV-202 — Branch switching changes on-hand / location / count context
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** On-hand, locations, counts (branch-scoped)
- **Permission / Role:** `STOCK.VIEW` + `STOCK.LOCATION.VIEW` + `STOCK.COUNT.VIEW` — runs as a user assigned to MANY branches
- **Variation:** default vs non-default branch; user assigned to one branch vs many
- **Steps:**
  1. On `/admin/stock`, switch the branch selector to a non-default branch → on-hand reloads for that branch.
  2. Repeat on counts/locations where branch context applies.
- **Expected Result:** data follows the active branch; default-branch and non-default-branch sets differ.
- **Convention Assertions:** C7 branch scope; C2 meta recalculated.
- **Negative / Edge:** a user assigned to ONE branch sees only that branch; acting in a non-assigned branch (X-Branch-Uid) → denied.

### TC-INV-203 — rootadmin superuser sees all inventory across tenants/branches
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** all inventory endpoints
- **Permission / Role:** bootstrap SUPERUSER — runs as `rootadmin`
- **Steps:** as `rootadmin`, browse on-hand/locations/transfers/counts/batches/serials/valuation across multiple companies/branches.
- **Expected Result:** all accessible (bypasses permission + cross-tenant scope); confirm rootadmin is NOT used for negative-auth assertions elsewhere.
- **Convention Assertions:** superuser bypass; contrast with TC-INV-901 (batch/serial work for rootadmin despite missing grantable codes).
- **Negative / Edge:** none — this is the privileged baseline.

### TC-INV-204 — Money & date wire conventions across inventory
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** on-hand (qty), by-location/valuation (money), transfers/counts (dates)
- **Permission / Role:** relevant view perms — runs as `ORG_ADMIN`/`ACCOUNTANT`
- **Steps:** assert on-hand quantity rendered to 3-dp; valuation/by-location money formatted `CUR 1,234.56`; transferDate/countDate/postingDate/expiry as ISO `yyyy-MM-dd`; BigDecimal/Long arrive as strings on the wire.
- **Expected Result:** consistent C8 formatting; no JS precision loss.
- **Convention Assertions:** C8 money/date; C2 envelope.
- **Negative / Edge:** very large quantities/values do not overflow/round incorrectly.

### TC-INV-205 — Axe accessibility sweep across all inventory screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** all inventory routes
- **Permission / Role:** `ORG_ADMIN` (+ finance perms)
- **Steps:** run an axe scan on each: `/admin/stock`, `/admin/stock/locations`, `/admin/stock-transfers`, `/admin/stock-transfers/create`, `/admin/stock-transfers/uid/:uid`, `/admin/stock-counts`, `/admin/stock-counts/create`, `/admin/stock-counts/uid/:uid`, `/admin/stock/batches`, `/admin/stock/serials`, `/admin/stock/valuation`, `/admin/stock/valuation/opening`.
- **Expected Result:** axe-clean (WCAG 2.1 AA): labelled controls/pickers, table captions + scoped headers, keyboard-operable, aria on status badges/drawers.
- **Convention Assertions:** C6 a11y.
- **Negative / Edge:** pickers and inline forms must be keyboard reachable and labelled.

### TC-INV-901 — DEFECT: batch/serial gates reference ungranted permission codes
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Batches (`/admin/stock/batches`), Serials (`/admin/stock/serials`)
- **Permission / Role:** controllers require `STOCK.BATCH.VIEW` / `STOCK.SERIAL.VIEW`, but the seed (V41) only creates `INVENTORY.BATCH.VIEW` / `INVENTORY.SERIAL.VIEW`
- **Steps:**
  1. As `ORG_ADMIN` (granted all V41 codes incl. INVENTORY.BATCH.VIEW/INVENTORY.SERIAL.VIEW) navigate to `/admin/stock/batches` and `/admin/stock/serials`.
  2. Observe: nav items hidden (nav checks `STOCK.BATCH.VIEW`/`STOCK.SERIAL.VIEW`, which no role holds); direct API calls → 403.
  3. As `rootadmin`, navigate to the same screens → works (superuser bypass).
- **Expected Result (current/observed):** non-superuser users CANNOT view batches/serials by-location/detail even though intended to; only the expiry tab (gated `INVENTORY.EXPIRY.VIEW`) is reachable for ORG_ADMIN.
- **Expected Result (intended/fix target):** either (a) the controllers + FE should gate on the seeded `INVENTORY.BATCH.VIEW`/`INVENTORY.SERIAL.VIEW`, or (b) a migration should add and grant `STOCK.BATCH.VIEW`/`STOCK.SERIAL.VIEW`. Re-run after fix and assert ORG_ADMIN access.
- **Convention Assertions:** C3 RBAC code consistency (gating code must be a real, grantable permission).
- **Negative / Edge:** this is the negative-auth baseline for batches/serials until reconciled — DO NOT assert non-superuser success against current seed; use `rootadmin` for functional batch/serial coverage (TC-INV-080..093) and track this defect.

### TC-INV-902 — Batch/serial functional coverage runs as rootadmin (workaround for TC-INV-901)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Batches, Serials
- **Permission / Role:** `rootadmin` (superuser bypass) — explicit workaround note
- **Steps:** run the batch/serial functional cases (TC-INV-080..093) as `rootadmin` so the missing grantable codes don't block coverage.
- **Expected Result:** functional behaviour validated; RBAC for these two submodules tracked separately in TC-INV-901.
- **Convention Assertions:** documents the deviation from "never use rootadmin for functional tests" — justified by the seed gap; revisit once TC-INV-901 is fixed.
- **Negative / Edge:** once fixed, migrate these cases to a properly-granted non-superuser role.
