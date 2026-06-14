# 05 — Sales / Order-to-Cash (O2C) Test Cases

Scope: the full order-to-cash chain — Quotation → accept → Sales Order → confirm (reserves stock) → Delivery (full + partial/backorder) → Sales Invoice (per-delivery + DIRECT walk-in) → Sales Return (RMA). Covers every controller endpoint, every status-lifecycle transition and its illegal counterparts, cash-vs-credit customer behaviour, order/line discounts, GOODS vs SERVICE lines, RBAC by permission code, the four screen states, pagination, and the C1–C9 conventions.

All endpoints, permission codes, enum values, and frontend routes below were verified by reading the controllers, DTOs, enum files, SQL migrations, and Angular components — none are invented.

## Modules / submodules covered

| Submodule | Frontend route(s) | API base path | Controller |
|---|---|---|---|
| Quotation | `/admin/quotations`, `/admin/quotations/uid/:uid` | `/api/v1/quotations` | `QuotationController` |
| Sales Order | `/admin/sales-orders`, `/admin/sales-orders/uid/:uid` | `/api/v1/sales-orders` | `SalesOrderController` |
| Delivery | `/admin/deliveries`, `/admin/deliveries/create`, `/admin/deliveries/uid/:uid` | `/api/v1/deliveries` | `DeliveryController` |
| Sales Invoice | `/admin/sales-invoices`, `/admin/sales-invoices/uid/:uid` | `/api/v1/sales-invoices` | `SalesInvoiceController` |
| Sales Return (RMA) | `/admin/sales-returns`, `/admin/sales-returns/create`, `/admin/sales-returns/uid/:uid` | `/api/v1/sales-returns` | `SalesReturnController` |

Shell nav labels (verified `shell.component.ts`): "Quotations", "Sales Orders", "Deliveries", "Invoices", "Sales Returns" — each hidden unless the user holds the corresponding `*.VIEW` permission.

### Endpoint inventory (verified per controller)

**QuotationController** (`/api/v1/quotations`)
- `POST /` — create draft (201) · `SALES.QUOTE.CREATE` (scoped to `companyUid`)
- `GET /uid/{uid}` — view · `SALES.QUOTE.VIEW`
- `GET /?companyId=&page=&size=` — paginated list · `SALES.QUOTE.VIEW`
- `GET /uid/{uid}/lines` — list lines · `SALES.QUOTE.VIEW`
- `POST /uid/{uid}/lines` — add line (201) · `SALES.QUOTE.CREATE`
- `DELETE /uid/{uid}/lines/{lineUid}` — remove line (204) · `SALES.QUOTE.CREATE`
- `PUT /uid/{uid}/send` — send (204) · `SALES.QUOTE.SEND`
- `PUT /uid/{uid}/accept` — accept→creates SO (201) · `SALES.QUOTE.ACCEPT`
- `PUT /uid/{uid}/reject` — reject (204) · `SALES.QUOTE.ACCEPT`

**SalesOrderController** (`/api/v1/sales-orders`)
- `POST /` — create draft (201) · `SALES.ORDER.CREATE`
- `GET /uid/{uid}` — view · `SALES.ORDER.VIEW`
- `GET /?companyId=` — paginated list · `SALES.ORDER.VIEW`
- `GET /uid/{uid}/lines` · `SALES.ORDER.VIEW`
- `POST /uid/{uid}/lines` (201) · `SALES.ORDER.CREATE`
- `DELETE /uid/{uid}/lines/{lineUid}` (204) · `SALES.ORDER.CREATE`
- `PUT /uid/{uid}/confirm` — reserves stock (204) · `SALES.ORDER.CONFIRM`
- `PUT /uid/{uid}/cancel` — releases reservation (204) · `SALES.ORDER.CANCEL`

**DeliveryController** (`/api/v1/deliveries`)
- `POST /` — create against confirmed SO (201) · `SALES.DELIVERY.CREATE` (scoped to `salesOrderUid`)
- `GET /uid/{uid}` · `SALES.DELIVERY.VIEW`
- `GET /?companyId=` — paginated list · `SALES.DELIVERY.VIEW`
- `GET /for-order/{salesOrderUid}` · `SALES.DELIVERY.VIEW`
- `POST /uid/{uid}/invoice` — generate DRAFT invoice from delivery (201) · `SALES.DELIVERY.CREATE`

**SalesInvoiceController** (`/api/v1/sales-invoices`)
- `POST /` — create DIRECT draft (201) · `SALES.INVOICE.CREATE` (scoped to `companyUid`)
- `GET /uid/{uid}` · `SALES.INVOICE.VIEW`
- `GET /?companyId=&q=&status=` — paginated list/search · `SALES.INVOICE.VIEW`
- `PUT /uid/{uid}/finalize` (204) · `SALES.INVOICE.CREATE`
- `PUT /uid/{uid}/void` (204) · `SALES.INVOICE.VOID`
- `GET /uid/{uid}/lines` · `SALES.INVOICE.VIEW`
- `POST /uid/{uid}/lines` (201) · `SALES.INVOICE.CREATE`
- `DELETE /uid/{uid}/lines/{lineUid}` (204) · `SALES.INVOICE.CREATE`
- `GET /uid/{uid}/payments` · `SALES.INVOICE.VIEW`
- `POST /uid/{uid}/payments` (201) · `SALES.INVOICE.SETTLE`
- `DELETE /uid/{uid}/payments/{paymentUid}` (204) · `SALES.INVOICE.CREATE`

**SalesReturnController** (`/api/v1/sales-returns`)
- `POST /` — create against a delivery, status=CONFIRMED, raises credit note (201) · `SALES.RETURN.CREATE` (scoped to `deliveryUid`)
- `GET /uid/{uid}` · `SALES.RETURN.VIEW`
- `GET /?companyId=` — paginated list by company · `SALES.RETURN.VIEW`
- `GET /for-delivery/{deliveryUid}` — paginated list by delivery · `SALES.RETURN.VIEW`

## Permission codes in scope (exact `@PreAuthorize` codes)

Seeded in `V18__sales_orders.sql` (QUOTE/ORDER/DELIVERY/RETURN) and `V5__sales.sql` (INVOICE/TAXRATE). All granted to `ORG_ADMIN` by the seed.

- `SALES.QUOTE.VIEW`, `SALES.QUOTE.CREATE`, `SALES.QUOTE.SEND`, `SALES.QUOTE.ACCEPT`
- `SALES.ORDER.VIEW`, `SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.CANCEL`
- `SALES.DELIVERY.VIEW`, `SALES.DELIVERY.CREATE`
- `SALES.INVOICE.VIEW`, `SALES.INVOICE.CREATE`, `SALES.INVOICE.SETTLE`, `SALES.INVOICE.VOID`, `SALES.INVOICE.OVERRIDE`
- `SALES.RETURN.VIEW`, `SALES.RETURN.CREATE`
- `SALES.CREDIT.OVERRIDE` — verified in `SalesInvoiceServiceImpl.finalise` as a credit-limit override gate (no controller endpoint; applied at finalise of a credit-customer invoice).
- `TAXRATE.VIEW`, `TAXRATE.MANAGE` — VAT rate maintenance (prerequisite config; not the O2C chain itself).

