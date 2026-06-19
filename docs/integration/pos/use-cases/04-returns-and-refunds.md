# POS Use Cases — Returns & Refunds

End-to-end scenarios for handling returns, refunds, and mis-rung sales at the POS — and an honest account of what the API supports today versus what must be done in the back office.

> **Read this first.** The POS surface has **no reversal, refund, or void endpoint**. `POST /api/v1/pos/sales` is one-way; the office-side `POST /api/v1/sales-returns` needs a `deliveryUid` that a DIRECT POS invoice never produces; and the only POS-side "refund" mechanism is a cash-drawer **payout** that adjusts till reconciliation but touches **no stock, GL revenue/VAT, or AR**. Both use cases below are therefore documented as **Not supported at POS today**, with the operational workaround and the recommended server-side fix. Grounding: [§10 Returns & Refunds](../10-returns-refunds.md) and [§12 Known Limitations](../12-known-limitations.md).

Section links used below: [§08 Sessions](../08-sessions.md) · [§09 Sales, Payments & Receipts](../09-sales-payments-receipts.md) · [§10 Returns & Refunds](../10-returns-refunds.md) · [§11 Errors, Offline & Idempotency](../11-errors-offline-idempotency.md) · [§12 Known Limitations](../12-known-limitations.md).

---

### UC-D1: Customer wants to return goods / get a refund

