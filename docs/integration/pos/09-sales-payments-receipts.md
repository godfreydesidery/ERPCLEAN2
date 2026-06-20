# Sales, Payments & Receipts

This is the core POS document. It covers the single endpoint that rings a sale —
`POST /api/v1/pos/sales` — end to end: the exact request shape, what the server
computes (line nets, discounts, VAT, totals, change), the tender/payment model
(single exact cash, or an optional split / non-cash `tenders` list), the response
DTO returned for receipt printing, and the side effects (a finalised DIRECT-origin
invoice plus the asynchronous stock issue and GL/AR postings).

> **Shipped in commit `f08fb08` (ADR-0042).** Three contract gaps that earlier
> editions of this page documented as *not available* are now live on this
> endpoint: an optional **`Idempotency-Key`** request header for safe retries
> (full semantics in `11-errors-offline-idempotency.md`), an optional
> **`tenders`** list for **split / non-cash** payment, and **server-authoritative
> pricing** (the client `unitPrice` is accepted for back-compat but ignored —
> the server re-derives price and VAT). Whole-sale reversal also now exists as a
> separate endpoint, `POST /api/v1/pos/sales/uid/{uid}/reverse` (see
> `10-returns-refunds.md`). The sections below reflect the shipped behaviour.

Everything below is grounded in the actual backend code:

| Concern | Source file |
| --- | --- |
| Endpoint + permission | `src/main/java/com/erp/api/PosSaleController.java` |
| Request DTO | `src/main/java/com/erp/modules/sales/domain/dto/PosSaleRequest.java` |
| Orchestration | `src/main/java/com/erp/modules/sales/service/PosSaleServiceImpl.java` |
| Line / VAT / discount / totals math | `src/main/java/com/erp/modules/sales/service/InvoiceTotalsCalculator.java` |
| Tender model + paid-in-full + change | `src/main/java/com/erp/modules/sales/service/SalesInvoiceServiceImpl.java` |
| `tenders` element (split / non-cash) | `PosSaleRequest.PosTender` (nested record in `PosSaleRequest.java`) |
| Idempotency reservation table | `src/main/resources/db/migration/V70__pos_sale_idempotency.sql` |
| Invoice numbering | `src/main/java/com/erp/modules/sales/service/SalesInvoiceCodeGenerator.java` |
| Response DTO (header) | `src/main/java/com/erp/modules/sales/domain/dto/SalesInvoiceDto.java` |
| Line read DTO | `src/main/java/com/erp/modules/sales/domain/dto/SalesInvoiceLineDto.java` |
| Payment read DTO | `src/main/java/com/erp/modules/sales/domain/dto/SalesInvoicePaymentDto.java` |
| Stock side effect | `src/main/java/com/erp/modules/stock/events/SaleIssueStockHandler.java` |
| GL side effect | `src/main/java/com/erp/modules/gl/events/SalesPostingHandler.java` |
| AR side effect | `src/main/java/com/erp/modules/ar/events/ArSalePostedHandler.java` |

The shared contract (base URL, `ApiResponse<T>` envelope, auth/JWT, error table,
pagination, idempotency) is documented once in the contract preamble for this
guide and is **not** re-derived here. Read it first; this section references it.

> **Read this before you start.** Unlike the multi-step
> `/api/v1/sales-invoices` flow (create draft → add lines → add payments →
> finalise), the POS endpoint does **all of that in one call**. You hand it a
> session, a customer, a currency and a list of lines; the server creates a DRAFT
> invoice, **re-derives** the price and VAT for every line from its own price
> list (§2.2), settles the gross (a single exact CASH payment by default, or each
> entry of an optional `tenders` list — §6), finalises the invoice, and returns
> the finalised header. You do not (and cannot) drive the draft lifecycle
> yourself from the POS endpoint.

---

## 1. Endpoint summary

| Property | Value |
| --- | --- |
| Method + path | `POST /api/v1/pos/sales` |
| Permission | `POS.SALE.CREATE` (`@PreAuthorize("@perm.has('POS.SALE.CREATE')")`) |
| Content-Type | `application/json` (required — wrong type → **415**) |
| Auth | `Authorization: Bearer <accessToken>` (see contract) |
| Idempotency | **Optional** `Idempotency-Key` request header (≤80 chars) — safe-retry guarantee; see §10 and `11-errors-offline-idempotency.md` |
| Success status | **201 Created** (also **201** on an idempotent replay — see §10) |
| Success body | `SalesInvoiceDto` wrapped in `ApiResponse<T>` |
| Branch scope | From the JWT / optional `X-Branch-Uid` header — **never** in the body |

`PosSaleController` exposes the ring-a-sale endpoint (it also carries the
`/reverse` endpoint — see `10-returns-refunds.md`):

```java
@RestController
@RequestMapping("/api/v1/pos/sales")
public class PosSaleController {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('POS.SALE.CREATE')")
    public SalesInvoiceDto processSale(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PosSaleRequest request) {
        return posSaleService.processSale(idempotencyKey, request);
    }
}
```

There are no path or query parameters on `POST .../sales`. The only header it
reads beyond auth/branch is the **optional `Idempotency-Key`** (≤80 chars):
supply it — and reuse the **same** value when retrying an ambiguous POST — to get
the safe-retry guarantee (the original sale is returned, never double-posted).
Omit it and you get the legacy non-idempotent behaviour. Full semantics, the
replay-vs-409 distinction, and per-company scope are in §10 and
`11-errors-offline-idempotency.md`.

