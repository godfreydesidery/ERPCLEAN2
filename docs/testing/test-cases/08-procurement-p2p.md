# 08 — Procurement / Procure-to-Pay (PROC) Test Cases

Exhaustive UI-driven (Playwright) and manual test cases for the full procure-to-pay chain:
requisition → submit → approve → convert → RFQ → send → supplier-quote → award (creates PO) →
PO place/approve → goods-receipt → landed-cost → supplier-bill → 3-way bill-match → purchase-return.
Cases are written against the deployed QA app (http://16.170.11.41/), navigated by route, acting by
accessible role/label, choosing resources by NAME via picker, never by uid.

## Modules / submodules covered (verified controllers + base paths + frontend routes)

| Submodule | Controller (`@RequestMapping`) | Frontend route(s) |
|---|---|---|
| Purchase Requisition | `PurchaseRequisitionController` — `/api/v1/purchase-requisitions` | `/admin/purchase-requisitions`, `/admin/purchase-requisitions/create`, `/admin/purchase-requisitions/uid/:uid` |
| RFQ / Sourcing | `RfqController` — `/api/v1/rfqs` | `/admin/rfqs`, `/admin/rfqs/create`, `/admin/rfqs/uid/:uid` |
| Supplier Quote | `SupplierQuoteController` — `/api/v1/supplier-quotes` | **No dedicated route** — capture/list/award embedded in `/admin/rfqs/uid/:uid` (RFQ detail) |
| Purchase Order | `PurchaseOrderController` — `/api/v1/purchase-orders` | `/admin/purchase-orders`, `/admin/purchase-orders/uid/:uid` (no create route — created from quote or via API) |
| Goods Receipt | `GoodsReceiptController` — `/api/v1/goods-receipts` | `/admin/goods-receipts`, `/admin/goods-receipts/create`, `/admin/goods-receipts/uid/:uid` |
| Landed Cost | `LandedCostController` — `/api/v1/landed-costs` | `/admin/landed-costs`, `/admin/landed-costs/create`, `/admin/landed-costs/uid/:uid` |
| Supplier Bill | `SupplierBillController` — `/api/v1/ap/supplier-bills` | `/admin/ap/supplier-bills`, `/admin/ap/supplier-bills/enter`, `/admin/ap/supplier-bills/uid/:uid` |
| 3-Way Bill Match | `BillMatchController` — `/api/v1/ap/supplier-bills/uid/{billUid}/match` | Run automatically inside `/admin/ap/supplier-bills/enter`; "Match" action on `/admin/ap/supplier-bills` row. **Bill detail (`.../uid/:uid`) has NO match action** |
| Purchase Return | `PurchaseReturnController` — `/api/v1/purchase-returns` | `/admin/purchase-returns`, `/admin/purchase-returns/create`, `/admin/purchase-returns/uid/:uid` |
| Purchase Settings | `PurchaseSettingsController` — `/api/v1/purchase-settings` | `/admin/purchase-settings` |

## Permission codes in scope (EXACT `@PreAuthorize` codes from the controllers)

Requisition: `PURCHASE.REQUISITION.VIEW`, `PURCHASE.REQUISITION.CREATE`, `PURCHASE.REQUISITION.APPROVE`
RFQ: `PURCHASE.RFQ.VIEW`, `PURCHASE.RFQ.CREATE`, `PURCHASE.RFQ.AWARD`
Supplier Quote: `PURCHASE.QUOTE.CREATE`, `PURCHASE.QUOTE.VIEW`
Purchase Order: `PURCHASE.ORDER.VIEW`, `PURCHASE.ORDER.CREATE`, `PURCHASE.ORDER.VOID`, `PURCHASE.ORDER.APPROVE`
Goods Receipt: `PURCHASE.GOODS_RECEIPT.VIEW`, `PURCHASE.RECEIVE`, `PURCHASE.VOID`
Landed Cost: `PURCHASE.LANDED_COST.VIEW`, `PURCHASE.LANDED_COST.CREATE`, `PURCHASE.LANDED_COST.CONFIRM`
Supplier Bill: `AP.BILL.ENTER`, `AP.VIEW`
Bill Match: `AP.BILL.MATCH`
Purchase Return: `PURCHASE.RETURN.VIEW`, `PURCHASE.RETURN.CREATE`, `PURCHASE.RETURN.CONFIRM`
Purchase Settings: `PURCHASE.SETTINGS.VIEW`, `PURCHASE.SETTINGS.EDIT`

> **CRITICAL ACCURACY NOTE — RBAC permission-code mismatch (verified, real defect).**
> Several `@PreAuthorize` codes used by these controllers are **NOT seeded by any SQL migration**, so
> **no role (custom or seeded) can ever be granted them** — only `rootadmin` (which bypasses all checks)
> can exercise the endpoint. Verified by grepping `backend/src/main/resources/db/migration/*.sql` vs the
> controller annotations:
> - `RfqController` uses `PURCHASE.RFQ.CREATE` and `PURCHASE.RFQ.AWARD`; SQL (`V33__purchase_rfq.sql`) seeds only `PURCHASE.RFQ.VIEW` and `PURCHASE.RFQ.MANAGE`. → **send/create/cancel/award unreachable for non-root.**
> - `SupplierQuoteController` uses `PURCHASE.QUOTE.CREATE` / `PURCHASE.QUOTE.VIEW`; **neither code is seeded anywhere.** → **quote capture/view/by-rfq/last-cost unreachable for non-root.**
> - `LandedCostController` uses `PURCHASE.LANDED_COST.VIEW/CREATE/CONFIRM` (underscore); SQL (`V34__purchase_landed_cost.sql`) seeds `PURCHASE.LANDEDCOST.VIEW` / `PURCHASE.LANDEDCOST.MANAGE` (no underscore). → **all landed-cost endpoints unreachable for non-root.**
> - `PurchaseReturnController` uses `PURCHASE.RETURN.CONFIRM`; SQL (`V35__purchase_returns.sql`) seeds only `PURCHASE.RETURN.VIEW` / `PURCHASE.RETURN.CREATE`. → **confirm unreachable for non-root.**
> - `PurchaseSettingsController` uses `PURCHASE.SETTINGS.VIEW` / `PURCHASE.SETTINGS.EDIT`; SQL (`V32__purchase_requisitions.sql`) seeds only `PURCHASE.SETTINGS.MANAGE`. → **view/edit unreachable for non-root.**
> The frontend route guards and `session.hasPermission(...)` checks use the **controller** codes (e.g. `PURCHASE.LANDED_COST.VIEW`, `PURCHASE.RFQ.AWARD`, `PURCHASE.SETTINGS.VIEW`), which also are not grantable — so for non-root users these nav items/screens render forbidden even when an admin "grants everything available". Negative-auth cases below are written to **confirm this current behaviour** (TC-PROC-110..114), and each affected positive case notes "run as rootadmin (only grantable principal)".

> **OTHER VERIFIED NOTES (backend-only / embedded-UI):**
> - **PO approval gate has no UI.** `PurchaseOrderController` exposes `/approve` + `/reject` (`PURCHASE.ORDER.APPROVE`) and `PoApprovalStatus {NOT_REQUIRED, PENDING, APPROVED, REJECTED}` exists, but `PurchaseOrderDto` does NOT expose `approvalStatus` and `purchase-order-detail.component.ts` has NO approve/reject action. → PO approve/reject is **backend-only (API-only)**.
> - **Create-PO-from-quote** (`POST /api/v1/purchase-orders/from-quote/{quoteUid}`, `PURCHASE.ORDER.CREATE`) is invoked **server-side by RFQ award**; there is no separate "from quote" button. PO has no standalone create screen in the UI — POs originate from requisition-convert(PURCHASE_ORDER) or RFQ-award.
> - **3-way match on bill detail:** `bill-detail.component.ts` shows match *status* only; the match is *run* automatically right after Enter-Bill (`enter-bill.component`) and via the "Match" action on the bills list. `accept-variance` UI lives only on the Enter-Bill result panel.
> - `GoodsReceiptController` has **no separate "void" UI confirmed in purchases components**; void is API-level (`PURCHASE.VOID`).

## Lifecycle enums (verified — exact values + transitions)

- `RequisitionStatus`: DRAFT, SUBMITTED, APPROVED, REJECTED, CONVERTED, CANCELLED
  - Legal: DRAFT→SUBMITTED (submit); SUBMITTED→APPROVED (approve); SUBMITTED→REJECTED (reject); APPROVED→CONVERTED (convert, targetType RFQ|PURCHASE_ORDER); any non-final→CANCELLED (cancel).
- `RfqStatus`: DRAFT, SENT, QUOTES_RECEIVED, AWARDED, CANCELLED
  - Legal: DRAFT→SENT (send); SENT→AWARDED (award; also creates PO); active→CANCELLED (cancel). (QUOTES_RECEIVED is set when quotes captured.)
- `SupplierQuoteStatus`: RECEIVED, AWARDED, NOT_AWARDED (capture→RECEIVED; award sets winner AWARDED, others NOT_AWARDED).
- `PurchaseOrderStatus`: DRAFT, ORDERED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, VOID
  - Legal: DRAFT→ORDERED (place); ORDERED→PARTIALLY_RECEIVED→RECEIVED (driven by GR); {ORDERED,PARTIALLY_RECEIVED,RECEIVED}→CLOSED; {DRAFT,ORDERED,PARTIALLY_RECEIVED}→VOID.
- `PoApprovalStatus`: NOT_REQUIRED, PENDING, APPROVED, REJECTED (backend-only).
- `GoodsReceiptStatus`: DRAFT, RECEIVED, VOID (create-and-receive → RECEIVED; RECEIVED→VOID).
- `LandedCostStatus`: DRAFT, CONFIRMED (create→DRAFT; confirm→CONFIRMED). `LandedCostBasis`: BY_VALUE, BY_QUANTITY. `LandedCostChargeType`: FREIGHT, DUTY, CLEARING, INSURANCE, OTHER.
- `SupplierBillStatus`: DRAFT, MATCHED, HELD, APPROVED, PARTIALLY_PAID, PAID.
- `BillMatchStatus` (per line): MATCHED, HELD_PRICE_VARIANCE, HELD_QTY_VARIANCE, VARIANCE_ACCEPTED.
- `PurchaseReturnStatus`: DRAFT, CONFIRMED (create→DRAFT; confirm→CONFIRMED).

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User type | `rootadmin` (superuser bypass, the only grantable principal for several endpoints — see RBAC note); `PURCHASE_OFFICER`, `STOREKEEPER`, `ACCOUNTANT` seeded roles; CUSTOM role (subset); NO-PERMISSION user (forbidden/empty-nav) |
| Supplier kind | `SupplierKind {GOODS, SERVICE}` — GOODS supplier drives stock-moving GR/landed-cost; SERVICE supplier on a service-only bill (no GR, no 3-way match) |
| Product type | `ProductType {GOODS, SERVICE}` — GOODS lines are receivable/stockable; SERVICE lines are not stockable |
| Branch | default vs non-default; user assigned to ONE / MANY / ALL branches; acting in an unassigned branch (denied); X-Branch-Uid switching |
| Company / tenant | multi-company isolation (tenant A cannot see tenant B's requisitions/POs/bills) |
| Convert target | requisition convert `targetType` = `PURCHASE_ORDER` vs `RFQ` |
| Landed-cost basis | BY_VALUE vs BY_QUANTITY; each charge type |
| Match outcome | all-MATCHED; HELD_PRICE_VARIANCE; HELD_QTY_VARIANCE; VARIANCE_ACCEPTED; bill with no PO (no match) |
| Screen states | loading / empty / error / forbidden on every list + detail |

---

# TEST CASES

## A. Purchase Requisition (`/api/v1/purchase-requisitions`)

### TC-PROC-001 — Requisition list loads with pagination + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions` · `/api/v1/purchase-requisitions`)
- **Permission / Role:** `PURCHASE.REQUISITION.VIEW` — runs as PURCHASE_OFFICER (granted); also as NO-PERMISSION user → expect nav item hidden + route guard redirect to `/admin/home`
- **Preconditions / Seed:** ≥ 26 requisitions for the active company (seed via TC-PROC-005 ×26 or API) to force ≥ 2 pages at default size 25
- **Steps:**
  1. Login as PURCHASE_OFFICER; navigate to `/admin/purchase-requisitions`.
  2. Observe loading state, then the list table.
  3. Assert paginator shows FIRST, PREVIOUS, page numbers, NEXT, LAST; click NEXT then LAST.
  4. Apply status filter = `DRAFT`; assert rows filter.
- **Test Data:** company = "Acme Trading Ltd" (default), 26 seeded requisitions
- **Expected Result:** Table lists requisitions with human columns (requisition number, status badge, required-by date); envelope `ApiResponse<List>` with `meta {page,size,totalElements,totalPages,hasNext}`; HTTP 200.
- **Convention Assertions:** C2 envelope+meta; C4 four states; C5 paginator (all five controls) and self-hidden at 1 page; C1 no raw uid in any column; C6 axe clean; C7 only this company's rows.
- **Negative / Edge:** Empty company → distinct empty state ("no requisitions"); backend 500 → error state; NO-PERMISSION user → 403 / forbidden screen.

### TC-PROC-002 — Requisition list empty state
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions` · `/api/v1/purchase-requisitions`)
- **Permission / Role:** `PURCHASE.REQUISITION.VIEW` — runs as PURCHASE_OFFICER
- **Preconditions / Seed:** a fresh company with zero requisitions
- **Steps:** Switch active company to the empty one; navigate to the list.
- **Expected Result:** Distinct empty-state message, no table rows, paginator hidden.
- **Convention Assertions:** C4 empty; C5 paginator hidden when 1/0 pages; C6 axe.
- **Negative / Edge:** N/A.

### TC-PROC-003 — Create requisition (GOODS lines) via picker, lands DRAFT
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions/create` · `POST /api/v1/purchase-requisitions`)
- **Permission / Role:** `PURCHASE.REQUISITION.CREATE` — runs as PURCHASE_OFFICER; also as a user with only `PURCHASE.REQUISITION.VIEW` → Create button/route hidden + API 403
- **Variation:** product = GOODS; branch = default
- **Preconditions / Seed:** ≥ 1 ACTIVE GOODS product + a unit of measure exist for the company
- **Steps:**
  1. Navigate `/admin/purchase-requisitions/create`.
  2. Set required-by date, optional cost-centre, notes.
  3. Add a line: choose product **by name** via picker; choose unit; enter requestedQty=10, estimatedUnitCost=5,000.
  4. Submit.
- **Test Data:** product "Cement 50kg"; unit "BAG"; requestedQty 10; estimatedUnitCost 5000
- **Expected Result:** HTTP 201; new requisition status = `DRAFT`; redirect to its detail; uid only in URL.
- **Convention Assertions:** C1 product/unit chosen via picker by name, no typed uid, no raw uid on screen; C2 201 envelope; C8 money formatted; C3 RBAC.
- **Negative / Edge:** No lines (`@NotEmpty`) → validation error; requestedQty ≤ 0 (`@Positive`) → rejected; missing companyUid → 400; viewer-only user → 403.

### TC-PROC-004 — Requisition detail four states + draft → submit (DRAFT→SUBMITTED)
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions/uid/:uid` · `POST .../uid/{uid}/submit`)
- **Permission / Role:** `PURCHASE.REQUISITION.CREATE` (submit) — runs as PURCHASE_OFFICER; also as cross-tenant user → forbidden
- **Preconditions / Seed:** a DRAFT requisition (TC-PROC-003)
- **Steps:** Open the requisition detail; click Submit.
- **Expected Result:** Status badge → `SUBMITTED`; `submittedAt` shown; Submit hidden afterward.
- **Convention Assertions:** C4 loading/idle/error/forbidden; C1 no uid shown; C3 RBAC; C6 axe.
- **Negative / Edge:** Submit on an already-SUBMITTED/APPROVED requisition → illegal transition rejected (error toast); detail of another tenant's requisition → forbidden state.

### TC-PROC-005 — Approve a submitted requisition (SUBMITTED→APPROVED)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions/uid/:uid` · `POST .../uid/{uid}/approve`)
- **Permission / Role:** `PURCHASE.REQUISITION.APPROVE` — runs as a role granted APPROVE; also as PURCHASE_OFFICER lacking APPROVE → Approve button hidden + API 403
- **Preconditions / Seed:** a SUBMITTED requisition
- **Steps:** Open detail; click Approve.
- **Expected Result:** Status → `APPROVED`; `approvedAt` set; Convert action now offered.
- **Convention Assertions:** C3 RBAC (APPROVE-gated, distinct from CREATE); C1 uid hidden; C4 states.
- **Negative / Edge:** Approve a DRAFT (not submitted) → illegal transition rejected; user with CREATE but not APPROVE → 403.

