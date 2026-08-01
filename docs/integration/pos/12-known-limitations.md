# Known Limitations & API Gaps

This page consolidates the **current limitations of the POS API** that an integrator must design
around, and that the product team should weigh for a production rollout. Each item is grounded in
the actual backend code (file references below). These are **API/behaviour gaps, not documentation
gaps** — the rest of this guide describes the API as it really is; this page calls out where that
reality falls short of what a full retail POS typically needs.

> Status update (commit `f08fb08`, **ADR-0042** — now *Implemented*): the three highest-impact gaps
> on this page — **sale idempotency**, **whole-sale reversal/refund/void**, and **multi-tender /
> non-cash payments** — are **CLOSED** and ship in the current API. They are documented below as the
> shipped mechanism (kept on this page for the historical record and the integration notes that come
> with them). The only items that remain genuinely open are **partial / line-level POS refunds**
> (explicitly deferred by ADR-0042) and **client-side offline ingest**. Server-authoritative pricing
> is **by design**, not a limitation.

| # | Item | Status | Affects |
|---|------|--------|---------|
| 1 | Sale idempotency / dedup on `POST /pos/sales` | **CLOSED** (`Idempotency-Key` header, V70) | data integrity (double-posting) |
| 2 | Whole-sale POS reversal / refund / void | **CLOSED** (`POST /pos/sales/uid/{uid}/reverse`) | returns, mistake correction |
| 3 | Multi-tender / non-cash payments | **CLOSED** (optional `tenders[]` list) | payments (card, mobile money, split, change) |
| 4 | Server-authoritative pricing; `unitPrice`/`agentId` informational | **By design** (not a gap) | price integrity, agent attribution |
| 5 | Partial / line-level POS refunds | **Open** (deferred by ADR-0042) | per-line returns |
| 6 | Client-side offline ingest | **Open** | disconnected operation |

