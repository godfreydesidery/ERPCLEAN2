# Returns & Refunds

This section explains how an external POS client handles **returns** (giving stock back and crediting the
customer) and **refunds** (paying cash back out of the till), and — importantly — what the ERP does **not**
offer at the POS surface.

Read the shared contract first (envelope, auth, error table, pagination, idempotency). This page only adds
the returns/refunds specifics and references the shared contract instead of repeating it.

> **TL;DR**
> There is **no POS return/refund endpoint that reverses a POS sale**. `POST /api/v1/pos/sales` is one-way:
> it creates and finalises a paid DIRECT invoice. To get the full accounting reversal (stock back in,
> COGS reversal, customer credit note) you must use the **office-side** `POST /api/v1/sales-returns`, and
> that endpoint requires a **delivery** — which a pure POS cash sale never has. The only thing the POS
> session API records is a cash-drawer **payout** of type `REFUND` (`POST /api/v1/pos/sessions/uid/{uid}/payouts`),
> which adjusts the till's expected cash for reconciliation **but does not touch stock, GL revenue, or AR**.
> See "What this means for a POS client" below.

---

## 1. The three things that are easy to confuse

The backend has three distinct, non-overlapping concepts. A POS client developer must keep them apart:

| Concept | Endpoint | What it actually does |
|---|---|---|
| **POS sale** | `POST /api/v1/pos/sales` | Creates + finalises a fully-paid CASH **DIRECT** invoice tagged to the session. One-way; cannot be reversed at this endpoint. |
| **Sales return / RMA** | `POST /api/v1/sales-returns` | Office-side. Returns stock, reverses COGS, raises an AR **credit note**. Requires a **delivery** + delivery lines. |
| **POS cash payout (`REFUND`)** | `POST /api/v1/pos/sessions/uid/{uid}/payouts` | A cash-drawer ledger line only. Adjusts the session's expected cash for X/Z reconciliation. **No stock, no GL revenue reversal, no AR, no link to any invoice or product.** |

There is **no** code path that:

- takes a POS `SalesInvoiceDto` (or its uid) and reverses it;
- creates a "negative" POS sale;
- accepts an `Idempotency-Key` to safely retry (see the shared contract — idempotency is **NONE**).

`PosSaleController` (`com.erp.api.PosSaleController`) exposes exactly one mapping: `POST /api/v1/pos/sales`
(`processSale`). `PosSessionController` (`com.erp.api.PosSessionController`) exposes open / get / list /
**payouts** / close / x-read / reconcile — and nothing else. Neither controller has a `return`, `refund`,
`reverse`, `void`, or `credit` mapping.

---

## 2. Why you cannot run a POS sale through `/api/v1/sales-returns`

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
> standard order-to-cash flow (Sales Order → Delivery → Invoice), **not** for POS-originated invoices.
> If your deployment needs returns of POS merchandise, that is an operational/back-office decision (e.g.
> exchange handled as a fresh sale, or a manual credit note / GL adjustment by finance) — there is no
> first-class API for "return this POS receipt".

---

## 3. The only POS-side mechanism: a cash-drawer payout of type `REFUND`

When a cashier gives money back to a customer at the till, the **only** thing the POS API records is a
**payout** on the open session. This is a till-cash ledger entry for reconciliation — it is deliberately
**not** an accounting reversal.

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
refund (stock back in, revenue/VAT reversal, customer credit) is **not** handled by it. If your operation
needs those effects, that is the back-office return path (Section 5), which is unavailable for pure POS
invoices (Section 2).

---

## 5. For reference: the office-side return that POS sales cannot use

This is the *real* return mechanism in the ERP. It is documented here so you know what exists and why a POS
client cannot drive it for POS-originated invoices. Use it only for order-to-cash deliveries.

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

- **Customer wants cash back at the till, same session:** record a `POST .../payouts` with
  `payoutType: "REFUND"` so the drawer reconciles. Understand this is **cash-only bookkeeping** — it does
  *not* put stock back, reverse VAT/revenue, or credit the customer's AR ledger.
- **You need the full accounting reversal of a POS receipt (stock + GL + credit note):** there is **no API
  for this against a POS invoice.** A POS invoice has no delivery, so `/api/v1/sales-returns` rejects it.
  Treat this as an operational/back-office task (finance raises a manual credit note / adjustment), or model
  a return as a brand-new offsetting sale per your business policy. **Do not** invent or assume a refund
  endpoint — none exists.
- **You are doing standard order-to-cash (SO → delivery → invoice), not POS:** use `POST /api/v1/sales-returns`
  with the delivery + delivery-line uids (Section 5). This is the only first-class return in the system.
- **Retry safety:** there is no idempotency on either `POST /api/v1/pos/sales` or `POST .../payouts`. A
  blind retry after a network timeout can create a duplicate payout (or a duplicate sale). Implement
  client-side dedupe; `X-Request-Id` is correlation/logging only and is **not** used for deduplication.