### TC-PROC-006 — Reject a submitted requisition with reason (SUBMITTED→REJECTED)
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions/uid/:uid` · `POST .../uid/{uid}/reject?reason=`)
- **Permission / Role:** `PURCHASE.REQUISITION.APPROVE` — runs as approver; also as approver-less user → 403
- **Preconditions / Seed:** a SUBMITTED requisition
- **Steps:** Open detail; click Reject; the FE requires a non-empty reason (client-validated); enter reason; confirm.
- **Test Data:** reason = "Budget exceeded for Q2"
- **Expected Result:** Status → `REJECTED`; `rejectedAt` set; reason captured.
- **Convention Assertions:** C3 RBAC; C4; C1 uid hidden; required-field (reason) client validation.
- **Negative / Edge:** Empty reason → FE blocks ("Rejection reason is required"); reject an APPROVED requisition → illegal transition rejected by service.

### TC-PROC-007 — Convert approved requisition to PURCHASE_ORDER (APPROVED→CONVERTED)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions/uid/:uid` · `POST .../uid/{uid}/convert?targetType=PURCHASE_ORDER`)
- **Permission / Role:** `PURCHASE.REQUISITION.APPROVE` (convert is APPROVE-gated, verified) — runs as approver
- **Variation:** convert target = PURCHASE_ORDER
- **Preconditions / Seed:** an APPROVED requisition with GOODS lines
- **Steps:** Open detail; open Convert form; select target = "Purchase Order"; confirm.
- **Expected Result:** Returns the created PO uid (in `data`); requisition status → `CONVERTED` with `convertedToType=PURCHASE_ORDER`; a "View created PURCHASE_ORDER" link routes to `/admin/purchase-orders/uid/{uid}`.
- **Convention Assertions:** C1 result uid only in the follow link URL, not displayed as a typed value to copy; C3 RBAC; C2 envelope (`data` = uid string).
- **Negative / Edge:** Convert a DRAFT/SUBMITTED (not approved) → illegal transition rejected; convert again after CONVERTED → rejected.