> ⚠️ **Not supported at POS today.** There is no POS endpoint that reverses a POS sale (stock back in + VAT/revenue reversal + customer credit). The closest the POS offers is a cash-drawer **payout** (`payoutType: "REFUND"`) — drawer bookkeeping only — plus a back-office correcting entry. See **Notes & limitations** and [§12 #2](../12-known-limitations.md).

- **Actor:** cashier (records the cash payout); store manager / finance (raises the back-office correction).
- **Goal:** give a customer their money back for returned merchandise and keep both the cash drawer and the ledger correct.
- **Preconditions:**
  - Authenticated cashier with a valid bearer token (see [§09](../09-sales-payments-receipts.md), [§11](../11-errors-offline-idempotency.md)).
  - Permission **`POS.SESSION.OPEN`** — the payout endpoint is gated by `@perm.scoped(#uid,'possession','POS.SESSION.OPEN')`; there is **no** dedicated refund permission ([§10 §3](../10-returns-refunds.md)).
  - An **OPEN** POS session on the till (the same session, or any open session — the payout carries no link to the original invoice). A `CLOSED`/`RECONCILED` session → **409**.
  - You know the cash amount to return. (The original receipt number is useful only as a free-text `reason`; the API does not validate it.)

- **Main flow (the only thing the POS API can do — drawer bookkeeping):**
  1. Cashier confirms the goods and the cash amount to refund out of the drawer (per store policy — the API enforces nothing here).
  2. Record a cash payout of type `REFUND` against the open session:
     `POST /api/v1/pos/sessions/uid/{uid}/payouts` ([§10 §3](../10-returns-refunds.md), [§08 §6](../08-sessions.md)).
     Body (`PosPayoutRequest`): `{ "payoutType": "REFUND", "amount": 25000.00, "reason": "Returned 2x SKU-1180, cash refund (ref INV-0042)" }`.
     - `payoutType` `@NotNull` (`REFUND` | `PAID_OUT`); `amount` `@NotNull @DecimalMin("0.01")`; `reason` `@Size(max=255)`, optional.
     - **Response:** the controller method returns `void` → empty-data envelope `{ "data": null, "errors": [], "meta": null }` at **HTTP 200**. No payout uid is echoed — record the reference client-side.
  3. Hand the cash back and print/annotate your own refund slip from the values you sent (nothing about the payout is returned to reprint).
  4. **Out-of-band, mandatory:** raise a back-office correcting entry for the *merchandise/accounting* side — see **Notes & limitations**. The payout alone leaves stock, revenue, and VAT overstated.

- **Alternate / exception flows:**
  - **No open session / session already closed:** payout → **409** `Session <number> is not OPEN.` Open a fresh session first (or do the whole thing as a back-office credit note). ([§10 §3 errors](../10-returns-refunds.md))
  - **Bad amount or enum:** `amount` null or `< 0.01`, unknown `payoutType`, or `reason` > 255 chars → **400** (field errors as `"field: message"`).
  - **Unknown session uid** → **404** `PosSession`.
  - **Missing permission / wrong company scope / rejected `X-Branch-Uid`** → **403** (generic; the missing code is never named).
  - **Wrong `Content-Type`** → **415**.
  - **You tried the "proper" return endpoint instead:** `POST /api/v1/sales-returns` requires a `deliveryUid` + `deliveryLineUid` (both `@NotNull` on `CreateSalesReturnRequest`). A POS sale is a DIRECT invoice with **no delivery**, so there is nothing to pass — you cannot drive that endpoint for a POS receipt at all (see UC-D1's limitations and [§10 §2](../10-returns-refunds.md)).

- **Outcome:**
  - A `PosSessionPayout` of type `REFUND` is recorded against the session (audit action `POS.SESSION.PAYOUT`). At X-read/close it is **subtracted** from expected cash: `expectedCash = openingFloat + cashSales − totalPayouts` ([§08 §7–§9](../08-sessions.md)), so the drawer reconciles to the lower cash.
  - **Nothing else changes.** No stock-in, no COGS reversal, no GL revenue/VAT reversal, no AR credit note, and **no link to the original invoice or product** — the payout entity stores only `payoutType`, `amount`, `reason`, session/company/branch, and audit columns ([§10 §3](../10-returns-refunds.md)).
  - Until the back-office correction is posted, the ledger overstates revenue/VAT and understates stock for the returned goods.

- **Notes & limitations:**
  - **No POS reversal/refund/void** ([§12 #2](../12-known-limitations.md)): the only first-class return is `POST /api/v1/sales-returns` (perm `SALES.RETURN.CREATE`), which does stock-in + COGS reversal + an AR **credit note** — but it is keyed by `deliveryUid`/`deliveryLineUid` and is reachable **only** for order-to-cash sales (SO → Delivery → Invoice), **never** for a POS DIRECT invoice ([§10 §2, §5](../10-returns-refunds.md)).
  - **Operational workaround for the accounting side** (pick per your finance policy):
    1. **Back-office credit note / GL adjustment.** Finance manually raises a credit note or correcting journal against the original POS invoice (look it up via `GET /api/v1/sales-invoices?companyId={id}`), and adjusts stock via a stock adjustment, to reverse revenue, VAT, COGS and put the goods back. This is the recommended path for a true return.
    2. **Model the return as a fresh offsetting transaction** if your policy allows (e.g. an exchange handled as a new sale). Note the POS sale path cannot post negative lines, so a literal "negative sale" is not possible at the POS.
    3. In all cases, still record the `REFUND` payout (step 2 above) so the **cash drawer** reconciles — it is the only POS-visible trace of the cash leaving the till.
  - **Cash-only** ([§12 #3](../12-known-limitations.md)): the payout records a cash outflow only; there is no card/mobile-money refund concept at the POS.
  - **No idempotency** ([§12 #1](../12-known-limitations.md), [§11](../11-errors-offline-idempotency.md)): a blind retry of the payout after a timeout can record a **duplicate** payout (under-stating expected cash). `X-Request-Id` is correlation only — implement client-side dedupe and treat a timed-out payout as *unknown*, reconciling via X-read before resending.
  - **Tip:** put the original receipt number and SKU/qty in `reason` so the back-office correction can be matched to the drawer payout later.
  - **Recommended server-side fix** ([§12 #2](../12-known-limitations.md)): a first-class `POST /api/v1/pos/sales/uid/{uid}/reverse` (or a credit-note path that accepts a POS invoice as origin) that reverses stock + GL + AR atomically, gated by a `POS.SALE.REFUND` / `POS.SALE.VOID` permission and audited.

---

### UC-D2: Correcting a mis-rung sale (wrong item, wrong quantity, accidental sale)

> ⚠️ **Not supported at POS today.** Same root constraint as UC-D1: `POST /api/v1/pos/sales` is one-way and there is no void/reverse. A finalised POS invoice cannot be cancelled or edited from the POS, and it has no delivery to return. See [§12 #1–#2](../12-known-limitations.md).

- **Actor:** cashier (notices the error, records any cash payout); shift supervisor / store manager (authorises); finance (back-office correction).
- **Goal:** undo or fix a sale that was rung incorrectly (wrong product, wrong quantity, duplicate/accidental sale) so stock, revenue, VAT, AR, and the drawer are all correct.
- **Preconditions:**
  - Authenticated cashier; bearer token valid.
  - The mis-rung sale already returned **HTTP 201** from `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)) — i.e. it is **FINALISED**, fully-paid, `origin=POS`, with an `INV-####` number and (eventually) stock/GL/AR effects via the outbox poller.
  - For any cash given back: an **OPEN** session and **`POS.SESSION.OPEN`** (the payout path, as in UC-D1).
  - For the accounting correction: back-office access (e.g. `SALES.INVOICE.VIEW` to look the invoice up; finance permissions to post a credit note / adjustment).

- **Main flow (what is actually possible):**
  1. **Identify the bad invoice.** From the original 201 response keep its `uid`/`invoiceNumber`; or look it up via `GET /api/v1/sales-invoices?companyId={id}` (perm `SALES.INVOICE.VIEW`) filtered to your session/time window, then `GET /api/v1/sales-invoices/uid/{uid}` and `.../lines` for detail ([§09 §8](../09-sales-payments-receipts.md)).
  2. **Recognise there is no POS undo.** `PosSaleController` exposes only `POST /api/v1/pos/sales` — no `void`/`reverse`/`refund` mapping ([§10 §1](../10-returns-refunds.md), [§12 #2](../12-known-limitations.md)). You cannot edit, cancel, or negate the invoice from the POS.
  3. **If cash must be returned now**, record it as a drawer payout so the till reconciles:
     `POST /api/v1/pos/sessions/uid/{uid}/payouts` with `{ "payoutType": "REFUND", "amount": <amount>, "reason": "Correction: mis-rung INV-####" }` ([§10 §3](../10-returns-refunds.md), [§08 §6](../08-sessions.md)) → **HTTP 200**, empty-data envelope. (Drawer-only; no ledger effect.)
  4. **If the corrected basket should still be sold**, ring a **new, correct** `POST /api/v1/pos/sales` ([§09](../09-sales-payments-receipts.md)) → **HTTP 201**, a new finalised invoice. (This does not cancel the wrong one — both invoices exist.)
  5. **Out-of-band, mandatory:** finance posts a back-office correcting entry against the wrong invoice (credit note / reversing journal + stock adjustment) — see **Notes & limitations**. Without it, the wrong sale stays on the ledger.

- **Alternate / exception flows:**
  - **Error spotted before submitting:** simply do not POST — fix the basket client-side and ring the correct sale. (There is nothing to undo because no invoice exists yet.)
  - **Ambiguous/timed-out original POST** (you are unsure the sale committed): do **not** blindly retry — that risks a duplicate finalised invoice (double stock/GL/AR), with no way to reverse it from the POS. Reconcile first via `GET /api/v1/sales-invoices?companyId={id}` before any resend ([§09 §10](../09-sales-payments-receipts.md), [§11](../11-errors-offline-idempotency.md), [§12 #1](../12-known-limitations.md)).
  - **Payout against a non-open session** → **409** `Session <number> is not OPEN.`; **bad amount/enum** → **400**; **unknown session** → **404**; **missing perm/scope** → **403**; **wrong `Content-Type`** → **415** ([§10 §3 errors](../10-returns-refunds.md)).
  - **Attempting `POST /api/v1/sales-returns` to "return" the mis-rung items:** rejected — no `deliveryUid` exists for a POS DIRECT invoice (`CreateSalesReturnRequest.deliveryUid` is `@NotNull`) ([§10 §2](../10-returns-refunds.md)).

- **Outcome:**
  - The mis-rung invoice **remains FINALISED and unchanged** at the POS; its eventual stock/GL/AR effects stand until a back-office correction reverses them.
  - Any `REFUND` payout reduces the session's expected cash at X-read/close ([§08 §7–§9](../08-sessions.md)) but produces no ledger reversal.
  - A correct re-rung sale (if used) is an independent finalised invoice with its own number and side effects.
  - Books are correct only after the back-office correcting entry posts.

- **Notes & limitations:**
  - **No void / mistake correction at the POS** ([§12 #2](../12-known-limitations.md)): "A cashier cannot correct a wrong sale, void a mis-rung receipt, or process a merchandise return at the till." This is the same gap as UC-D1.
  - **Duplicate-posting risk** ([§12 #1](../12-known-limitations.md)): because there is no idempotency *and* no reversal, a duplicate sale created by a retry is unrecoverable from the POS — prevention (client-side dedupe + reconcile-before-resend) is the only defence.
  - **`unitPrice` is ignored; only `lineDiscountAmount` is honoured** ([§12 #4](../12-known-limitations.md), [§09 §2.2](../09-sales-payments-receipts.md)): a "wrong price" cannot be fixed by re-sending a different `unitPrice` (the server prices from its own price list). The only price lever at the POS is `lineDiscountAmount`; genuine price corrections are a back-office task.
  - **Operational workaround:** identify the invoice, record a `REFUND` payout for any cash returned, re-ring the correct sale if needed, and have finance post the correcting credit note / journal + stock adjustment against the wrong invoice. Use the `reason` field to cross-reference `INV-####`.
  - **Recommended server-side fix** ([§12 #2](../12-known-limitations.md)): a permission-gated, audited `POST /api/v1/pos/sales/uid/{uid}/reverse` (or void) that atomically reverses stock + GL + AR for a POS invoice — closing the gap for both UC-D1 and UC-D2.
