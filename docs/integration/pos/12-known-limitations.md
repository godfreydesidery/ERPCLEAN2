# Known Limitations & API Gaps

This page consolidates the **current limitations of the POS API** that an integrator must design
around, and that the product team should weigh for a production rollout. Each item is grounded in
the actual backend code (file references below). These are **API/behaviour gaps, not documentation
gaps** — the rest of this guide describes the API as it really is; this page calls out where that
reality falls short of what a full retail POS typically needs.

> Scope note: nothing here is a blocker for a *controlled* cash-only pilot. Items 1 and 2 are the
> ones to close before any unattended or high-volume production use.

| # | Gap | Severity | Affects |
|---|-----|----------|---------|
| 1 | No idempotency / dedup on `POST /pos/sales` | **High** | data integrity (double-posting) |
| 2 | No POS reversal / refund / void endpoint | **High** | returns, mistake correction |
| 3 | Single exact **cash** tender only | **Medium–High** | payments (card, mobile money, split, change) |
| 4 | `unitPrice` is required but ignored; no manual price override | **Medium** | API clarity, price overrides |

---

## 1. No idempotency on sale creation — duplicate-posting risk

**Current behaviour.** `POST /api/v1/pos/sales` accepts no idempotency key. `PosSaleRequest`
(`com.erp.modules.sales.domain.dto.PosSaleRequest`) has fields `sessionUid`, `customerId`,
`agentId`, `currency`, `lines[]`, `tenderedAmount`, `notes` — and **no `Idempotency-Key` header, no
client reference / dedup field**. `X-Request-Id` is honoured for log correlation only (it does **not**
dedupe). Each accepted call independently creates a new finalised invoice and enqueues its own stock
issue + GL + AR postings.

**Impact on a POS.** A network timeout or dropped connection after the server committed but before
the client received the `201` is ambiguous. A blind retry creates a **second finalised invoice**,
double-issuing stock and double-posting revenue/VAT/cash — and (see #2) there is no way to reverse
it from the POS. This is the single most important gap for any client that can lose connectivity.

**Client-side mitigation (today).** Never auto-retry on an ambiguous outcome; on ambiguity,
reconcile via `GET /api/v1/sales-invoices?companyId={id}` before resending (see
[11 — Errors, Offline & Idempotency](./11-errors-offline-idempotency.md)).

**Recommended server-side fix.** Accept an `Idempotency-Key` header (or a `clientSaleRef` on
`PosSaleRequest`), persist it unique per company, and return the original `201` on replay.

---

## 2. No POS reversal / refund / void endpoint

**Current behaviour.** `PosSaleController` exposes only `POST /api/v1/pos/sales` — there is **no
void, reverse, or refund** operation for a POS sale. The general returns path,
`POST /api/v1/sales-returns`, requires a `deliveryUid` **and** a `deliveryLineUid`
(`CreateSalesReturnRequest`: both `@NotNull`) — but a POS sale produces a **DIRECT-origin invoice
with no delivery**, so it can never be returned through that endpoint. The POS session **payout**
(`POST /pos/sessions/uid/{uid}/payouts`) is cash-drawer bookkeeping only: it records cash leaving the
till and posts **no** stock-in, VAT reversal, revenue reversal, or AR credit.

**Impact on a POS.** A cashier cannot correct a wrong sale, void a mis-rung receipt, or process a
merchandise return at the till. Refunds can only be handled as out-of-band cash payouts that leave
the ledger (stock/revenue/VAT) overstated, requiring a back-office correcting journal.

**Recommended server-side fix.** A first-class `POST /pos/sales/uid/{uid}/reverse` (or a credit-note
path that accepts a POS invoice as the origin) that reverses stock + GL + AR atomically, gated by a
`POS.SALE.REFUND` / `POS.SALE.VOID` permission and audited.

---

## 3. Single exact cash tender only

**Current behaviour.** `PosSaleServiceImpl` hard-codes the payment:
`invoiceService.addPayment(invoiceUid, new AddPaymentRequest(TenderType.CASH, grossTotal, currency, null))`
— always `TenderType.CASH`, always the **exact gross total** (paid-in-full). `PosSaleRequest.tenderedAmount`
is, per its own Javadoc, "for receipt printing, **not stored on invoice**", so change is computed on
the client and never reaches the ledger. The payment layer's `TenderType` enum supports
`CASH, MOBILE_MONEY, CHEQUE, CARD`, but the POS orchestrator never uses anything but `CASH`, and
sends no split/multi-tender list.

**Impact on a POS.** No card, mobile money, or cheque tender; no split tender (e.g. part cash + part
card); no over-tender / change recorded at the ledger; the tendered amount and tender mix are not
auditable from the invoice. Most real retail needs at least card/mobile money.

**Recommended server-side fix.** Let `PosSaleRequest` carry a list of tenders
`[{ tenderType, amount, currency }]`, persist them, validate sum ≥ gross with change on `CASH`
(the `BR-SALES-07` over-tender rule already exists in the payment layer), and surface the tender
breakdown on the invoice/receipt DTO.

---

## 4. `unitPrice` is required but ignored; no manual price override

**Current behaviour.** `PosSaleRequest.LineItem.unitPrice` is `@NotNull @DecimalMin("0.00")` —
the client **must** send it — but `PosSaleServiceImpl` **never passes it through**: each line is
rebuilt as `new AddInvoiceLineRequest(productUid, unitUid, quantity, lineDiscountAmount, null)`
(the trailing `null` is `lineDiscountPercent`; `AddInvoiceLineRequest` has **no price field**). The
invoice service then resolves the price itself via `resolveListPrice(product, companyId, currency)`
(`SalesInvoiceServiceImpl`), using the product's `ProductPrice` row. So pricing is
**server-authoritative** — the client's `unitPrice` has no effect. (Note: the `LineItem.unitPrice`
Javadoc claiming "validated against list price by service" is **inaccurate** — the value is *ignored*,
not validated. Sections [04](./04-pricing-tax-currency.md) and
[09](./09-sales-payments-receipts.md) describe the real behaviour.)

**Impact on a POS.** This is **good for price integrity** — a client cannot inject an arbitrary price.
But: (a) the required `unitPrice` field is misleading (you must send a value satisfying the
validators, yet it does nothing); (b) there is **no manual price-override at the POS** — the only way
to reduce a line is `lineDiscountAmount`; the back-office invoice's permission-gated
`overrideLinePrice` has no POS equivalent; (c) the line **fails** if the product has no price row for
the company. Always treat fetched price-list/tier/customer prices as a *preview* and the returned
`SalesInvoiceDto` as the source of truth for the receipt.

**Recommended server-side fix.** Either honour `unitPrice` as a permission-gated manual override
(`POS.SALE.PRICE_OVERRIDE`, audited) **or** drop the field and correct the Javadoc — and document
that negotiated reductions must be expressed as `lineDiscountAmount`.

---

## Summary for planning

A controlled, attended, **cash-only** POS pilot is viable on the API as it stands today. Before a
production rollout, prioritise **#1 (idempotency)** and **#2 (reversal/refund)** — they are the two
that cause unrecoverable data problems in normal retail operation — then **#3 (tenders)** for real
payment coverage, and **#4** for API clarity. None of these are documentation problems; they are
backend capabilities to add. See each section above for the recommended endpoint/field shape.
