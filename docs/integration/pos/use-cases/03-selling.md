# POS Use Cases — Selling

End-to-end scenarios for ringing sales at the till on top of the single sale endpoint
(`POST /api/v1/pos/sales`), grounded in the verified API guide ([§03](../03-catalog-products-units.md)–[§12](../12-known-limitations.md)).

> **How to read this page.** Each use case below assumes you already have the API
> reference. The flows tell you *which* endpoints to call, *in what order*, with the
> *load-bearing* fields and the *realistic* failure paths. Where a desirable retail
> capability is **not supported today**, the use case is still listed and marked as such,
> with the [§12 Known Limitations](../12-known-limitations.md) reference and the closest
> workaround — so you plan around reality, not aspiration.
>
> **Three facts that shape every selling scenario (don't fight them):**
> 1. **The server is authoritative on price, VAT and totals.** The `unitPrice` you send on a
>    line is **dropped before pricing** — the server re-derives the unit price from the product's
>    company-scoped price list and VAT from `tax_rates`, so a buggy/malicious client cannot set
>    price=0 ([§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation)). The only discount field honoured is `lineDiscountAmount`. (Closed in
>    commit `f08fb08` / ADR-0042; `unitPrice` is now optional and informational.)
> 2. **Tender defaults to a single exact-gross CASH payment, but you can now split or pay non-cash.**
>    Send the optional `tenders` list to do card/mobile-money/cheque and/or split tender; omit it for
>    the legacy single exact-CASH behaviour ([§12 #3](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list)). Change is still a client-side computation from
>    `tenderedAmount`. (Multi-tender added in commit `f08fb08` / ADR-0042.)
> 3. **Sales can now be made idempotent, and a whole-sale POS refund/void exists.** Send an
>    `Idempotency-Key` header on every sale attempt for safe retries ([§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header)); reverse a whole
>    POS sale via `POST /pos/sales/uid/{uid}/reverse` while its session is still OPEN
>    ([§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint)). Both shipped in commit `f08fb08` / ADR-0042. If you omit the `Idempotency-Key`
>    header you get legacy non-idempotent behaviour, so still reconcile a header-less timed-out POST
>    before retrying. Partial/line-level refunds remain deferred.

**Permission cheat-sheet for a selling cashier** (check effective codes via
`GET /api/v1/auth/me`):

| To… | You need |
|---|---|
| Ring a sale | `POS.SALE.CREATE` |
| Search/pick a customer | `CUSTOMER.VIEW` |
| Create a customer on the fly | `CUSTOMER.MANAGE` |
| Search the catalog / scan barcodes | `PRODUCT.VIEW` (+ `UOM.VIEW` to resolve unit ids) |
| Preview price / VAT / currency | `PRICELIST.VIEW`, `SALES.PRICING.RULE.VIEW`, `TAXRATE.VIEW`, `CURRENCY.VIEW` |
| Check stock availability | `STOCK.VIEW` (+ `STOCK.LOCATION.VIEW`, batch/serial codes per [§06](../06-stock-availability.md)) |
| Reprint / look up a past receipt | `SALES.INVOICE.VIEW` |
| Pick a sales agent | `AGENT.VIEW` |

---

## UC-C1: Ring a simple cash walk-in sale (single line)

- **Actor:** cashier.
- **Goal:** sell one item to an anonymous walk-in and take cash, in one call.
- **Preconditions:**
  - Authenticated; caller holds `POS.SALE.CREATE`.
  - An **OPEN** POS session for this terminal (you have its `sessionUid` — see [§08](../08-sessions.md)).
  - A configured **walk-in / cash customer** exists and you know its numeric `customerId`
    (`customerKind = CASH_WALK_IN`; create one once per company — see UC-C3 / [§05](../05-customers.md)).
  - The product is on a **price list** for the company (else the line is rejected, see below).
  - You have the line's `productId` and `unitId` (numeric ids — [§03](../03-catalog-products-units.md)).

- **Main flow:**
  1. (Optional, advisory) Confirm stock — see UC-C6.
  2. Ring the sale: `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)), sending an `Idempotency-Key` header for
     safe retries (see below). Minimal body:
     ```http
     POST /api/v1/pos/sales
     Idempotency-Key: a7f3c1e9-2b8d-4f10-9a6c-basket-0042
     Content-Type: application/json
     ```
     ```json
     {
       "sessionUid": "pos_sess_7c1e9a2b",
       "customerId": 55,
       "agentId": 7,
       "currency": "TZS",
       "lines": [
         { "productId": 1201, "unitId": 9, "quantity": 1,
           "lineDiscountAmount": 0 }
       ],
       "tenderedAmount": 5000.00
     }
     ```
     - `currency` must be enabled for the scope (default it to `resolvedDefault` from
       `GET /api/v1/fx/currencies/enabled` — [§04 §5.1](../04-pricing-tax-currency.md)).
     - **`Idempotency-Key` (HTTP header, optional, ≤80 chars).** Send one opaque value per basket /
       sale attempt to make the POST safely retryable. A replay with the same key returns the
       **original** finalised `SalesInvoiceDto` — no duplicate invoice, stock issue, GL/AR posting,
       payment or outbox event. The key is scoped per company. **Always send it** to get the
       guarantee; omitting it = legacy non-idempotent behaviour (a blind retry duplicates the sale).
       See the retry semantics under *Alternate / exception flows* and UC-C8 ([§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header)).
     - `unitPrice` is now **optional and informational** — it is dropped before any pricing code, so
       the server always re-derives the unit price from the product's price list ([§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation)). Send it
       only if you want it for your own receipt display; treat the returned totals as authoritative.
     - `tenderedAmount` is a **receipt-printing aid only**; it is not stored and does not set
       the payment amount ([§09 §6](../09-sales-payments-receipts.md)). For split / non-cash payment, send the optional
       `tenders` list instead (UC-C4a; [§12 #3](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list)).
  3. On **201** read the finalised `SalesInvoiceDto` from `data`: `invoiceNumber` (`INV-####`),
     `grossTotalAmount` (the amount charged), `netTotalAmount`, `vatTotalAmount`, `taxSummary`,
     `finalisedAt`, `uid`. Print the receipt (UC-C7).

- **Alternate / exception flows:**
  - Session not OPEN → **409** `POS session <uid> is not OPEN.` (open/resume it — [§08](../08-sessions.md)).
  - Unknown `sessionUid` / `customerId` / line `productId` / `unitId` → **404**.
  - Product has no price on any price list → **400** `Product has no price on any price list (BR-SALES-03)`.
  - `currency` not enabled for the scope → **422** (`CurrencyNotEnabledException`).
  - Missing/blank required field, `quantity < 0.0001`, empty `lines` → **400**.
  - **Idempotency replay** (same `Idempotency-Key`, original already committed) → **201** with the
    **original** `SalesInvoiceDto` (note: the controller hardcodes 201, so identify a replay by
    matching the returned `uid` to one you already hold — not by a 200-vs-201 distinction).
  - **Idempotency in-flight** (same key, the winning request hasn't stamped its invoice yet) → **409**
    `A POS sale with this Idempotency-Key is still in progress; retry shortly.` This 409 is
    **retryable** — resend the **same** key after a short delay. (A failed/rolled-back sale frees the
    key, so the next send with that key creates the sale normally.)
  - Lacks `POS.SALE.CREATE`, or cannot act in the session's company → **403**.
  - Wrong `Content-Type` (must be `application/json`) → **415**.

- **Outcome:** a **FINALISED**, fully-paid (single CASH tender = gross, unless you sent `tenders`)
  invoice with `origin = POS` and `posSessionId` set, and an allocated `INV-####` number —
  **synchronously** committed. **Eventually** (outbox poller, ~1s): stock is decremented, and the
  sales GL + AR journals post. Do **not** assume the ledger is posted at 201 time ([§09 §7](../09-sales-payments-receipts.md)).

- **Notes & limitations:** defaults to single exact-gross CASH tender, with optional split/non-cash
  via `tenders` ([§12 #3](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list)); idempotency is available via the `Idempotency-Key` header ([§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header)) — send
  it on every attempt, but if you ever omit it, reconcile (UC-C8 / [§11](../11-errors-offline-idempotency.md)) before resending; a
  whole-sale void/refund exists while the session is OPEN ([§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint)). Tip: derive the
  `Idempotency-Key` deterministically from the basket so a double-tap reuses the same key and the
  second POST replays rather than duplicates.

---

## UC-C2: Multi-line sale

- **Actor:** cashier.
- **Goal:** ring several items in one basket and take cash once.
- **Preconditions:** as UC-C1; every line's product is sellable and priced; you have each
  `productId`/`unitId`.

- **Main flow:**
  1. Build the basket; add one entry to `lines[]` per cart line.
  2. `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)) with `lines` ≥ 1:
     ```json
     "lines": [
       { "productId": 1201, "unitId": 9,  "quantity": 3, "lineDiscountAmount": 0 },
       { "productId": 1330, "unitId": 9,  "quantity": 2, "lineDiscountAmount": 0 },
       { "productId": 1408, "unitId": 14, "quantity": 1, "lineDiscountAmount": 0 }
     ]
     ```
     - The server prices and VATs **each** line from the price list / `tax_rates`, recomputes
       header totals after every line, and groups VAT by `(vatStatus, rate)` band into
       `taxSummary` ([§04 §6](../04-pricing-tax-currency.md), [§09 §4](../09-sales-payments-receipts.md)).
     - For carton/case selling, set `unitId` to a **bulk-pack** unit id
       (`GET /api/v1/products/uid/{uid}/bulk-packs` — [§03 §3](../03-catalog-products-units.md)); the server converts to base via `factorToBase`.
  3. On **201**, the tender(s) cover the **summed** `grossTotalAmount` — a single CASH payment by
     default, or your `tenders` list. Print the receipt; for the priced per-line block use
     `GET /api/v1/sales-invoices/uid/{uid}/lines` (UC-C7).

- **Alternate / exception flows:**
  - The call is **all-or-nothing** (one `@Transactional`): if **any** line fails (unknown
    product/unit → **404**; unpriced product → **400 BR-SALES-03**), the **whole sale** is
    rejected and nothing is committed. Re-send the corrected basket.
  - Empty `lines` → **400** (`@NotEmpty`). Otherwise as UC-C1.

- **Outcome:** one finalised multi-line invoice, the tender(s) covering the total (a single CASH
  tender by default); async stock issue per line (BOM/composed products explode to stockable
  components — [§09 §7](../09-sales-payments-receipts.md)) + GL/AR.

- **Notes & limitations:** there is no "add line to an existing POS sale" call — the basket is
  submitted whole. No partial commit. Same tender (default-CASH, optional split/non-cash) /
  idempotency-via-header / whole-sale-void capabilities as UC-C1.

---

## UC-C3: Sale to a registered customer (look up, or create-on-the-fly)

- **Actor:** cashier (create-on-the-fly needs supervisor-level grant — `CUSTOMER.MANAGE`).
- **Goal:** attribute the sale to a named customer — found by search, or created at the till.
- **Preconditions:** `POS.SALE.CREATE` + `CUSTOMER.VIEW`; **plus `CUSTOMER.MANAGE`** only if
  creating. You know the active `companyId`.

- **Main flow (look up an existing customer):**
  1. `GET /api/v1/customers?companyId={id}&q={text}` ([§05](../05-customers.md)). `q` is an OR over
     `displayName` (case-insensitive *contains*), `tin`/`phone`/`code` (*exact*). Blank `q`
     lists all (paged).
  2. Pick the row where `status == "ACTIVE"` (archived customers still appear in results) and
     take its **`id`** (the wire value is a JSON string, e.g. `"1024"`).
  3. `POST /api/v1/pos/sales` with `customerId` = that `id` (UC-C1/UC-C2). `customerName` on the
     201 response is the bill-to shown on the receipt.

- **Main flow (create on the fly):**
  1. `POST /api/v1/customers` ([§05](../05-customers.md#create-a-customer-on-the-fly)) with the four required fields — fastest walk-in form:
     ```json
     { "companyId": 1, "partyType": "INDIVIDUAL",
       "displayName": "Jane Walk-in", "customerKind": "CASH_WALK_IN", "phone": "0712345678" }
     ```
     Prefer `partyType=INDIVIDUAL` + `customerKind=CASH_WALK_IN` to avoid the BUSINESS
     `tin` requirement (`BR-PARTY-04`).
  2. On **201**, take `data.id` and use it as `customerId` on the very next sale.

- **Alternate / exception flows:**
  - Lacks `CUSTOMER.MANAGE` → **403** (generic message; hide the "New customer" button when the
    code is absent — [§05](../05-customers.md)).
  - BUSINESS customer without `tin` → **400** `A business customer must have a TIN (BR-PARTY-04)`.
  - Selling to a `CREDIT_ACCOUNT` customer is allowed but **still rung as a fully-paid CASH sale**
    (POS is not an on-account channel). If that customer is over their credit limit, `finalise`
    throws on the **sale** call → **409** unless the cashier holds `SALES.CREDIT.OVERRIDE`
    ([§05 — credit interaction](../05-customers.md#how-customer-credit-interacts-with-pos)).
  - `customer-create` has **no idempotency** — a retried create after a timeout makes a *second*
    record (no unique name/phone constraint). On retry, re-run search by phone first and reuse a
    match ([§05](../05-customers.md)).

- **Outcome:** the invoice's `customerId`/`customerName` reflect the chosen/created customer; for
  a `CASH_WALK_IN` the sale finalises cleanly because POS pays the full gross.

- **Notes & limitations:** a POS client **cannot read credit status** (`creditStatus`/`manualHold`
  are not on `CustomerDto`); treat the finalise **409** as the authoritative credit gate and show
  its (user-safe) message. The credit check is on the **synchronous** sale path, so you learn of a
  block immediately, not after async posting.

---

## UC-C4: Apply a line discount

- **Actor:** cashier.
- **Goal:** reduce a line by an absolute amount before VAT.
- **Preconditions:** as UC-C1. You know the displayed price so you can compute a sensible
  discount.

- **Main flow:**
  1. Compute the discount as an **absolute money amount** in the document currency.
  2. Put it on the line as `lineDiscountAmount`:
     ```json
     { "productId": 1201, "unitId": 9, "quantity": 3, "lineDiscountAmount": 500 }
     ```
  3. `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)). The server applies the discount **before VAT**:
     `rawNet = round(listPrice × qty) − lineDiscountAmount` (floored at 0), then
     `vat = round(discountedNet × vatRate)` ([§04 §6](../04-pricing-tax-currency.md), [§09 §4](../09-sales-payments-receipts.md)). The CASH tender = the discounted gross.

- **Alternate / exception flows:**
  - A discount larger than the line's gross does not go negative — `rawNet` floors at 0
    (the line nets to zero, VAT to zero).
  - Everything else as UC-C1 (unknown ids → 404; unpriced product → 400; etc.).

- **Outcome:** the finalised invoice carries the discounted net/VAT/gross; the line read DTO shows
  `lineDiscountAmount`, `netAmount`, `vatAmount`, `grossAmount` ([§09 §8](../09-sales-payments-receipts.md)).

- **Notes & limitations:**
  - **`unitPrice` is dropped before pricing — you cannot override the price** ([§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation)). The server
    always re-derives the unit price from the price list, so `lineDiscountAmount` is the **only** way
    to reduce a line at the POS. Express any negotiated/tier/customer/promotion price as a discount,
    because the POS path does **not** auto-apply tiers/customer-prices/promotions — those endpoints
    are *advisory display data* ([§04 §3](../04-pricing-tax-currency.md)).
  - **`lineDiscountPercent` is not reachable** from the POS path (the orchestrator forwards only
    `lineDiscountAmount` and passes percent as `null`). Convert a percentage to an absolute amount
    client-side before sending ([§09 §2.2](../09-sales-payments-receipts.md)).
  - There is **no document-level (whole-basket) discount** on the POS path; distribute it across
    lines as per-line `lineDiscountAmount` yourself.
  - **100%-off / free lines.** A `lineDiscountAmount` equal to (or above) the line's gross nets the
    line to **zero** (`rawNet` floors at 0, VAT to 0). The server still resolves a positive list
    price first, so a single-line fully-discounted sale yields a **0 gross** and a **CASH tender of
    0**. `PosSaleServiceImpl` has **no explicit zero-gross guard**, so if you rely on free /
    100%-off sales, confirm the finalise behaviour (and the resulting 0-value invoice/tender) in
    your own environment before shipping.

---

## UC-C4a: Take a split or non-cash payment (multi-tender)

- **Actor:** cashier.
- **Goal:** settle a sale with more than one payment method, or a single non-cash method
  (card / mobile money / cheque), instead of the default single exact-CASH tender.
- **Preconditions:** as UC-C1. (Shipped in commit `f08fb08` / ADR-0042; instrument refs per
  ADR-0041.)

- **Main flow:**
  1. Build the basket exactly as UC-C1/UC-C2.
  2. Add the optional **`tenders`** array (`List<PosTender>`). Each tender carries `tenderType`,
     `amount`, an optional `reference`, plus the relevant instrument ref: `cashBankAccountId`,
     `chequeId`, `mobileMoneyRef`, `cardRef`. Example — split cash + card:
     ```json
     {
       "sessionUid": "pos_sess_7c1e9a2b",
       "customerId": 55,
       "agentId": 7,
       "currency": "TZS",
       "lines": [ { "productId": 1201, "unitId": 9, "quantity": 1, "lineDiscountAmount": 0 } ],
       "tenders": [
         { "tenderType": "CASH", "amount": 2000.00, "cashBankAccountId": 31 },
         { "tenderType": "CARD", "amount": 3000.00, "cardRef": "auth-7741" }
       ]
     }
     ```
  3. `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)) (send an `Idempotency-Key` as in UC-C1). The service loops
     every tender into a payment, forcing each to the request `currency`. The **sum of tenders must
     be ≥ the gross total** (validated). On **201** the invoice is finalised and fully paid.

- **Alternate / exception flows:**
  - Tender sum **< gross total** → **400** (validation).
  - **Omitting `tenders`** keeps the legacy behaviour: one exact-gross **CASH** payment (UC-C1).
  - Otherwise as UC-C1 (unknown ids → 404; unpriced product → 400; idempotency 201/409 cases; etc.).

- **Outcome:** a finalised invoice settled by the supplied tenders; `GET /sales-invoices/uid/{uid}/payments`
  returns one row per tender.

- **Notes & limitations:** all tenders are forced to the document `currency`. Over-tender (sum >
  gross) is accepted; compute and print change client-side as usual — the POS path does not write a
  ledger `changeAmount`.

---

## UC-C4b: Reverse / refund a whole POS sale

- **Actor:** cashier / shift supervisor (needs `POS.SALE.VOID`, auto-granted to `ORG_ADMIN`).
- **Goal:** fully reverse (refund) a POS sale rung in error or returned in full, while its till is
  still open. (Shipped in commit `f08fb08` / ADR-0042.)
- **Preconditions:**
  - Caller holds **`POS.SALE.VOID`** (scoped on the invoice `uid`).
  - The invoice is **POS-origin** (has a `posSessionId`) and **FINALISED**.
  - Its originating **session is still OPEN** (not CLOSED/RECONCILED).

- **Main flow:**
  1. `POST /api/v1/pos/sales/uid/{uid}/reverse` with body `{ "reason": "<text>" }` (`reason`
     is `@NotBlank`) → **204 No Content**.
  2. The reversal acts as a **full refund**: it reverses revenue + VAT + cash (POS cash sale, no AR
     leg), reverses the stock issue + restores inventory valuation, and posts DR Inventory / CR COGS.
  3. The reversed sale **automatically drops out of the session's expected cash (drawer)** at the
     X/Z-read — no separate payout entry is needed.

- **Alternate / exception flows:**
  - Not POS-origin, not FINALISED, or the session is already CLOSED/RECONCILED → **409**. A sale on a
    closed/reconciled session must be handled via the **back-office invoice void** on `/sales-invoices`,
    where the cash difference is a reconciled-variance matter, not a till refund.
  - Lacks `POS.SALE.VOID` (on this invoice's scope) → **403**.
  - Unknown `uid` → **404**; blank `reason` → **400**.

- **Outcome:** the sale is fully reversed; stock and the ledger are restored and the till no longer
  expects that cash.

- **Notes & limitations:** **whole-sale only** — **partial / line-level refunds are NOT supported**
  (explicitly deferred by ADR-0042; they need a credit-note-by-line path). For a partial return,
  use the back-office returns/credit-note flow ([§10](../10-returns-refunds.md)).

---

## UC-C5: Attribute a sale to a sales agent

- **Actor:** cashier / shift supervisor.
- **Goal:** record which sales agent/clerk a sale belongs to (for commission/attribution).
- **Preconditions:** as UC-C1. To *pick* an agent from a list, the caller needs `AGENT.VIEW`.

- **Main flow:**
  1. (Optional) Resolve the agent: `GET /api/v1/agents?companyId={id}&q={text}` →
     `AgentDto` (or `GET /api/v1/agents/uid/{uid}`), perm `AGENT.VIEW`. Take the agent's numeric
     `id`.
  2. `POST /api/v1/pos/sales` with `agentId` = that id (it is `@NotNull` — you must send a value).

- **Alternate / exception flows:**
  - `agentId` is required by validation; omitting/nulling it → **400**.

- **Outcome:** the sale finalises as normal.

- **Notes & limitations — read this carefully (behaviour ≠ field name):**
  - **The `agentId` you send is currently NOT applied to the invoice's agent.** Although
    `PosSaleRequest.agentId` is required, `PosSaleServiceImpl` builds the invoice with
    `agentUid = null`, and the sales-invoice service then **auto-defaults the agent to the
    logged-in user**. So the invoice's `agentId`/`agentName` reflect the **cashier who is
    authenticated**, not the `agentId` in your request. (This mirrors the `unitPrice`-ignored
    pattern — a required field that the POS path does not forward.)
  - **Practical consequence:** to attribute a sale to a specific agent today, that agent must be
    the **authenticated user** ringing the sale. There is no POS field that overrides the agent on
    the finalised invoice. If per-line/commission attribution to an arbitrary agent is required,
    that is **not supported via the POS endpoint** today; the closest path is the back-office
    `/api/v1/sales-invoices` draft flow (whose `CreateSalesInvoiceRequest` accepts an `agentUid`)
    — a different integration from the POS quick-sale.
  - Still send a valid `agentId` to satisfy validation, and use it for your own client-side
    bookkeeping if useful — but do not rely on it round-tripping onto the invoice. Treat the
    returned `SalesInvoiceDto.agentId`/`agentName` as the source of truth for what was recorded.

---

## UC-C6: Check stock availability before / while ringing (advisory)

- **Actor:** cashier.
- **Goal:** show "in stock / out of stock" and quantity before committing a line — advisory only.
- **Preconditions:** `STOCK.VIEW` (and `STOCK.LOCATION.VIEW` for the per-location split; batch/
  serial reads need the batch/serial codes — note the seed/`@PreAuthorize` mismatch in
  [§06](../06-stock-availability.md)). The terminal's token must be scoped to a usable branch
  (`hasBranch=true`), else the branch on-hand feed returns **409 Conflict**.

- **Main flow:**
  1. At shift start: `GET /api/v1/stock-locations` ([§06 §4](../06-stock-availability.md)) → cache sellable locations
     (`sellable=true`, `status=ACTIVE`); `GET /api/v1/stock/on-hand` ([§06 §1](../06-stock-availability.md)) → cache branch on-hand.
  2. Per product as needed: `GET /api/v1/stock/on-hand/by-product/uid/{productUid}?companyId={id}`
     ([§06 §3](../06-stock-availability.md)) for the per-location split. A product is sellable from this branch if a row
     has `quantity > 0`; `negative=true` flags an already-oversold branch.
  3. For lot/serial products: `GET /api/v1/stock-batches` (FEFO by `expiryDate`) or
     `GET /api/v1/stock-serials?...&status=IN_STOCK` ([§06 §5–6](../06-stock-availability.md)) to pick/scan the exact unit.
  4. Ring the sale (UC-C1/UC-C2) regardless — the stock check is advisory.

- **Alternate / exception flows:**
  - Token without active branch → **409 Conflict** (`No active company/branch in request context.` — `IllegalStateException`, mapped to 409 by the global handler).
  - Out-of-scope `companyId` → **403**; missing required `companyId`/`branchId`/`locationId`/
    `productId` → **400**; unknown product uid on `by-product` → **404**.

- **Outcome:** you can display availability and choose a lot/serial — but nothing is reserved.

- **Notes & limitations:**
  - **On-hand reads are point-in-time and NOT reserved** — two terminals can both see the last
    unit. The POS endpoint does **not** hard-block overselling; `allowNegative` is a
    location/upstream policy. Build your own client guard if you need a hard reservation
    ([§06 — how this feeds the sale](../06-stock-availability.md#how-this-feeds-the-sale)).
  - **Stock decrement is asynchronous** (outbox poller after 201). Live counts will lag your sale
    by ~1s+; re-query after a short delay if you display them.
  - **Selecting a specific lot/serial at the POS is advisory only.** `POST /api/v1/pos/sales` takes
    no batch/serial parameter — the server consumes lots FEFO and assigns serials internally. You
    cannot direct *which* lot/serial is issued through the POS endpoint.

---

## UC-C7: Print a receipt from the 201 response

- **Actor:** cashier.
- **Goal:** produce a printed receipt immediately after the sale.
- **Preconditions:** you hold the **201** `SalesInvoiceDto` from `POST /api/v1/pos/sales`
  ([§09](../09-sales-payments-receipts.md)). Optional line/payment enrichment needs `SALES.INVOICE.VIEW`.

- **Main flow:**
  1. From the response header, render:
     - **header** → `invoiceNumber`, `finalisedAt`, `agentName`, `customerName`;
     - **totals** → `netTotalAmount`, `vatTotalAmount`, `grossTotalAmount` (= amount due/charged),
       and the per-band VAT block by parsing `taxSummary` (a JSON **string** of
       `[{status, rate, net, vat}, …]`);
     - **tender (client-side)** → paid = `tenderedAmount` you sent; `change = tenderedAmount −
       grossTotalAmount` ([§09 §6, §9](../09-sales-payments-receipts.md)).
  2. (Optional) For the authoritative **priced per-line block**, call
     `GET /api/v1/sales-invoices/uid/{uid}/lines` ([§09 §8](../09-sales-payments-receipts.md)) → `SalesInvoiceLineDto[]`
     (`productName`, `quantity`, `unitName`, `unitPriceAmount` = server list price,
     `lineDiscountAmount`, `netAmount`/`vatAmount`/`grossAmount`).
  3. (Optional) For the ledger tender row, `GET /api/v1/sales-invoices/uid/{uid}/payments` →
     `SalesInvoicePaymentDto[]` (`tenderType=CASH`, `amount`, `changeAmount` is **null** on the
     POS path).

- **Alternate / exception flows:** the read endpoints return **403** without `SALES.INVOICE.VIEW`
  and **404** for an unknown uid. If you skip them you can still print from the header + your
  submitted lines.

- **Outcome:** a complete receipt. To avoid two extra round-trips, you may build the line block
  from your submitted basket — but the **lines** endpoint is the authoritative source for the
  server-snapshotted price/VAT (which may differ from your `unitPrice`, per [§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation)).

- **Notes & limitations:**
  - The POS **invoice number is `INV-####`** (the shared sales sequence) — there is **no separate
    POS receipt-number series**; POS-ness is recorded by `origin=POS`/`posSessionId`, not the
    number ([§09 §5](../09-sales-payments-receipts.md)).
  - The header DTO does **not** carry the tender amount or change — change is your client-side
    `tenderedAmount − grossTotalAmount`; the ledger `changeAmount` is null for POS.
  - **Gift receipt (no prices) is a client-side rendering choice.** The 201 `SalesInvoiceDto` (and
    the lines endpoint) always carries the prices/VAT/totals; to print a gift receipt simply **omit
    the price columns** (and totals) when you render — there is no separate "gift receipt" mode on
    the API.
  - **A whole-sale POS refund/void now exists** (`POST /pos/sales/uid/{uid}/reverse` while the
    session is OPEN — [§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint)). The reversal returns 204 and reverses revenue/VAT/cash + restores
    stock; it does not stamp the original invoice with a refunded flag the receipt can render, so a
    reprint still shows the original sale. If you reverse a sale, render the refund from your own
    record of the `/reverse` call rather than expecting a reversed state on the invoice DTO.

---

## UC-C8: Reprint / look up a past receipt

- **Actor:** cashier / shift supervisor / store manager.
- **Goal:** find an earlier sale and reprint its receipt; or reconcile after an ambiguous POST.
- **Preconditions:** caller holds `SALES.INVOICE.VIEW`. You know the active `companyId`, and ideally
  the invoice `uid` or `invoiceNumber`.

- **Main flow (you have the uid):**
  1. `GET /api/v1/sales-invoices/uid/{uid}` ([§09 §8](../09-sales-payments-receipts.md)) → the same `SalesInvoiceDto` header.
  2. `GET /api/v1/sales-invoices/uid/{uid}/lines` and `/payments` for the full detail.
  3. Re-render exactly as UC-C7. (Change cannot be reprinted from the ledger — `changeAmount` is
     null for POS; if you need it on a reprint, store `tenderedAmount` client-side at sale time.)

- **Main flow (search by number / text):**
  1. `GET /api/v1/sales-invoices?companyId={id}&q={invoiceNumber-or-text}` (perm
     `SALES.INVOICE.VIEW`, paged; optional `status` filter, e.g. `FINALISED`). Take the matching
     row's `uid`, then continue as above.

- **Reconciliation use (after a timed-out sale):**
  1. **Preferred:** simply re-send the original POST with the **same `Idempotency-Key`** — a replay
     returns the original invoice and creates nothing new ([§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header), UC-C1). If you get the
     retryable **409** "still in progress", wait briefly and resend the same key.
  2. **Fallback (only if you omitted the header):** list the recent invoices for the
     company/time-window with the search above and check whether a **FINALISED** invoice already
     matches the basket **before** retrying the POST ([§09 §10](../09-sales-payments-receipts.md), [§11](../11-errors-offline-idempotency.md)) — the legacy
     non-idempotent path duplicates a blind retry.

- **Alternate / exception flows:** **403** without `SALES.INVOICE.VIEW` or for an invoice outside
  your scope; **404** for an unknown uid; **400** if `companyId` is missing on the list call.

- **Outcome:** the historical sale is re-displayed/reprinted; or you confirm whether a retry would
  duplicate a sale.

- **Notes & limitations:** there is **no POS-specific** "list my session's sales" endpoint — use
  the shared `/api/v1/sales-invoices` list (filter client-side; POS sales carry `origin=POS` but
  the list does not expose an `origin` query filter). The list does not return lines/payments —
  fetch those per uid. Reprinting itself does **not** reverse or modify anything; to actually reverse
  a POS sale use the whole-sale `POST /pos/sales/uid/{uid}/reverse` while its session is OPEN
  ([§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint)).

---

## UC-C9: Suspend / park & recall a sale — not supported at POS today

- **Actor:** cashier.
- **Goal:** put an in-progress basket on hold (e.g. the customer forgot their wallet, or a price
  check is needed), serve the next customer, then recall and finalise the held basket later.
- **Preconditions:** as UC-C1 (an OPEN session, `POS.SALE.CREATE`).

- **Main flow:** **none — there is no draft / hold / park concept on the POS API.**
  `POST /api/v1/pos/sales` is a **one-shot finalise**: it allocates a number, prices, takes the
  CASH tender and commits the invoice in a single call. `PosSaleController` exposes **only** the
  create — there is no "save draft", "hold", "park" or "recall" endpoint.

- **Alternate / exception flows:** n/a (no server path exists).

- **Outcome (workaround):** the **client** holds the in-progress basket in its **own local state**
  (the cart of `lines[]`, the chosen `customerId`/`agentId`/`currency`, and the running
  client-side total) and only calls `POST /api/v1/pos/sales` when the cashier **finalises**.
  Nothing is persisted server-side until that POST, so a "parked" sale lives entirely in the
  cashier app; recall = re-load the saved local basket and submit it. (Because the basket is never
  on the server, a lost/closed client app loses the parked sale.)

- **Notes & limitations:**
  - This is **absent from the API** — there is simply no park/draft endpoint to call. Treat it like
    the other "not supported at POS today" rows. (Unlike park/draft, whole-sale void/refund is now
    shipped — [§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint).)
  - The recommended **server fix** would be a draft-sale / hold endpoint (persist an un-finalised
    basket and recall it by uid), distinct from the one-shot `POST /pos/sales` finalise.
  - Do **not** try to emulate "hold" by posting a sale and reversing it — even though a whole-sale
    `POST /pos/sales/uid/{uid}/reverse` now exists ([§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint)), it is a refund, not a
    park mechanism, and it perturbs stock/cash/GL; hold the basket in client state instead.

---

## UC-C10: Cashier access token expires mid-shift

- **Actor:** cashier.
- **Goal:** keep ringing without losing the in-progress basket when the short-lived access token
  expires partway through a shift (or mid-ring).
- **Preconditions:** as UC-C1; you stored both the access token **and** the refresh token at login
  ([§01](../01-authentication-and-permissions.md)).

- **Main flow:**
  1. Access tokens are **short-lived** (see [§01](../01-authentication-and-permissions.md) for the
     exact TTL); a `401` mid-ring almost always means *expired*, not *deauthorised* — it is a
     **refresh**, not a re-login, situation.
  2. On any `401`, call `POST /api/v1/auth/refresh`
     ([§01 §4](../01-authentication-and-permissions.md#4-refresh--post-apiv1authrefresh)) with the
     **stored refresh token**.
  3. The refresh token is **single-use / rotated** — store the **new** refresh token from the
     response (and the new access token) and discard the old one (rotation rule in
     [§01 §4](../01-authentication-and-permissions.md#critical-refresh-semantics-single-use-rotation)).
  4. **Retry the original request** with the new access token. Keep the in-progress basket in client
     state across the refresh so nothing is lost (cf. UC-C9 — the basket only ever lives client-side
     until you finalise).

- **Alternate / exception flows:**
  - Refresh itself returns `401` (refresh token expired/revoked, or the user is no longer ACTIVE) →
    this **is** a re-login: send the cashier back through `POST /api/v1/auth/login`
    ([§01](../01-authentication-and-permissions.md)); the held basket survives if you kept it in
    local state.
  - Retrying a **`POST /pos/sales`** that 401'd is safe **if you sent an `Idempotency-Key`**: resend
    with the new access token **and the same key** — a replay returns the original invoice and creates
    nothing new ([§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header)). If you omitted the header, the sale may already have committed before the
    token check, so reconcile via the invoice list (UC-C8) before resending, exactly as for a network
    drop.

- **Outcome:** the cashier rings continuously across token expiry; the only user-visible effect of a
  well-built client is a momentary pause while the token refreshes.

- **Notes & limitations:** do **not** re-derive the token lifetimes or the rotation rule here — they
  are owned by [§01](../01-authentication-and-permissions.md) (access-token TTL, refresh-token TTL,
  single-use rotation). Refresh **proactively** before expiry where you can (the login response
  carries `accessTokenExpiresAt` in epoch seconds — [§01](../01-authentication-and-permissions.md))
  so a `401` mid-sale is the exception, not the norm.

---

## Recently shipped (commit `f08fb08` / ADR-0042)

Four previously-catalogued gaps are now **closed** on the POS endpoint. They are no longer in the
"Not supported" table below; the use cases above show how to use them:

| Capability | How | Use case / reference |
|---|---|---|
| **Idempotent / safe-retry sale** | Send the optional `Idempotency-Key` HTTP header (≤80 chars) per sale attempt; a replay returns the original invoice (201) and creates nothing new; in-flight duplicate gets a retryable 409. | UC-C1, UC-C8 ([§12 #1](../12-known-limitations.md#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header)) |
| **Whole-sale void / refund** | `POST /pos/sales/uid/{uid}/reverse` (`POS.SALE.VOID`) while the session is still OPEN. | UC-C4b ([§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint)) |
| **Split / non-cash tender (card, mobile money, cheque)** | Send the optional `tenders` list; sum must be ≥ gross. | UC-C4a ([§12 #3](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list)) |
| **Server-authoritative pricing** | `unitPrice` is now optional and dropped before pricing; the server re-derives price + VAT, so a client cannot set price=0. | UC-C1, UC-C4 ([§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation)) |

## Not supported today (selling scenarios you will be asked for)

These are real retail needs that the **POS endpoint still does not provide**. They are listed so you
can plan around them; each cites the reference and the closest workaround.

| Scenario | Status | Why / reference | Closest workaround today |
|---|---|---|---|
| **Partial / line-level refund** | **Not supported** | Reversal is **whole-sale only**; per-line refunds are explicitly **deferred** by ADR-0042 (they need a credit-note-by-line path) ([§12 #2](../12-known-limitations.md#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint), [§10](../10-returns-refunds.md)). | Reverse the whole sale (UC-C4b) and re-ring the kept lines; or use the back-office returns/credit-note flow. |
| **Over-tender with change recorded at the ledger** | **Not supported** | No `changeAmount` is written on the POS path even with multi-tender ([§09 §6](../09-sales-payments-receipts.md), [§12 #3](../12-known-limitations.md#3-multi-tender--non-cash-payments--closed-tenders-list)). | Compute and print change client-side: `tenderedAmount − grossTotalAmount`. |
| **Manual price override at the till** | **Not supported** | `unitPrice` is dropped before pricing (server is authoritative); no POS price-override permission/path ([§12 #4](../12-known-limitations.md#4-server-authoritative-pricing--by-design-not-a-limitation)). | Express the reduction as `lineDiscountAmount` (UC-C4). |
| **Auto-applied tiers / customer prices / promotions on the sale** | **Not supported** | The POS path prices from the product's price-list row only; pricing rules are advisory display data ([§04 §3](../04-pricing-tax-currency.md)). | Read the rule, then reflect the negotiated price as `lineDiscountAmount`. |
| **Sell "on account" to a credit customer** | **Not supported** | POS always rings a fully-paid sale regardless of `customerKind` ([§05](../05-customers.md#how-customer-credit-interacts-with-pos)). | Use the back-office `/api/v1/sales-invoices` credit flow. |
| **Attribute the invoice to an arbitrary agent** | **Not supported** | POS forwards `agentUid=null`; the invoice agent defaults to the logged-in user (UC-C5). | Have the target agent be the authenticated user; or use the back-office draft flow (`CreateSalesInvoiceRequest.agentUid`). |