> **Beyond transaction integrity:** for **supermarket / grocery** readiness — embedded-weight
> barcodes, fiscal/EFD receipts, age-restriction, promotions-on-POS, weighed goods, partial refunds,
> loyalty, gift cards — see **[§7 Supermarket-readiness gaps](#7-supermarket-readiness-gaps-grocery)**
> and **[ADR-0044](../../decisions/0044-pos-supermarket-readiness.md)**.

---

## 1. Sale idempotency on sale creation — CLOSED (`Idempotency-Key` header)

> **Status: CLOSED** in commit `f08fb08` (ADR-0042). The duplicate-posting risk described below is
> resolved when the client sends the header.

**Shipped behaviour.** `POST /api/v1/pos/sales` accepts an **optional** HTTP header
`Idempotency-Key` (opaque string, ≤ 80 chars). The `PosSaleRequest` body is **unchanged**
(`com.erp.modules.sales.domain.dto.PosSaleRequest` still has `sessionUid`, `customerId`, `agentId`,
`currency`, `lines[]`, `tenderedAmount`, `notes`). The key is backed by table
`pos_sale_idempotency(company_id, idem_key, invoice_uid)` with `UNIQUE(company_id, idem_key)`
(Flyway **V70**). It is **reserve-before-process**: a native `INSERT ... ON CONFLICT (company_id,
idem_key) DO NOTHING` runs inside the sale's single transaction **before** the invoice is created.
`X-Request-Id` remains log-correlation only (it does **not** dedupe). Verified by
`PosSaleIdempotencyRepositoryIT`.

**Replay (same key, original already committed).** The API returns the **original** finalised
`SalesInvoiceDto` — no duplicate invoice, stock issue, GL/AR posting, payment, or `SALE_FINALISED`
outbox event. Note a replay **still returns HTTP `201`** (the controller hardcodes `CREATED`), so the
client must recognise a replay by **matching the returned invoice `uid` to one it already holds** —
not by a `200`-vs-`201` distinction.

**Concurrent in-flight duplicate.** The duplicate `INSERT` blocks until the winner commits, then
returns the winner's original invoice. In the narrow window where the marker row exists but
`invoice_uid` is not yet stamped, the duplicate gets **HTTP `409`** — *"This sale is still being
processed. Please try again in a moment."* This `409` is **retryable and not terminal**: keep the
key and resend it after a short delay.

**Scope & rules.**
- **Per company** — namespaced by the authenticated session's company. The key need only be unique
  within a company; the same value in another company is independent.
- **Omitting the header** ⇒ legacy non-idempotent behaviour (a blind retry creates a duplicate
  sale). The client **must always send the header** to get the guarantee.
- A **failed / rolled-back** sale frees the key — the next send with the same key creates the sale
  normally.

**The residual gap is on the client — and it is a real one.** The server side is closed; the
guarantee still fails if the key is not **durable**. A key held in memory dies with the process, so
an app killed between the server committing and the response landing loses it, the cashier re-rings
the basket, a fresh key is minted, and a **second finalised invoice** is created — duplicate
revenue, VAT, COGS and stock issue. The client contract is therefore:

1. **Persist the key (with the request body) to device storage BEFORE the POST.**
2. **Clear it only on a confirmed terminal outcome** — the invoice came back, or the server
   definitively rejected the request. Ambiguity is not an outcome.
3. **On relaunch, reconcile an unresolved key** by replaying the stored body under the stored key —
   never silently re-ring the basket, and block the till until it is settled.
4. **Never release the key on a `409`** — that response means the original attempt is still
   processing, and freeing the key there re-opens the very duplicate window this closes.

Full contract and the status→action table: [11 — Errors, Offline & Idempotency
§4.1a/§4.2a](./11-errors-offline-idempotency.md). The shipped OrbixPOS client implements the durable
slot and the unfinished-sale recovery prompt; its live payment path still releases the slot on a
`409`, which is the one rule above it does not yet meet — build to the contract, not to that path.

---

## 2. Whole-sale POS reversal / refund / void — CLOSED (`/reverse` endpoint)

> **Status: CLOSED** in commit `f08fb08` (ADR-0042) for **whole-sale** reversal. Partial / line-level
> refunds remain deferred — see [§5](#5-partial--line-level-pos-refunds--deferred).

**Shipped behaviour.** `PosSaleController` exposes
`POST /api/v1/pos/sales/uid/{uid}/reverse` with body `{ "reason": "<text>" }` (`reason` is
`@NotBlank`) returning **`204 No Content`**. It is gated by permission **`POS.SALE.VOID`** (scoped on
the invoice `uid`; seeded, auto-granted to `ORG_ADMIN`). The whole-invoice reversal acts as a **full
refund**: it reverses revenue + VAT + cash (a POS cash sale has no AR leg), reverses the stock issue
and restores inventory valuation, and posts **DR Inventory / CR COGS**. The general returns path
(`POST /api/v1/sales-returns`, which still requires a `deliveryUid` **and** `deliveryLineUid`) does
**not** apply to POS sales — POS reversal is this dedicated endpoint instead.

**Drawer effect.** The reversed sale automatically **drops out of the session's expected cash**
(drawer) at X/Z-read — **no separate payout entry is needed**. (The POS session **payout**,
`POST /pos/sessions/uid/{uid}/payouts`, remains cash-drawer bookkeeping only and is *not* the way to
refund a sale.)

**Preconditions (else `409`).**
- The invoice must be **POS-origin** (has a `posSessionId`).
- Its originating **session must still be OPEN**. A sale whose session is already CLOSED/RECONCILED
  must be handled via the back-office invoice void on `/sales-invoices`, where the cash difference is
  a reconciled-variance matter, not a till refund.
- The invoice must be **FINALISED**.

**Not supported.** **Partial / line-level** refunds are explicitly **deferred** by ADR-0042 (they
need a credit-note-by-line path) — this endpoint reverses the **whole** sale only. See
[§5](#5-partial--line-level-pos-refunds--deferred).

---

## 3. Multi-tender / non-cash payments — CLOSED (`tenders[]` list)

> **Status: CLOSED** in commit `f08fb08` (ADR-0042, building on ADR-0041 tender instruments).

**Shipped behaviour.** `PosSaleRequest` carries an **optional** `tenders` list
(`List<PosTender>`). Each `PosTender` has `tenderType`, `amount`, `reference`, plus the instrument
refs `cashBankAccountId`, `chequeId`, `mobileMoneyRef`, `cardRef` (ADR-0041). `PosSaleServiceImpl`
loops **every** tender into `addPayment`, forcing each to the request's currency; the tender sum must
be **≥ gross total** (validated). This supports **split tender** and **non-cash** methods (card,
mobile money, cheque) — the payment layer's `TenderType` enum (`CASH, MOBILE_MONEY, CHEQUE, CARD`) is
now driven by the request rather than hard-coded.

**Back-compat.** **Omitting `tenders`** ⇒ today's single exact-**CASH** behaviour: the service posts
one `CASH` payment for the gross total (paid-in-full). `PosSaleRequest.tenderedAmount` remains a
receipt-printing hint ("not stored on invoice"); change is still computed on the client for cash.

**Client guidance.** Send a `tenders[]` entry per tender taken (e.g. part cash + part card), with the
appropriate instrument ref per method. The invoice's recorded payments reflect the tender mix, so it
is auditable from the returned `SalesInvoiceDto`.

---

## 4. Server-authoritative pricing — BY DESIGN (not a limitation)

> **Status: By design**, clarified in commit `f08fb08` (ADR-0042 **D-4**). This is a deliberate
> price-integrity guarantee, not a gap. `unitPrice` and `agentId` are now **informational** fields.

**Pricing is server-authoritative — the client `unitPrice` is not trusted.**
`PosSaleRequest.LineItem.unitPrice` is still **accepted** by the API (back-compat) but is **dropped
before any pricing code** runs: each line is built as `new AddInvoiceLineRequest(productUid, unitUid,
quantity, lineDiscountAmount, null)` and **`AddInvoiceLineRequest` has no `unitPrice` field**. The
server **re-derives** the unit price from the product's company-scoped price list (`ProductPrice`,
via `resolveListPrice(product, companyId, currency)` in `SalesInvoiceServiceImpl`) and VAT from the
company's `tax_rates`. A malicious or buggy client therefore **cannot set `price = 0`**. The
`@NotNull` on `unitPrice` was **relaxed** (D-4), so the field is genuinely optional now. The client
may still send `unitPrice` for its own receipt display, but must treat the **server totals** in the
returned `SalesInvoiceDto` as authoritative. Sections [04](./04-pricing-tax-currency.md) and
[09](./09-sales-payments-receipts.md) describe the real behaviour.

**`agentId` is informational.** `agentId` is likewise not forwarded; agent attribution defaults to
the logged-in cashier on the returned `SalesInvoiceDto`. (Attributing a POS sale to a *different*
agent is not supported — a deliberate constraint, not a posting bug.)

**Residual notes (not data-integrity gaps).**
- There is **no manual price-override at the POS** — the only way to reduce a line is
  `lineDiscountAmount`; the back-office invoice's permission-gated `overrideLinePrice` has no POS
  equivalent. (Negotiated reductions must be expressed as `lineDiscountAmount`.)
- A line **fails** if the product has no `ProductPrice` row for the company/currency. Always treat
  fetched price-list/tier/customer prices as a *preview* and the returned `SalesInvoiceDto` as the
  source of truth for the receipt.

---

## 5. Partial / line-level POS refunds — DEFERRED

> **Status: Open** — explicitly **deferred** by ADR-0042.

The `/reverse` endpoint ([§2](#2-whole-sale-pos-reversal--refund--void--closed-reverse-endpoint))
reverses the **whole** sale only. **Partial** refunds (return some lines, or a partial quantity of a
line) are **not supported**: they require a credit-note-by-line path that ADR-0042 deferred. Until
that ships, a partial return must be handled in the back office (a per-line credit note against the
originating invoice), not at the till.

---

## 6. Client-side offline ingest — OPEN

The server has **no batch/offline-ingest endpoint** to drain a queue of sales captured while the
client was disconnected; each sale is still posted by an individual online `POST /pos/sales` call.
With sale idempotency now shipped ([§1](#1-sale-idempotency-on-sale-creation--closed-idempotency-key-header)),
a client can **safely replay** its queued sales on reconnect (each with its own `Idempotency-Key`)
without risk of duplicates — but the **queue-and-replay logic lives on the client**. See
[11 — Errors, Offline & Idempotency](./11-errors-offline-idempotency.md) for the recommended pattern.

---

## 7. Supermarket-readiness gaps (grocery)

The items above (#1–#6) are the POS **transaction-integrity** gaps — mostly now closed. A separate
**supermarket / grocery design review** (2026-06-20) assessed the POS against a real grocery
operation and found a distinct set of gaps. Full analysis, proposed data-model/API shapes, and a
recommended phasing are in **[ADR-0044 — POS supermarket-readiness](../../decisions/0044-pos-supermarket-readiness.md)**;
this is the client-facing summary.

> **Scope note.** A POS sale is one `POST /pos/sales` over a **client-side basket**, so cashier
> ergonomics — scan loop, void-a-line / edit-qty before submit, suspend-&-recall, price-check,
> no-sale/open-drawer, peripheral & scale integration, e-receipt rendering — are the **client's**
> job, not API gaps (ADR-0044 D-7). The table below lists genuine **backend/design** gaps only.

| Gap | Status | Grocery criticality | Proposed (ADR-0044) |
|---|---|---|---|
| **Embedded weight/price barcodes** (EAN-13 type-2; deli/produce scale labels) | **Absent** — exact-match lookup 404s on every such scan | **Critical** | D-1a |
| **Fiscal / EFD receipt** (TRA VFD signed device + verification code) | **Absent** — legal prerequisite for a TZ VAT retailer | **Critical** | D-3b |
| **Age-restricted item gate** (alcohol / tobacco verification) | **Absent** | **Critical** | D-3a |
| **POS-applied promotions** (multi-buy / 3-for-2 / BOGO / threshold) | **Absent** — a `Promotion` engine exists but is **not applied on the sale path** and can't express multi-buy | **Critical** | D-2 |
| **First-class weighed goods** (sell-by-weight, tare, scale rounding) | **Partial** — math works via a WEIGHT unit; no weighed-product type | **Critical** | D-1b |
| **Partial / line-level refund** | **Deferred** (= §5 above) | **High** | D-4 |
| **PLU codes** (ring-by-number for loose produce) | **Absent** (workaround: type the SKU code) | **High** | D-1c |
| **Manual price override** at the till (supervisor) | **Absent** on POS (exists in the back-office invoice flow) | **High** | (D-2 note) |
| **Markdown / clearance** (reduced-to-clear) | **Absent** (only lever is a line discount) | **High** | D-2 |
| **Loyalty / membership** (points accrual/redeem, member pricing) | **Absent** — no module | **High** | D-5 |
| **Gift card / store credit** (issue + redeem) | **Absent** | **High** | D-6 |
| Vouchers/EBT, FX tender, deposit items, exchange, no-receipt return, denomination count, gift/e-receipt | **Absent** | Medium–Low | D-4 / D-6 |

**The four critical, must-fix-for-a-real-supermarket gaps** are: embedded-weight barcodes (D-1a),
fiscal/EFD receipts (D-3b), age-restriction (D-3a), and weighed goods (D-1b) — the legal + core
grocery basics — plus **promotions-on-POS (D-2)**, the highest-leverage single change for grocery
economics. Prioritisation/phasing is the owner's call (ADR-0044, *Consequences*).

---

## Summary for planning

The three highest-impact gaps that previously blocked production use — **idempotency (#1)**,
**reversal/refund (#2)**, and **multi-tender (#3)** — are now **CLOSED** in commit `f08fb08`
(ADR-0042), so an attended, multi-tender POS with safe retries and at-till whole-sale refunds is
viable on the shipped API. **Server-authoritative pricing (#4)** is a deliberate by-design guarantee,
not a gap. The only genuinely open items are **partial / line-level refunds (#5)**, deferred by
ADR-0042 pending a credit-note-by-line path, and **client-side offline ingest (#6)**, where the
queue-and-replay logic lives on the client (now safe to build on top of the shipped idempotency key).
