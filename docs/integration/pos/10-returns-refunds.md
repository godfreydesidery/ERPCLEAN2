# Returns & Refunds

This section explains how an external POS client handles **returns** (giving stock back and crediting the
customer) and **refunds** (paying cash back out of the till) at the POS surface, and where the back-office
return path still applies.

Read the shared contract first (envelope, auth, error table, pagination, idempotency). This page only adds
the returns/refunds specifics and references the shared contract instead of repeating it.

> **TL;DR**
> A POS sale **can** be reversed at the till: `POST /api/v1/pos/sales/uid/{uid}/reverse` (body `{ "reason": "…" }`,
> permission `POS.SALE.VOID`, **204 No Content**) performs a **whole-invoice reversal** — it reverses
> revenue/VAT/cash, reverses the stock issue and inventory valuation (DR Inventory / CR COGS), and the
> reversed sale automatically drops out of the session's expected cash at X/Z-read. Shipped in
> **commit `f08fb08` (ADR-0042 D-2)**. Preconditions: the invoice must be **POS-origin** and its
> originating **session still OPEN** (and the invoice FINALISED), else **409** — a sale whose session has
> already been closed/reconciled is handled by the back-office invoice void on `/sales-invoices`.
> **Partial / line-level refunds are deferred** (ADR-0042) — only whole-sale reversal exists today.
> The order-to-cash `POST /api/v1/sales-returns` (stock-in + COGS reversal + AR credit note) still exists
> for **delivery-backed** invoices, but a pure POS cash sale has no delivery, so use the POS `/reverse`
> endpoint for those. A cash-drawer **payout** of type `REFUND`
> (`POST /api/v1/pos/sessions/uid/{uid}/payouts`) remains available for ad-hoc till cash-outs that are
> *not* tied to a specific sale (it adjusts expected cash for reconciliation but does **not** touch stock,
> GL revenue, or AR). See "What this means for a POS client" below.

---

## 1. The four things that are easy to confuse

The backend has four distinct, non-overlapping concepts. A POS client developer must keep them apart:

| Concept | Endpoint | What it actually does |
|---|---|---|
| **POS sale** | `POST /api/v1/pos/sales` | Creates + finalises a fully-paid **DIRECT** invoice tagged to the session (single CASH payment, or split/non-cash tenders — see the sale page). Server-authoritative pricing. |
| **POS sale reversal** | `POST /api/v1/pos/sales/uid/{uid}/reverse` | **The POS refund/void.** Whole-invoice reversal of a POS sale: reverses revenue/VAT/cash, reverses the stock issue + valuation (DR Inventory / CR COGS), and drops the sale out of the till's expected cash. Requires the originating session to still be OPEN. Perm `POS.SALE.VOID`. **204**. (ADR-0042 D-2, commit `f08fb08`.) |
| **Sales return / RMA** | `POST /api/v1/sales-returns` | Office-side. Returns stock, reverses COGS, raises an AR **credit note**. Requires a **delivery** + delivery lines — so it serves the order-to-cash flow, not pure POS cash sales. |
| **POS cash payout (`REFUND`)** | `POST /api/v1/pos/sessions/uid/{uid}/payouts` | A cash-drawer ledger line only, **not** tied to any sale. Adjusts the session's expected cash for X/Z reconciliation. **No stock, no GL revenue reversal, no AR, no link to any invoice or product.** Use it for ad-hoc till cash-outs, not for reversing a recorded POS receipt. |

So the POS surface **does** now have a first-class reversal: a POS `SalesInvoiceDto` (by its uid) can be
reversed via `POST /api/v1/pos/sales/uid/{uid}/reverse` (Section 1a). What is **still** missing:

- a **partial / line-level** POS refund — explicitly **deferred** by ADR-0042 (it needs a
  credit-note-by-line path); only whole-sale reversal exists today;
- a reversal of a POS sale whose **session has already closed/reconciled** — that is a back-office invoice
  void on `/sales-invoices`, where the cash difference is a reconciled-variance matter (see Section 1a
  preconditions).