Note: `SALES_MANAGER` / `SALES_REP` are seeded role names; this codebase enforces RBAC strictly by permission code, so each case below names the permission and uses a role/custom-role holding (or lacking) that exact code.

## Status-lifecycle enums (verified from enum files + service code)

- **QuotationStatus**: `DRAFT, SENT, ACCEPTED, EXPIRED, REJECTED`. Legal: `DRAFT→SENT` (send, requires validUntil ≥ today), `SENT→ACCEPTED` (accept→creates SO; auto-flips to `EXPIRED` and rejects if validUntil < today), `SENT→REJECTED` (reject). `EXPIRED` is set as a side-effect of accepting a stale SENT quote.
- **SalesOrderStatus**: `DRAFT, CONFIRMED, PARTIALLY_FULFILLED, FULFILLED, PARTIALLY_INVOICED, INVOICED, CLOSED, CANCELLED`. Lines editable only in `DRAFT`. `DRAFT→CONFIRMED` (confirm, reserves stock, requires ≥1 line). Delivery rollup derives `PARTIALLY_FULFILLED`/`FULFILLED`; invoice rollup derives `PARTIALLY_INVOICED`/`CLOSED`. `→CANCELLED` (cancel, releases reservation) allowed from any state except `CANCELLED`/`CLOSED`. (`INVOICED` exists in the enum; the rollup function `deriveStatus` produces `CLOSED` when fully fulfilled+invoiced.)
- **DeliveryStatus**: `DRAFT, CONFIRMED`. v1 creates deliveries directly in `CONFIRMED` (picking deferred); `DRAFT` reserved, no v1 code path.
- **InvoiceStatus**: `DRAFT, FINALISED, VOID`. Only legal transitions: `DRAFT→FINALISED→VOID`. Lines/payments editable only in `DRAFT`.
- **SalesReturnStatus**: `DRAFT, CONFIRMED`. v1 creates returns directly in `CONFIRMED` (stock-in + COGS reversal + credit note atomic on create); `DRAFT` reserved, no v1 code path.
- **TenderType**: `CASH, MOBILE_MONEY` (CARD/CREDIT reserved, not admitted).
- **DocumentOrigin**: `DIRECT` (walk-in, issues stock on finalise), `SALES_ORDER` (from delivery, revenue-only), `POS`.
- **CustomerKind** (parties): `CASH_WALK_IN` (paid-in-full required at finalise), `CREDIT_ACCOUNT` (partial/zero pay allowed, AR open item, credit-limit check).
- **ProductType** (products): `GOODS` (stockable/reservable), `SERVICE` (not stockable).

## Type / role variations exercised