---

## 2. Request body — `PosSaleRequest`

This is the **exact** record (`PosSaleRequest.java`); the field names and
validation annotations are reproduced verbatim:

```java
public record PosSaleRequest(
        @NotBlank String sessionUid,
        @NotNull  Long customerId,
        /** Optional (ADR-0042 D-4). Informational — the sale is recorded against the logged-in cashier. */
        Long agentId,
        @NotBlank String currency,
        @NotEmpty @Valid List<LineItem> lines,
        /** Optional (ADR-0042 D-3). Split / non-cash tenders; absent or empty => single exact CASH. */
        @Valid List<PosTender> tenders,
        /** Total tendered (for receipt printing, not stored on invoice). */
        BigDecimal tenderedAmount,
        @Size(max = 500) String notes
) {
    public record LineItem(
            @NotNull Long productId,
            @NotNull Long unitId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            /** Optional (ADR-0042 D-4). IGNORED — pricing is server-authoritative (price list). */
            BigDecimal unitPrice,
            BigDecimal lineDiscountAmount
    ) {}

    /** A single POS tender (ADR-0042 D-3); instrument fields mirror AddPaymentRequest (ADR-0041 D3). */
    public record PosTender(
            @NotNull TenderType tenderType,
            @NotNull @DecimalMin("0.0001") BigDecimal amount,
            String reference,
            Long cashBankAccountId,
            Long chequeId,
            String mobileMoneyRef,
            String cardRef
    ) {}
}
```

> **Back-compat constructor.** `PosSaleRequest` keeps a 7-arg constructor (no
> `tenders`) so existing callers and JSON bodies that omit `tenders` are
> unaffected — they settle as a single exact CASH payment exactly as before.

### 2.1 Header fields