`PosSaleController` (`com.erp.api.PosSaleController`) now exposes **two** mappings: `POST /api/v1/pos/sales`
(`processSale`) and `POST /api/v1/pos/sales/uid/{uid}/reverse` (`reverseSale`). `PosSessionController`
(`com.erp.api.PosSessionController`) exposes open / get / list / **payouts** / close / x-read / reconcile.
`POST /api/v1/pos/sales` also accepts an optional `Idempotency-Key` header to safely retry (see the shared
contract — idempotency is now **provided** for the sale endpoint, ADR-0042 D-1).

---

## 1a. Reversing a POS sale at the till (the POS refund/void)

This is the POS-native way to refund or void a mis-rung sale. It is a **whole-invoice reversal** that acts
as a full refund.

### Endpoint

`POST /api/v1/pos/sales/uid/{uid}/reverse`

- **Controller:** `PosSaleController.reverseSale` → `PosSaleServiceImpl.reverseSale`.
- **Required permission:** `POS.SALE.VOID`
  (`@PreAuthorize("@perm.scoped(#uid,'invoice','POS.SALE.VOID')")` — scoped to the **invoice** uid). Seeded
  by `R__seed_permissions.sql` as *"Reverse / void a POS sale at the till (refund)"* and, like every
  permission, auto-granted to `ORG_ADMIN`.
- **Path param:** `uid` — the **POS sale invoice** uid (the `uid` of the `SalesInvoiceDto` returned by the
  sale endpoint), e.g. `INV-2026-000042`.
- **Return type:** the controller returns `void` with `@ResponseStatus(NO_CONTENT)` → **HTTP 204**, empty body.

### Request JSON (`com.erp.modules.sales.domain.dto.VoidInvoiceRequest`)

```java
public record VoidInvoiceRequest(
        @NotBlank String reason
) {}
```

```json
{ "reason": "Customer changed mind — full refund, receipt INV-2026-000042" }
```

### What it does (synchronous, single TX)

`PosSaleServiceImpl.reverseSale` re-checks scope and the POS/session preconditions, then delegates to the
invoice void (`SalesInvoiceServiceImpl.voidInvoice`). The reversal:

- reverses **revenue + VAT + cash** — because a POS sale is a cash sale (no AR leg), the cash leg is
  credited back out, so there is **no separate payout** to record;
- reverses the **stock issue** and restores inventory valuation, posting **DR Inventory / CR COGS**;
- causes the reversed sale to **automatically drop out of the session's expected cash** at X-read / Z-read
  — no `REFUND` payout entry is needed for a reversal done this way.

### Preconditions (else 409)

1. The invoice must be **POS-origin** — `origin = POS` **and** it carries a `posSessionId`. A non-POS
   invoice gets `409` ("… is not a POS sale; use the standard invoice void.").
2. The **originating session must still be OPEN** so the till absorbs the cash refund. If that session is
   already `CLOSED` / `RECONCILED`, you get `409` — reverse such a sale via the **back-office invoice void**
   on `/sales-invoices`, where the cash difference is a reconciled-variance matter, not a till refund.
3. The invoice must be **FINALISED** (a POS sale always finalises, so this normally holds).

### Partial / line-level refunds — DEFERRED

ADR-0042 explicitly **defers** partial and line-level POS refunds (they require a credit-note-by-line
path). Today only **whole-sale** reversal exists. To refund part of a basket, reverse the whole sale and
ring a fresh sale for the kept items, per your business policy.

### curl

```bash
curl -i -X POST \
  "$BASE/api/v1/pos/sales/uid/INV-2026-000042/reverse" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "reason": "Customer changed mind — full refund" }'
# → HTTP/1.1 204 No Content
```

### Notable errors

| Status | When |
|---|---|
| **400** | `reason` missing/blank (`@NotBlank`); malformed JSON. |
| **401** | Missing/invalid/expired bearer token, or user no longer ACTIVE. |
| **403** | Caller lacks `POS.SALE.VOID`, or is out of scope for the invoice's company. |
| **404** | `uid` does not resolve to a `SalesInvoice` — `NotFoundException.of("SalesInvoice", uid)`. |
| **409** | Invoice is not POS-origin; or its originating session is not `OPEN`; or the invoice is not `FINALISED`. |
| **415** | Wrong `Content-Type`. Always send `application/json`. |