### TC-PROC-008 — Convert approved requisition to RFQ (APPROVED→CONVERTED, target=RFQ)
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`.../uid/{uid}/convert?targetType=RFQ`)
- **Permission / Role:** `PURCHASE.REQUISITION.APPROVE` — runs as approver
- **Variation:** convert target = RFQ
- **Preconditions / Seed:** an APPROVED requisition
- **Steps:** Convert with target = "RFQ".
- **Expected Result:** Created RFQ uid returned; status `CONVERTED`, `convertedToType=RFQ`; follow link routes to `/admin/rfqs/uid/{uid}`.
- **Convention Assertions:** C1 picker/route only; C3 RBAC.
- **Negative / Edge:** Invalid targetType value (e.g. `FOO`) → 400/rejected.

### TC-PROC-009 — Cancel a requisition (non-final → CANCELLED)
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Purchase Requisition (`.../uid/{uid}/cancel?reason=`)
- **Permission / Role:** `PURCHASE.REQUISITION.CREATE` (cancel is CREATE-gated, verified) — runs as PURCHASE_OFFICER
- **Preconditions / Seed:** a DRAFT (and separately a SUBMITTED) requisition
- **Steps:** Open detail; Cancel; optionally enter reason; confirm.
- **Expected Result:** Status → `CANCELLED`; `cancelledAt` set.
- **Convention Assertions:** C3 RBAC; C4; C1 uid hidden.
- **Negative / Edge:** Cancel a CONVERTED or already-CANCELLED requisition → rejected (final state).

### TC-PROC-010 — Requisition detail forbidden (cross-tenant) + RBAC nav hiding
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Purchase Requisition (`/admin/purchase-requisitions/uid/:uid`)
- **Permission / Role:** `PURCHASE.REQUISITION.VIEW` scoped — runs as tenant-B user opening tenant-A requisition
- **Preconditions / Seed:** requisition belonging to company A; user belongs only to company B
- **Steps:** Tenant-B user navigates directly to tenant-A requisition uid URL.
- **Expected Result:** API 403; detail shows the `forbidden` state (distinct from error).
- **Convention Assertions:** C3 RBAC scoping; C7 tenant isolation; C4 forbidden state distinct.
- **Negative / Edge:** NO-PERMISSION user → "Purchase Requisitions" nav item absent.

---

## B. RFQ / Sourcing (`/api/v1/rfqs`) — RBAC note: send/create/cancel/award reachable only by rootadmin

### TC-PROC-020 — RFQ list loads with pagination + four states
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** RFQ (`/admin/rfqs` · `GET /api/v1/rfqs`)
- **Permission / Role:** `PURCHASE.RFQ.VIEW` (this code IS seeded — grantable) — runs as a role granted RFQ VIEW; also NO-PERMISSION → nav hidden
- **Preconditions / Seed:** ≥ 26 RFQs
- **Steps:** Navigate `/admin/rfqs`; observe states; page through.
- **Expected Result:** RFQ list (rfq number, status badge, response-due date); paginator full controls; HTTP 200.
- **Convention Assertions:** C2 meta; C4 four states; C5 paginator; C1 no uid columns; C6 axe; C7 company-scoped.
- **Negative / Edge:** empty company → empty state; error injected → error state.

### TC-PROC-021 — Create RFQ from scratch with supplier invites + GOODS lines (DRAFT)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** RFQ (`/admin/rfqs/create` · `POST /api/v1/rfqs`)
- **Permission / Role:** `PURCHASE.RFQ.CREATE` — **NOT seeded → run as rootadmin (only grantable principal)**; also as a granted-everything CUSTOM role user → expect forbidden (documents the mismatch defect)
- **Variation:** supplier = GOODS kind; ≥ 2 suppliers invited; product = GOODS
- **Preconditions / Seed:** ≥ 2 ACTIVE suppliers and ≥ 1 GOODS product
- **Steps:**
  1. Navigate `/admin/rfqs/create`.
  2. Set response-due date, notes.
  3. Invite suppliers: choose each **by name** via picker (≥ 2).
  4. Add lines: product **by name**, unit, quantity.
  5. Submit.
- **Test Data:** suppliers "MegaSupply Co", "BuildMart"; product "Cement 50kg" qty 100
- **Expected Result:** HTTP 201; RFQ status = `DRAFT`; invited supplier list shows human names; redirect to detail.
- **Convention Assertions:** C1 suppliers + products via picker by name, supplier uids stored under the hood, no raw uid visible; C2 201; C3 RBAC.
- **Negative / Edge:** no suppliers (`@NotEmpty supplierUids`) → rejected; no lines → rejected; qty ≤ 0 → rejected; CUSTOM-role user → 403 (mismatch).

### TC-PROC-022 — RFQ detail: send (DRAFT→SENT)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** RFQ (`/admin/rfqs/uid/:uid` · `POST .../uid/{uid}/send`)
- **Permission / Role:** `PURCHASE.RFQ.CREATE` (send is CREATE-gated) — run as rootadmin (only grantable)
- **Preconditions / Seed:** a DRAFT RFQ with invited suppliers
- **Steps:** Open RFQ detail; click Send.
- **Expected Result:** Status badge → `SENT`; success toast "Suppliers have been notified."
- **Convention Assertions:** C4 states (loading/idle/error/forbidden); C1 uid hidden; C3 RBAC.
- **Negative / Edge:** Send an already-SENT/AWARDED/CANCELLED RFQ → illegal transition rejected.

### TC-PROC-023 — Capture a supplier quote against a SENT RFQ (embedded; quote→RECEIVED, RFQ→QUOTES_RECEIVED)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Supplier Quote (embedded in `/admin/rfqs/uid/:uid` · `POST /api/v1/supplier-quotes`)
- **Permission / Role:** `PURCHASE.QUOTE.CREATE` — **NOT seeded → run as rootadmin only**; the capture-quote form is hidden unless `session.hasPermission('PURCHASE.QUOTE.CREATE')`
- **Variation:** supplier = GOODS; ≥ 2 quotes captured (to compare/award later)
- **Preconditions / Seed:** a SENT RFQ with ≥ 2 invited suppliers
- **Steps:**
  1. Open RFQ detail; click "Capture Quote".
  2. Pick supplier **by name** via picker (from invited list).
  3. Optional validUntil, leadTimeDays, notes.
  4. Per RFQ line: enter quotedQty + unitPriceAmount.
  5. Submit; repeat for the 2nd supplier with a different price.
