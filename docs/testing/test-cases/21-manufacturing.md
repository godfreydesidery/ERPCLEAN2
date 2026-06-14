# 21 — Manufacturing (BOM authoring, Work Orders, WIP reconciliation)

Exhaustive UI-driven (Playwright) and manual test cases for the Manufacturing domain: multi-level Bill of Materials (BOM) authoring and lifecycle, Work Order execution (release → issue → apply-cost → complete → close, plus cancel reversal), per-order cost report, and the company-level WIP reconciliation report. Every endpoint, permission code, status transition (legal and illegal), entity-type variation, and the C1–C9 conventions are covered.

All endpoints/permissions/enums/routes below were verified by reading the controllers, DTOs, enums, service implementations, the SQL permission seed, and the Angular routes/components — nothing is invented.

## Modules / submodules covered

- **Bills of Materials (BOM)** — `BomController` · base path `/api/v1/boms`
  - Frontend: list at `/admin/boms` (`BomListComponent`, inline create), detail at `/admin/boms/uid/:uid` (`BomDetailComponent`: header edit, component add/edit/remove, activate, archive). Nav: Manufacturing → "Bills of Materials".
- **Work Orders** — `WorkOrderController` · base path `/api/v1/work-orders`
  - Frontend: list at `/admin/work-orders` (`WorkOrderListComponent`, inline create + status filter), detail at `/admin/work-orders/uid/:uid` (`WorkOrderDetailComponent`: edit, release, issue-components, apply-cost, complete, close, cancel, add/remove operations), cost report at `/admin/work-orders/uid/:uid/cost-report` (`WorkOrderCostReportComponent`). Nav: Manufacturing → "Work Orders".
- **Manufacturing reporting** — `ManufacturingReportController` · base path `/api/v1/manufacturing`
  - Frontend: WIP reconciliation at `/admin/manufacturing/wip-reconciliation` (`WipReconciliationComponent`). Nav: Manufacturing → "WIP Reconciliation".

### Backend-only-with-UI / embedded / no-UI notes (verified)

- **BOM explosion** `GET /api/v1/boms/explode`, **where-used** `GET /api/v1/boms/where-used/{componentProductUid}`, **cost roll-up** `GET /api/v1/boms/cost-roll-up`, **list components** `GET /api/v1/boms/uid/{uid}/components` — these BomController endpoints exist on the backend but are **NOT surfaced by any BOM screen** (`BomDetailComponent`/`BomListComponent` never call them). Cases for them are API-level (Manual) only.
- **Promote legacy recipe to BOM** — `POST /api/v1/products/uid/{uid}/promote-recipe-to-bom` lives on `ProductController` (perm `BOM.MANAGE`, scoped to `product`). No dedicated UI button was found; treated as API-level (Manual).
- **BOM create has no dedicated route** — creation is an inline form on `/admin/boms` (`BomListComponent.create`). There is no `boms/create` route.
- **Work order "issue components" UI is full-issue only** — `WorkOrderDetailComponent.issueComponents()` always sends `{ full: true, postingDate }`; the partial-issue (`full:false` + `componentUids`) path is API-only.
- **`WORKORDER.QC` permission is seeded but RESERVED** — no controller method uses it (V74 comment: "RESERVED — no workflow in v1"). No test exercises it beyond confirming it grants nothing.
- **`ComponentSourcing` clone / `cloneFromBomUid`** on create is API-only (the inline create form does not expose it).

## Permission codes in scope (exact `@PreAuthorize` codes)

| Code | Granted by seed to | Used on |
| --- | --- | --- |
| `BOM.VIEW` | ORG_ADMIN (module `products` grant) | BOM list, get, list-components, explode, where-used, cost-roll-up |
| `BOM.MANAGE` | ORG_ADMIN | BOM create, update, add/update/remove component, activate, archive, promote-recipe |
| `MANUFACTURING.VIEW` | ORG_ADMIN (module `manufacturing` grant) | WO list, WO get, WO cost-report, WIP reconciliation |
| `WORKORDER.MANAGE` | ORG_ADMIN | WO create, update, cancel, issue-components, apply-cost, add/remove operation |
| `WORKORDER.RELEASE` | ORG_ADMIN | WO release |
| `WORKORDER.CLOSE` | ORG_ADMIN | WO complete, WO close |
| `WORKORDER.QC` | ORG_ADMIN | RESERVED — no endpoint |

> Scoping note: `@perm.scoped(#uid,'bom'|'workorder'|'product',CODE)` resolves the resource's company/branch and runs `ScopeGuard.assertCanActIn`. List endpoints take `@RequestParam Long companyId` and assert scope on that company. The BOM-list **route guard** uses `BOM.VIEW`; the work-order list/detail **route guards** use `MANUFACTURING.VIEW` (create/release/close actions are additionally gated client-side by `WORKORDER.MANAGE`/`RELEASE`/`CLOSE`).

## Status lifecycle enums (verified)

- **`BomStatus`**: `DRAFT → (activate) → ACTIVE → (archive | superseded by newer activate) → ARCHIVED` (terminal). At most one `ACTIVE` per parent product (`uq_bom_one_active`).
- **`WorkOrderStatus`**: `PLANNED → (release) → RELEASED → (first issue) → IN_PROGRESS → (complete) → COMPLETED → (close) → CLOSED` (terminal). `CANCELLED` (terminal) reachable from PLANNED/RELEASED/IN_PROGRESS (NOT from COMPLETED/CLOSED/CANCELLED).
- **`ComponentLineStatus`** (work-order component line): `PLANNED → PARTIAL → ISSUED`.
- **`ComponentSourcing`** (BOM component line): `MAKE` (recurse in explosion) / `BUY` (leaf). Default derived from child's ACTIVE-BOM state when null.

## Type / role variations exercised