---

## 2. Why you cannot run a POS sale through `/api/v1/sales-returns`

> The POS-native reversal in Section 1a is what you use for POS receipts. This section explains why the
> **office-side** `/api/v1/sales-returns` path is *not* an alternative for a pure POS cash sale — it is
> only for delivery-backed order-to-cash invoices.

`POST /api/v1/sales-returns` is the real document-level return. Its request DTO
(`com.erp.modules.sales.domain.dto.CreateSalesReturnRequest`) is:

```java
public record CreateSalesReturnRequest(
        @NotNull String deliveryUid,                 // the DELIVERY being returned (required)
        @NotNull LocalDate returnDate,
        String reason,
        @NotEmpty List<ReturnLineRequest> lines      // at least one line
) {
    public record ReturnLineRequest(
            @NotNull String deliveryLineUid,         // a DELIVERY LINE uid (required)
            @NotNull BigDecimal qtyReturned
    ) {}
}
```

Every line is keyed by a **`deliveryLineUid`**, and the header is keyed by a **`deliveryUid`**. The service
(`SalesReturnServiceImpl.create`) loads the `Delivery` and its `DeliveryLine`s, and enforces
`qtyReturned <= qtyDelivered - alreadyReturned` per delivery line (business rule BR-SO-11).

A **POS sale does not produce a delivery.** `PosSaleServiceImpl.processSale` creates the invoice via
`CreateSalesInvoiceRequest(companyUid, customerUid, null /* no SO */, currency, notes, null)`, stamps
`origin = DocumentOrigin.POS`, and finalises it as a **DIRECT** invoice. There is no `SalesOrder`,
no `Delivery`, and no `DeliveryLine`. So there is no `deliveryUid`/`deliveryLineUid` to pass — meaning a
plain POS cash sale **cannot** be returned via `/api/v1/sales-returns` at all.

> **Practical consequence:** A back-office return through `/api/v1/sales-returns` is only possible for the
> standard order-to-cash flow (Sales Order → Delivery → Invoice), **not** for POS-originated invoices. But
> that no longer leaves POS receipts without a reversal: for a POS sale whose session is still OPEN, use the
> first-class **POS reversal** `POST /api/v1/pos/sales/uid/{uid}/reverse` (Section 1a). For a POS sale whose
> session has already closed/reconciled, fall back to the **back-office invoice void** on `/sales-invoices`
> (a reconciled-variance matter). A partial/line-level refund is deferred (ADR-0042), so model that as a
> reversal-plus-fresh-sale per your business policy.

---

## 3. The other POS-side mechanism: a cash-drawer payout of type `REFUND`

The first-class way to refund a recorded POS sale is the reversal in Section 1a (it both pays the cash back
*and* reverses stock/GL). Separately, the POS session API can record a bare **payout** on the open session
— a till-cash ledger entry for reconciliation that is deliberately **not** an accounting reversal and is
**not** tied to any invoice. Use a `REFUND` payout for ad-hoc cash-outs that do **not** correspond to a
reversible POS sale (e.g. an over-the-counter goodwill cash-back, or a refund of a sale already settled in a
prior, now-closed session). Prefer the Section 1a reversal whenever the sale and its open session exist.

### Endpoint

`POST /api/v1/pos/sessions/uid/{uid}/payouts`

- **Controller:** `PosSessionController.recordPayout`
- **Required permission:** `POS.SESSION.OPEN`
  (`@PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.OPEN')")` — scoped to the session uid).
  Note: this is the *same* permission used to open a session, **not** a dedicated refund permission.
- **Return type:** the controller method returns `void`. The body is auto-wrapped by `ApiResponseAdvice`
  into the standard envelope with `data: null` (HTTP 200). There is no payout DTO returned — the created
  payout is *not* echoed back.

### Path parameters

| Name | In | Type | Notes |
|---|---|---|---|
| `uid` | path | string | The **open** POS session uid. Must be `OPEN`, else 409 (see below). |

### Request JSON (`com.erp.modules.sales.domain.dto.PosPayoutRequest`)