- **Test Data:** supplier A unit price 4,800; supplier B unit price 4,950
- **Expected Result:** Each quote created (HTTP 201) with status `RECEIVED`; quote appears in the RFQ's quote list (fetched `GET /api/v1/supplier-quotes/by-rfq/{rfqUid}`); RFQ status becomes `QUOTES_RECEIVED`.
- **Convention Assertions:** C1 supplier via picker by name, no uid typed/shown; C2 201 + list meta on by-rfq; C8 unit price formatted; C5 quotes sub-list has paginator.
- **Negative / Edge:** capture on a DRAFT (not SENT) RFQ → rejected; quotedQty ≤ 0 / unitPrice ≤ 0 (`@Positive`) → FE blocks + API 400; supplier not invited → service rejects; CUSTOM-role user → capture form absent + 403.

### TC-PROC-024 — Quote comparison list (by-rfq) + last-quoted-cost reference
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Supplier Quote (`/admin/rfqs/uid/:uid` quote panel · `GET /api/v1/supplier-quotes/by-rfq/{rfqUid}`, `GET /api/v1/supplier-quotes/last-quoted-cost`)
- **Permission / Role:** `PURCHASE.QUOTE.VIEW` — **NOT seeded → run as rootadmin only**
- **Preconditions / Seed:** an RFQ with ≥ 2 captured quotes
- **Steps:** Open RFQ detail; review the quotes table (supplier, per-line unit price, status); confirm prices are comparable side-by-side.
- **Expected Result:** Quotes listed by supplier name with prices; statuses `RECEIVED`; the cheaper quote is identifiable.
- **Convention Assertions:** C2 envelope; C1 supplier shown by name not uid; C4 quotes sub-state (loading/idle/error); C5 paginator on the quotes list.
- **Negative / Edge:** RFQ with no quotes → empty quotes state; `last-quoted-cost` for an unknown product/supplier → null/empty without error.

### TC-PROC-025 — Award RFQ to winning quote (SENT/QUOTES_RECEIVED→AWARDED; creates PO; quote→AWARDED, losers→NOT_AWARDED)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** RFQ (`/admin/rfqs/uid/:uid` · `POST .../uid/{uid}/award?quoteUid=`)
- **Permission / Role:** `PURCHASE.RFQ.AWARD` — **NOT seeded → run as rootadmin only**; award buttons gated by `session.hasPermission('PURCHASE.RFQ.AWARD')`
- **Preconditions / Seed:** RFQ with ≥ 2 RECEIVED quotes
- **Steps:** In the quotes table, click "Award" on the winning quote.
- **Expected Result:** RFQ status → `AWARDED`; the awarded PO uid returned (`awardedPoUid`); success toast "PO {uid} created."; winning quote `AWARDED`, others `NOT_AWARDED`; PO created in `DRAFT` (verify via `/admin/purchase-orders/uid/{awardedPoUid}`).
- **Convention Assertions:** C1 quote chosen via its row action (by supplier name), awardedPoUid surfaced only as a follow-link; C3 RBAC; C2 envelope.
- **Negative / Edge:** award a DRAFT (unsent) RFQ → rejected; award an already-AWARDED RFQ → rejected; award with a quoteUid from a different RFQ → rejected.

### TC-PROC-026 — Cancel RFQ (active → CANCELLED)
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** RFQ (`.../uid/{uid}/cancel`)
- **Permission / Role:** `PURCHASE.RFQ.CREATE` (cancel CREATE-gated) — run as rootadmin only
- **Preconditions / Seed:** a DRAFT and a SENT RFQ
- **Steps:** Open detail; Cancel.
- **Expected Result:** Status → `CANCELLED`.
- **Convention Assertions:** C3 RBAC; C4; C1.
- **Negative / Edge:** cancel an AWARDED RFQ → rejected (final).

### TC-PROC-027 — RFQ create-from-requisition source link
- **Type:** Manual · **Priority:** P3
- **Module / Submodule:** RFQ (`POST /api/v1/rfqs` with `sourceRequisitionUid`)
- **Permission / Role:** `PURCHASE.RFQ.CREATE` — rootadmin
- **Preconditions / Seed:** an APPROVED requisition converted to RFQ (TC-PROC-008) OR create RFQ supplying `sourceRequisitionUid`
- **Steps:** Verify the resulting RFQ references the source requisition (lines/notes carried).
- **Expected Result:** RFQ created with `sourceRequisitionUid` linkage; lines reflect requisition lines.
- **Convention Assertions:** C1 source chosen/linked by uid under the hood (not displayed); C2 201.
- **Negative / Edge:** invalid sourceRequisitionUid → rejected.

---

## C. Purchase Order (`/api/v1/purchase-orders`)

### TC-PROC-040 — PO list loads with search + pagination + four states
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Purchase Order (`/admin/purchase-orders` · `GET /api/v1/purchase-orders?q=`)
- **Permission / Role:** `PURCHASE.ORDER.VIEW` — runs as PURCHASE_OFFICER; also NO-PERMISSION → nav hidden + redirect
- **Preconditions / Seed:** ≥ 26 POs
- **Steps:** Navigate `/admin/purchase-orders`; observe states; enter a search term `q`; page through.
- **Expected Result:** PO list (order number, status badge, supplier name, order total); `q` filters; paginator full controls.
- **Convention Assertions:** C2 meta; C4; C5; C1 supplier by name not uid; C8 money "CUR 1,234.56"; C6 axe; C7 company-scoped.
- **Negative / Edge:** empty → empty state; error → error state.

### TC-PROC-041 — PO detail four states + DRAFT line management via pickers
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Order (`/admin/purchase-orders/uid/:uid` · `POST/PUT/DELETE .../uid/{uid}/lines`, `GET .../uid/{uid}/lines`)
- **Permission / Role:** `PURCHASE.ORDER.CREATE` (line edits) + `PURCHASE.ORDER.VIEW` — runs as PURCHASE_OFFICER; viewer-only → line controls hidden
- **Variation:** product = GOODS; DRAFT PO
- **Preconditions / Seed:** a DRAFT PO (from RFQ award or requisition convert)
- **Steps:**
  1. Open PO detail; observe loading→idle.
  2. Add a line: search product **by name**, select; choose unit; orderedQty=20; unitCost=4,800; add.
  3. Edit the line qty; then remove a line.
- **Test Data:** product "Cement 50kg"; unit "BAG"; qty 20; unit cost 4800
- **Expected Result:** Line added (201), edited (200), removed (204); PO `orderTotalAmount` recomputed server-side and refetched; lines list reflects changes.
- **Convention Assertions:** C1 product + unit via picker by name; no uid typed or shown; C2 envelope/201/204; C8 money formatting; C4 line panel states (loading/idle/error); C3 RBAC.
- **Negative / Edge:** add/edit/remove line on a non-DRAFT PO → rejected (DRAFT-only); qty ≤ 0 / cost < 0 → FE blocks + API rejects; viewer-only → 403.