| Dimension | Values exercised |
|---|---|
| User type | `rootadmin` (superuser, sees all — positive smoke only); `ORG_ADMIN` (full O2C); custom role holding only specific codes (e.g. CREATE but not CONFIRM); NO-PERMISSION user (forbidden/hidden nav) |
| CustomerKind | `CASH_WALK_IN` (paid-in-full enforced); `CREDIT_ACCOUNT` (partial allowed, credit-limit gate) |
| PartyType | `INDIVIDUAL`, `BUSINESS` (on customer; does not change O2C posting behaviour) |
| ProductType | `GOODS` (reserves/issues stock); `SERVICE` (no stock movement, finalise still posts revenue) |
| Discounts | line discount (amount XOR percent); document discount (amount XOR percent); pro-rated on partial invoice |
| Delivery shape | full (single delivery covers all open qty); partial / backorder (split across two deliveries) |
| Invoice origin | `DIRECT` walk-in (created from invoice list); `SALES_ORDER` (generated from a delivery) |
| Tender | `CASH`, `MOBILE_MONEY` |
| Branch | default vs non-default; user assigned to ONE vs MANY vs ALL; acting in an unassigned branch (denied) |
| Company | multi-company isolation (tenant A cannot see tenant B's O2C documents) |

---

# TEST CASES

## A. Quotation lifecycle

### TC-SALES-001 — Create a draft quotation (CREDIT_ACCOUNT customer)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Quotation (`/admin/quotations` · `POST /api/v1/quotations`)
- **Permission / Role:** `SALES.QUOTE.CREATE` — runs as `ORG_ADMIN`; also as NO-PERMISSION user → expect forbidden
- **Variation:** customer = BUSINESS + CREDIT_ACCOUNT; currency = TZS
- **Preconditions / Seed:** one company with a default branch; one ACTIVE customer (BUSINESS, CREDIT_ACCOUNT); logged in with an active branch (X-Branch-Uid set)
- **Steps:**
  1. Navigate to `/admin/quotations`.
  2. Open the inline create form ("New quotation").
  3. In the customer picker, type part of the customer name/code and select it by NAME from the dropdown.
  4. Set Quote date = today, Valid until = today + 30.
  5. Submit.
- **Test Data:** customer "Acme Ltd (CUST-0001)", quoteDate=today, validUntil=today+30, currency=TZS
- **Expected Result:** 201; a DRAFT quotation row appears in the list with status badge DRAFT and label "DRAFT" (no QUOTE-#### yet — number is allocated on send). Envelope `ApiResponse<QuotationDto>`.
- **Convention Assertions:** C1 customer chosen via picker by name, no raw uid typed or shown; C2 envelope; C3 RBAC; C4 form error/empty states; C8 dates ISO `yyyy-MM-dd`
- **Negative / Edge:** missing customer → "Customer is required."; missing quote date → "Quote date is required."; missing valid-until → "Valid until date is required."; NO-PERMISSION user → nav item hidden + route guard blocks + `POST` returns 403

### TC-SALES-002 — Add GOODS line to a draft quotation (with line discount %)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Quotation lines (`/admin/quotations/uid/:uid` · `POST /api/v1/quotations/uid/{uid}/lines`)
- **Permission / Role:** `SALES.QUOTE.CREATE` — runs as `ORG_ADMIN`; also as user with only `SALES.QUOTE.VIEW` → add-line action hidden / 403
- **Variation:** product = GOODS; line discount = percent
- **Preconditions / Seed:** TC-SALES-001 quotation (DRAFT); a sellable GOODS product with a company price; a VAT rate configured for the product's VatStatus; an ACTIVE unit
- **Steps:**
  1. Open the quotation detail at `/admin/quotations/uid/:uid`.
  2. In the line product search, type and select the product by code/name.
  3. Choose a unit, qty = 10, discount % = 5, leave unit-price override blank.
  4. Add the line.
- **Test Data:** product "Widget (PRD-0001)", unit "EACH", qty=10, lineDiscountPercent=5
- **Expected Result:** 201; line appears with computed net/VAT/gross from the server DTO; quotation totals recompute (net/VAT/gross shown). List price pulled from the product company price.
- **Convention Assertions:** C1 product + unit via picker by name; C2 envelope; C8 money string "TZS 1,234.56"; C6 axe scan on detail
- **Negative / Edge:** qty ≤ 0 → "Enter a valid quantity greater than zero."; setting BOTH line discount amount AND percent → rejected (DiscountValidator, at most one); product with no company price → 400 "Product has no price for this company"; product VatStatus with no tax rate → 500/400 "VAT rate not configured"; ARCHIVED or non-sellable product → 400 "Product not sellable"

### TC-SALES-003 — SERVICE line on a quotation (non-stockable)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Quotation lines (`POST /api/v1/quotations/uid/{uid}/lines`)
- **Permission / Role:** `SALES.QUOTE.CREATE` — `ORG_ADMIN`
- **Variation:** product = SERVICE
- **Preconditions / Seed:** DRAFT quotation; a sellable SERVICE product with a company price + VAT rate
- **Steps:** Add a SERVICE product line (qty=2) as in TC-SALES-002.
- **Test Data:** product "Installation (SVC-0001)", qty=2
- **Expected Result:** 201; line added and priced like a goods line. No reservation/stock concept attaches to a service line (relevant later at SO confirm — service lines still get `qtyReservedBase` set per the SO confirm loop, but no physical stock semantics).
- **Convention Assertions:** C1 picker; C2 envelope; C8 money
- **Negative / Edge:** N/A specific; same discount/price guards as TC-SALES-002

### TC-SALES-004 — Remove a draft quotation line
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Quotation lines (`DELETE /api/v1/quotations/uid/{uid}/lines/{lineUid}`)
- **Permission / Role:** `SALES.QUOTE.CREATE` — `ORG_ADMIN`
- **Preconditions / Seed:** DRAFT quotation with ≥1 line
- **Steps:** On the quotation detail, click Remove on a line row; confirm.
- **Expected Result:** 204; line disappears, totals recompute.
- **Convention Assertions:** C2 envelope; C4 empty-state when last line removed
- **Negative / Edge:** removing a line from a SENT/ACCEPTED quotation → blocked ("Only DRAFT quotations can be modified")

### TC-SALES-005 — Send a quotation (DRAFT → SENT, allocates QUOTE-####)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Quotation (`PUT /api/v1/quotations/uid/{uid}/send`)
- **Permission / Role:** `SALES.QUOTE.SEND` — runs as `ORG_ADMIN`; also as user with `SALES.QUOTE.CREATE` but NOT `SALES.QUOTE.SEND` → Send button hidden / 403
- **Preconditions / Seed:** DRAFT quotation with ≥1 line; validUntil ≥ today
- **Steps:**
  1. On quotation detail, click "Send".
  2. Observe status badge changes to SENT and a QUOTE-#### number now displays.
- **Expected Result:** 204; status=SENT; quoteNumber allocated; `sentAt` stamped. UI shows the number in the header.
- **Convention Assertions:** C1 number shown (human code), no uid; C3 RBAC; C6 axe
- **Negative / Edge:** Send with zero lines → Send button disabled (UI gate `canSendNow = isDraft && lines>0`); send a quotation whose validUntil < today → 400 "Valid-until date is in the past; cannot send."; send an already-SENT quotation → 400 "Only DRAFT quotations can be sent; current: SENT"

### TC-SALES-006 — Accept a quotation → creates Sales Order (SENT → ACCEPTED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Quotation (`PUT /api/v1/quotations/uid/{uid}/accept` → returns `SalesOrderDto`)
- **Permission / Role:** `SALES.QUOTE.ACCEPT` — runs as `ORG_ADMIN`; also as user lacking it → Accept hidden / 403
- **Preconditions / Seed:** SENT quotation (TC-SALES-005), validUntil ≥ today
- **Steps:**
  1. On quotation detail, click "Accept".
  2. Observe success toast "Quotation accepted — Sales Order created" with the new SO number.
- **Expected Result:** 201; quotation status=ACCEPTED with `convertedOrderUid`; a new DRAFT Sales Order is created with `sourceQuotationUid` set, lines + totals copied verbatim from the quote. Navigates/links to the new SO.
- **Convention Assertions:** C1 SO referenced by its order number, not uid in UI; C2 envelope `ApiResponse<SalesOrderDto>`; C3 RBAC
- **Negative / Edge:** accept a DRAFT quotation → 400 "Only SENT quotations can be accepted; current: DRAFT"; accept a SENT quotation whose validUntil < today → quotation auto-flips to EXPIRED and 400 "Quotation … has expired … cannot be accepted (BR-SO-01)"; accept an already-ACCEPTED quotation → 400 "Only SENT quotations can be accepted; current: ACCEPTED"

### TC-SALES-007 — Reject a quotation (SENT → REJECTED)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Quotation (`PUT /api/v1/quotations/uid/{uid}/reject`)
- **Permission / Role:** `SALES.QUOTE.ACCEPT` (reject shares the ACCEPT permission) — `ORG_ADMIN`; also as user lacking it → Reject hidden / 403
- **Preconditions / Seed:** SENT quotation
- **Steps:** On quotation detail click "Reject".
- **Expected Result:** 204; status=REJECTED, `rejectedAt` stamped; success toast "Quotation rejected".
- **Convention Assertions:** C3 RBAC (reject gated by `SALES.QUOTE.ACCEPT`)
- **Negative / Edge:** reject a DRAFT quotation → 400 "Only SENT quotations can be rejected; current: DRAFT"; reject an ACCEPTED quotation → 400 illegal transition

### TC-SALES-008 — Illegal quotation transitions matrix
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Quotation lifecycle (all PUT transitions)
- **Permission / Role:** all quote permissions — `ORG_ADMIN`
- **Preconditions / Seed:** quotations in each status
- **Steps:** Attempt each illegal transition via the API:
  - send a SENT/ACCEPTED/REJECTED/EXPIRED quote;
  - accept a DRAFT/ACCEPTED/REJECTED/EXPIRED quote;
  - reject a DRAFT/ACCEPTED/REJECTED/EXPIRED quote;
  - add/remove a line on a SENT/ACCEPTED quote.
- **Expected Result:** every illegal transition → HTTP 400 (`IllegalStateException` mapped) with a descriptive `errors[]`; no state change persisted.
- **Convention Assertions:** C2 envelope error array; C9 append-only (no destructive edit on non-draft)
- **Negative / Edge:** this case IS the negative matrix

### TC-SALES-009 — Quotation list: four states + pagination + status filter
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Quotation list (`/admin/quotations` · `GET /api/v1/quotations`)
- **Permission / Role:** `SALES.QUOTE.VIEW` — `ORG_ADMIN`; NO-PERMISSION user → forbidden screen + hidden nav
- **Preconditions / Seed:** ≥ 2 pages of quotations (size default; seed >size rows) across multiple statuses
- **Steps:**
  1. Navigate to `/admin/quotations`; capture loading then idle.
  2. Assert paginator: FIRST, PREVIOUS, page numbers, NEXT, LAST.
  3. Apply the status filter (e.g. SENT).
  4. Force an error (e.g. invalid companyId) and a no-data filter for empty.
- **Expected Result:** loading spinner → table with rows; empty state when filter yields none; error state on backend failure; forbidden state for the no-permission user; paginator visible (and self-hidden when 1 page). Meta `{page,size,totalElements,totalPages,hasNext}`.
- **Convention Assertions:** C1 no uid columns (shows quote number/customer name); C2 meta; C4 four states; C5 paginator; C6 axe
- **Negative / Edge:** single-page result → paginator hidden

## B. Sales Order lifecycle

### TC-SALES-010 — Create a standalone draft Sales Order (CASH_WALK_IN customer)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Order (`/admin/sales-orders` · `POST /api/v1/sales-orders`)
- **Permission / Role:** `SALES.ORDER.CREATE` — `ORG_ADMIN`; NO-PERMISSION → forbidden
- **Variation:** customer = INDIVIDUAL + CASH_WALK_IN; document discount = percent
- **Preconditions / Seed:** company + default branch; a CASH_WALK_IN customer
- **Steps:**
  1. Navigate to `/admin/sales-orders`; open create form.
  2. Pick customer by name; set order date = today; set document discount % = 2.
  3. Submit.
- **Test Data:** customer "Walk-in Joe (CUST-0009)", orderDate=today, docDiscountPercent=2, currency=TZS
- **Expected Result:** 201; DRAFT SO created with an order number; no stock reserved yet.
- **Convention Assertions:** C1 customer picker by name; C2 envelope; C8 dates ISO
- **Negative / Edge:** missing customer → validation; doc discount amount AND percent both set → 400 (DiscountValidator); creating in a branch the user is NOT assigned to → 403 (ScopeGuard)

### TC-SALES-011 — Add lines (GOODS + SERVICE) to a draft Sales Order
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** SO lines (`POST /api/v1/sales-orders/uid/{uid}/lines`)
- **Permission / Role:** `SALES.ORDER.CREATE` — `ORG_ADMIN`; user with only VIEW → add hidden / 403
- **Variation:** line 1 = GOODS (line discount amount); line 2 = SERVICE
- **Preconditions / Seed:** DRAFT SO; a sellable GOODS product + a SERVICE product (each with company price + VAT rate)
- **Steps:** Add a GOODS line (qty=20, line discount amount=1000) and a SERVICE line (qty=1).
- **Expected Result:** 201 each; lines show ordered qty, list price, unit price, net/VAT/gross from DTO; SO totals recompute. `qtyReservedBase`/`qtyFulfilledBase`/`qtyInvoicedBase` start at 0; `openQtyBase` = ordered.
- **Convention Assertions:** C1 product/unit pickers; C2 envelope; C8 money
- **Negative / Edge:** add line to a CONFIRMED/CANCELLED SO → 400 "Cannot modify a non-DRAFT sales order"; qty ≤ 0 → validation; non-sellable product → 400

### TC-SALES-012 — Confirm a Sales Order reserves stock (DRAFT → CONFIRMED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Order (`PUT /api/v1/sales-orders/uid/{uid}/confirm`)
- **Permission / Role:** `SALES.ORDER.CONFIRM` — runs as `ORG_ADMIN`; also as user with `SALES.ORDER.CREATE` but NOT `CONFIRM` → Confirm button hidden / 403
- **Variation:** GOODS line present (reservation observable)
- **Preconditions / Seed:** DRAFT SO with ≥1 line; the GOODS product has on-hand stock in the branch
- **Steps:**
  1. On SO detail, click "Confirm".
  2. Observe toast "Order confirmed — stock reserved".
  3. Verify each line now shows reserved qty = ordered qty; status badge CONFIRMED.
  4. Check stock: reserved quantity for the product/branch increased by the ordered base qty.
- **Expected Result:** 204; status=CONFIRMED, `confirmedAt` stamped; per-line `qtyReservedBase = qtyOrderedBase`; stock reservation delta applied via `StockReservationService`.
- **Convention Assertions:** C3 RBAC; C7 reservation is branch-scoped; C6 axe
- **Negative / Edge:** confirm an SO with zero lines → 400 "Cannot confirm a sales order with no lines."; confirm an already-CONFIRMED SO → 400 "Cannot modify a non-DRAFT sales order; current: CONFIRMED"; confirm a CANCELLED SO → 400

### TC-SALES-013 — Cancel a confirmed Sales Order releases reservation (→ CANCELLED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Order (`PUT /api/v1/sales-orders/uid/{uid}/cancel`)
- **Permission / Role:** `SALES.ORDER.CANCEL` — `ORG_ADMIN`; user lacking it → Cancel hidden / 403
- **Preconditions / Seed:** CONFIRMED SO with reserved stock (TC-SALES-012)
- **Steps:**
  1. On SO detail, open the Cancel form; enter a reason.
  2. Submit.
  3. Verify status=CANCELLED and per-line reserved qty back to 0; stock reservation released.
- **Test Data:** cancelReason = "Customer changed mind"
- **Expected Result:** 204; status=CANCELLED, `cancelledAt` + `cancelReason` stamped; reservation delta negated back; stock reserved count decreased.
- **Convention Assertions:** C3 RBAC; C7 release branch-scoped; C9 reason captured (no hard delete)
- **Negative / Edge:** cancel a CANCELLED SO → 400 "Cannot cancel an order in status CANCELLED"; cancel a CLOSED SO → 400; reason optional (request body may be absent) — cancel still succeeds with null reason

### TC-SALES-014 — SO status rollup: partial delivery → PARTIALLY_FULFILLED → FULFILLED
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Sales Order status derivation (driven by Delivery create; `deriveStatus`)
- **Permission / Role:** `SALES.ORDER.CONFIRM` + `SALES.DELIVERY.CREATE` — `ORG_ADMIN`
- **Variation:** backorder (qty split across two deliveries)
- **Preconditions / Seed:** CONFIRMED SO, single GOODS line qty=10
- **Steps:**
  1. Create a delivery for qty 6 against the line (TC-SALES-016).
  2. Observe SO status = PARTIALLY_FULFILLED; line fulfilled=6, open=4.
  3. Create a second delivery for the remaining qty 4.
  4. Observe SO status = FULFILLED; line fulfilled=10, open=0.
- **Expected Result:** after partial: PARTIALLY_FULFILLED; after full: FULFILLED. `deriveStatus` rules: anyFulfilled<ordered → PARTIALLY_FULFILLED; fullyFulfilled → FULFILLED.
- **Convention Assertions:** C2 DTO open/fulfilled qty fields; C8 numeric formatting
- **Negative / Edge:** delivering more than open qty in step 3 → blocked (BR-SO-11, see TC-SALES-017)

### TC-SALES-015 — SO status rollup: invoicing → PARTIALLY_INVOICED → CLOSED
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** Sales Order status derivation (driven by `POST /deliveries/uid/{uid}/invoice`)
- **Permission / Role:** `SALES.DELIVERY.CREATE` (generate invoice) + `SALES.INVOICE.CREATE` (finalise) — `ORG_ADMIN`
- **Preconditions / Seed:** FULLY-FULFILLED SO across two deliveries (TC-SALES-014)
- **Steps:**
  1. Generate an invoice from delivery #1 (qty 6) and finalise it.
  2. Observe SO = PARTIALLY_INVOICED.
  3. Generate an invoice from delivery #2 (qty 4) and finalise it.
  4. Observe SO = CLOSED.
- **Expected Result:** PARTIALLY_INVOICED after first invoice; CLOSED when fullyFulfilled && fullyInvoiced (`deriveStatus`).
- **Convention Assertions:** C2 envelope; C9 append-only postings
- **Negative / Edge:** the enum value `INVOICED` exists but the v1 rollup yields `CLOSED` for fully-fulfilled+invoiced — assert UI never wrongly shows a stuck PARTIALLY state

### TC-SALES-016 — Sales Order list: four states + pagination + cross-tenant isolation
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** SO list (`/admin/sales-orders` · `GET /api/v1/sales-orders`)
- **Permission / Role:** `SALES.ORDER.VIEW` — `ORG_ADMIN` of tenant A; also a tenant-B user
- **Preconditions / Seed:** ≥2 pages of SOs in tenant A; SOs in tenant B
- **Steps:**
  1. As tenant A, navigate to `/admin/sales-orders`; verify rows + paginator.
  2. Force empty (filter) and error states.
  3. As tenant B user, confirm tenant A's SOs are NOT visible.
- **Expected Result:** four states render distinctly; paginator present; tenant B sees only its own SOs (ScopeGuard / company scoping).
- **Convention Assertions:** C4 four states; C5 paginator; C7 cross-tenant isolation; C1 no uid column; C6 axe
- **Negative / Edge:** tenant B attempting `GET /uid/{uid}` of a tenant-A SO → 403/404

## C. Delivery lifecycle

### TC-SALES-016b — Create a FULL delivery against a confirmed SO (→ CONFIRMED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Delivery (`/admin/deliveries/create` · `POST /api/v1/deliveries`)
- **Permission / Role:** `SALES.DELIVERY.CREATE` — runs as `ORG_ADMIN`; user with only `SALES.DELIVERY.VIEW` → create route guard blocks + 403
- **Variation:** full delivery (qtyDelivered = open qty for every line)
- **Preconditions / Seed:** CONFIRMED SO with open GOODS line qty=10; product has stock
- **Steps:**
  1. Navigate to `/admin/deliveries/create` for the SO (selected by SO number).
  2. The form loads SO open lines (only lines with `openQtyBase > 0`), defaulting each qty input to the open balance.
  3. Set delivery date = today; keep full qty; submit.
- **Test Data:** deliveryDate=today, line qty=10
- **Expected Result:** 201; delivery created directly in CONFIRMED status with a DELIVERY-#### number; SO line `qtyFulfilledBase` += 10, reservation released by the delivered qty; SO status → FULFILLED; a `DELIVERY.CONFIRMED` domain event published (stock issued + COGS via handler).
- **Convention Assertions:** C1 SO selected by number, lines shown by product name; C2 envelope; C8 dates/qty; C6 axe
- **Negative / Edge:** delivery date missing → "Delivery date is required."; deliver against a DRAFT SO → 400 "Can only deliver against a CONFIRMED or PARTIALLY_FULFILLED order"; deliver against a CANCELLED SO → 400; empty lines list → 400 (`@NotEmpty`)

### TC-SALES-017 — Create a PARTIAL delivery / backorder (SO → PARTIALLY_FULFILLED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Delivery (`POST /api/v1/deliveries`)
- **Permission / Role:** `SALES.DELIVERY.CREATE` — `ORG_ADMIN`
- **Variation:** partial (qtyDelivered < open qty)
- **Preconditions / Seed:** CONFIRMED SO with line qty=10
- **Steps:**
  1. On the delivery-create form, set the line qty to 6 (less than open 10).
  2. Submit.
- **Expected Result:** 201; SO line fulfilled=6, open=4; SO status=PARTIALLY_FULFILLED; reservation released by 6. The remaining 4 is the backorder, deliverable in a subsequent delivery (PARTIALLY_FULFILLED still allows further delivery).
- **Convention Assertions:** C2 DTO fulfilled/open fields; C8 numbers
- **Negative / Edge:** entering qty 11 (> open 10) → UI blocks ("Quantity … exceeds open balance (10).") AND backend 400 "Delivery qty 11 exceeds open qty 10 … (BR-SO-11)"; qty 0 or negative → 400 "Delivery qty must be > 0"; delivering a line that does not belong to the SO → 400

### TC-SALES-018 — Delivery list + detail: four states, pagination, for-order list
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Delivery list/detail (`/admin/deliveries`, `/admin/deliveries/uid/:uid` · `GET /api/v1/deliveries`, `GET /for-order/{salesOrderUid}`)
- **Permission / Role:** `SALES.DELIVERY.VIEW` — `ORG_ADMIN`; NO-PERMISSION → forbidden + hidden nav
- **Preconditions / Seed:** ≥2 pages of deliveries; an SO with ≥1 delivery
- **Steps:**
  1. Navigate to `/admin/deliveries`; verify rows + paginator + loading/empty/error.
  2. Open a delivery detail; verify lines + linked SO number.
  3. On the SO detail, verify the Deliveries panel lists this delivery (via `for-order`).
- **Expected Result:** four states; paginator; delivery detail shows DELIVERY-#### + line qtys; SO detail deliveries panel populated.
- **Convention Assertions:** C4 four states; C5 paginator; C1 numbers not uids; C6 axe
- **Negative / Edge:** for-order of an SO the user can't scope to → 403

## D. Sales Invoice — from delivery (origin = SALES_ORDER)

### TC-SALES-019 — Generate a DRAFT invoice from a delivery
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Delivery→Invoice seam (`POST /api/v1/deliveries/uid/{uid}/invoice`)
- **Permission / Role:** `SALES.DELIVERY.CREATE` — `ORG_ADMIN`; user lacking it → 403
- **Variation:** origin = SALES_ORDER; partial-invoice doc-discount pro-rating
- **Preconditions / Seed:** a CONFIRMED delivery with uninvoiced lines; the source SO carried a fixed `docDiscountAmount`
- **Steps:**
  1. From the delivery detail, trigger "Create invoice from delivery".
  2. Open the resulting DRAFT invoice.
- **Expected Result:** 201; a DRAFT `SalesInvoice` with `origin=SALES_ORDER`, `sourceOrderUid` + `sourceDeliveryUid` set, lines copied from the delivery's open-invoice qty; the SO's fixed doc-discount is pro-rated to the delivered subset (percent copied verbatim); delivery line `qtyInvoicedBase` and SO line `qtyInvoicedBase` incremented.
- **Convention Assertions:** C2 envelope; C8 money pro-rating; C1 references by number
- **Negative / Edge:** invoicing a delivery with no uninvoiced lines → 400 "Delivery … has no uninvoiced lines."; an SO with no agent → 400 "SalesOrder … has no agent."

### TC-SALES-020 — Finalise a SALES_ORDER-origin invoice (revenue-only, no stock re-issue)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Invoice (`PUT /api/v1/sales-invoices/uid/{uid}/finalize`)
- **Permission / Role:** `SALES.INVOICE.CREATE` — `ORG_ADMIN`; user with only VIEW → Finalize hidden / 403
- **Variation:** origin=SALES_ORDER (issuesStock=false); customer = CREDIT_ACCOUNT
- **Preconditions / Seed:** DRAFT invoice from TC-SALES-019 for a CREDIT_ACCOUNT customer
- **Steps:**
  1. Open the invoice detail; click "Finalize".
  2. Verify status FINALISED with an invoice number.
- **Expected Result:** 204; status=FINALISED, invoice number allocated, `finalisedAt`; `SALE.FINALISED` event published with `issuesStock=false` (delivery already issued stock — revenue only); for a credit customer no paid-in-full requirement, AR open item created for residual.
- **Convention Assertions:** C2 envelope; C9 finalised invoice immutable (append-only); C3 RBAC
- **Negative / Edge:** finalise an invoice with no lines → 400 "Cannot finalise an invoice with no lines."; finalise an already-FINALISED invoice → 400 "Only DRAFT … can be …"; credit-limit exceeded without `SALES.CREDIT.OVERRIDE` → 400 "Credit limit exceeded …"; with override permission → succeeds + audit `SALES_CREDIT_OVERRIDE`

## E. Sales Invoice — DIRECT walk-in (origin = DIRECT)

### TC-SALES-021 — Create a DIRECT walk-in invoice draft (CASH_WALK_IN customer)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Invoice (`/admin/sales-invoices` · `POST /api/v1/sales-invoices`)
- **Permission / Role:** `SALES.INVOICE.CREATE` — `ORG_ADMIN`; NO-PERMISSION → forbidden
- **Variation:** customer = CASH_WALK_IN; origin = DIRECT
- **Preconditions / Seed:** company + branch; a CASH_WALK_IN customer; (agent auto-defaults to the logged-in user's internal agent if omitted)
- **Steps:**
  1. Navigate to `/admin/sales-invoices`; open the create form.
  2. Pick the customer by name (optionally pick an agent + route via picker, else leave blank to auto-default).
  3. Submit.
- **Test Data:** customer "Walk-in Joe (CUST-0009)", currency=TZS
- **Expected Result:** 201; a DRAFT invoice with `origin=DIRECT` (default), no number yet; agent auto-resolved from the logged-in user's internal agent when omitted; route auto-defaults from the agent's primary route when omitted.
- **Convention Assertions:** C1 customer/agent/route via picker by name, no uid typed; C2 envelope; C8 currency
- **Negative / Edge:** missing customer → "Customer is required."; create in an unassigned branch → 403

### TC-SALES-022 — Add lines to a DIRECT invoice (GOODS, with line discount amount)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Invoice lines (`POST /api/v1/sales-invoices/uid/{uid}/lines`)
- **Permission / Role:** `SALES.INVOICE.CREATE` — `ORG_ADMIN`; VIEW-only user → add hidden / 403
- **Variation:** GOODS line, line discount amount
- **Preconditions / Seed:** DRAFT DIRECT invoice; sellable GOODS product with company price + VAT rate
- **Steps:** On invoice detail, search+select the product, choose unit, qty=3, discount amount=500; add.
- **Expected Result:** 201; line added with server-computed net/VAT/gross; invoice totals recompute (all amounts from DTO, not client-computed).
- **Convention Assertions:** C1 product/unit picker; C2 envelope; C8 money "TZS 1,234.56"
- **Negative / Edge:** qty ≤ 0 → validation; both line discount amount AND percent → 400; add line to a FINALISED invoice → 400 (draft-only)

### TC-SALES-023 — Add a CASH payment to a DIRECT invoice
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Invoice payments (`POST /api/v1/sales-invoices/uid/{uid}/payments`)
- **Permission / Role:** `SALES.INVOICE.SETTLE` — runs as `ORG_ADMIN`; user with `SALES.INVOICE.CREATE` but NOT `SETTLE` → Add-payment hidden / 403
- **Variation:** tender = CASH; currency = invoice currency
- **Preconditions / Seed:** DRAFT DIRECT invoice with lines (gross known)
- **Steps:** On the payments panel, tender=CASH, amount = invoice gross, reference optional; add.
- **Expected Result:** 201; payment row added; running paid total updates; balance to zero.
- **Convention Assertions:** C2 envelope; C8 money; C3 RBAC (SETTLE distinct from CREATE)
- **Negative / Edge:** amount ≤ 0 → 400 (`@Positive`); payment currency ≠ invoice currency → 400 "Payment currency … does not match … (BR-CUR-07)"; tender other than CASH/MOBILE_MONEY → 400 (enum)

### TC-SALES-024 — Add a MOBILE_MONEY payment (tender variation)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Invoice payments (`POST /api/v1/sales-invoices/uid/{uid}/payments`)
- **Permission / Role:** `SALES.INVOICE.SETTLE` — `ORG_ADMIN`
- **Variation:** tender = MOBILE_MONEY (reference required in practice)
- **Preconditions / Seed:** DRAFT DIRECT invoice with lines
- **Steps:** tender=MOBILE_MONEY, amount=part of gross, reference="MPESA-ABC123"; add.
- **Expected Result:** 201; payment recorded with the tender + reference shown.
- **Convention Assertions:** C2 envelope; C8 money
- **Negative / Edge:** see TC-SALES-023 negatives

### TC-SALES-025 — Finalise a DIRECT walk-in invoice — paid-in-full enforced (CASH_WALK_IN)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Invoice (`PUT /api/v1/sales-invoices/uid/{uid}/finalize`)
- **Permission / Role:** `SALES.INVOICE.CREATE` — `ORG_ADMIN`
- **Variation:** CASH_WALK_IN → must be paid in full; origin=DIRECT → issuesStock=true
- **Preconditions / Seed:** DRAFT DIRECT invoice for a CASH_WALK_IN customer, fully paid (TC-SALES-023)
- **Steps:** Click "Finalize".
- **Expected Result:** 204; status=FINALISED, invoice number allocated; `SALE.FINALISED` with `issuesStock=true` (DIRECT issues stock on finalise); FX rate-triple stamped at finalise.
- **Convention Assertions:** C2 envelope; C9 immutable after finalise; C8 money
- **Negative / Edge:** finalise a CASH_WALK_IN invoice NOT paid in full → 400 "paid-in-full" violation (`assertPaidInFull`, BR-SALES-07); finalise with zero lines → 400

### TC-SALES-026 — Remove a line / payment from a DRAFT invoice
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Invoice lines/payments (`DELETE …/lines/{lineUid}`, `DELETE …/payments/{paymentUid}`)
- **Permission / Role:** `SALES.INVOICE.CREATE` (both deletes are gated by CREATE) — `ORG_ADMIN`
- **Preconditions / Seed:** DRAFT invoice with a line + a payment
- **Steps:** Remove a payment; remove a line.
- **Expected Result:** 204 each; totals/paid recompute; empty-state when last removed.
- **Convention Assertions:** C2 envelope; C4 empty state
- **Negative / Edge:** delete on a FINALISED invoice → 400 (draft-only); note removePayment is gated by `SALES.INVOICE.CREATE`, NOT `SETTLE` — verify a SETTLE-only user CANNOT delete a payment (403)

### TC-SALES-027 — Void a finalised invoice (FINALISED → VOID)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Invoice (`PUT /api/v1/sales-invoices/uid/{uid}/void`)
- **Permission / Role:** `SALES.INVOICE.VOID` — runs as `ORG_ADMIN`; user lacking it → Void hidden / 403
- **Preconditions / Seed:** a FINALISED invoice (TC-SALES-025 or TC-SALES-020)
- **Steps:** Open the void form, enter a reason; submit.
- **Test Data:** reason = "Wrong customer"
- **Expected Result:** 204; status=VOID; invoice retains its number (void ≠ delete); a reversing AR credit note (origin=SALE_VOID) and stock/GL reversals raised.
- **Convention Assertions:** C2 envelope; C9 append-only reversal, number retained; C3 RBAC
- **Negative / Edge:** void a DRAFT invoice → 400 "Only FINALISED invoices can be voided; current status: DRAFT"; void an already-VOID invoice → 400; missing reason → 400 (`@NotBlank`)

### TC-SALES-028 — Illegal invoice transitions matrix
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Invoice lifecycle (DRAFT→FINALISED→VOID only)
- **Permission / Role:** all invoice permissions — `ORG_ADMIN`
- **Preconditions / Seed:** invoices in DRAFT, FINALISED, VOID
- **Steps:** Attempt: finalise a FINALISED/VOID invoice; void a DRAFT/VOID invoice; add/remove line or payment on a FINALISED/VOID invoice.
- **Expected Result:** every illegal action → 400 with descriptive `errors[]`; no state change.
- **Convention Assertions:** C2 envelope errors; C9 immutability
- **Negative / Edge:** this case IS the matrix

### TC-SALES-029 — Invoice list: four states, pagination, search (`q`)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Invoice list (`/admin/sales-invoices` · `GET /api/v1/sales-invoices?q=&status=`)
- **Permission / Role:** `SALES.INVOICE.VIEW` — `ORG_ADMIN`; NO-PERMISSION → forbidden + hidden nav
- **Preconditions / Seed:** ≥2 pages of invoices in mixed statuses
- **Steps:**
  1. Navigate to `/admin/sales-invoices`; verify loading→rows + paginator.
  2. Search via `q` for an invoice number/customer; verify filtered result.
  3. Force empty (no-match search) and error states.
- **Expected Result:** four states distinctly; search narrows results; paginator with FIRST/PREV/numbers/NEXT/LAST; meta present.
- **Convention Assertions:** C1 no uid column (invoice number/customer name); C2 meta; C4 four states; C5 paginator; C6 axe
- **Negative / Edge:** single-page → paginator hidden; note the `status` query param is accepted by the controller but the service `list(companyId, q, pageable)` filters by `q` only — assert behaviour matches (status filter is a no-op server-side in v1)

## F. Sales Return / RMA

### TC-SALES-030 — Create a sales return against a delivery (status CONFIRMED, raises credit note)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Return (`/admin/sales-returns/create` · `POST /api/v1/sales-returns`)
- **Permission / Role:** `SALES.RETURN.CREATE` — runs as `ORG_ADMIN`; user with only `SALES.RETURN.VIEW` → create route guard blocks + 403
- **Variation:** partial return (qtyReturned < delivered)
- **Preconditions / Seed:** a CONFIRMED delivery with delivered line qty=10 (TC-SALES-016b)
- **Steps:**
  1. Navigate to `/admin/sales-returns/create` for the delivery (selected by delivery number).
  2. Pick the delivery line(s); set qtyReturned=4; set return date=today; reason.
  3. Submit.
- **Test Data:** returnDate=today, line qtyReturned=4, reason="Damaged on arrival"
- **Expected Result:** 201; return created directly in CONFIRMED status with a RET-#### number; stock returned in; COGS reversal event; an AR credit note (origin=RETURN) raised synchronously, pro-rated (value = issueValue × qtyReturned/qtyDelivered, HALF_UP 4dp).
- **Convention Assertions:** C1 delivery + lines chosen by number/name, no uid; C2 envelope; C8 money pro-rating; C9 credit note is an append-only posting; C6 axe
- **Negative / Edge:** return date missing → validation; empty lines → 400 (`@NotEmpty`); qtyReturned ≤ 0 → 400; qtyReturned > (delivered − already-returned) → 400 "Return qty … exceeds returnable qty … (BR-SO-11)"; a return line not belonging to the delivery → 400

### TC-SALES-031 — Full return then attempt over-return (returnable-qty guard)
- **Type:** Manual
- **Priority:** P2
- **Module / Submodule:** Sales Return (`POST /api/v1/sales-returns`)
- **Permission / Role:** `SALES.RETURN.CREATE` — `ORG_ADMIN`
- **Preconditions / Seed:** delivery line delivered qty=10 with a prior partial return of 4 (TC-SALES-030)
- **Steps:**
  1. Create a second return for the remaining 6 → succeeds (returnable now 6).
  2. Attempt a third return for any qty > 0 → returnable now 0.
- **Expected Result:** step 1 → 201; step 2 → 400 "Return qty … exceeds returnable qty 0 (BR-SO-11)". `returnedQtyBase` on the delivery line accumulates across returns.
- **Convention Assertions:** C2 envelope errors; C9 each return is a separate append-only document
- **Negative / Edge:** this case IS the boundary test

### TC-SALES-032 — Sales Return list + detail: four states, pagination, for-delivery list
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Sales Return list/detail (`/admin/sales-returns`, `/admin/sales-returns/uid/:uid` · `GET /api/v1/sales-returns`, `GET /for-delivery/{deliveryUid}`)
- **Permission / Role:** `SALES.RETURN.VIEW` — `ORG_ADMIN`; NO-PERMISSION → forbidden + hidden nav
- **Preconditions / Seed:** ≥2 pages of returns; a delivery with ≥1 return
- **Steps:**
  1. Navigate to `/admin/sales-returns`; verify loading→rows + paginator; force empty/error.
  2. Open a return detail; verify RET-#### + lines + linked credit note reference.
  3. Verify the for-delivery list returns this return for its delivery.
- **Expected Result:** four states; paginator (both list-by-company and list-by-delivery are paginated); detail shows return number, qty, credit note.
- **Convention Assertions:** C4 four states; C5 paginator; C1 numbers not uids; C7 company scoping; C6 axe
- **Negative / Edge:** for-delivery of a delivery the user can't scope to → 403

## G. End-to-end happy paths (full chain)

### TC-SALES-033 — Full O2C via quotation: Quote→Accept→SO→Confirm→Deliver(full)→Invoice→Finalise (CREDIT_ACCOUNT)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All five submodules
- **Permission / Role:** all SALES.* O2C codes — `ORG_ADMIN`
- **Variation:** customer = BUSINESS + CREDIT_ACCOUNT; one GOODS line; order-level + line-level discount
- **Preconditions / Seed:** company + default branch; CREDIT_ACCOUNT customer (credit limit set high enough); sellable GOODS product with price/VAT/stock; an internal agent for the logged-in user
- **Steps:**
  1. Create quotation (picker), add GOODS line with 5% line discount + 2% doc discount, send, accept → SO created.
  2. On the SO: confirm (stock reserved).
  3. Create a full delivery → SO FULFILLED, stock issued.
  4. Generate invoice from delivery → DRAFT (origin=SALES_ORDER), finalise → SO CLOSED.
- **Expected Result:** each step transitions as documented; final SO=CLOSED, invoice FINALISED with number, AR open item for the credit customer; discounts pro-rated correctly through the chain.
- **Convention Assertions:** C1 every resource chosen via picker by name; C2 envelope throughout; C3 RBAC at each gate; C8 money formatting; C6 axe on each screen
- **Negative / Edge:** at any step run as a user missing that step's permission → that action's button is hidden and the API returns 403

### TC-SALES-034 — Full O2C walk-in: DIRECT invoice→lines→CASH payment→Finalise (CASH_WALK_IN), then VOID
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales Invoice (DIRECT) end-to-end
- **Permission / Role:** `SALES.INVOICE.CREATE` + `SETTLE` + `VOID` — `ORG_ADMIN`
- **Variation:** customer = CASH_WALK_IN; tender = CASH; origin = DIRECT
- **Preconditions / Seed:** CASH_WALK_IN customer; sellable GOODS product with price/VAT/stock; logged-in user has an internal agent
- **Steps:**
  1. Create DIRECT invoice (customer picker), add a GOODS line.
  2. Add a CASH payment for the full gross.
  3. Finalise (paid-in-full passes; stock issued).
  4. Void the finalised invoice with a reason.
- **Expected Result:** invoice FINALISED with number + stock issued, then VOID with number retained + reversing credit note/stock/GL.
- **Convention Assertions:** C1 picker; C2 envelope; C8 money; C9 void is append-only; C6 axe
- **Negative / Edge:** attempt finalise BEFORE full payment → 400 paid-in-full; void before finalise → 400

## H. RBAC, scoping & accessibility cross-cuts

### TC-SALES-035 — NO-PERMISSION user: empty nav + forbidden routes across all O2C screens
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All five (nav + route guards)
- **Permission / Role:** none of the SALES.* codes — runs as NO-PERMISSION user
- **Preconditions / Seed:** a user assigned to a branch but holding zero sales permissions
- **Steps:** Log in; inspect the shell nav; directly navigate to each O2C route (`/admin/quotations`, `/admin/sales-orders`, `/admin/deliveries`, `/admin/sales-invoices`, `/admin/sales-returns`).
- **Expected Result:** the Sales nav items are hidden (each `available` gated by its `*.VIEW` permission); direct navigation is blocked by `requirePermission` guard (forbidden screen); any API call returns 403 with `ApiResponse` errors.
- **Convention Assertions:** C3 RBAC; C4 forbidden state distinct from empty/error; C1 no data leaked
- **Negative / Edge:** custom role holding only `*.VIEW` → can read lists/detail but every mutate button (create/confirm/deliver/finalise/void/return) hidden + 403

### TC-SALES-036 — Branch scoping: act in an unassigned branch is denied
- **Type:** Manual
- **Priority:** P1
- **Module / Submodule:** All create/confirm endpoints (ScopeGuard `assertCanActIn`)
- **Permission / Role:** holds the relevant SALES.* codes but is NOT assigned to the target branch
- **Variation:** branch = non-default; user assigned to ONE branch attempting to act in another
- **Preconditions / Seed:** multi-branch company; user assigned to branch A only; documents in branch B
- **Steps:**
  1. Switch the active branch (X-Branch-Uid) to branch B (unassigned).
  2. Attempt to create a quotation / SO / invoice or confirm an SO in branch B.
- **Expected Result:** 403 (ScopeGuard denies acting in an unassigned branch); also cannot view branch-B-scoped documents.
- **Convention Assertions:** C7 branch + company scoping enforced; C3 RBAC
- **Negative / Edge:** a user assigned to ALL branches succeeds in any branch; switching to an assigned branch then acting succeeds

### TC-SALES-037 — Multi-tenant isolation across the O2C chain
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All five (company scoping)
- **Permission / Role:** `ORG_ADMIN` of tenant A vs `ORG_ADMIN` of tenant B
- **Preconditions / Seed:** two organisations/companies each with quotations, SOs, deliveries, invoices, returns
- **Steps:** As tenant B, attempt to list/view tenant A's documents by route and by direct `GET /uid/{uid}`.
- **Expected Result:** tenant B never sees tenant A's rows in any list; `GET /uid/{uid}` of a foreign document → 403/404; no cross-tenant picker leakage (customer/product/agent pickers only show tenant B's data).
- **Convention Assertions:** C7 tenant isolation; C1 pickers scoped; C2 envelope
- **Negative / Edge:** rootadmin (superuser) CAN see across tenants — positive smoke only; never used for negative-auth assertions

### TC-SALES-038 — Accessibility sweep (axe) across all O2C list + detail + create screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** All five frontend routes
- **Permission / Role:** `ORG_ADMIN`
- **Preconditions / Seed:** seeded data so each screen renders its idle state
- **Steps:** Visit each route (`/admin/quotations`, `…/uid/:uid`, `/admin/sales-orders`, `…/uid/:uid`, `/admin/deliveries`, `/admin/deliveries/create`, `…/uid/:uid`, `/admin/sales-invoices`, `…/uid/:uid`, `/admin/sales-returns`, `/admin/sales-returns/create`, `…/uid/:uid`) and run an axe scan; verify keyboard operability of the create forms and lifecycle buttons; verify table captions/scope.
- **Expected Result:** axe-clean (no WCAG 2.1 AA violations) on every screen; pickers and lifecycle buttons reachable + operable by keyboard with aria labels.
- **Convention Assertions:** C6 axe + keyboard + aria; C4 four states each remain axe-clean (loading/empty/error/forbidden)
- **Negative / Edge:** N/A (this is the a11y gate)

---

## Coverage notes / gaps observed (verified, not invented)

- **DeliveryStatus / SalesReturnStatus `DRAFT`** values exist in the enums but have NO v1 code path (deliveries and returns are created directly in `CONFIRMED`). There is no UI or endpoint to move a delivery/return from DRAFT→CONFIRMED, so no transition case is written for them beyond confirming v1 creates them CONFIRMED.
- **SalesOrderStatus `INVOICED`** exists in the 8-value enum, but the rollup `deriveStatus` produces `CLOSED` (not `INVOICED`) for a fully-fulfilled + fully-invoiced order in v1. TC-SALES-015 asserts the observed `CLOSED`.
- **`status` query param** on `GET /api/v1/sales-invoices` is accepted by the controller signature but the service `list(companyId, q, pageable)` ignores it (filters by `q` only). TC-SALES-029 documents this as a v1 no-op.
- **`removePayment`** (`DELETE …/payments/{paymentUid}`) is gated by `SALES.INVOICE.CREATE`, NOT `SALES.INVOICE.SETTLE` — TC-SALES-026 asserts a SETTLE-only user cannot delete a payment.
- **`SALES.CREDIT.OVERRIDE`** has no dedicated endpoint; it is checked inside `finalise` for credit-limit-exceeded credit customers (TC-SALES-020).
- **`SALES.INVOICE.OVERRIDE`** is seeded (line price / non-default discount / default-agent override). A service method `SalesInvoiceServiceImpl.overrideLinePrice(...)` exists (gated by this permission, draft-only, audited) but `SalesInvoiceController` exposes NO `/override` endpoint in this build, so there is no HTTP route or UI for it. No standalone override case is written (would be invented). Note that add-line itself does not carry a unit-price override field on `AddInvoiceLineRequest` (it has only quantity + discount); the SO/quotation add-line DTOs DO carry `unitPriceOverride`, covered in TC-SALES-002/011.
- **DocumentOrigin.POS** is out of scope for this O2C document (POS is a separate module/controller).