```java
public record PosPayoutRequest(
        @NotNull PosPayoutType payoutType,                 // REFUND | PAID_OUT
        @NotNull @DecimalMin("0.01") BigDecimal amount,    // cash paid out, > 0
        @Size(max = 255) String reason
) {}
```

`PosPayoutType` (`com.erp.modules.sales.domain.enums.PosPayoutType`) has exactly two values:

- **`REFUND`** — cash paid out on a POS refund (cash returned to the customer).
- **`PAID_OUT`** — misc cash payout (drawer-to-safe drop / petty-cash payout).

Both types are **outflows**: at X-read / close they are *subtracted* from expected cash. The `REFUND`
type does **not** carry any product, quantity, invoice, or customer reference — the persisted entity
(`PosSessionPayout`) stores only `payoutType`, `amount`, `reason`, the session id, company/branch, and audit
columns. There is **no** stock movement, **no** GL journal, and **no** AR credit produced by a payout.

### Example request

```json
{
  "payoutType": "REFUND",
  "amount": 25000.00,
  "reason": "Customer returned 2x SKU-1180, cash refund (receipt INV-0042)"
}
```

### Success response

The method returns `void`, so the success body is the empty-data envelope (HTTP 200):

```json
{ "data": null, "errors": [], "meta": null }
```

> **Tip:** because nothing is echoed, record the receipt/reason yourself client-side. The payout *is*
> auditable server-side (audit action `POS.SESSION.PAYOUT`), but the API gives you no payout uid in the
> response.

### curl

```bash
curl -i -X POST \
  "$BASE/api/v1/pos/sessions/uid/01HZX9Q7M3K2J8VN4C6B1TFD5R/payouts" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Request-Id: $(uuidgen)" \
  -d '{
        "payoutType": "REFUND",
        "amount": 25000.00,
        "reason": "Cash refund for returned goods, ref INV-0042"
      }'
```

### Notable errors

| Status | When |
|---|---|
| **400** | `payoutType` missing/invalid enum value; `amount` null or `< 0.01` (`@DecimalMin("0.01")`); `reason` longer than 255 chars; malformed JSON. Field errors come back as `"field: message"`. |
| **401** | Missing/invalid/expired bearer token, or user no longer ACTIVE. |
| **403** | Caller lacks `POS.SESSION.OPEN`, or is acting outside the session's company scope, or a rejected `X-Branch-Uid`. Generic message — the missing permission is never named. |
| **404** | `uid` does not resolve to a session — `NotFoundException.of("PosSession", uid)`. |
| **409** | Session is not `OPEN` — `ConflictException("Session <number> is not OPEN.")`. You cannot record a payout against a `CLOSED` or `RECONCILED` session. |
| **415** | Wrong `Content-Type`. Always send `application/json`. |

---

## 4. How a `REFUND` payout affects reconciliation (X-read / Z-read)

Payouts only matter at session settlement. From `PosSessionServiceImpl`:

- **Expected cash** at close = `openingFloatAmount + cashSalesTotal − totalPayouts`, where `totalPayouts`
  is the sum of **all** payouts on the session (both `REFUND` and `PAID_OUT` — they are treated identically
  as outflows; `PosSessionPayoutRepository.totalPayoutsForSession`).
- **X-read** (`GET /api/v1/pos/sessions/uid/{uid}/x-read`, perm `POS.SESSION.VIEW`) returns `XReadDto` with
  `totalPayoutsNetAmount` and `expectedCashAmount` reflecting payouts mid-session.
- **Close** (`POST /api/v1/pos/sessions/uid/{uid}/close`, perm `POS.SESSION.CLOSE`) computes
  `varianceAmount = countedCashAmount − expectedCashAmount`.
- **Reconcile / Z-read** (`POST /api/v1/pos/sessions/uid/{uid}/reconcile`, perm `POS.SESSION.RECONCILE`)
  posts only the *variance* journal (POS_CASH_OVER / POS_CASH_SHORT). A `REFUND` payout itself never posts
  revenue/COGS/AR reversal — it just reduces the cash the till is expected to hold.