### TC-PROC-042 — Place a PO (DRAFT→ORDERED, assigns PO-####)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Order (`.../uid/{uid}/place`)
- **Permission / Role:** `PURCHASE.ORDER.CREATE` — runs as PURCHASE_OFFICER
- **Preconditions / Seed:** a DRAFT PO with ≥ 1 line (Place button enabled only when DRAFT + has lines + has CREATE)
- **Steps:** Open detail; click Place.
- **Expected Result:** Status → `ORDERED`; `orderNumber` (PO-####) assigned and shown; `orderedAt` set; Receive/Close/Void available.
- **Convention Assertions:** C3 RBAC; C4; C1 uid hidden (order number is human, not uid); C9 append-only lifecycle.
- **Negative / Edge:** place a PO with zero lines → button disabled; place a non-DRAFT PO → rejected.

### TC-PROC-043 — Close a PO ({ORDERED,PARTIALLY_RECEIVED,RECEIVED}→CLOSED)
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Purchase Order (`.../uid/{uid}/close`)
- **Permission / Role:** `PURCHASE.ORDER.CREATE` — runs as PURCHASE_OFFICER
- **Preconditions / Seed:** an ORDERED PO (and separately a RECEIVED PO)
- **Steps:** Open detail; click Close.
- **Expected Result:** Status → `CLOSED`; `closedAt` set; PO becomes read-only.
- **Convention Assertions:** C3 RBAC; C4; C1.
- **Negative / Edge:** close a DRAFT PO → rejected (only ORDERED/PARTIALLY_RECEIVED/RECEIVED closeable); close an already-CLOSED/VOID PO → rejected.

### TC-PROC-044 — Void a PO with required reason ({DRAFT,ORDERED,PARTIALLY_RECEIVED}→VOID)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Order (`.../uid/{uid}/void`)
- **Permission / Role:** `PURCHASE.ORDER.VOID` — runs as a role granted VOID; also as PURCHASE_OFFICER lacking VOID → Void hidden + API 403
- **Preconditions / Seed:** a DRAFT (and an ORDERED) PO
- **Steps:** Open detail; click Void; FE requires a non-empty reason; enter reason; confirm.
- **Test Data:** reason = "Supplier discontinued item"
- **Expected Result:** Status → `VOID`; `voidedAt` + `voidReason` set.
- **Convention Assertions:** C3 RBAC (VOID distinct from CREATE); required-field reason; C4; C1.
- **Negative / Edge:** empty reason → FE blocks ("A void reason is required"); void a CLOSED PO → rejected; void a RECEIVED PO → rejected (only DRAFT/ORDERED/PARTIALLY_RECEIVED voidable).

### TC-PROC-045 — PO approval gate (backend-only): approve a PENDING PO
- **Type:** Manual (API) · **Priority:** P2
- **Module / Submodule:** Purchase Order (`POST .../uid/{uid}/approve` · `PURCHASE.ORDER.APPROVE`)
- **Permission / Role:** `PURCHASE.ORDER.APPROVE` (seeded in V32) — runs as approver role; PURCHASE_OFFICER without APPROVE → 403
- **Preconditions / Seed:** Purchase Settings with `poApprovalEnabled=true` and a threshold below the PO total, so the PO is in `PoApprovalStatus.PENDING`
- **Steps:** Call `POST /api/v1/purchase-orders/uid/{uid}/approve` with `ApprovePoRequest` body.
- **Expected Result:** `approvalStatus` PENDING→APPROVED (server). **Note: there is NO PO approve UI; PurchaseOrderDto does not expose approvalStatus** — verify via API/DB only.
- **Convention Assertions:** C3 RBAC; C2 envelope.
- **Negative / Edge:** approve a NOT_REQUIRED PO → rejected; user lacking APPROVE → 403; reject path (`.../reject`) sets REJECTED.

### TC-PROC-046 — PO approval gate (backend-only): reject a PENDING PO
- **Type:** Manual (API) · **Priority:** P3
- **Module / Submodule:** Purchase Order (`POST .../uid/{uid}/reject` · `PURCHASE.ORDER.APPROVE`)
- **Permission / Role:** `PURCHASE.ORDER.APPROVE` — approver
- **Preconditions / Seed:** a PENDING PO (as TC-PROC-045)
- **Steps:** Call `.../reject` with `ApprovePoRequest`.
- **Expected Result:** `approvalStatus` → REJECTED.
- **Convention Assertions:** C3 RBAC; C2 envelope.
- **Negative / Edge:** reject a non-PENDING PO → rejected.

### TC-PROC-047 — Create PO from awarded quote (API) — `from-quote/{quoteUid}`
- **Type:** Manual (API) · **Priority:** P2
- **Module / Submodule:** Purchase Order (`POST /api/v1/purchase-orders/from-quote/{quoteUid}` · `PURCHASE.ORDER.CREATE`)
- **Permission / Role:** `PURCHASE.ORDER.CREATE` (`@perm.has`, not scoped) — runs as PURCHASE_OFFICER
- **Preconditions / Seed:** an AWARDED supplier quote (this endpoint is normally called server-side by RFQ award)
- **Steps:** Call `POST /api/v1/purchase-orders/from-quote/{quoteUid}`.
- **Expected Result:** HTTP 201; PO created in `DRAFT` from the quote's lines/prices.
- **Convention Assertions:** C2 201; C1 quoteUid in path only.
- **Negative / Edge:** quote not AWARDED → rejected; duplicate from-quote call → rejected/idempotent per service.

### TC-PROC-048 — PO multi-tenant + branch isolation
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Purchase Order (`/admin/purchase-orders` + detail)
- **Permission / Role:** `PURCHASE.ORDER.VIEW` scoped — runs as tenant-B user against tenant-A PO; and as a user assigned to branch X acting in branch Y
- **Variation:** branch = non-default; user assigned ONE branch
- **Preconditions / Seed:** PO in company A / branch "Arusha"; user in company B; and a user assigned only to branch "Dar" attempting branch "Arusha" (X-Branch-Uid)
- **Steps:** (a) tenant-B opens tenant-A PO uid; (b) single-branch user switches X-Branch-Uid to an unassigned branch and lists POs.
- **Expected Result:** (a) 403 forbidden state; (b) acting in an unassigned branch is denied; only assigned-branch/company POs visible.
- **Convention Assertions:** C7 tenant + branch scoping; C3 RBAC; C4 forbidden state.
- **Negative / Edge:** ALL-branch user sees all branches' POs of their company only.

---

## D. Goods Receipt (`/api/v1/goods-receipts`)

### TC-PROC-060 — GR list loads with search + pagination + four states
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Goods Receipt (`/admin/goods-receipts` · `GET /api/v1/goods-receipts?q=`)
- **Permission / Role:** `PURCHASE.GOODS_RECEIPT.VIEW` (API). **Note: the FE route guard for the GR list uses `PURCHASE.ORDER.VIEW`, not GOODS_RECEIPT.VIEW** (verified in admin.routes.ts) — runs as PURCHASE_OFFICER; NO-PERMISSION → hidden
- **Preconditions / Seed:** ≥ 26 GRNs
- **Steps:** Navigate `/admin/goods-receipts`; observe states; search; page.
- **Expected Result:** GR list (GRN number, status badge, PO/supplier, received date); paginator full controls.
- **Convention Assertions:** C2 meta; C4; C5; C1 no uid columns; C6 axe; C7 company-scoped. Document the FE/API guard discrepancy (list page guard = PURCHASE.ORDER.VIEW).
- **Negative / Edge:** empty → empty state; error → error state.

### TC-PROC-061 — Create-and-receive a GR against an ORDERED PO (DRAFT→RECEIVED; PO→PARTIALLY_RECEIVED / RECEIVED)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Goods Receipt (`/admin/goods-receipts/create` · `POST /api/v1/goods-receipts`)
- **Permission / Role:** `PURCHASE.RECEIVE` (authority scoped to the PO's company, target type `purchaseorder`) — runs as STOREKEEPER (granted RECEIVE); also as viewer → Receive route/button hidden + 403
- **Variation:** product = GOODS (stockable); partial then full receipt
- **Preconditions / Seed:** an ORDERED PO with GOODS lines (outstanding qty > 0)
- **Steps:**
  1. Navigate `/admin/goods-receipts/create`.
  2. Choose the PO **by order number** via picker.
  3. For a line, receive a partial qty (< outstanding).
  4. Submit → GR RECEIVED; PO becomes PARTIALLY_RECEIVED.
  5. Create a 2nd GR receiving the remainder → PO RECEIVED.
- **Test Data:** PO line outstanding 20; first receipt 12; second receipt 8
- **Expected Result:** Each GR is `RECEIVED` (assigns GRN-####, emits STOCK.RECEIVED); PO status moves ORDERED→PARTIALLY_RECEIVED→RECEIVED; stock pushed in (GOODS only).
- **Convention Assertions:** C1 PO + line chosen by human number, no uid typed/shown; C2 201; C9 receipt is append-only; C3 RBAC.
- **Negative / Edge:** over-receipt (receivedQty > outstanding) → rejected (BR-PURCH-10); receive against a DRAFT/VOID/CLOSED PO → rejected; receive a SERVICE-only line → not stockable (service has no stock-in); viewer → 403.

### TC-PROC-062 — GR detail four states
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Goods Receipt (`/admin/goods-receipts/uid/:uid` · `GET .../uid/{uid}`). FE route guard = `PURCHASE.ORDER.VIEW`
- **Permission / Role:** `PURCHASE.GOODS_RECEIPT.VIEW` (API) — runs as STOREKEEPER; cross-tenant → forbidden
- **Preconditions / Seed:** a RECEIVED GR
- **Steps:** Open GR detail; review header + lines + received qty.
- **Expected Result:** GR shown with received quantities sourced from server DTO.
- **Convention Assertions:** C4 four states; C1 uid hidden; C7 scoped; C6 axe.
- **Negative / Edge:** unknown uid → error state; other tenant's GR → forbidden.

### TC-PROC-063 — Void a RECEIVED GR (API) — RECEIVED→VOID, reverses stock + PO outstanding
- **Type:** Manual (API) · **Priority:** P2
- **Module / Submodule:** Goods Receipt (`POST .../uid/{uid}/void` · `PURCHASE.VOID`)
- **Permission / Role:** `PURCHASE.VOID` — runs as a role granted VOID; STOREKEEPER without VOID → 403. **Note: no confirmed void UI in purchases components — API-level.**
- **Preconditions / Seed:** a RECEIVED GR
- **Steps:** Call `POST /api/v1/goods-receipts/uid/{uid}/void` with `VoidGoodsReceiptRequest`.
- **Expected Result:** GR status → `VOID`; emits STOCK.RECEIPT.VOIDED; PO outstanding restored; stock reversed.
- **Convention Assertions:** C3 RBAC; C9 reversal (append-only, not edit); C2 envelope.
- **Negative / Edge:** void a DRAFT/already-VOID GR → rejected; lacking VOID → 403.

---

## E. Landed Cost (`/api/v1/landed-costs`) — RBAC note: all endpoints reachable only by rootadmin (LANDED_COST vs LANDEDCOST mismatch)

### TC-PROC-080 — Landed-cost list loads with pagination + four states
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Landed Cost (`/admin/landed-costs` · `GET /api/v1/landed-costs`)
- **Permission / Role:** `PURCHASE.LANDED_COST.VIEW` — **NOT seeded → run as rootadmin only**; any non-root (even granted everything) → nav hidden + 403
- **Preconditions / Seed:** ≥ 26 landed-cost docs
- **Steps:** Navigate `/admin/landed-costs`; observe states; page through.
- **Expected Result:** List (LC number, basis, status badge, total charges); paginator full controls.
- **Convention Assertions:** C2 meta; C4; C5; C1; C8 money; C6 axe; C7 scoped.
- **Negative / Edge:** empty → empty state; error → error state; CUSTOM-role user → forbidden (mismatch defect).

### TC-PROC-081 — Create landed cost over GR(s), basis BY_VALUE (DRAFT)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Landed Cost (`/admin/landed-costs/create` · `POST /api/v1/landed-costs`)
- **Permission / Role:** `PURCHASE.LANDED_COST.CREATE` — run as rootadmin only
- **Variation:** basis = BY_VALUE; charge types FREIGHT + DUTY
- **Preconditions / Seed:** ≥ 1 RECEIVED GR with GOODS lines
- **Steps:**
  1. Navigate `/admin/landed-costs/create`.
  2. Select basis = "By Value".
  3. Choose GR(s) **by GRN number** via picker (`receiptUids`).
  4. Add charges: FREIGHT 200,000; DUTY 150,000.
  5. Submit.
- **Test Data:** GRN selected; FREIGHT 200000; DUTY 150000; basis BY_VALUE
- **Expected Result:** HTTP 201; LC status `DRAFT`; charges captured; basis BY_VALUE.
- **Convention Assertions:** C1 GR chosen by number via picker; C2 201; C8 money; C3 RBAC.
- **Negative / Edge:** no receipts (`@NotEmpty receiptUids`) → rejected; no charges → rejected; charge amount ≤ 0 (`@Positive`) → rejected; missing basis (`@NotNull`) → rejected.

### TC-PROC-082 — Create landed cost basis BY_QUANTITY + each charge type
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Landed Cost (`POST /api/v1/landed-costs`)
- **Permission / Role:** `PURCHASE.LANDED_COST.CREATE` — rootadmin only
- **Variation:** basis = BY_QUANTITY; charge types FREIGHT, DUTY, CLEARING, INSURANCE, OTHER
- **Preconditions / Seed:** a RECEIVED GR
- **Steps:** Create LC with basis "By Quantity" and one charge of each `LandedCostChargeType`.
- **Expected Result:** LC DRAFT created; allocation will be pro-rata to qty_in_base on confirm.
- **Convention Assertions:** C1 GR picker; C2 201; C8.
- **Negative / Edge:** invalid chargeType → 400.

### TC-PROC-083 — Confirm landed cost (DRAFT→CONFIRMED; allocates to GR lines)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Landed Cost (`/admin/landed-costs/uid/:uid` · `POST .../uid/{uid}/confirm`)
- **Permission / Role:** `PURCHASE.LANDED_COST.CONFIRM` — **NOT seeded → run as rootadmin only**
- **Preconditions / Seed:** a DRAFT LC over RECEIVED GR(s)
- **Steps:** Open LC detail; click Confirm.
- **Expected Result:** Status → `CONFIRMED`; charges allocated across GR lines per basis; outbox event published (LandedCostAllocated).
- **Convention Assertions:** C3 RBAC; C4; C1; C9 allocation append-only.
- **Negative / Edge:** confirm an already-CONFIRMED LC → rejected; confirm an LC whose GR was voided → rejected/handled.

### TC-PROC-084 — Landed-cost detail four states + allocation view
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Landed Cost (`/admin/landed-costs/uid/:uid`)
- **Permission / Role:** `PURCHASE.LANDED_COST.VIEW` — rootadmin only
- **Preconditions / Seed:** a CONFIRMED LC
- **Steps:** Open detail; review charges + per-GR-line allocations.
- **Expected Result:** Charges and allocations shown; status `CONFIRMED`.
- **Convention Assertions:** C4; C1 uid hidden; C8 money; C6 axe.
- **Negative / Edge:** unknown uid → error; cross-tenant → forbidden.

---

## F. Supplier Bill + 3-Way Match (`/api/v1/ap/supplier-bills`, `.../uid/{billUid}/match`)

### TC-PROC-100 — Supplier-bills list with status filter + supplier filter + pagination
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Supplier Bill (`/admin/ap/supplier-bills` · `GET /api/v1/ap/supplier-bills?companyId=&supplierId=`)
- **Permission / Role:** `AP.VIEW` — runs as ACCOUNTANT (granted); NO-PERMISSION → nav hidden
- **Preconditions / Seed:** ≥ 26 bills, mixed statuses, ≥ 2 suppliers
- **Steps:** Navigate `/admin/ap/supplier-bills`; filter status = `MATCHED`; filter by supplier (by name); page through.
- **Expected Result:** Bill list (bill number, supplier name, status badge, totals); the supplier filter applies; paginator full controls. **Accuracy note (verified):** the FE renders a status-filter control and sends `status`/`supplierUid` query params (`ap.service.listBills`), but `SupplierBillController.list` only reads `companyId` + `supplierId` (Long) — it ignores `status` and does not bind `supplierUid`. So the status filter is currently a **no-op server-side** (latent FE/BE param mismatch). Assert the status-filter rows do NOT change (documents current behaviour) rather than asserting they narrow.
- **Convention Assertions:** C2 meta; C4; C5; C1 supplier by name not uid; C8 money; C6 axe; C7 company-scoped.
- **Negative / Edge:** empty → empty state; error → error state; status filter changes nothing server-side (param-mismatch defect).

### TC-PROC-101 — Enter a supplier bill against a PO + auto 3-way match (all MATCHED)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Supplier Bill + Bill Match (`/admin/ap/supplier-bills/enter` · `POST /api/v1/ap/supplier-bills` then `POST .../match/run`)
- **Permission / Role:** `AP.BILL.ENTER` (enter) + `AP.BILL.MATCH` (match) — runs as ACCOUNTANT granted both; also as `AP.VIEW`-only user → Enter Bill route/button hidden + 403
- **Variation:** supplier = GOODS; bill lines == PO lines == GR received qty & price (no variance)
- **Preconditions / Seed:** an ORDERED/RECEIVED PO with a matching GR (received qty + unit cost identical to bill lines)
- **Steps:**
  1. Navigate `/admin/ap/supplier-bills/enter`.
  2. Pick supplier **by name**; enter supplier invoice no, bill date, due date, currency.
  3. Choose the Purchase Order **by number** via picker (optional but needed for 3-way match).
  4. Add bill lines matching the PO/GR qty + price; submit "Enter Bill & Match".
- **Test Data:** invoiceNo "INV-2026-014"; currency TZS; lines equal to GR (qty 20 @ 4,800)
- **Expected Result:** Bill created `DRAFT` (HTTP 201, no GL posting yet); match runs; all line `matchStatus = MATCHED`; bill `status = MATCHED`; result panel shows "All lines matched — bill is ready for payment." GL posts synchronously on full match.
- **Convention Assertions:** C1 supplier + PO via picker by name/number; C2 201 + match result envelope; C8 money formatted; C9 posting append-only (no edit); C3 RBAC.
- **Negative / Edge:** missing supplierInvoiceNo/billDate/dueDate/currency (`@NotBlank`/`@NotNull`) → rejected; no lines (`@NotEmpty`) → rejected; `AP.VIEW`-only user reaching enter route → 403.

### TC-PROC-102 — 3-way match HELD_PRICE_VARIANCE then accept-variance → posts
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Bill Match (`/admin/ap/supplier-bills/enter` result panel · `POST .../match/run`, `POST .../match/accept-variance`)
- **Permission / Role:** `AP.BILL.MATCH` — runs as ACCOUNTANT granted MATCH; user without MATCH → Accept Variance hidden + 403
- **Variation:** bill unit price > PO unit price beyond tolerance
- **Preconditions / Seed:** PO+GR at unit cost 4,800; bill line at unit cost 5,200 (price over-tolerance)
- **Steps:**
  1. Enter the bill with the higher price; match runs.
  2. Observe the line `matchStatus = HELD_PRICE_VARIANCE` (badge + price variance amount/pct); bill `status = HELD`.
  3. Click "Accept Variance" on the held line.
- **Expected Result:** Line → `VARIANCE_ACCEPTED`; once last held variance accepted, bill → `MATCHED`/posts to GL.
- **Convention Assertions:** C3 RBAC (MATCH-gated accept); C8 variance amounts formatted; C2 BillMatchResultDto envelope; C9 append-only posting.
- **Negative / Edge:** accept variance without MATCH permission → 403; accept on a line that is MATCHED (no variance) → rejected/no-op.

### TC-PROC-103 — 3-way match HELD_QTY_VARIANCE
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Bill Match (`POST .../match/run`)
- **Permission / Role:** `AP.BILL.MATCH` — ACCOUNTANT
- **Variation:** billed qty > GR received qty beyond tolerance
- **Preconditions / Seed:** GR received qty 20; bill line qty 25
- **Steps:** Enter bill with qty 25; match runs.
- **Expected Result:** Line `matchStatus = HELD_QTY_VARIANCE`; bill `status = HELD`; qtyVariance shown.
- **Convention Assertions:** C8 qty/variance display; C2 envelope; C3 RBAC.
- **Negative / Edge:** accept the qty variance → VARIANCE_ACCEPTED → bill MATCHED.

### TC-PROC-104 — Service-only bill (no PO) — no 3-way match
- **Type:** Both · **Priority:** P2
- **Module / Submodule:** Supplier Bill (`/admin/ap/supplier-bills/enter` · `POST /api/v1/ap/supplier-bills`)
- **Permission / Role:** `AP.BILL.ENTER` — ACCOUNTANT
- **Variation:** supplier = SERVICE kind; no `purchaseOrderUid`
- **Preconditions / Seed:** a SERVICE supplier
- **Steps:** Enter a bill with no PO selected and service lines; submit.
- **Expected Result:** Bill created `DRAFT`; with no PO the match either cannot run or yields a no-PO result (per service); the FE note "PO optional — for 3-way match" applies.
- **Convention Assertions:** C1 supplier picker by name; C2 201; C8.
- **Negative / Edge:** running match on a bill with no PO → rejected/handled gracefully.

### TC-PROC-105 — Match from the bills list "Match" action
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Bill Match (`/admin/ap/supplier-bills` row "Match" · `POST .../match/run`)
- **Permission / Role:** `AP.BILL.MATCH` — runs as ACCOUNTANT granted MATCH; user lacking MATCH → "Match" action absent
- **Preconditions / Seed:** a DRAFT bill against a PO (canMatchBill condition on the row)
- **Steps:** On the bills list, click the row "Match" action.
- **Expected Result:** Match runs; status updates (MATCHED/HELD).
- **Convention Assertions:** C3 RBAC (action gated by `canMatch`); C2 envelope; C1.
- **Negative / Edge:** match a bill already PAID/APPROVED → action hidden/rejected.

### TC-PROC-106 — Bill detail four states (match status display only; no match action)
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Supplier Bill (`/admin/ap/supplier-bills/uid/:uid` · `GET .../uid/{uid}`)
- **Permission / Role:** `AP.VIEW` — runs as ACCOUNTANT; cross-tenant → forbidden
- **Preconditions / Seed:** a MATCHED bill
- **Steps:** Open bill detail; review header, lines, status, match status.
- **Expected Result:** Bill shown with status + per-line match status; **no run-match action present** (verified — match lives in enter/list).
- **Convention Assertions:** C4 four states; C1 uid hidden, supplier by name; C8 money; C6 axe.
- **Negative / Edge:** unknown uid → error; another tenant's bill → forbidden.

---

## G. Purchase Return (`/api/v1/purchase-returns`) — RBAC note: confirm reachable only by rootadmin

### TC-PROC-120 — Purchase-return list loads with pagination + four states
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Purchase Return (`/admin/purchase-returns` · `GET /api/v1/purchase-returns`)
- **Permission / Role:** `PURCHASE.RETURN.VIEW` (seeded — grantable) — runs as PURCHASE_OFFICER granted RETURN.VIEW; NO-PERMISSION → nav hidden
- **Preconditions / Seed:** ≥ 26 purchase returns
- **Steps:** Navigate `/admin/purchase-returns`; observe states; page.
- **Expected Result:** List (return number, status badge, GR/supplier, reason); paginator full controls.
- **Convention Assertions:** C2 meta; C4; C5; C1; C6 axe; C7 scoped.
- **Negative / Edge:** empty → empty state; error → error state.

### TC-PROC-121 — Create purchase return against a RECEIVED GR (DRAFT)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Return (`/admin/purchase-returns/create` · `POST /api/v1/purchase-returns`)
- **Permission / Role:** `PURCHASE.RETURN.CREATE` (seeded — grantable) — runs as PURCHASE_OFFICER granted RETURN.CREATE; viewer-only → Create hidden + 403
- **Variation:** product = GOODS; partial return
- **Preconditions / Seed:** a RECEIVED GR with GOODS lines
- **Steps:**
  1. Navigate `/admin/purchase-returns/create`.
  2. Choose the GR **by GRN number** via picker.
  3. Enter a reason.
  4. Per GR line, enter returnedQty (≤ received).
  5. Submit.
- **Test Data:** GRN selected; reason "Damaged on arrival"; returnedQty 5 of 20
- **Expected Result:** HTTP 201; return status `DRAFT`; lines reference GR lines.
- **Convention Assertions:** C1 GR + GR line chosen by number via picker; C2 201; C3 RBAC.
- **Negative / Edge:** missing reason (`@NotBlank`) → rejected; no lines (`@NotEmpty`) → rejected; returnedQty ≤ 0 (`@Positive`) → rejected; returnedQty > received → service rejects; GR not RECEIVED → rejected.

### TC-PROC-122 — Confirm purchase return (DRAFT→CONFIRMED; emits PURCHASE.RETURNED)
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** Purchase Return (`/admin/purchase-returns/uid/:uid` · `POST .../uid/{uid}/confirm`)
- **Permission / Role:** `PURCHASE.RETURN.CONFIRM` — **NOT seeded → run as rootadmin only**; any non-root (even with RETURN.CREATE) → confirm 403
- **Preconditions / Seed:** a DRAFT purchase return
- **Steps:** Open detail; click Confirm.
- **Expected Result:** Status → `CONFIRMED`; PURCHASE.RETURNED outbox event published (stock-out + supplier debit downstream).
- **Convention Assertions:** C3 RBAC (CONFIRM-gated); C4; C1; C9 append-only.
- **Negative / Edge:** confirm an already-CONFIRMED return → rejected; user with CREATE but not CONFIRM → 403 (mismatch defect — only root can confirm).

### TC-PROC-123 — Purchase-return detail four states
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Purchase Return (`/admin/purchase-returns/uid/:uid`)
- **Permission / Role:** `PURCHASE.RETURN.VIEW` — runs as PURCHASE_OFFICER; cross-tenant → forbidden
- **Preconditions / Seed:** a CONFIRMED return
- **Steps:** Open detail; review header + lines + reason + status.
- **Expected Result:** Return shown with status `CONFIRMED`.
- **Convention Assertions:** C4; C1 uid hidden; C6 axe.
- **Negative / Edge:** unknown uid → error; other tenant → forbidden.

---

## H. Purchase Settings (`/api/v1/purchase-settings`) — RBAC note: view/edit reachable only by rootadmin

### TC-PROC-140 — View purchase settings (PO approval threshold)
- **Type:** Both · **Priority:** P2
- **Module / Submodule:** Purchase Settings (`/admin/purchase-settings` · `GET /api/v1/purchase-settings/by-company/{companyUid}`)
- **Permission / Role:** `PURCHASE.SETTINGS.VIEW` — **NOT seeded → run as rootadmin only**; any non-root → nav hidden + 403
- **Preconditions / Seed:** a company (settings auto-created/default)
- **Steps:** Navigate `/admin/purchase-settings`.
- **Expected Result:** Shows `poApprovalEnabled`, `poApprovalThresholdAmount`, `currency`.
- **Convention Assertions:** C2 envelope; C4 states; C1 companyUid in path only; C8 money.
- **Negative / Edge:** CUSTOM-role user → forbidden (mismatch defect).

### TC-PROC-141 — Edit purchase settings (enable gate + set threshold)
- **Type:** Both · **Priority:** P2
- **Module / Submodule:** Purchase Settings (`/admin/purchase-settings` · `PUT /api/v1/purchase-settings`)
- **Permission / Role:** `PURCHASE.SETTINGS.EDIT` — **NOT seeded → run as rootadmin only**
- **Preconditions / Seed:** existing settings
- **Steps:** Toggle `poApprovalEnabled = true`; set threshold 1,000,000; set currency TZS; save.
- **Test Data:** poApprovalEnabled true; threshold 1000000; currency TZS
- **Expected Result:** HTTP 200; settings persisted; subsequently POs ≥ threshold enter `PoApprovalStatus.PENDING` (ties to TC-PROC-045).
- **Convention Assertions:** C3 RBAC; C2 envelope; C8 money; required companyUid (`@NotBlank`).
- **Negative / Edge:** negative threshold (`@PositiveOrZero`) → rejected; missing companyUid → 400; non-root → 403.

---

## I. Cross-cutting RBAC mismatch confirmation (current-state regression guards)

> These cases deliberately assert the **current (defective) behaviour** so that a future fix
> (seeding the missing permission codes) flips them — they document the gap explicitly.

### TC-PROC-110 — RFQ create/send/award denied to a fully-granted CUSTOM role
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** RFQ + Supplier Quote (`/admin/rfqs/*`)
- **Permission / Role:** `PURCHASE.RFQ.CREATE`, `PURCHASE.RFQ.AWARD`, `PURCHASE.QUOTE.CREATE` (none seeded) — runs as a CUSTOM role granted EVERY grantable permission
- **Preconditions / Seed:** CUSTOM role with all available permissions assigned; a SENT RFQ
- **Steps:** As the custom user, attempt to create an RFQ / send / capture quote / award.
- **Expected Result:** Create route guard (`PURCHASE.RFQ.CREATE`) blocks; award buttons hidden; capture-quote form hidden; any direct API call → 403 (codes are ungrantable).
- **Convention Assertions:** C3 RBAC; documents the seed gap.
- **Negative / Edge:** rootadmin can perform all of these (bypass).

### TC-PROC-111 — Landed-cost view/create/confirm denied to a fully-granted CUSTOM role
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** Landed Cost (`/admin/landed-costs/*`)
- **Permission / Role:** `PURCHASE.LANDED_COST.*` (controller) vs `PURCHASE.LANDEDCOST.*` (seeded) — runs as fully-granted CUSTOM role
- **Steps:** As custom user, open `/admin/landed-costs`, attempt create/confirm.
- **Expected Result:** Nav item hidden, route guard redirects, API → 403. Only rootadmin succeeds.
- **Convention Assertions:** C3 RBAC; documents underscore mismatch.

### TC-PROC-112 — Purchase-return confirm denied to RETURN.CREATE holder
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Purchase Return (`.../uid/{uid}/confirm`)
- **Permission / Role:** holder of `PURCHASE.RETURN.CREATE` + `PURCHASE.RETURN.VIEW` (both seeded) attempting Confirm (`PURCHASE.RETURN.CONFIRM`, not seeded)
- **Steps:** Create + open a DRAFT return; attempt Confirm.
- **Expected Result:** Confirm → 403 (only rootadmin can confirm).
- **Convention Assertions:** C3 RBAC; documents missing CONFIRM seed.

### TC-PROC-113 — Purchase-settings view/edit denied to a fully-granted CUSTOM role
- **Type:** Automated (Playwright) · **Priority:** P2
- **Module / Submodule:** Purchase Settings (`/admin/purchase-settings`)
- **Permission / Role:** `PURCHASE.SETTINGS.VIEW/EDIT` (controller) vs `PURCHASE.SETTINGS.MANAGE` (seeded)
- **Steps:** As custom user, open `/admin/purchase-settings`, attempt save.
- **Expected Result:** Nav hidden + route guard blocks; API → 403; only rootadmin succeeds.
- **Convention Assertions:** C3 RBAC; documents VIEW/EDIT vs MANAGE mismatch.

### TC-PROC-114 — NO-PERMISSION user sees no procurement nav and is redirected from every PROC route
- **Type:** Automated (Playwright) · **Priority:** P1
- **Module / Submodule:** All PROC routes
- **Permission / Role:** NO-PERMISSION user
- **Preconditions / Seed:** a user with zero permissions, assigned to a company/branch
- **Steps:** Login; inspect the shell nav; attempt direct navigation to each PROC route (`/admin/purchase-requisitions`, `/admin/rfqs`, `/admin/purchase-orders`, `/admin/goods-receipts`, `/admin/landed-costs`, `/admin/purchase-returns`, `/admin/purchase-settings`, `/admin/ap/supplier-bills`).
- **Expected Result:** No procurement nav items rendered; each direct route redirects to `/admin/home` (route guard); any direct API call → 403.
- **Convention Assertions:** C3 RBAC; C4 forbidden handling; C7 scoping.
- **Negative / Edge:** rootadmin sees all items.

---

## J. End-to-end procure-to-pay happy path (chain integration)

### TC-PROC-160 — Full P2P chain: requisition → RFQ → quote → award(PO) → place → receive → landed-cost → bill → match → return
- **Type:** Both · **Priority:** P1
- **Module / Submodule:** All PROC controllers + AP supplier-bill/match
- **Permission / Role:** run as **rootadmin** (the only principal able to traverse the whole chain given the RBAC mismatch on RFQ/QUOTE/LANDED_COST/RETURN.CONFIRM)
- **Variation:** supplier = GOODS; product = GOODS; basis BY_VALUE; default branch
- **Preconditions / Seed:** 1 GOODS supplier, 1 GOODS product + unit, company with default branch; purchase settings with approval disabled (so PO needs no approval gate)
- **Steps:**
  1. Create requisition (DRAFT) with one GOODS line; Submit; Approve; Convert → RFQ.
  2. Open RFQ; Send; Capture two supplier quotes (different prices); Award the cheaper → PO created (DRAFT).
  3. Open the awarded PO; Place (DRAFT→ORDERED).
  4. Create-and-receive a GR for full qty (PO→RECEIVED, GRN-#### assigned).
  5. Create a landed cost (BY_VALUE, FREIGHT charge) over the GR; Confirm.
  6. Enter a supplier bill against the PO with matching qty+price; auto 3-way match → all MATCHED → bill MATCHED.
  7. Create a purchase return for a damaged unit against the GR; Confirm.
- **Expected Result:** Every transition lands its expected state (RequisitionStatus CONVERTED; RfqStatus AWARDED; SupplierQuoteStatus AWARDED/NOT_AWARDED; PurchaseOrderStatus RECEIVED; GoodsReceiptStatus RECEIVED; LandedCostStatus CONFIRMED; SupplierBillStatus MATCHED; PurchaseReturnStatus CONFIRMED). Documents/numbers (PO-####, GRN-####, BILL-####) are human-readable; uids only in URLs.
- **Convention Assertions:** C1 every resource selection via picker by name/number, never a typed uid, never a raw uid on screen; C2 envelopes at each step; C8 money/date formatting end-to-end; C9 append-only postings (no edits); C7 single-company/branch scope throughout.
- **Negative / Edge:** Repeat as a fully-granted CUSTOM role and confirm the chain breaks at the RFQ-create/quote-capture/award and landed-cost/return-confirm steps (403), per TC-PROC-110/111/112 — proving the seed-gap defect blocks non-root P2P.

---

## Coverage map (controller action → case)

- Requisition: create→001/003; getByUid→004/010; list→001/002; submit→004; approve→005; reject→006; convert→007/008; cancel→009.
- RFQ: create→021/027; getByUid→022; list→020; send→022; award→025; cancel→026.
- Supplier Quote: capture→023; getByUid/listByRfq→024; last-quoted-cost→024.
- Purchase Order: create→(via 007/025/047); getByUid→041; list→040; update/addLine/updateLine/removeLine/listLines→041; place→042; close→043; void→044; approve→045; reject→046; createFromQuote→047; multi-tenant→048.
- Goods Receipt: createAndReceive→061; getByUid→062; list→060; void→063.
- Landed Cost: create→081/082; getByUid→084; list→080; confirm→083.
- Supplier Bill: enter→101/104; getByUid→106; list→100.
- Bill Match: run→101/103/105; accept-variance→102/103.
- Purchase Return: create→121; getByUid→123; list→120; confirm→122.
- Purchase Settings: getByCompany→140; update→141.
- RBAC mismatch + chain: 110–114, 160.
