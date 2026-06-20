# POS Use Cases — Returns & Refunds

End-to-end scenarios for handling returns, refunds, and mis-rung sales at the POS — using the shipped whole-sale reversal endpoint, with the cash-drawer payout and back-office paths as fall-backs.

> **Read this first.** A first-class **whole-sale void/refund** now ships (commit `f08fb08`, ADR-0042): `POST /api/v1/pos/sales/uid/{uid}/reverse` atomically reverses revenue + VAT + cash, reverses the stock issue + restores inventory valuation, and posts DR Inventory / CR COGS — gated by **`POS.SALE.VOID`** and requiring the originating session to still be **OPEN**. The reversed sale automatically drops out of the session's expected cash at X/Z-read; no separate payout is needed. **Partial / line-level refunds are still NOT supported** (explicitly deferred by ADR-0042 — they need a credit-note-by-line path). For a sale whose session is already **CLOSED/RECONCILED**, the till cannot reverse it — that is a back-office invoice-void / reconciled-variance matter. Grounding: [§10 Returns & Refunds](../10-returns-refunds.md) and [§12 Known Limitations](../12-known-limitations.md).

Section links used below: [§08 Sessions](../08-sessions.md) · [§09 Sales, Payments & Receipts](../09-sales-payments-receipts.md) · [§10 Returns & Refunds](../10-returns-refunds.md) · [§11 Errors, Offline & Idempotency](../11-errors-offline-idempotency.md) · [§12 Known Limitations](../12-known-limitations.md).

---

### UC-D1: Customer wants to return goods / get a refund