| Dimension | Values varied across cases |
| --- | --- |
| User roles | `rootadmin` (superuser bypass), `ORG_ADMIN` (all mfg perms), `ACCOUNTANT`/`STOREKEEPER`/`SALES_REP` (lacking mfg perms → forbidden), a CUSTOM role granted only `BOM.VIEW` + `MANUFACTURING.VIEW` (read-only), a NO-PERMISSION user (empty nav / 403) |
| Permission granularity | `BOM.VIEW` vs `BOM.MANAGE`; `MANUFACTURING.VIEW` vs `WORKORDER.MANAGE` vs `WORKORDER.RELEASE` vs `WORKORDER.CLOSE` (e.g. a user with VIEW+MANAGE but not RELEASE) |
| Product type (parent / FG) | `ProductType.GOODS` (stockable — required for FG receipt) vs `ProductType.SERVICE` (non-stockable) |
| Component sourcing | `MAKE` vs `BUY` vs `Auto (derive)` |
| BOM status | DRAFT / ACTIVE / ARCHIVED; single version vs superseding a prior ACTIVE |
| WO status | every state PLANNED→RELEASED→IN_PROGRESS→COMPLETED→CLOSED + CANCELLED |
| Branch / company | default vs non-default branch; single-branch vs multi-branch company; user assigned to the WO branch vs not; cross-tenant (company B's BOM/WO must be invisible/403) |
| Costing edge | costed component (avg_cost present) vs cost-skipped (`avg_cost` NULL → `incompleteCost=true`); over-run (`allowOverRun`); residual variance at close |

---

## TEST CASES

### TC-MFG-001 — BOM list loads, company-scoped, four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BOM (`/admin/boms` · `GET /api/v1/boms`)
- **Permission / Role:** `BOM.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → expect forbidden/redirect
- **Variation:** company = default; ≥1 BOM seeded
- **Preconditions / Seed:** at least one product (GOODS) with a BOM created via TC-MFG-010 or API.
- **Steps:**
  1. Login as ORG_ADMIN; navigate to `/admin/boms`.
  2. Observe the loading state, then the table.
  3. Read the company selector at the top (defaults to first company).
- **Test Data:** company = "Acme Manufacturing".
- **Expected Result:** table renders BOM rows with parent product (by name/code), version, status badge, output qty, yield %. Envelope is `ApiResponse<List<BomDto>>` with `meta` pagination. Status filter dropdown shows All / Draft / Active / Archived.
- **Convention Assertions:** C2 envelope+meta; C4 loading→idle (and empty/error/forbidden states distinct); C5 paginator present (`<app-paginator>`); C6 axe clean; C7 only current-company BOMs shown; C8 qty/percent formatting.
- **Negative / Edge:** as NO-PERMISSION user, the route guard `requirePermission('BOM.VIEW')` redirects to admin home and the "Bills of Materials" nav item is hidden.

### TC-MFG-002 — BOM list empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BOM (`/admin/boms` · `GET /api/v1/boms`)
- **Permission / Role:** `BOM.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a company with zero BOMs (or a fresh tenant).
- **Steps:** 1. Navigate to `/admin/boms`. 2. Select the empty company.
- **Expected Result:** distinct empty state (no rows; "no BOMs" message), `isEmpty` true; paginator hidden (1 page / 0 elements).
- **Convention Assertions:** C4 empty distinct from loading/error; C5 paginator self-hidden when totalPages ≤ 1; C6 axe.
- **Negative / Edge:** switching back to a populated company re-renders rows (C7 re-scope).

### TC-MFG-003 — BOM list status filter
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BOM (`/admin/boms` · `GET /api/v1/boms?status=`)
- **Permission / Role:** `BOM.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** BOMs in DRAFT, ACTIVE, and ARCHIVED for one company.
- **Steps:** 1. On `/admin/boms`, choose "Draft" in the status filter. 2. Then "Active". 3. Then "Archived". 4. Then "All statuses".
- **Test Data:** status enum values `DRAFT`, `ACTIVE`, `ARCHIVED`.
- **Expected Result:** each selection re-queries with `status=` and shows only matching rows; "All statuses" sends no `status` param. Filter resets to page 0.
- **Convention Assertions:** C2 query param; C4 loading on each refetch; C5 pagination resets.
- **Negative / Edge:** invalid status is impossible (closed dropdown); rapid toggles debounced (50ms).

### TC-MFG-004 — BOM list pagination controls
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BOM (`/admin/boms`)
- **Permission / Role:** `BOM.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** > 20 BOMs (DEFAULT_SIZE = 20) for one company.
- **Steps:** 1. Navigate to `/admin/boms`. 2. Click NEXT, LAST, page-number, PREVIOUS, FIRST.
- **Expected Result:** each control reloads the correct page; `meta.page/totalPages/hasNext` honoured; current page reflects in the paginator.
- **Convention Assertions:** C5 FIRST/PREV/numbers/NEXT/LAST all present; C2 meta drives controls.
- **Negative / Edge:** on the last page NEXT/LAST are disabled; on page 0 FIRST/PREV disabled.

### TC-MFG-005 — BOM list create-button hidden without BOM.MANAGE
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BOM (`/admin/boms`)
- **Permission / Role:** `BOM.VIEW` (read-only CUSTOM role) — also ORG_ADMIN (button visible)
- **Preconditions / Seed:** CUSTOM role granted `BOM.VIEW` only.
- **Steps:** 1. Login as the read-only user. 2. Navigate to `/admin/boms`.
- **Expected Result:** list renders, but the create form toggle is hidden (`canManage()` = `hasPermission('BOM.MANAGE')` false). As ORG_ADMIN the create toggle is visible.
- **Convention Assertions:** C3 action gated by `BOM.MANAGE`; C4 forbidden-action hidden, not errored.
- **Negative / Edge:** if such a user hits `POST /api/v1/boms` directly → 403.

### TC-MFG-006 — BOM detail loads header + components, no uid shown
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BOM (`/admin/boms/uid/:uid` · `GET /api/v1/boms/uid/{uid}`)
- **Permission / Role:** `BOM.VIEW` (scoped) — ORG_ADMIN; also cross-tenant user → forbidden
- **Variation:** BOM = DRAFT with ≥2 components (one MAKE, one BUY)
- **Preconditions / Seed:** a BOM with components.
- **Steps:** 1. From the list, click a BOM row to open `/admin/boms/uid/:uid`. 2. Inspect header (parent product by name, version, status, output qty, yield) and the component table.
- **Expected Result:** header + component lines (line no, component product **name/code**, qty per, sourcing, scrap %, reference). Auto-unwrapped `BomDto`.
- **Convention Assertions:** **C1** the BOM uid appears only in the URL — never in a label/cell; parent product and components are shown by name/code, never raw uid. C2 envelope; C4 states; C6 axe; C7 cross-tenant detail returns 403.
- **Negative / Edge:** opening a uid from another company → ScopeGuard 403; opening a non-existent uid → NotFound (error state).

### TC-MFG-007 — BOM create (DRAFT) via inline form using product picker
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BOM (`/admin/boms` create · `POST /api/v1/boms?companyId=`)
- **Permission / Role:** `BOM.MANAGE` — ORG_ADMIN; also as `BOM.VIEW`-only → toggle hidden, API 403
- **Variation:** parent product = GOODS; outputQty=10; yield=95
- **Preconditions / Seed:** an ACTIVE GOODS product in the selected company.
- **Steps:**
  1. On `/admin/boms`, click the create toggle.
  2. In the **parent product picker** (`<app-uid-picker>`), search and select the product **by name**.
  3. Enter Output Qty = 10, Yield % = 95, Notes = "Pilot batch".
  4. Submit.
- **Test Data:** product "Widget A"; outputQty `10`; yieldPercent `95`; notes "Pilot batch".
- **Expected Result:** `201 Created`; success alert "BOM created — v{n} — {uid}"; new DRAFT row appears with versionNo and status DRAFT. Request body = `{parentProductUid, outputQty:"10", yieldPercent:"95", notes}`.
- **Convention Assertions:** **C1** parent chosen via picker by name; the uid is stored under the hood, no raw uid typed/shown. C2 `201` + `BomDto`. C3 gated by `BOM.MANAGE`. C6 axe on the form.
- **Negative / Edge:**
  - Empty product → client error "Parent product is required."
  - outputQty = 0 / blank / non-numeric → "Output quantity must be a positive number." (also server `@DecimalMin("0.000001")`).
  - yieldPercent > 100 → server `@DecimalMax("100")` 400.
  - Parent product from another company → server `ForbiddenException` "Parent product does not belong to the specified company."
  - Parent product ARCHIVED → 400 "Cannot create a BOM for an ARCHIVED product (BR-BOM-12)."

### TC-MFG-008 — BOM create allocates next version_no per parent
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** BOM (`POST /api/v1/boms`)
- **Permission / Role:** `BOM.MANAGE` — ORG_ADMIN
- **Preconditions / Seed:** one existing BOM v1 for product "Widget A".
- **Steps:** 1. Create another BOM for the same parent product.
- **Expected Result:** new BOM has `versionNo = max+1` (v2); both coexist (v1 may be ACTIVE, v2 DRAFT).
- **Convention Assertions:** C2 envelope; C9 prior version not mutated.
- **Negative / Edge:** concurrent create races backstopped by `uq_bom_parent_version` (Conflict).

### TC-MFG-009 — BOM header edit on DRAFT (outputQty, yield, notes)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BOM (`/admin/boms/uid/:uid` · `PUT /api/v1/boms/uid/{uid}`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** BOM = DRAFT
- **Preconditions / Seed:** a DRAFT BOM.
- **Steps:** 1. Open BOM detail. 2. Change Output Qty to 20, Yield % to 90, Notes. 3. Save.
- **Test Data:** outputQty `20`, yieldPercent `90`.
- **Expected Result:** success "BOM updated"; header reflects new values; `UpdateBomRequest` sent with all three.
- **Convention Assertions:** C2 envelope; C3 `BOM.MANAGE`; C8 qty/percent format.
- **Negative / Edge:** outputQty ≤ 0 → client validation; yieldPercent out of (0.0001..100] → server 400.

### TC-MFG-010 — BOM header edit on ACTIVE updates notes only (component set frozen)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** BOM (`PUT /api/v1/boms/uid/{uid}`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** BOM = ACTIVE
- **Preconditions / Seed:** an ACTIVE BOM.
- **Steps:** 1. Open an ACTIVE BOM. 2. Edit notes and (attempt) outputQty. 3. Save.
- **Expected Result:** notes updated; outputQty/yieldPercent **ignored** on ACTIVE (service applies structural fields only when DRAFT — BR-BOM-03). The detail UI gates structural fields behind `isDraft()`.
- **Convention Assertions:** C9 component/structure frozen on ACTIVE; C3 perm.
- **Negative / Edge:** editing an ARCHIVED BOM → 400 "Cannot update an ARCHIVED BOM."

### TC-MFG-011 — Add BOM component via product picker (sourcing Auto/MAKE/BUY)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BOM (`/admin/boms/uid/:uid` · `POST /api/v1/boms/uid/{uid}/components`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** sourcing = Auto, then MAKE, then BUY; on a DRAFT BOM
- **Preconditions / Seed:** a DRAFT BOM; ACTIVE component products.
- **Steps:**
  1. Open BOM detail; click "Add component".
  2. In the **component product picker**, select a component **by name**.
  3. Enter Qty per = 2, Scrap % = 5, Reference = "RES-1".
  4. Choose sourcing = "Auto (derive from child)" → submit. Repeat with MAKE, then BUY.
- **Test Data:** qtyPer `2`, scrapPercent `5`, reference `RES-1`; sourcing `''` (auto) / `MAKE` / `BUY`.
- **Expected Result:** `201`; component line appended with line no, component code/name, qty per, sourcing, scrap %. With Auto, server derives MAKE if child has an ACTIVE BOM else BUY (BR-BOM-07).
- **Convention Assertions:** **C1** component chosen via picker by name (no uid typed). C2 `201`. C3 `BOM.MANAGE`. C6 axe.
- **Negative / Edge:**
  - Empty component → "Component product is required."
  - qtyPer ≤ 0 → "Quantity per must be a positive number." (server `@DecimalMin("0.000001")`).
  - scrapPercent ≥ 100 → server `@DecimalMax("99.9999")` 400.
  - Add component that would create a cycle (component's BOM contains the parent) → cycle guard rejection (enforced at activate; see TC-MFG-016).

### TC-MFG-012 — Inline edit BOM component (DRAFT only)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BOM (`PUT /api/v1/boms/uid/{uid}/components/{componentUid}`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** BOM = DRAFT
- **Preconditions / Seed:** a DRAFT BOM with a component line.
- **Steps:** 1. Click edit on a component row. 2. Change qty per, sourcing, scrap %, reference. 3. Save.
- **Test Data:** qtyPer `3.5`, sourcing `BUY`, scrapPercent `0`.
- **Expected Result:** line updates in place; success "Component updated". Only supplied fields change.
- **Convention Assertions:** C2 envelope; C3 perm; C8 numeric format.
- **Negative / Edge:** qtyPer ≤ 0 → client validation; editing a component on an ACTIVE BOM is not offered in UI (component edit controls gated by `isDraft()`); API call would be a structure mutation rejected by BR-BOM-03.

### TC-MFG-013 — Remove BOM component (DRAFT only)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BOM (`DELETE /api/v1/boms/uid/{uid}/components/{componentUid}` → 204)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** BOM = DRAFT
- **Preconditions / Seed:** a DRAFT BOM with ≥2 components.
- **Steps:** 1. Click remove on a component row. 2. Confirm.
- **Expected Result:** `204 No Content`; line disappears; success "Component removed".
- **Convention Assertions:** C2 204; C3 perm.
- **Negative / Edge:** removing the last component leaves zero lines → BOM then cannot be activated (TC-MFG-015 negative).

### TC-MFG-014 — Activate DRAFT BOM (DRAFT → ACTIVE) with effective-from
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** BOM (`/admin/boms/uid/:uid` · `POST /api/v1/boms/uid/{uid}/activate`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN; also `BOM.VIEW`-only → activate hidden / API 403
- **Variation:** DRAFT BOM with ≥1 component; no prior ACTIVE version
- **Preconditions / Seed:** a DRAFT BOM with at least one valid component.
- **Steps:** 1. Open detail; click "Activate". 2. Enter Effective-from = today. 3. Confirm.
- **Test Data:** effectiveFrom = `2026-06-14`.
- **Expected Result:** status → ACTIVE; `effectiveFrom` set, `effectiveTo` null, `activatedAt` stamped; success "BOM activated".
- **Convention Assertions:** C2 envelope; C3 `BOM.MANAGE`; C8 date ISO.
- **Negative / Edge:**
  - Missing effective-from → client "Effective-from date is required." (server `@NotNull`).
  - Activate a BOM with **no components** → 400 "Cannot activate a BOM with no components."
  - Activate a non-DRAFT BOM → 400 "Only a DRAFT BOM can be activated. Current status: ..." (illegal transition ACTIVE→ACTIVE / ARCHIVED→ACTIVE).

### TC-MFG-015 — Activate supersedes the prior ACTIVE version (one-active rule)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** BOM (`POST /api/v1/boms/uid/{uid}/activate`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** parent product already has an ACTIVE v1; activating DRAFT v2
- **Preconditions / Seed:** product with ACTIVE BOM v1 and a DRAFT v2 (same parent).
- **Steps:** 1. Open v2; activate with effective-from = today.
- **Expected Result:** v2 → ACTIVE (effectiveTo null); v1 atomically → ARCHIVED with `effectiveTo = effectiveFrom`, `archivedAt` stamped (BR-BOM-04/05). Audit records BOM_SUPERSEDE + BOM_ACTIVATE. Only one ACTIVE remains for the parent.
- **Convention Assertions:** C9 v1 archived not deleted (append-only history); C2 envelope.
- **Negative / Edge:** `uq_bom_one_active` partial-unique index is the race backstop if two activations collide → Conflict.

### TC-MFG-016 — Activate rejects a structural cycle
- **Type:** Manual (API) / Both
- **Priority:** P2
- **Module / Submodule:** BOM (`POST /api/v1/boms/uid/{uid}/activate`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** Product P has DRAFT BOM containing component Q; Q has an ACTIVE BOM containing P (would form a cycle).
- **Steps:** 1. Attempt to activate P's BOM.
- **Expected Result:** `BomCycleGuard.assertNoCycle` rejects (cycle detected) — activation fails; status stays DRAFT.
- **Convention Assertions:** C9 no partial state change (transactional).
- **Negative / Edge:** self-reference (parent == component) likewise rejected.

### TC-MFG-017 — Archive a BOM (DRAFT or ACTIVE → ARCHIVED)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BOM (`/admin/boms/uid/:uid` · `POST /api/v1/boms/uid/{uid}/archive`)
- **Permission / Role:** `BOM.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** archive an ACTIVE BOM (decommission); also archive a DRAFT BOM
- **Preconditions / Seed:** an ACTIVE BOM and a DRAFT BOM.
- **Steps:** 1. Open BOM detail; click "Archive". 2. Confirm.
- **Expected Result:** status → ARCHIVED; `archivedAt` stamped; success "BOM archived". Header/component edit controls disappear.
- **Convention Assertions:** C9 soft-decommission (not hard delete); C3 perm.
- **Negative / Edge:** archive an already-ARCHIVED BOM → 400 "BOM is already ARCHIVED."

### TC-MFG-018 — BOM list-components endpoint (API-only)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** BOM (`GET /api/v1/boms/uid/{uid}/components`)
- **Permission / Role:** `BOM.VIEW` (scoped) — ORG_ADMIN; cross-tenant → 403
- **Preconditions / Seed:** a BOM with components.
- **Steps:** 1. `GET /api/v1/boms/uid/{uid}/components`.
- **Expected Result:** `List<BomComponentDto>` ordered by line no. (Note: the detail screen already embeds components via the header `GET`; this list endpoint is not separately surfaced.)
- **Convention Assertions:** C2 envelope; C7 scope.
- **Negative / Edge:** unknown uid → NotFound.

### TC-MFG-019 — BOM multi-level explosion (API-only)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** BOM (`GET /api/v1/boms/explode`)
- **Permission / Role:** `BOM.VIEW` scoped to `product` (`@perm.scoped(#parentProductUid,'product','BOM.VIEW')`) — ORG_ADMIN
- **Variation:** multiLevel=true vs false; withCost=true vs false; MAKE child recursed, BUY child leaf
- **Preconditions / Seed:** a multi-level BOM (parent → MAKE sub-assembly → BUY raw).
- **Steps:**
  1. `GET /api/v1/boms/explode?parentProductUid={uid}&outputQty=5&multiLevel=true&withCost=false`.
  2. Repeat with `multiLevel=false`, then `withCost=true&branchUid={uid}`.
- **Test Data:** outputQty `5`; optional `asOfDate`, `branchUid`, `bomUid`.
- **Expected Result:** `BomExplosionResultDto` tree; MAKE components recursed, BUY components are leaves regardless of any BOM they have. ScopeGuard enforced inside the service.
- **Convention Assertions:** C2 envelope; C7 read-path scope guard; C8 cost figures as money strings (withCost).
- **Negative / Edge:** neither `parentProductUid` nor `bomUid` supplied → validation/argument error; cross-tenant product → 403.

### TC-MFG-020 — BOM where-used / implosion (API-only)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** BOM (`GET /api/v1/boms/where-used/{componentProductUid}?companyId=&full=`)
- **Permission / Role:** `BOM.VIEW` scoped to `product` — ORG_ADMIN
- **Preconditions / Seed:** a component used by ≥1 parent BOM.
- **Steps:** 1. `GET .../where-used/{uid}?companyId={id}&full=false` (rows). 2. `full=true` (tree).
- **Expected Result:** `full=false` → `List<WhereUsedRowDto>`; `full=true` → `WhereUsedTreeDto`. `ScopeGuard.assertCanActIn(companyId)` enforced.
- **Convention Assertions:** C2 envelope; C7 company scope.
- **Negative / Edge:** companyId of another tenant → 403.

### TC-MFG-021 — BOM cost roll-up (API-only)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** BOM (`GET /api/v1/boms/cost-roll-up?parentProductUid=&branchUid=&outputQty=`)
- **Permission / Role:** `BOM.VIEW` scoped to `product` — ORG_ADMIN
- **Preconditions / Seed:** a costed BOM (component avg costs present) in a branch.
- **Steps:** 1. `GET .../cost-roll-up?parentProductUid={uid}&branchUid={uid}&outputQty=1`.
- **Expected Result:** `BomCostRollUpDto` derived standard cost; `branchUid` is required.
- **Convention Assertions:** C2 envelope; C7 scope; C8 money strings.
- **Negative / Edge:** missing `branchUid` (required param) → 400; cross-tenant product → 403.

### TC-MFG-022 — Promote legacy recipe to DRAFT BOM (API-only, ProductController)
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** BOM convenience (`POST /api/v1/products/uid/{uid}/promote-recipe-to-bom?companyId=` → 201)
- **Permission / Role:** `BOM.MANAGE` scoped to `product` — ORG_ADMIN
- **Preconditions / Seed:** a product with legacy single-level `product_components` rows and no equivalent BOM.
- **Steps:** 1. `POST /api/v1/products/uid/{uid}/promote-recipe-to-bom?companyId={id}`.
- **Expected Result:** `201`; new DRAFT BOM (next version) with components copied (line nos 10,20,...), sourcing defaulted MAKE if child has ACTIVE BOM else BUY; notes "Promoted from legacy recipe"; legacy rows left intact.
- **Convention Assertions:** C2 `201`; C9 legacy recipe not destroyed.
- **Negative / Edge:** product with no legacy recipe → NotFound "Product has no legacy single-level recipe to promote"; product of another company → ForbiddenException.

### TC-MFG-023 — Work order list loads, company-scoped, four states + status filter
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders` · `GET /api/v1/work-orders`)
- **Permission / Role:** `MANUFACTURING.VIEW` — ORG_ADMIN; also NO-PERMISSION user → redirect/hidden nav
- **Variation:** status filter across all 6 WorkOrderStatus values
- **Preconditions / Seed:** WOs in several statuses for one company.
- **Steps:** 1. Navigate `/admin/work-orders`. 2. Cycle the status filter (Planned/Released/In Progress/Completed/Closed/Cancelled/All).
- **Expected Result:** rows show WO number, FG product (code/name), planned qty, status badge; each filter re-queries `?status=`. Auto-unwrap retains `meta`.
- **Convention Assertions:** C2 envelope+meta; C4 four states; C5 paginator (FIRST/PREV/numbers/NEXT/LAST); C6 axe; C7 company scope; C8 qty format.
- **Negative / Edge:** NO-PERMISSION → `requirePermission('MANUFACTURING.VIEW')` redirect; cross-tenant company id → 403.

### TC-MFG-024 — Work order create via product + branch pickers
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders` create · `POST /api/v1/work-orders` → 201)
- **Permission / Role:** `WORKORDER.MANAGE` — ORG_ADMIN; also `MANUFACTURING.VIEW`-only → create toggle hidden, API 403
- **Variation:** FG product = GOODS; branch = non-default; optional BOM pin
- **Preconditions / Seed:** ACTIVE GOODS product + ≥1 ACTIVE branch in company.
- **Steps:**
  1. On `/admin/work-orders`, open create form.
  2. **Finished product picker** → select by name.
  3. **Branch picker** → select a (non-default) branch by name.
  4. Planned Qty = 100; optionally pin a BOM via picker; planned date; notes.
  5. Submit.
- **Test Data:** finishedProductUid (via picker), plannedQty `100`, branchUid (via picker), optional bomUid.
- **Expected Result:** `201`; success "Work order created — {woNumber}"; new row in PLANNED. Body = `{finishedProductUid, plannedQty:"100", branchUid, bomUid?, plannedDate?, notes?}`. `companyId` comes from the request principal (not a body field).
- **Convention Assertions:** **C1** product/branch/BOM chosen via pickers by name; uids stored under the hood — note the validation messages text say "UID is required" but the inputs are pickers (uid never hand-typed). C2 `201`. C3 `WORKORDER.MANAGE`. C6 axe.
- **Negative / Edge:**
  - No product → "Finished product UID is required."
  - plannedQty ≤ 0 / blank → "Planned quantity must be a positive number." (server `@DecimalMin("0.000001")`).
  - No branch → "Branch UID is required."
  - Product from another company → 400 "Product does not belong to your company."
  - Pinned BOM from another company → 400 "BOM does not belong to your company."
  - Unknown branch uid → NotFound.

### TC-MFG-025 — Work order detail loads, no uid on screen
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `GET /api/v1/work-orders/uid/{uid}`)
- **Permission / Role:** `MANUFACTURING.VIEW` (scoped) — ORG_ADMIN; cross-tenant → 403
- **Preconditions / Seed:** a WO with component lines + operations (RELEASED or later).
- **Steps:** 1. Open a WO from the list. 2. Inspect header (WO number, FG product, planned/good/scrap qty, status badge, WIP debit/credit, labour/overhead, computed unit cost, variance, incompleteCost), component lines (status badges), operations.
- **Expected Result:** full `WorkOrderDto` with components + operations; `ApiResponse<WorkOrderDto>` auto-unwrapped.
- **Convention Assertions:** **C1** WO uid only in URL; WO referenced by **woNumber** in UI, FG product by code/name — never raw uid. C2 envelope. C4 states. C6 axe. C8 money/qty format.
- **Negative / Edge:** another company's WO uid → 403; unknown uid → error state.

### TC-MFG-026 — Work order edit allowed only while PLANNED
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`PUT /api/v1/work-orders/uid/{uid}`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** WO = PLANNED (editable) vs RELEASED (not editable)
- **Preconditions / Seed:** one PLANNED WO, one RELEASED WO.
- **Steps:** 1. On a PLANNED WO, change planned qty / branch / planned date / notes; Save. 2. On a RELEASED WO, observe edit controls.
- **Expected Result:** PLANNED edit succeeds (`UpdateWorkOrderRequest`); RELEASED WO has edit gated (`canEdit` = status PLANNED && `WORKORDER.MANAGE`).
- **Convention Assertions:** C3 `WORKORDER.MANAGE`; C1 branch re-selected via picker.
- **Negative / Edge:** API `PUT` on a non-PLANNED WO → 400 "Work order can only be edited while PLANNED (BR-MFG-04)."

### TC-MFG-027 — Release work order (PLANNED → RELEASED) using ACTIVE BOM
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `POST /api/v1/work-orders/uid/{uid}/release`)
- **Permission / Role:** `WORKORDER.RELEASE` (scoped) — ORG_ADMIN; also `WORKORDER.MANAGE` without `RELEASE` → release hidden / API 403
- **Variation:** no BOM pinned (resolve product's ACTIVE BOM) vs override BOM via picker
- **Preconditions / Seed:** PLANNED WO whose FG product has an ACTIVE BOM.
- **Steps:** 1. Open the PLANNED WO. 2. Click "Release" (optionally pick an override BOM). 3. Confirm.
- **Expected Result:** status → RELEASED; `releasedAt` stamped; BOM pinned; success "Work order released". Outbox `WORK_ORDER_RELEASED` emitted (no GL effect yet).
- **Convention Assertions:** C2 envelope; C3 `WORKORDER.RELEASE`; C1 override BOM via picker.
- **Negative / Edge:**
  - Release with no ACTIVE BOM and none pinned → 400 "No ACTIVE BOM for product {code}. Pin a BOM before releasing (BR-MFG-02)."
  - Release a non-PLANNED WO → 400 "Only PLANNED work orders can be released." (illegal transition).
  - User with VIEW+MANAGE but not RELEASE → release button hidden (`canRelease()` false); API 403.

### TC-MFG-028 — Issue components (RELEASED → IN_PROGRESS on first issue)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `POST /api/v1/work-orders/uid/{uid}/issue-components`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** components with avg-cost present (costed) — full issue
- **Preconditions / Seed:** RELEASED WO; component products with on-hand stock + avg cost in the WO branch.
- **Steps:** 1. Open RELEASED WO. 2. Set Posting date. 3. Click "Issue components".
- **Test Data:** postingDate = `2026-06-14` (UI always sends `full:true`).
- **Expected Result:** status → IN_PROGRESS; component lines materialised from BOM explosion (leaf level) and marked ISSUED/PARTIAL; stock `PRODUCTION_ISSUE` movements posted; WIP debit accumulated; GL DR WIP / CR Inventory; success "Components issued".
- **Convention Assertions:** C2 envelope; C3 `WORKORDER.MANAGE`; C8 posting date ISO; C9 stock/GL postings append-only.
- **Negative / Edge:**
  - Missing posting date → client "Posting date is required." (server `@NotNull`).
  - Issue on a PLANNED WO → 400 "Components can only be issued for RELEASED or IN_PROGRESS work orders." (illegal transition).
  - All lines already ISSUED → 400 "No unissued component lines to process."
  - BOM has no leaf components → 400 "BOM has no leaf components to issue."

### TC-MFG-029 — Issue components with NULL avg_cost → cost-skipped + incompleteCost flag
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Work Orders (`POST /api/v1/work-orders/uid/{uid}/issue-components`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** a component product whose `avg_cost` is NULL
- **Preconditions / Seed:** RELEASED WO with one component lacking a valuation.
- **Steps:** 1. Issue components.
- **Expected Result:** stock movement posted with null cost; line `costSkipped=true`; WO `incompleteCost=true`; no GL leg for that component (BR-MFG-06). Detail shows the incomplete-cost indicator.
- **Convention Assertions:** C8 cost fields may be null/zero; the report flags incompleteCost.
- **Negative / Edge:** subsequent cost report and WIP recon should still tie at zero-value legs.

### TC-MFG-030 — Partial component issue (API-only)
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Work Orders (`POST /api/v1/work-orders/uid/{uid}/issue-components`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** RELEASED/IN_PROGRESS WO with ≥2 component lines.
- **Steps:** 1. `POST` body `{ full:false, componentUids:["<lineUid>"], postingDate }` issuing only one line.
- **Expected Result:** only the listed line is issued; its status becomes PARTIAL or ISSUED; others stay PLANNED. (Not reachable from the UI, which sends `full:true`.)
- **Convention Assertions:** C2 envelope.
- **Negative / Edge:** empty `componentUids` with `full:false` falls back to all unissued lines (per service default).

### TC-MFG-031 — Apply labour/overhead cost (header-level)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `POST /api/v1/work-orders/uid/{uid}/apply-cost`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** RELEASED or IN_PROGRESS; labour only / overhead only / both
- **Preconditions / Seed:** a RELEASED or IN_PROGRESS WO.
- **Steps:** 1. Open WO; in Apply Cost, enter Labour and/or Overhead amounts and Posting date. 2. Submit.
- **Test Data:** labourAmount `5000`, overheadAmount `2000`, postingDate `2026-06-14`.
- **Expected Result:** labour/overhead accumulators increase; WIP debit accumulated; GL `PRODUCTION_LABOUR` posting; success "Cost applied".
- **Convention Assertions:** C2 envelope; C3 perm; C8 money strings; C9 append-only postings.
- **Negative / Edge:**
  - Both amounts blank/zero → client "At least one of labour or overhead amount must be positive." (server "At least one of labourAmount or overheadAmount must be non-zero.").
  - Missing posting date → "Posting date is required."
  - Apply cost on PLANNED/COMPLETED/CLOSED → 400 "Can only apply cost to RELEASED or IN_PROGRESS work orders."

### TC-MFG-032 — Apply cost tied to an operation (idempotency)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Work Orders (`POST /api/v1/work-orders/uid/{uid}/apply-cost` with `operationUid`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** operation selected via the operations picker
- **Preconditions / Seed:** WO with an un-applied operation.
- **Steps:** 1. In Apply Cost, choose an operation via the **operation picker** (label = description, hint = Seq). 2. Apply cost. 3. Attempt to apply to the same operation again.
- **Expected Result:** first apply marks the operation `applied=true`; second apply → 400 "Operation {uid} already applied."
- **Convention Assertions:** **C1** operation chosen via picker by description, not uid. C2 envelope.
- **Negative / Edge:** unknown operation uid → NotFound.

### TC-MFG-033 — Add operation to a work order
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `POST /api/v1/work-orders/uid/{uid}/operations` → 201)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** WO not CLOSED/CANCELLED
- **Preconditions / Seed:** a PLANNED/RELEASED/IN_PROGRESS WO.
- **Steps:** 1. In "Add operation", enter Seq No = 10, Description, Work centre, Labour, Overhead. 2. Submit.
- **Test Data:** seqNo `10`, description "Cutting", workCentre "WC-1", labourAmount `100`, overheadAmount `50`.
- **Expected Result:** `201`; operation row appended; success "Operation added".
- **Convention Assertions:** C2 `201`; C3 perm.
- **Negative / Edge:**
  - seqNo < 1 → client "Sequence number must be a positive integer." (server `@Min(1)`).
  - Blank description → "Description is required." (server `@NotBlank`).
  - Add operation to a CLOSED/CANCELLED WO → 400 "Cannot add operations to a {status} work order."

### TC-MFG-034 — Remove operation (only if not applied)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Work Orders (`DELETE /api/v1/work-orders/uid/{uid}/operations/{opUid}` → 204)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** a WO with one applied and one un-applied operation.
- **Steps:** 1. Remove the un-applied operation. 2. Attempt to remove the applied one.
- **Expected Result:** un-applied removal → `204`, row disappears, success "Operation removed". Applied removal → 400 "Cannot remove an already-applied operation."
- **Convention Assertions:** C2 204; C3 perm; C9 applied cost not reversible by delete.
- **Negative / Edge:** unknown opUid → NotFound.

### TC-MFG-035 — Complete work order (IN_PROGRESS → COMPLETED), FG receipt + WIP relief
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `POST /api/v1/work-orders/uid/{uid}/complete`)
- **Permission / Role:** `WORKORDER.CLOSE` (scoped) — ORG_ADMIN; also `WORKORDER.MANAGE` without `CLOSE` → complete hidden / API 403
- **Variation:** FG product = GOODS (stockable); goodQty within planned
- **Preconditions / Seed:** IN_PROGRESS WO with WIP accumulated.
- **Steps:** 1. Open WO; in Complete, enter Good Qty, Scrap Qty, Posting date; submit.
- **Test Data:** goodQty `90`, scrapQty `10`, allowOverRun false, postingDate `2026-06-14`.
- **Expected Result:** status → COMPLETED; `goodQty/scrapQty` set; `computedUnitCost` derived from open WIP / goodQty; FG `PRODUCTION_RECEIPT` stock movement; GL DR Finished-Goods / CR WIP; outbox `WORK_ORDER_COMPLETED`; success "Work order completed".
- **Convention Assertions:** C2 envelope; C3 `WORKORDER.CLOSE`; C8 unit cost as money string; C9 postings append-only.
- **Negative / Edge:**
  - goodQty ≤ 0 / blank → client "Good quantity must be a positive number." (server `@DecimalMin("0.000001")`).
  - Missing posting date → "Posting date is required."
  - Complete a non-IN_PROGRESS WO → 400 "Only IN_PROGRESS work orders can be completed." (illegal transition).
  - User with MANAGE but not CLOSE → complete hidden; API 403.

### TC-MFG-036 — Complete over-run guard (allowOverRun)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Work Orders (`POST /api/v1/work-orders/uid/{uid}/complete`)
- **Permission / Role:** `WORKORDER.CLOSE` (scoped) — ORG_ADMIN
- **Variation:** good+scrap exceeds planned qty
- **Preconditions / Seed:** IN_PROGRESS WO with plannedQty 100.
- **Steps:** 1. Complete with goodQty=110, allowOverRun off. 2. Retry with the "allow over-run" checkbox on.
- **Expected Result:** step 1 → 400 "Output (110) exceeds planned qty (100). Set allowOverRun=true to override (BR-MFG-07)." Step 2 succeeds.
- **Convention Assertions:** C2 envelope.
- **Negative / Edge:** exact equality (110 vs 110) is allowed (only strictly greater is blocked).

### TC-MFG-037 — Close work order (COMPLETED → CLOSED), variance clear
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `POST /api/v1/work-orders/uid/{uid}/close`)
- **Permission / Role:** `WORKORDER.CLOSE` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** a COMPLETED WO with residual WIP (rounding/variance).
- **Steps:** 1. Open COMPLETED WO; in Close, enter Posting date; submit.
- **Test Data:** postingDate `2026-06-14`.
- **Expected Result:** status → CLOSED (terminal); residual WIP cleared to Manufacturing Variance (GL `PRODUCTION_VARIANCE` DR/CR per sign); `varianceAmount` set; `closedAt` stamped; success "Work order closed".
- **Convention Assertions:** C2 envelope; C3 `WORKORDER.CLOSE`; C8 variance money string; C9 postings append-only.
- **Negative / Edge:**
  - Missing posting date → "Posting date is required."
  - Close a non-COMPLETED WO (PLANNED/RELEASED/IN_PROGRESS/CLOSED/CANCELLED) → 400 "Only COMPLETED work orders can be closed." (illegal transition).
  - Zero residual → no variance GL posted (close still succeeds).

### TC-MFG-038 — Cancel work order from PLANNED (no reversal needed)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid` · `POST /api/v1/work-orders/uid/{uid}/cancel?reason=`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** a PLANNED WO.
- **Steps:** 1. Open PLANNED WO; enter cancel reason; confirm cancel.
- **Test Data:** reason "Customer cancelled".
- **Expected Result:** status → CANCELLED (terminal); `cancelledAt` stamped; no stock/GL reversal (nothing issued); success "Work order cancelled".
- **Convention Assertions:** C2 envelope; C3 `WORKORDER.MANAGE`.
- **Negative / Edge:** reason is optional (cancel without reason allowed).

### TC-MFG-039 — Cancel work order after issue (IN_PROGRESS) reverses stock + GL
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Work Orders (`POST /api/v1/work-orders/uid/{uid}/cancel`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** WO with issued components + applied labour/overhead
- **Preconditions / Seed:** IN_PROGRESS WO with issued (costed) components and applied labour/overhead.
- **Steps:** 1. Cancel the WO.
- **Expected Result:** status → CANCELLED; each issued component reversed via `PRODUCTION_ISSUE_REVERSAL` (stock restored at original value), WIP debit reversed, GL DR Inventory / CR WIP; applied labour/overhead reversed (GL); accumulators consistent. Audit records reversal counts.
- **Convention Assertions:** C9 reversals (append-only) not edits; C2 envelope.
- **Negative / Edge:** cost-skipped lines (issuedValue=0) restore qty only.

### TC-MFG-040 — Cancel is blocked for COMPLETED, CLOSED, CANCELLED
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Work Orders (`POST /api/v1/work-orders/uid/{uid}/cancel`)
- **Permission / Role:** `WORKORDER.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** WO = COMPLETED, then CLOSED, then CANCELLED
- **Preconditions / Seed:** one WO in each terminal/near-terminal state.
- **Steps:** 1. Attempt cancel on a COMPLETED WO. 2. On a CLOSED WO. 3. On an already-CANCELLED WO.
- **Expected Result:**
  - COMPLETED → 400 "Cannot cancel a COMPLETED work order — close it first or contact a manager."
  - CLOSED → 400 "Cannot cancel a CLOSED work order."
  - CANCELLED → 400 "Cannot cancel a CANCELLED work order."
  The UI also hides the cancel control for these statuses (`canCancel` allows only PLANNED/RELEASED/IN_PROGRESS/COMPLETED — and even COMPLETED is server-blocked).
- **Convention Assertions:** C3 perm; illegal-transition rejection (no state change).
- **Negative / Edge:** confirms COMPLETED is offered client-side but rejected server-side (defect-surface case).

### TC-MFG-041 — Full happy-path lifecycle end-to-end
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (all transitions) + BOM
- **Permission / Role:** ORG_ADMIN (all mfg perms)
- **Variation:** FG = GOODS; ACTIVE BOM; costed components
- **Preconditions / Seed:** ACTIVE BOM for FG; component stock with avg cost in branch.
- **Steps:**
  1. Create WO (PLANNED).
  2. Release (RELEASED).
  3. Issue components (IN_PROGRESS).
  4. Apply labour + overhead.
  5. Complete (COMPLETED) with goodQty.
  6. Close (CLOSED).
- **Expected Result:** status badge walks PLANNED→RELEASED→IN_PROGRESS→COMPLETED→CLOSED; final cost report shows WIP debit ≈ WIP credit, computed unit cost, residual variance cleared; FG on-hand increased.
- **Convention Assertions:** C2 envelope at each step; C8 money/qty; C9 append-only postings; C6 axe on the detail at each state.
- **Negative / Edge:** skipping release before issue → issue rejected (covered in TC-MFG-028 negative).

### TC-MFG-042 — Work order cost report screen
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Work Orders (`/admin/work-orders/uid/:uid/cost-report` · `GET /api/v1/work-orders/uid/{uid}/cost-report`)
- **Permission / Role:** `MANUFACTURING.VIEW` (scoped) — ORG_ADMIN; cross-tenant → 403
- **Preconditions / Seed:** a WO with issued components + applied labour/overhead.
- **Steps:** 1. From WO detail, open the cost report route. 2. Inspect planned-vs-actual components, applied labour/overhead, WIP debit/credit, net WIP, computed unit cost, variance, incompleteCost flag.
- **Expected Result:** `WorkOrderCostReportDto` rendered; net WIP = wipDebitTotal − wipCreditTotal; component rows by code/name.
- **Convention Assertions:** **C1** WO referenced by woNumber, products by code/name — uid only in URL. C2 envelope. C4 loading/idle/error. C6 axe. C8 money strings.
- **Negative / Edge:** uid of another company → 403; unknown uid → error state.

### TC-MFG-043 — WIP reconciliation report — ties (balanced)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Manufacturing reporting (`/admin/manufacturing/wip-reconciliation` · `GET /api/v1/manufacturing/wip-reconciliation?companyId=`)
- **Permission / Role:** `MANUFACTURING.VIEW` — ORG_ADMIN; also NO-PERMISSION → redirect; cross-tenant company → 403
- **Variation:** company with open WOs whose WIP matches GL 1320
- **Preconditions / Seed:** a company with at least one open WO and a balanced WIP_INVENTORY (1320) GL balance.
- **Steps:** 1. Navigate `/admin/manufacturing/wip-reconciliation`. 2. Select company.
- **Expected Result:** `WipReconciliationDto {label, computed, expected, ties}` shown; `ties=true` rendered as balanced (no error alert). computed = Σ(wipDebit − wipCredit) of open WOs; expected = GL 1320 balance, base currency.
- **Convention Assertions:** C2 envelope; C4 loading/idle/error/forbidden distinct; C6 axe; C7 company scope; C8 base-currency money strings.
- **Negative / Edge:** company with no open WOs → computed 0; ties true if GL also 0.

### TC-MFG-044 — WIP reconciliation report — does NOT tie (finance-grade defect surfaced)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Manufacturing reporting (`GET /api/v1/manufacturing/wip-reconciliation`)
- **Permission / Role:** `MANUFACTURING.VIEW` — ORG_ADMIN
- **Variation:** computed ≠ expected
- **Preconditions / Seed:** a company where Σ open-WO WIP differs from GL 1320 (e.g. seeded mismatch).
- **Steps:** 1. Open WIP reconciliation for that company.
- **Expected Result:** `ties=false`; the screen raises an error alert "WIP Reconciliation Defect — Computed WIP ({computed}) does not match GL balance ({expected}). Finance review required."
- **Convention Assertions:** C2 envelope; C8 amounts; the mismatch is surfaced, not hidden.
- **Negative / Edge:** confirms a `ties=false` is treated as a defect indicator (per DTO doc — finance-grade).

### TC-MFG-045 — RBAC matrix: each manufacturing/BOM action denied to a role lacking its permission
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** BOM + Work Orders (all gated endpoints)
- **Permission / Role:** negative-auth across roles
- **Preconditions / Seed:** users for ACCOUNTANT, STOREKEEPER, SALES_REP (none granted mfg/BOM perms), a CUSTOM read-only role (`BOM.VIEW`+`MANUFACTURING.VIEW`), a NO-PERMISSION user.
- **Steps:** For each user, attempt: GET BOM list/detail, POST BOM create/activate; GET WO list/detail/cost-report/WIP-recon, POST WO create/release/complete/close/cancel.
- **Expected Result:**
  - NO-PERMISSION / non-mfg roles: nav items hidden; routes redirect; direct API → 403 for every endpoint.
  - CUSTOM read-only: can view BOM + WO lists/details/reports; all create/manage/release/close/cancel buttons hidden; corresponding APIs → 403.
  - ORG_ADMIN: all allowed.
- **Convention Assertions:** **C3** every action gated by its exact `@PreAuthorize` code (`BOM.VIEW/MANAGE`, `MANUFACTURING.VIEW`, `WORKORDER.MANAGE/RELEASE/CLOSE`); C4 forbidden state distinct.
- **Negative / Edge:** a user with `WORKORDER.MANAGE` but not `WORKORDER.RELEASE` cannot release (button hidden, API 403); with MANAGE but not `WORKORDER.CLOSE` cannot complete/close.

### TC-MFG-046 — rootadmin superuser bypass + cross-tenant visibility
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** BOM + Work Orders
- **Permission / Role:** rootadmin (bypasses permission + scope)
- **Preconditions / Seed:** BOMs/WOs in two different companies (tenant A and tenant B).
- **Steps:** 1. As rootadmin, view BOMs/WOs across both companies. 2. As ORG_ADMIN of tenant A, attempt to read tenant B's BOM/WO uid.
- **Expected Result:** rootadmin sees both tenants; ORG_ADMIN of A gets 403 on B's resources (ScopeGuard). Do NOT use rootadmin for negative-auth assertions.
- **Convention Assertions:** **C7** multi-tenant isolation enforced for normal users; rootadmin documented as the only bypass.
- **Negative / Edge:** tenant A's WO list with `companyId` of tenant B → 403 (ScopeGuard.assertCanActIn).

### TC-MFG-047 — Branch scoping: act in an unassigned branch is denied; non-default branch works
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Work Orders (create/issue/complete are branch-bound)
- **Permission / Role:** `WORKORDER.MANAGE`/`CLOSE` — ORG_ADMIN restricted to specific branches
- **Variation:** user assigned to ONE branch vs MANY vs ALL; default vs non-default branch
- **Preconditions / Seed:** multi-branch company; user assigned only to Branch-1.
- **Steps:** 1. Create/issue/complete a WO in Branch-1 (assigned). 2. Attempt a WO bound to Branch-2 (unassigned).
- **Expected Result:** Branch-1 operations succeed (including a non-default branch); Branch-2 operations denied by ScopeGuard.
- **Convention Assertions:** **C7** branch-scoped; C1 branch chosen via picker by name.
- **Negative / Edge:** switching the active branch (X-Branch-Uid) updates what the user can act on.

### TC-MFG-048 — ProductType.SERVICE finished good cannot receive stock at complete
- **Type:** Manual (API) / Both
- **Priority:** P2
- **Module / Submodule:** Work Orders (`complete`) + Product (`ProductType`)
- **Permission / Role:** `WORKORDER.CLOSE` — ORG_ADMIN
- **Variation:** FG product = SERVICE (non-stockable, `chk_product_service_stockable`)
- **Preconditions / Seed:** a WO whose FG is a SERVICE product.
- **Steps:** 1. Drive the WO to IN_PROGRESS and attempt complete (FG receipt).
- **Expected Result:** FG receipt into stock is invalid for a non-stockable SERVICE product — the stock posting path rejects (service products are not stockable). Document the actual behaviour observed (BOM/WO are typically authored for GOODS).
- **Convention Assertions:** C9 stockable constraint respected.
- **Negative / Edge:** confirms GOODS is the supported FG type for production receipt.

### TC-MFG-049 — Money & date formatting across manufacturing screens
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** BOM + Work Orders + reports
- **Permission / Role:** read perms — ORG_ADMIN
- **Preconditions / Seed:** WO with cost figures; BOM with qty/scrap.
- **Steps:** 1. Inspect WO detail/cost-report/WIP-recon money fields and any dates (planned/posting/effective-from).
- **Expected Result:** money rendered as `CUR 1,234.56` style on the wire (string), qty/percent via DecimalPipe; dates ISO `yyyy-MM-dd`.
- **Convention Assertions:** **C8** money strings + ISO dates.
- **Negative / Edge:** null/zero cost (incompleteCost) renders gracefully.

### TC-MFG-050 — Accessibility sweep (axe) for all manufacturing screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** BOM list/detail, WO list/detail/cost-report, WIP recon
- **Permission / Role:** read perms — ORG_ADMIN
- **Preconditions / Seed:** representative data on each screen.
- **Steps:** 1. Visit each route; run an axe scan; tab through forms/tables; check table captions/scope and form labels.
- **Expected Result:** zero serious/critical axe violations; pickers, status filters, lifecycle action forms keyboard-operable with aria labels; tables have captions/scope.
- **Convention Assertions:** **C6** WCAG 2.1 AA across all six routes; C1 pickers expose accessible names by resource name.
- **Negative / Edge:** four-state screens (loading/empty/error/forbidden) each remain axe-clean.