So a `REFUND` payout keeps the cash drawer honest at Z-read, but the *merchandise/accounting* side of the
refund (stock back in, revenue/VAT reversal) is **not** handled by it. If you need those effects for a POS
receipt, use the **POS reversal** (Section 1a) — it handles cash *and* stock/GL, and the reversed sale drops
out of expected cash on its own (so you do **not** also record a `REFUND` payout for it). The back-office
return path (Section 5) remains for order-to-cash deliveries only, and is unavailable for pure POS invoices
(Section 2).

---

## 5. For reference: the office-side return for order-to-cash deliveries

This is the document-level return for the **order-to-cash** flow (Sales Order → Delivery → Invoice). It is
documented here so you know what exists and why a POS client cannot drive it for POS-originated invoices
(which have no delivery — see Section 2). For POS receipts, use the POS reversal in Section 1a instead. Use
this endpoint only for order-to-cash deliveries.

### Endpoint

`POST /api/v1/sales-returns`

- **Controller:** `SalesReturnController.create`
- **Required permission:** `SALES.RETURN.CREATE`
  (`@PreAuthorize("@perm.scoped(#request.deliveryUid(),'delivery','SALES.RETURN.CREATE')")` — scoped to the
  delivery uid). Seeded as *"Create a sales return against a delivery (stock back in + credit note)"*.
- **HTTP status:** `201 Created`.
- **Returns:** `SalesReturnDto` (wrapped in the envelope).

### Request JSON

```json
{
  "deliveryUid": "DLV-2026-000118",
  "returnDate": "2026-06-19",
  "reason": "Damaged on arrival",
  "lines": [
    { "deliveryLineUid": "DLVL-2026-000231", "qtyReturned": 2 }
  ]
}
```

### What it does (synchronous, single TX)

`SalesReturnServiceImpl.create`:

1. Resolves the `Delivery` by `deliveryUid` and `ScopeGuard.assertCanActIn(...)` on its company.
2. Validates each line: `deliveryLineUid` must belong to the delivery; `qtyReturned > 0`; and
   `qtyReturned <= qtyDelivered − alreadyReturned` (BR-SO-11).
3. Creates a `SalesReturn` header directly in **`CONFIRMED`** status with a `RET-####` number
   (`SalesReturnStatus` has `DRAFT` and `CONFIRMED`, but v1 always creates `CONFIRMED`).