> ✅ **Supported at POS for a whole sale** (commit `f08fb08`, ADR-0042). `POST /api/v1/pos/sales/uid/{uid}/reverse` reverses the entire POS sale — stock back in + valuation restored + revenue/VAT/cash reversed (DR Inventory / CR COGS) — provided the originating session is still **OPEN** and you hold **`POS.SALE.VOID`**. The cash drops out of the drawer automatically at X/Z-read, so **no payout is needed**. **Partial (line-level) refunds are NOT supported** — deferred by ADR-0042. If the session is already CLOSED/RECONCILED, fall back to the back-office paths in **Notes & limitations**. See [§12 #2](../12-known-limitations.md).

- **Actor:** cashier or supervisor with `POS.SALE.VOID` (whole-sale reverse); store manager / finance (back-office path when the session is no longer open).
- **Goal:** give a customer their money back for returned merchandise and keep both the cash drawer and the ledger correct.
- **Preconditions:**
  - Authenticated cashier with a valid bearer token (see [§09](../09-sales-payments-receipts.md), [§11](../11-errors-offline-idempotency.md)).
  - Permission **`POS.SALE.VOID`** — the reverse endpoint is gated by `@perm.scoped(#uid,'salesinvoice','POS.SALE.VOID')` (seeded, auto-granted to `ORG_ADMIN`).
  - The invoice is **FINALISED**, is **POS-origin** (has a `posSessionId`), and its **originating session is still OPEN** — otherwise **409** (see exception flows).
  - You know the invoice `uid` (kept from the original 201, or looked up via `GET /api/v1/sales-invoices?companyId={id}`).

- **Main flow (whole-sale reverse — the first-class path):**
  1. Cashier confirms the goods coming back and identifies the sale to reverse (its `uid`).
  2. Reverse the whole sale:
     `POST /api/v1/pos/sales/uid/{uid}/reverse` ([§10](../10-returns-refunds.md)).
     Body: `{ "reason": "Returned 2x SKU-1180, customer refund (INV-0042)" }` — `reason` is `@NotBlank`.
     - **Response:** **HTTP 204 No Content** (empty body). The reversal is atomic within one transaction.
  3. Hand the cash back. The reversed sale automatically **drops out of the session's expected cash** at X/Z-read — there is no separate payout to record and nothing to re-key against the drawer.
  4. Print/annotate a refund slip from your own records (the 204 returns no body to reprint). The ledger is already correct: revenue, VAT and cash reversed; stock back in with valuation restored; DR Inventory / CR COGS posted.

- **Alternate / exception flows:**
  - **Session already CLOSED/RECONCILED, or invoice is not POS-origin (no `posSessionId`):** reverse → **409**. The till cannot refund it; handle it as a back-office invoice void on `/sales-invoices`, where the cash difference is a reconciled-variance matter (see **Notes & limitations**).
  - **Invoice not FINALISED** → **409**.
  - **Blank `reason`** → **400** (`reason: must not be blank`).
  - **Unknown invoice uid** → **404** `SalesInvoice`.
  - **Missing `POS.SALE.VOID` / wrong company scope / rejected `X-Branch-Uid`** → **403** (generic; the missing code is never named).
  - **Wrong `Content-Type`** → **415**.
  - **Customer wants only *part* of the sale back:** partial / line-level refunds are **not supported** (deferred by ADR-0042). Reverse the whole sale and re-ring the items the customer is keeping as a new `POST /api/v1/pos/sales`, or handle the partial credit in the back office (`POST /api/v1/sales-returns` for order-to-cash sales; a POS DIRECT invoice has no delivery to return — see [§10 §2](../10-returns-refunds.md)).

- **Outcome:**
  - The POS sale is **fully reversed**: stock is returned and inventory valuation restored, revenue + VAT + cash are reversed, and a DR Inventory / CR COGS entry is posted — all atomically.
  - The reversed sale is **removed from the session's expected cash** at X-read/close ([§08 §7–§9](../08-sessions.md)); no `PosSessionPayout` is created for it, and the drawer reconciles to the lower cash automatically.
  - The ledger is correct immediately — **no out-of-band back-office correction is required** for a whole-sale reverse on an open session.

- **Notes & limitations:**
  - **Whole-sale only; partial refunds deferred** ([§12 #2](../12-known-limitations.md)): `/reverse` voids the entire invoice. ADR-0042 explicitly defers partial / line-level refunds (they need a credit-note-by-line path). For a partial outcome, reverse the whole sale and re-ring the kept items.
  - **Session must be OPEN** ([§10](../10-returns-refunds.md)): a sale whose session is already CLOSED/RECONCILED can only be undone via the **back-office invoice void** on `/sales-invoices`, where the cash difference is a reconciled-variance matter, not a till refund.
  - **Back-office path for non-open-session returns** (pick per your finance policy):
    1. **Back-office credit note / GL adjustment.** Finance raises a credit note or correcting journal against the original POS invoice (look it up via `GET /api/v1/sales-invoices?companyId={id}`), and adjusts stock via a stock adjustment, to reverse revenue, VAT, COGS and put the goods back.
    2. **Model the return as a fresh offsetting transaction** if your policy allows (e.g. an exchange handled as a new sale). Note the POS sale path cannot post negative lines, so a literal "negative sale" is not possible at the POS.
  - **Refunds follow the original tenders.** The original sale may have used **split / non-cash tenders** ([§09 §2.2](../09-sales-payments-receipts.md), ADR-0041); the whole-sale reverse reverses the cash leg for a POS cash sale (no AR leg). Reconcile any card/mobile-money refund out-of-band with your acquirer as your finance policy requires.
  - **Tip:** put the original receipt number and SKU/qty in `reason` so the reversal is easy to trace in the audit trail.
  - **Idempotency on the sale itself** ([§12 #1](../12-known-limitations.md), [§11](../11-errors-offline-idempotency.md)): the *original sale* now supports an `Idempotency-Key` header to prevent duplicate finalised invoices on retry. The `/reverse` call carries no idempotency key — treat a timed-out reverse as *unknown* and re-check the invoice/session state before resending.

---

### UC-D2: Correcting a mis-rung sale (wrong item, wrong quantity, accidental sale)

> ✅ **Supported at POS while the session is open** (commit `f08fb08`, ADR-0042). A finalised POS invoice can be voided whole with `POST /api/v1/pos/sales/uid/{uid}/reverse` (perm `POS.SALE.VOID`), then re-rung correctly. There is no in-place edit and **no partial void** — you reverse the whole receipt and ring a fresh one. If the session is already closed, it falls back to a back-office correction. See [§12 #1–#2](../12-known-limitations.md).

- **Actor:** cashier or supervisor with `POS.SALE.VOID` (reverses the receipt, re-rings); finance (back-office correction when the session is no longer open).
- **Goal:** undo or fix a sale that was rung incorrectly (wrong product, wrong quantity, duplicate/accidental sale) so stock, revenue, VAT, AR, and the drawer are all correct.
- **Preconditions:**
  - Authenticated cashier; bearer token valid.
  - The mis-rung sale already returned **HTTP 201** from `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)) — i.e. it is **FINALISED**, fully-paid, `origin=POS`, with an `INV-####` number and (eventually) stock/GL/AR effects via the outbox poller.
  - To reverse it at the POS: **`POS.SALE.VOID`** and the invoice's **originating session still OPEN**.
  - For the back-office fall-back (session already closed): back-office access (e.g. `SALES.INVOICE.VIEW` to look the invoice up; finance permissions to post a credit note / adjustment).

- **Main flow (reverse-and-re-ring):**
  1. **Identify the bad invoice.** From the original 201 response keep its `uid`/`invoiceNumber`; or look it up via `GET /api/v1/sales-invoices?companyId={id}` (perm `SALES.INVOICE.VIEW`) filtered to your session/time window, then `GET /api/v1/sales-invoices/uid/{uid}` and `.../lines` for detail ([§09 §8](../09-sales-payments-receipts.md)).
  2. **Void the whole receipt:** `POST /api/v1/pos/sales/uid/{uid}/reverse` with `{ "reason": "Correction: mis-rung INV-####" }` (`reason` `@NotBlank`) → **HTTP 204 No Content**. This atomically reverses stock + valuation + revenue/VAT/cash (DR Inventory / CR COGS) and drops the sale out of the session's expected cash ([§10](../10-returns-refunds.md), [§12 #2](../12-known-limitations.md)). There is **no in-place edit** — you reverse, then re-ring.
  3. **If the corrected basket should still be sold**, ring a **new, correct** `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)) → **HTTP 201**, a new finalised invoice. The wrong one is already reversed, so only the correct sale stands.
  4. **No drawer payout is needed** for the cash: the reverse already removes the original sale from expected cash. (A `REFUND` payout is only for ad-hoc cash-out that isn't tied to reversing a sale.)

- **Alternate / exception flows:**
  - **Error spotted before submitting:** simply do not POST — fix the basket client-side and ring the correct sale. (There is nothing to undo because no invoice exists yet.)
  - **Ambiguous/timed-out original POST** (you are unsure the sale committed): send the original sale with an **`Idempotency-Key`** header so a retry returns the original invoice rather than a duplicate ([§11](../11-errors-offline-idempotency.md), [§12 #1](../12-known-limitations.md)). If the original had no key, reconcile via `GET /api/v1/sales-invoices?companyId={id}` and, if a duplicate finalised invoice did land, `/reverse` it ([§09 §10](../09-sales-payments-receipts.md)).
  - **Session already CLOSED/RECONCILED, or invoice not POS-origin/not FINALISED:** reverse → **409**; fall back to a back-office invoice void / correcting entry (see **Notes & limitations**).
  - **Blank `reason`** → **400** (`reason: must not be blank`); **unknown invoice uid** → **404** `SalesInvoice`; **missing `POS.SALE.VOID`/scope** → **403**; **wrong `Content-Type`** → **415** ([§10](../10-returns-refunds.md)).
  - **Only part of the basket is wrong:** no partial void exists (deferred by ADR-0042). Reverse the whole receipt and re-ring the correct basket. As a POS DIRECT invoice, it also has no `deliveryUid` for `POST /api/v1/sales-returns` (`CreateSalesReturnRequest.deliveryUid` is `@NotNull`) ([§10 §2](../10-returns-refunds.md)).

- **Outcome:**
  - On a whole-sale reverse, the mis-rung invoice is **fully reversed** (stock/valuation/revenue/VAT/cash + DR Inventory / CR COGS) and drops out of the session's expected cash at X-read/close ([§08 §7–§9](../08-sessions.md)) — **no back-office correction needed** while the session is open.
  - A correct re-rung sale (if used) is an independent finalised invoice with its own number and side effects.
  - If the session was already closed, the mis-rung invoice **remains FINALISED** until a back-office correcting entry reverses it.

- **Notes & limitations:**
  - **Whole-sale void at the POS; no in-place edit, no partial void** ([§12 #2](../12-known-limitations.md)): a cashier reverses the entire receipt and re-rings — line-level corrections are deferred by ADR-0042. Same `/reverse` mechanism as UC-D1.
  - **Duplicate-posting risk is now mitigated** ([§12 #1](../12-known-limitations.md)): the original sale supports an `Idempotency-Key` header (per company) so a retry returns the *original* invoice instead of a duplicate. Should a duplicate still arise (e.g. no key sent), `/reverse` it while its session is open.
  - **`unitPrice` is server-derived; only `lineDiscountAmount` is honoured** ([§12 #4](../12-known-limitations.md), [§09 §2.2](../09-sales-payments-receipts.md)): a "wrong price" cannot be fixed by re-sending a different `unitPrice` — the server re-derives the unit price from its own price list (the client `unitPrice` is dropped before pricing). The only price lever at the POS is `lineDiscountAmount`; genuine price-list corrections are a back-office task.
  - **Back-office fall-back (closed session):** identify the invoice, have finance post a correcting credit note / reversing journal + stock adjustment against the wrong invoice, using the `reason`/notes to cross-reference `INV-####`.