| Field | JSON type | Required | Notes |
| --- | --- | --- | --- |
| `sessionUid` | string | yes (`@NotBlank`) | The open POS session uid (string, e.g. `pos-sess_…`). Resolved via `PosSessionRepository.findByUid`; must exist (else **404** `PosSession`) and be **OPEN** (else **409** `POS session <uid> is not OPEN.`). |
| `customerId` | number (long) | yes (`@NotNull`) | Numeric DB id of the customer (not a uid). `customers.findById(customerId)`; unknown → **404** `Customer`. For a true walk-in, pass your configured cash/walk-in customer id. |
| `agentId` | number (long) | **no** (ADR-0042 D-4 — was `@NotNull`) | Numeric DB id of the selling agent (cashier/clerk). **Informational and NOT forwarded** — see the important note below. The invoice's agent always defaults to the logged-in user, so the submitted `agentId` has no effect on attribution. The `@NotNull` was relaxed; you may omit it. |
| `currency` | string | yes (`@NotBlank`) | ISO currency code, e.g. `"TZS"`. Must match the session company's enabled currencies, otherwise **422** `CurrencyNotEnabledException`. Becomes the invoice header currency and the currency forced onto **every** tender (each `PosTender` is recorded in this currency). |
| `lines` | array | yes (`@NotEmpty`, each `@Valid`) | At least one line. Empty array → **400** bean-validation. |
| `tenders` | array | no (`@Valid`) | **Optional split / non-cash tenders** (ADR-0042 D-3). When present, each element is recorded as an invoice payment (`CASH` / `CARD` / `MOBILE_MONEY` / `CHEQUE`) and their **sum must cover the gross total** (≥ gross, else **400**/**409** — see §6). When **absent or empty**, the server settles with a single exact CASH payment (legacy behaviour). |
| `tenderedAmount` | number (decimal) | no | Cash the customer handed over. **Receipt-printing aid only** — see §6 (change). The server does **not** store it on the invoice and does **not** use it to compute the payment amount. |
| `notes` | string | no (`@Size(max=500)`) | Free-text note copied onto the invoice header (`createReq.notes`). >500 chars → **400**. |

> **Critical attribution behaviour — `agentId` is informational and ignored by the POS path.**
> Like `unitPrice` (§2.2), `agentId` is **not forwarded** to the invoice. As of ADR-0042 D-4 its
> `@NotNull` was relaxed, so the field is now **optional** — you may omit it entirely. The "resolve
> agent" step is unimplemented: in `PosSaleServiceImpl.processSale` the create call passes `null`
> for the agent slot —
>
> ```java
> var createReq = new CreateSalesInvoiceRequest(
>         company.getUid(), customer.getUid(), null, req.currency(), req.notes(), null);
> //                                          ^^^^ agent slot — always null, agentId is never used
> ```
>
> The invoice service then **defaults the agent to the logged-in user**. So a submitted `agentId`
> has **no effect** on attribution: the recorded agent always reflects the **cashier who is
> authenticated**, not the `agentId` in your request. You may send `agentId` for your own records,
> but do **not** rely on it to attribute the sale to a different agent — and treat the returned
> `agentId`/`agentName` (§5) as the cashier, not as an echo of what you submitted.

### 2.2 Line fields — `PosSaleRequest.LineItem`

| Field | JSON type | Required | Notes |
| --- | --- | --- | --- |
| `productId` | number (long) | yes (`@NotNull`) | Numeric product id. `products.findById`; unknown → **404** `Product`. |
| `unitId` | number (long) | yes (`@NotNull`) | Numeric unit-of-measure id (base unit or a bulk-pack unit of the product). `units.findById`; unknown → **404** `Unit`. |
| `quantity` | number (decimal) | yes (`@NotNull`, `@DecimalMin("0.0001")`) | Quantity in the chosen unit. ≤0 → **400**. |
| `unitPrice` | number (decimal) | **no** (ADR-0042 D-4 — `@NotNull` removed) | **Accepted but IGNORED — see the important note below.** Pricing is server-authoritative; this value is dropped before any pricing code. Safe to omit, or send your last-known price purely for receipt display. |
| `lineDiscountAmount` | number (decimal) | no | Absolute discount on the line, applied **before VAT** (see §4). |

> **Pricing is server-authoritative — `unitPrice` is accepted but ignored (ADR-0042 D-4).**
> The client `unitPrice` is **dropped before any pricing code**; the server
> **re-derives** the unit price and VAT itself, so a malicious or buggy client
> **cannot** set `price = 0`. The Javadoc on `LineItem.unitPrice` now states it is
> ignored. In `PosSaleServiceImpl.processSale` each line is added without a price:
>
> ```java
> var lineReq = new AddInvoiceLineRequest(
>         product.getUid(), unit.getUid(),
>         line.quantity(), line.lineDiscountAmount(), null);
> invoiceService.addLine(invoiceUid, lineReq);
> ```
>
> `AddInvoiceLineRequest` has **no price field**; `SalesInvoiceServiceImpl.addLine`
> snapshots the **list price** from the product's company-scoped price list
> (`ProductPrice` via `resolveListPrice(...)`) and uses it as both
> `listPriceAmount` and `unitPriceAmount`, and the **VAT** from the company's
> `tax_rates`. So the price that lands on the line — and therefore the totals and
> the payment(s) — comes from the **server price list / tax rates**, never from
> `unitPrice` in your request. You may still send `unitPrice` for your own receipt
> display, but **treat the `SalesInvoiceDto` totals (§5) returned by the server as
> authoritative**. If a product has **no price** on a price list for the company,
> `addLine` throws and you get a **400** (`Product has no price on any price list
> (BR-SALES-03)`).
>
> Likewise, `lineDiscountPercent` is **not** reachable from the POS path (only
> `lineDiscountAmount` is forwarded); the third `AddInvoiceLineRequest` argument
> (percent) is always passed as `null`.

---

## 3. What the server does (orchestration) — `PosSaleServiceImpl.processSale`

One `@Transactional` method performs the whole quick-sale, in order:

1. **Resolve + guard the session.** `posSessionRepo.findByUid(sessionUid)` (→ **404**
   if absent), then `scopeGuard.assertCanActIn(RequestContext.get(),
   session.getCompanyId())` (→ **403** if the caller cannot act in the session's
   company), then assert `session.getStatus() == OPEN` (→ **409** `POS session
   <uid> is not OPEN.`).
1b. **Reserve the `Idempotency-Key`** (only if the header was sent — ADR-0042 D-1).
   `idempotency.tryReserve(companyId, key)` runs `INSERT … ON CONFLICT
   (company_id, idem_key) DO NOTHING` **inside this transaction, before the
   invoice exists**. On a fresh key it succeeds and processing continues. On an
   already-used key it returns 0 (blocking first on any in-flight winner): if the
   marker's `invoiceUid` is stamped, the **original** finalised invoice is
   returned (replay — no second sale); if it is still null (a true in-flight
   duplicate), a **409** `… still in progress; retry shortly` is thrown. See §10.
2. **Resolve the customer + company.** `customers.findById(customerId)` (→ **404**),
   `companies.findById(session.getCompanyId())` (→ **404**). The sale's company is
   taken from the **session**, not from the request.
3. **Create a DRAFT invoice** via `invoiceService.create(...)` using the company
   uid, customer uid, the request `currency`, and `notes`. This allocates audit
   and creates the header (no number yet). Branch comes from `RequestContext`
   (the JWT / `X-Branch-Uid`), not the body.
4. **Stamp POS provenance.** The created entity is re-read and tagged
   `origin = DocumentOrigin.POS`, `posSessionId = session.getId()`, then
   `saveAndFlush` so the later finalise sees `origin=POS`.
5. **Add each line** via `invoiceService.addLine(...)`. This is where list price
   and VAT are snapshotted, `qtyInBase` is computed (qty × bulk-pack factor, or
   qty for a base unit), and the per-line discount is attached. Header totals are
   recomputed after every line (§4).
6. **Settle the sale.** The invoice is re-read and `grossTotal =
   reloaded.getGrossTotalAmount()` is taken, then:
   - **If `tenders` is present and non-empty** (ADR-0042 D-3): each `PosTender` is
     added via `invoiceService.addPayment(...)` — `new AddPaymentRequest(tenderType,
     amount, currency, reference, cashBankAccountId, chequeId, mobileMoneyRef,
     cardRef)` — **forced to the request `currency`**. After the loop the tender
     **sum must be ≥ `grossTotal`**, else **409** `Tenders (…) do not cover the
     gross total (…)`. This is the split / non-cash path (CASH, CARD, MOBILE_MONEY,
     CHEQUE; over-tender change for CASH is handled by the payment layer — §6).
   - **Otherwise** (`tenders` absent or empty): a single payment for **exactly**
     the gross — `new AddPaymentRequest(TenderType.CASH, grossTotal, currency,
     null)` — is added, the original behaviour. No over-tender, hence no server
     change row.
7. **Finalise** via `invoiceService.finalise(invoiceUid, new
   FinaliseInvoiceRequest())`. This freezes totals, stamps the FX rate-triple,
   allocates the invoice number, validates paid-in-full, sets status
   `FINALISED`, and **publishes the `SALE_FINALISED` outbox event** (§7).
7b. **Stamp the idempotency marker** (only if a key was sent). In the **same
   transaction**, `idempotency.stampInvoiceUid(companyId, key, invoiceUid)` writes
   the created invoice uid onto the reservation row, so a concurrent duplicate
   sees it the instant this transaction commits (ADR-0042 D-1). A
   rolled-back/failed sale takes the marker with it, freeing the key for a clean
   retry.
8. **Audit** `POS_SALE_FINALISE` (with `sessionUid` and `gross`), then return
   `invoiceService.getByUid(invoiceUid)` — the finalised header DTO (§5).

Because the customer used for a POS quick-sale is normally a **cash / walk-in**
customer (`CustomerKind` ≠ `CREDIT_ACCOUNT`), `finalise` takes the cash path and
enforces the **paid-in-full** invariant — which the full CASH payment in step 6
satisfies. If you point a POS sale at a `CREDIT_ACCOUNT` customer instead, the
credit path runs (no paid-in-full requirement, but a credit-limit check that can
throw **409** unless the caller holds `SALES.CREDIT.OVERRIDE`); this is an
unusual configuration for POS and is described in the full Sales-Invoice guide.

---

## 4. How totals are computed — `InvoiceTotalsCalculator`

Totals are recomputed on every line mutation and again at finalise. The
algorithm (tax-exclusive, ADR-0008 D-4) is authoritative — your POS UI should
mirror it exactly if it pre-displays a total before calling the API:

1. **Per-line raw net:** `rawNet = round(unitPrice × quantity) − lineDiscount`,
   floored at 0. `unitPrice` here is the **server-snapshotted list price**
   (§2.2). `lineDiscount` resolves from `lineDiscountAmount` first, else
   `lineDiscountPercent × lineGross / 100` (percent is not used by the POS path).
2. **Document discount apportionment:** a header-level discount (not set by the
   POS path — POS sends none) is spread pro-rata across lines by each line's raw
   net, with the last line absorbing the rounding residual.
3. **Per-line VAT + gross:** `vat = round(discountedNet × vatRate)` (0 for
   `ZERO_RATED` / `EXEMPT` products); `lineGross = discountedNet + vat`. The VAT
   rate is snapshotted per line from the company's `tax_rates` for the product's
   `vatStatus`.
4. **Header totals:** `netTotal = Σ discountedNet`; `vatTotal = Σ vat`;
   `grossTotal = netTotal + vatTotal`.
5. **`taxSummary`:** a JSON array grouping lines by `(vatStatus, vatRate)` band,
   each row `{status, rate, net, vat}`. Stored on the header and surfaced as the
   `taxSummary` string in the response DTO.

**Rounding:** `HALF_UP` at each boundary (per line, per band). The working scale
is **0 decimal places** (TZS is whole-shilling in practice; storage is
`NUMERIC(19,4)`). So for an 18% STANDARD-rated line at list price 1,000 × qty 3
with no discount: net = 3,000, vat = round(3,000 × 0.18) = 540, gross = 3,540.

---

## 5. Response — `SalesInvoiceDto` (the finalised header)

On success the controller returns the finalised `SalesInvoiceDto`, auto-wrapped
in the envelope, with **HTTP 201**. This is the **header only** — it does **not**
embed the lines or payments. If your receipt needs line detail or the payment
rows, fetch them with the read endpoints in §8.

Selected fields you will use for the receipt (full list in `SalesInvoiceDto.java`):

| Field | Type | Receipt use |
| --- | --- | --- |
| `id` | string* | Numeric invoice id (serialised as a string — global Long-as-string config). |
| `uid` | string | Stable invoice uid (use for follow-up reads / void). |
| `invoiceNumber` | string | The printed document number, format **`INV-0001`** (`INV-` + zero-padded 4-digit per-company sequence — see note below). |
| `status` | enum | `FINALISED` on success. |
| `documentType` | enum | The sales document type. |
| `customerId` / `customerName` | long / string | Bill-to display. |
| `agentId` / `agentName` | long / string | The recorded selling agent — which, on the POS path, is always the **logged-in user** (the `agentId` you submitted is ignored; see §2.1). In the example below `agentId` equals `finalisedBy` because both are the authenticated cashier. |
| `currency` | string | e.g. `TZS`. |
| `netTotalAmount` | decimal | Sum of line nets (pre-VAT). |
| `vatTotalAmount` | decimal | Total VAT. |
| `grossTotalAmount` | decimal | **Amount charged** — derived from the **server** price list + VAT (not your `unitPrice`). Settled by one exact CASH payment by default, or covered by the `tenders` sum (≥ gross) if you sent tenders. |
| `taxSummary` | string (JSON) | Per-band `{status, rate, net, vat}` breakdown for the VAT block on the receipt. |
| `finalisedAt` | string (ISO instant) | Receipt timestamp. |
| `finalisedBy` | long | User id who finalised. |
| `notes` | string | Echoed from the request. |
| `routeUid` / `routeCode` / `routeName` | string | Nullable route enrichment (rarely relevant for counter POS). |
| `version` | long | Optimistic-lock version. |
| `createdAt` / `createdBy` / `updatedAt` / `updatedBy` | string / long | Audit stamps. |

> **Invoice numbering note.** Despite the common assumption of a `POS-####`
> series, POS sales use the **same** sales-invoice sequence as every other
> invoice. `SalesInvoiceCodeGenerator.next(...)` formats `"INV-" +
> String.format("%04d", value)` from the per-company `code_sequence`
> (`entity_kind = "SALES_INVOICE"`). The POS nature of the sale is recorded by
> `origin = POS` and `posSessionId` on the entity — **not** in the number. There
> is no separate POS receipt-number series in this code path.

> **What is NOT in the header DTO:** the tender amount(s), the `tenderedAmount`
> you sent, and any change. The charged amount is `grossTotalAmount`; the
> individual payment rows (each tender + any CASH `changeAmount`) are reachable
> only via `GET /api/v1/sales-invoices/uid/{uid}/payments` (§8) — and a split sale
> returns **one row per tender** there.

### 5.1 Success body example

```json
{
  "data": {
    "id": "904",
    "uid": "si_2f9c1a7b3d8e4f10",
    "companyId": "1",
    "branchId": "2",
    "documentType": "INVOICE",
    "invoiceNumber": "INV-0042",
    "status": "FINALISED",
    "customerId": "55",
    "customerName": "Walk-in Customer",
    "agentId": "7",
    "agentName": "Jane Cashier",
    "currency": "TZS",
    "customerPoNumber": null,
    "paymentTermsId": null,
    "docDiscountAmount": null,
    "docDiscountPercent": null,
    "netTotalAmount": 3000,
    "vatTotalAmount": 540,
    "grossTotalAmount": 3540,
    "taxSummary": "[{\"status\":\"STANDARD\",\"rate\":\"0.1800\",\"net\":\"3000.0000\",\"vat\":\"540.0000\"}]",
    "finalisedAt": "2026-06-19T09:15:42.123Z",
    "finalisedBy": "7",
    "voidedAt": null,
    "voidedBy": null,
    "voidReason": null,
    "notes": "Counter sale",
    "shipToAddressId": null,
    "billToAddressId": null,
    "shipToAddressText": null,
    "billToAddressText": null,
    "routeId": null,
    "routeUid": null,
    "routeCode": null,
    "routeName": null,
    "version": "0",
    "createdAt": "2026-06-19T09:15:41.880Z",
    "createdBy": "7",
    "updatedAt": "2026-06-19T09:15:42.130Z",
    "updatedBy": "7"
  },
  "errors": [],
  "meta": null
}
```

(`id`, `companyId`, `branchId`, `customerId`, `agentId`, `finalisedBy`,
`createdBy`, `updatedBy`, `version` serialise as JSON strings under the global
Long-as-string config; the decimals are numbers.)

> **Note on `agentId`/`agentName` in this example.** They reflect the **logged-in
> cashier** (here id `7`, "Jane Cashier") — *not* an `agentId` you submitted. The POS
> path ignores the request `agentId` and defaults the invoice agent to the authenticated
> user (§2.1), which is why `agentId` equals `finalisedBy` (`7`) above. Do not read these
> fields as confirmation that a submitted agent was recorded.

---

## 6. The tender / payment model and change

The POS quick-sale path supports **two** settlement modes (ADR-0042 D-3),
selected by whether you send the optional `tenders` list:

**A. Single exact CASH (default — `tenders` omitted or empty).**
The server adds **one CASH tender** for **exactly** `grossTotalAmount`
(`AddPaymentRequest(TenderType.CASH, grossTotal, currency, null)`). Because the
tender equals the gross to the cent, **the paid-in-full invariant is satisfied
with no over-tender**, so the server records **no `changeAmount`** on this path.
This is the legacy behaviour and is fully back-compatible.

**B. Split / non-cash tenders (`tenders` present).**
Each `PosTender` element is recorded as a separate invoice payment, **forced to
the request `currency`**:

| `PosTender` field | Notes |
| --- | --- |
| `tenderType` | one of `CASH`, `CARD`, `MOBILE_MONEY`, `CHEQUE` (`@NotNull`). |
| `amount` | `@NotNull @DecimalMin("0.0001")` — this tender's amount. |
| `reference` | free-text payment reference (optional). |
| `cashBankAccountId` / `chequeId` / `mobileMoneyRef` / `cardRef` | optional structured instrument refs (ADR-0041 D3) — populate the one relevant to the tender type. |

Rules for mode B:
- The tender **sum must cover the gross** (`Σ amount ≥ grossTotalAmount`),
  otherwise **409** `Tenders (…) do not cover the gross total (…)`.
- You may **split** across several tenders (e.g. part CARD + part CASH) and use
  **non-cash** methods (card, mobile money, cheque).
- Over-tender / change follows the underlying payment rule
  (`SalesInvoiceServiceImpl.assertPaidInFull`): a CASH row absorbs over-tender as
  `changeAmount`, while non-cash over-tender (e.g. mobile money) is rejected. Keep
  non-cash tenders exact and let CASH be the swing tender if you over-tender.

### Change is still a client-side computation for the receipt

The `tenderedAmount` field exists purely so your receipt can print the headline
change figure. The server does not store it. Compute change on the client:

```
change = tenderedAmount − grossTotalAmount    (from the response DTO)
```

For the ledger-level change actually recorded (mode B over-tender), read the
payment rows (§8) — a CASH row may carry a `changeAmount`.

`TenderType` enum (`TenderType.java`): `CASH`, `MOBILE_MONEY`, `CHEQUE`, `CARD`
— all four are usable via the `tenders` list. If you need a settlement shape the
POS path still does not model (e.g. deferred / on-account credit settlement), you
can instead drive the `/api/v1/sales-invoices` draft lifecycle directly (add
lines, add one or more `AddPaymentRequest` payments, then finalise).

---

## 7. Side effects — synchronous vs eventual

`POST /api/v1/pos/sales` is **partially synchronous**. By the time you receive
the **201**:

**Synchronous (committed in the POST transaction):**
- A `FINALISED`, fully-paid invoice (a single exact CASH tender by default, or the
  `tenders` you supplied) with `origin = POS`, `posSessionId` set, an allocated
  `invoiceNumber` (`INV-####`), frozen totals, and the immutable FX rate-triple.
- If an `Idempotency-Key` was sent, its reservation row in `pos_sale_idempotency`
  carries the created `invoice_uid` (committed atomically with the sale).
- A queued `SALE_FINALISED` transactional-outbox row (`issuesStock = true`,
  because `origin = POS` is treated as `DIRECT` per ADR-0021 D-6).
- The `POS_SALE_FINALISE` audit record.

**Eventual (applied asynchronously by the outbox poller, ~1s default, retried
on failure):**
- **Stock issue** — `SaleIssueStockHandler` deducts `qtyInBase` per line
  (exploding composed/BOM products to stockable components, skipping
  non-stockable), updates valuation, and posts the COGS journal.
- **Sales GL journal** — `SalesPostingHandler` posts the balanced revenue + VAT +
  cash/AR entry (re-reading invoice totals; payload carries no amounts).
- **AR open item** — `ArSalePostedHandler` records the receivable / settlement
  data where applicable.

> **Do not assume the ledger is posted at response time.** The stock decrement
> and the GL/AR journals are **not** guaranteed committed when the 201 returns.
> Print the receipt from the response DTO immediately; treat the ledger as
> eventually consistent. Delivery is at-least-once and the handlers are
> idempotent per event (ADR-0009 D-5/D-6) — but that protects against
> **re-delivery of the same event**, not against two distinct sales created by
> two HTTP POSTs (see §9).

---

## 8. Reading lines / payments for the receipt

The POS response is the header only. To enrich the receipt with line detail or
the tender/change rows, use the standard sales-invoice read endpoints (require
`SALES.INVOICE.VIEW`, scoped to the invoice uid you got back):

| Method + path | Permission | Returns |
| --- | --- | --- |
| `GET /api/v1/sales-invoices/uid/{uid}` | `SALES.INVOICE.VIEW` | `SalesInvoiceDto` (same header) |
| `GET /api/v1/sales-invoices/uid/{uid}/lines` | `SALES.INVOICE.VIEW` | `List<SalesInvoiceLineDto>` |
| `GET /api/v1/sales-invoices/uid/{uid}/payments` | `SALES.INVOICE.VIEW` | `List<SalesInvoicePaymentDto>` |

`SalesInvoiceLineDto` (per `SalesInvoiceLineDto.java`) carries everything a line
needs on a printed receipt: `lineNo`, `productCode`, `productName`, `unitName`,
`quantity`, `qtyInBase`, `listPriceAmount`, `unitPriceAmount`, `priceOverridden`,
`lineDiscountAmount`, `lineDiscountPercent`, `vatStatus`, `vatRate`, `netAmount`,
`vatAmount`, `grossAmount`, `currency`.

`SalesInvoicePaymentDto` (per `SalesInvoicePaymentDto.java`) carries the tender:
`tenderType` (`CASH` for the default path, or `CASH`/`CARD`/`MOBILE_MONEY`/`CHEQUE`
when you sent a `tenders` list — **one row per tender**), `amount`, `currency`,
`changeAmount` (null on the default exact-gross path; populated on a CASH row if
you over-tendered in mode B — §6), `reference`, the structured instrument refs
(`cashBankAccountId`, `chequeId`, `mobileMoneyRef`, `cardRef`), `receivedAt`,
`receivedBy`.

> If you prefer to avoid two extra round-trips, build the printed line block from
> the lines you submitted plus the totals in the response header. The lines read
> endpoint is the authoritative source for the **server-snapshotted price and
> VAT** (which, per §2.2, may differ from the `unitPrice` you sent).

---

## 9. Receipt payload the POS prints

A counter receipt can be composed entirely from the **response DTO** plus the
client-known `tenderedAmount`; the lines read endpoint (§8) supplies the
authoritative priced line block. A typical receipt model:

```text
HEADER
  invoiceNumber          → data.invoiceNumber        (e.g. "INV-0042")
  finalisedAt            → data.finalisedAt           (sale timestamp)
  cashier / agent        → data.agentName
  customer               → data.customerName

LINES  (from GET .../uid/{uid}/lines, or from your submitted lines)
  productName            → SalesInvoiceLineDto.productName
  qty × unit             → quantity + unitName
  unit price             → unitPriceAmount            (server list price)
  line discount          → lineDiscountAmount
  line net / vat / gross → netAmount / vatAmount / grossAmount

TOTALS  (from the response header)
  net total              → data.netTotalAmount
  VAT block (per band)   → parse data.taxSummary  [{status,rate,net,vat}, ...]
  VAT total              → data.vatTotalAmount
  GROSS / amount due     → data.grossTotalAmount

TENDER  (client-computed; not on the header DTO)
  paid                   → request.tenderedAmount     (what the customer gave, default cash)
  change                 → tenderedAmount − data.grossTotalAmount
  (split / non-cash tenders, or ledger change → GET .../uid/{uid}/payments → tenderType/amount/changeAmount)
```

Notes:
- `taxSummary` is a JSON **string**; parse it to render the per-band VAT lines.
- The "amount due / charged" is always `grossTotalAmount` (the tender total covers
  exactly this in the default path, or ≥ this with a `tenders` list).
- For a **single cash** sale `changeAmount` on the ledger payment row is null and
  the printed change comes from `tenderedAmount − grossTotalAmount`. For a **split
  / non-cash** sale, read the per-tender rows via `/payments` (§8) to print each
  tender line and any CASH `changeAmount`.

---

## 10. Idempotency and retry safety

`POST /api/v1/pos/sales` now provides **opt-in server-side idempotency** via the
optional `Idempotency-Key` request header (ADR-0042 D-1, commit `f08fb08`,
backed by the `pos_sale_idempotency` table from `V70`). Full cross-page detail is
in `11-errors-offline-idempotency.md`; the POS-specific behaviour is:

- **Send the header to get the guarantee.** Supply an opaque `Idempotency-Key`
  (≤80 chars) and **reuse the same value** on every retry of *that* basket. The
  key is reserved *before* the invoice is created (reserve-before-process), inside
  the sale's single transaction.
- **Replay (same key, original already committed):** the server returns the
  **original** finalised `SalesInvoiceDto` — **no** duplicate invoice, stock
  issue, GL/AR posting, payment, or `SALE_FINALISED` event. Caveat: a replay still
  returns **HTTP 201** (the controller hardcodes `CREATED`), so detect a replay by
  matching the returned `uid` to the invoice you already hold — **not** by a
  200-vs-201 status.
- **Concurrent in-flight duplicate:** the duplicate `INSERT` **blocks** on the
  winner and then returns the winner's original invoice. In the narrow window
  where the marker row exists but `invoice_uid` is not yet stamped, the duplicate
  gets **409** `A POS sale with this Idempotency-Key is still in progress; retry
  shortly` — this specific 409 is **retryable**: resend the **same** key after a
  short delay.
- **Scope is per company.** The key need only be unique within the authenticated
  session's company; the same value in another company is independent.
- **A failed / rolled-back sale frees the key.** The reservation rolls back with
  the transaction, so the next send with the same key creates the sale normally.
- **Omitting the header => legacy non-idempotent behaviour.** A blind retry after
  a timeout then creates a **second** finalised invoice and a second
  `SALE_FINALISED` event (duplicate stock + GL/AR). `X-Request-Id` is a
  correlation/log id only; it is **not** used for dedup. **Always send the header**
  to be safe.

Client guidance:
- **Always send a stable `Idempotency-Key` per basket** (one value, reused across
  retries) so a UI double-tap, auto-retry, or post-timeout resend cannot
  double-post.
- Treat a timed-out POST as **unknown**, not failed — and just resend with the
  **same** key; the server either replays the original or completes the in-flight
  one (or returns the retryable 409 above).
- Without a key, reconcile manually before re-sending: list the session's recent
  invoices (e.g. `GET /api/v1/sales-invoices?companyId=…` filtered to your session
  / time window) and check whether a finalised invoice already matches the basket.

---

## 11. Notable errors

These follow the shared error table; the POS-specific triggers are:

| HTTP | Cause on this endpoint |
| --- | --- |
| **400** | Bean-validation: blank `sessionUid`/`currency`, null `customerId`, empty `lines`, a line with null `productId`/`unitId`, `quantity < 0.0001`, a `tenders` element with null `tenderType` or `amount < 0.0001`, or `notes` > 500 chars. (`agentId` and `unitPrice` are **no longer** validated — ADR-0042 D-4.) Also business `IllegalArgumentException` from `addLine` — e.g. **product has no price on a price list** (`BR-SALES-03`), product not sellable, or archived. |
| **401** | Missing/invalid/expired bearer token, or the user is no longer ACTIVE. |
| **403** | Caller lacks `POS.SALE.CREATE`, or cannot act in the session's company (`ScopeGuard.assertCanActIn`), or a rejected `X-Branch-Uid`. |
| **404** | Unknown `sessionUid` (`PosSession`), `customerId` (`Customer`), session company (`Company`), a line `productId` (`Product`) or `unitId` (`Unit`), or the freshly-created invoice uid. |
| **409** | Session not OPEN (`POS session <uid> is not OPEN.`); **tenders do not cover the gross total** (`Tenders (…) do not cover the gross total (…)` — mode B, §6); an **in-flight duplicate** of the same `Idempotency-Key` (`… still in progress; retry shortly` — **retryable**, §10); cash invoice not paid-in-full (should not happen on the default path since the server pays exactly gross); credit-limit exceeded without `SALES.CREDIT.OVERRIDE` (only if pointed at a `CREDIT_ACCOUNT` customer); optimistic-lock conflict on concurrent stock/state (retryable). |
| **415** | Wrong `Content-Type` — must be `application/json`. |
| **422** | `currency` not enabled for the session's company/branch scope (`CurrencyNotEnabledException`). |
| **500** | Unexpected server error (generic message; stack logged server-side). |

Error bodies always use the envelope: `{"data": null, "errors": ["…"],
"meta": null}` with user-safe messages.

---

## 12. Worked example (curl)

Ring a one-line cash sale on an open session. Replace ids/uids with real values.

```bash
# 1) Authenticate (see the auth section of the contract)
ACCESS_TOKEN=$(curl -s -X POST https://erp.example.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"jane.cashier","password":"••••••••"}' \
  | jq -r '.data.accessToken')

# 2) Ring the sale (single exact cash). Send a stable Idempotency-Key per basket
#    and reuse the SAME value on any retry — see §10.
curl -i -X POST https://erp.example.com/api/v1/pos/sales \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'X-Request-Id: pos-term-3-20260619-091542' \
  -H 'Idempotency-Key: pos-term-3-basket-8f2c1a' \
  -d '{
        "sessionUid": "pos_sess_7c1e9a2b",
        "customerId": 55,
        "currency": "TZS",
        "lines": [
          {
            "productId": 1201,
            "unitId": 9,
            "quantity": 3,
            "lineDiscountAmount": 0
          }
        ],
        "tenderedAmount": 5000.00,
        "notes": "Counter sale"
      }'
```

`agentId` and per-line `unitPrice` are omitted above on purpose — both are now
optional and ignored (ADR-0042 D-4); the server prices the line from its own
price list. Expected: **HTTP 201** with the `ApiResponse` envelope wrapping the
finalised `SalesInvoiceDto` (see §5.1). For the receipt: amount due =
`grossTotalAmount` (3,540 in the §4 example), change = `tenderedAmount (5000) −
grossTotalAmount (3540) = 1460`.

To **split** the same sale across card + cash, add a `tenders` list (each tender
is recorded in the request currency; the sum must be ≥ gross — §6):

```bash
curl -i -X POST https://erp.example.com/api/v1/pos/sales \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: pos-term-3-basket-9a3d2b' \
  -d '{
        "sessionUid": "pos_sess_7c1e9a2b",
        "customerId": 55,
        "currency": "TZS",
        "lines": [ { "productId": 1201, "unitId": 9, "quantity": 3 } ],
        "tenders": [
          { "tenderType": "CARD", "amount": 2000.00, "cardRef": "auth-7741" },
          { "tenderType": "CASH", "amount": 1540.00 }
        ]
      }'
```

```bash
# 3) (Optional) Pull the priced line block and the tender row for the printout
INV_UID=$(curl -s -X POST https://erp.example.com/api/v1/pos/sales ... | jq -r '.data.uid')

curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "https://erp.example.com/api/v1/sales-invoices/uid/${INV_UID}/lines"

curl -s -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "https://erp.example.com/api/v1/sales-invoices/uid/${INV_UID}/payments"
```

---

## 13. Quick reference

- **Ring a sale:** `POST /api/v1/pos/sales` · perm `POS.SALE.CREATE` ·
  body `PosSaleRequest` · optional `Idempotency-Key` header · → **201**
  `SalesInvoiceDto`.
- **Charged amount** = `grossTotalAmount`. Settled by one exact-gross **CASH**
  tender by default, or by an optional **`tenders`** list for **split / non-cash**
  (CASH/CARD/MOBILE_MONEY/CHEQUE) whose **sum must ≥ gross** (§6).
- **Price + VAT are server-authoritative** — re-derived from the **server price
  list / `tax_rates`**, **not** your `unitPrice` (accepted but ignored, ADR-0042
  D-4); only `lineDiscountAmount` of your discount fields is honoured. Trust the
  returned DTO totals.
- **Idempotency:** send a stable **`Idempotency-Key`** (≤80 chars, per company)
  and reuse it on retries — replay returns the original invoice (still HTTP 201;
  match by `uid`), in-flight duplicate gets a retryable **409** (§10,
  `11-errors-offline-idempotency.md`).
- **Reverse a mis-rung sale:** `POST /api/v1/pos/sales/uid/{uid}/reverse` · perm
  `POS.SALE.VOID` (whole-sale only; session must be OPEN) — see
  `10-returns-refunds.md`.
- **Invoice number** is `INV-####` (shared sales sequence); POS-ness is recorded
  by `origin=POS` + `posSessionId`, not the number.
- **Stock + GL + AR are eventual** (outbox poller); the 201 does not mean the
  ledger is posted.
- **Receipt detail** (lines / per-tender / change) via
  `GET /api/v1/sales-invoices/uid/{uid}/lines` and `/payments`.