4. Publishes a `DELIVERY.RETURNED` outbox event **in the same TX** → stock-in + COGS reversal happen
   **asynchronously** via the outbox poller (same eventual-consistency model as a POS sale's stock/GL).
5. Raises an AR **credit note** *synchronously* (`ArCreditNoteOrigin.RETURN`), unapplied by default, and
   stamps `creditNoteUid` on the return.

### Success response (envelope)

```json
{
  "data": {
    "id": 5012,
    "uid": "SR-2026-000044",
    "companyId": 1,
    "branchId": 3,
    "returnNumber": "RET-0001",
    "status": "CONFIRMED",
    "deliveryId": 9001,
    "deliveryUid": "DLV-2026-000118",
    "salesOrderUid": "SO-2026-000077",
    "customerId": 420,
    "returnDate": "2026-06-19",
    "creditNoteUid": "CN-2026-000019",
    "cogsReversalGlEntryUid": null,
    "reason": "Damaged on arrival",
    "netAmount": 50000.0000,
    "vatAmount": 9000.0000,
    "grossAmount": 59000.0000,
    "currency": "TZS",
    "lines": [
      {
        "id": 7301,
        "uid": "SRL-2026-000061",
        "salesReturnId": 5012,
        "deliveryLineId": 12044,
        "deliveryLineUid": "DLVL-2026-000231",
        "lineNo": 1,
        "productId": 1180,
        "productCode": "SKU-1180",
        "productName": "Widget, large",
        "unitId": 7,
        "unitName": "EA",
        "qtyReturned": 2.0000,
        "qtyReturnedBase": 2.0000,
        "unitPriceAmount": 25000.0000,
        "lineDiscountAmount": 0.0000,
        "lineDiscountPercent": null,
        "vatStatus": "STANDARD",
        "vatRate": 18.00,
        "netAmount": 50000.0000,
        "vatAmount": 9000.0000,
        "grossAmount": 59000.0000,
        "currency": "TZS"
      }
    ]
  },
  "errors": [],
  "meta": null
}
```

### curl

```bash
curl -i -X POST \
  "$BASE/api/v1/sales-returns" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "deliveryUid": "DLV-2026-000118",
        "returnDate": "2026-06-19",
        "reason": "Damaged on arrival",
        "lines": [
          { "deliveryLineUid": "DLVL-2026-000231", "qtyReturned": 2 }
        ]
      }'
```

### Read paths (for completeness, perm `SALES.RETURN.VIEW`)

| Method + path | Permission | Returns |
|---|---|---|
| `GET /api/v1/sales-returns/uid/{uid}` | `SALES.RETURN.VIEW` (scoped to the return) | single `SalesReturnDto` |
| `GET /api/v1/sales-returns?companyId={id}&page=&size=&sort=` | `SALES.RETURN.VIEW` | paged `List<SalesReturnDto>` (envelope `meta` populated) |
| `GET /api/v1/sales-returns/for-delivery/{deliveryUid}` | `SALES.RETURN.VIEW` (scoped to the delivery) | paged `List<SalesReturnDto>` for that delivery |

### Notable errors (create)

| Status | When |
|---|---|
| **400** | `deliveryUid` / `returnDate` null; `lines` empty (`@NotEmpty`); a line's `deliveryLineUid`/`qtyReturned` null; a line's `deliveryLineUid` does not belong to the delivery; `qtyReturned <= 0`. |
| **401 / 403** | Auth as per shared contract; 403 if missing `SALES.RETURN.CREATE` or out of scope for the delivery's company. |
| **404** | Unknown `deliveryUid` (`Delivery` not found), unknown `deliveryLineUid`, or the delivery's SO/customer/company missing. |
| **409** | `qtyReturned` exceeds returnable qty on a delivery line (BR-SO-11) → `IllegalStateException`. |
| **415** | Wrong `Content-Type`. |

---

## 6. What this means for a POS client (decision guide)

- **Refund / void a recorded POS sale, session still open:** call
  `POST /api/v1/pos/sales/uid/{uid}/reverse` with `{ "reason": "…" }` (perm `POS.SALE.VOID`, **204**). This
  is the full reversal — cash back **and** stock back in **and** revenue/VAT/COGS reversal — and the
  reversed sale drops out of the drawer's expected cash on its own. Do **not** also record a `REFUND`
  payout for it (Section 1a).
- **Customer wants ad-hoc cash back that is *not* a reversible sale** (goodwill cash-out, or a refund of a
  sale settled in an already-closed session): record a `POST .../payouts` with `payoutType: "REFUND"` so the
  drawer reconciles. Understand this is **cash-only bookkeeping** — it does *not* put stock back, reverse
  VAT/revenue, or credit any AR ledger, and is not linked to any invoice.
- **Refund only part of a POS basket:** there is **no partial / line-level POS refund** — ADR-0042 defers
  it. Reverse the whole sale (above) and ring a fresh sale for the kept items.
- **Reverse a POS sale whose session is already closed/reconciled:** the POS `/reverse` endpoint returns
  `409` in that case. Use the **back-office invoice void** on `/sales-invoices` (a reconciled-variance
  matter), not a till refund.
- **You are doing standard order-to-cash (SO → delivery → invoice), not POS:** use `POST /api/v1/sales-returns`
  with the delivery + delivery-line uids (Section 5). This is the document-level return for delivery-backed
  invoices; it cannot be used for POS invoices (no delivery).
- **Retry safety:** `POST /api/v1/pos/sales` now accepts an optional `Idempotency-Key` header — **always
  send it** and reuse the SAME value on a retry, and the original sale is returned instead of double-posting
  (ADR-0042 D-1; see the sale page / shared contract). A blind retry **without** the header still creates a
  duplicate sale. `POST .../payouts` has **no** idempotency, so a blind payout retry can still duplicate —
  dedupe those client-side. `X-Request-Id` is correlation/logging only and is **not** used for deduplication.
