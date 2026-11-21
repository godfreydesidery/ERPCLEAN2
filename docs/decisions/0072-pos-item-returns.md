# 0072 — POS item returns ("refund by item")

- **Status:** Accepted — owner rulings 2026-11-21; **Implemented** on `feat/pos-item-return` (migration **V117**).
- **Date:** 2026-11-21
- **Deciders:** owner (rulings 1-5 below), backend-engineer (design)
- **Supersedes the deferral in:** [ADR-0042](0042-pos-api-gap-closure.md) (partial refunds deferred) and
  [docs/integration/pos/12-known-limitations.md §5](../integration/pos/12-known-limitations.md).
- **Numbering note:** written as 0072 because 0071 is taken by the Orbix App PWA decision and 0070 is the
  (still untracked) EFD file-drop ADR.

## Context

A till could only reverse a **whole** sale (`POST /api/v1/pos/sales/uid/{uid}/reverse`). A customer who brings
back one of three items could only be served by reversing everything and re-ringing the rest, or by a
back-office credit note that needs a delivery. The owner approved a partial, item-level return at the till.

## Owner rulings (2026-11-21)

1. The refund is **always CASH from the till drawer**, whatever the original tender.
2. **Same authorisation as the reverse**: the caller's own OPEN session unless they hold `SALES.INVOICE.VOID`;
   a non-supervisor needs a step-up (`authorisedByUid`) re-verified server-side; refusals audited with
   `recordIndependent`. Endpoint gate `POS.SALE.VOID or SALES.INVOICE.VOID` scoped to the invoice's company.
   **No new permission codes.**
3. The originating session must be **OPEN**; the till must be online (no offline path).
4. After **any** item return on a sale the **whole-sale reverse is refused** (409, "Some items on this sale were
   already returned. Return the remaining items with Return items.") and the sale DTO exposes `hasReturns` so the
   app hides *Reverse*.
5. The refund slip is **non-fiscal** (no EFD work).

## Decision

### Data (V117, additive; two new tables, nothing existing touched)
`pos_sale_returns` (header: return_no `PRT-####` per company, session, invoice, reason, net/discount/VAT/gross,
`refund_tender` = CASH, `payout_id`, `authorised_by`) and `pos_sale_return_lines` (one row per returned sold
line, in the sold line's unit and in base). The quantity already returned per sold line is **derived** from the
line table — there is no counter on `sales_invoice_lines`, and the original invoice is never edited (it stays
`FINALISED`). `cost_amount` on the line stays **NULL**: the issue cost put back is known only to the stock
module, which consumes the event; sales does not reach into the stock ledger.

### API (flat controller `PosSaleReturnController`)
`POST /api/v1/pos/sales/uid/{invoiceUid}/returns` (201, `Idempotency-Key` header),
`GET /api/v1/pos/sales/uid/{invoiceUid}/returns` (returns made + per sold line sold / returned / remaining),
`GET /api/v1/pos/returns/uid/{returnUid}` (reprint). The sale DTO (`SalesInvoiceDto`) gains `hasReturns` and
`returnedGross`; the line DTO gains `qtyReturned`. See [10-returns-refunds.md](../integration/pos/10-returns-refunds.md).

### One transaction writes
the return + lines, a **REFUND `pos_session_payouts` row** (`source_invoice_uid` = the sale; linked by
`payout_id`) so expected cash at cash-up falls by the refund, the `POS.SALE.RETURNED` **outbox event**, and the
audit row (`POS.SALE.RETURN`). Idempotency reuses the sale mechanism: the key is reserved in
`pos_sale_idempotency` as `RET:<key>` (so it can never collide with a sale's key) and stamped with the
**return's uid** in the same transaction; a retry gets the original return back.

### Amounts
Pro-rated from the **sold line** by returned/sold quantity: net, discount, VAT; gross = net + VAT. Rounding is on
the *cumulative* returned quantity minus what was already given back, and the return that takes a line's last unit
takes the **exact remainder** — N partial returns always add up to what the customer paid.

### Concurrency
The sale row is read `PESSIMISTIC_FORCE_INCREMENT`: two concurrent returns queue (no over-return), and a
whole-sale reverse that loaded the invoice earlier fails its optimistic version check rather than refunding the
same goods twice.

### Event consumers (cross-module = outbox only)
| Consumer | Does | Notes |
|---|---|---|
| **Stock** `PosSaleReturnStockHandler` | positive `SALE_REVERSAL` movement per stockable product, `reverseIssue` at the **original issue cost**, Dr Inventory / Cr COGS (`source COGS`, ref = return uid) | cost = invoice's total issued value / quantity per product (an issue never moves avg cost); kits/recipes exploded for the returned quantity exactly as at sale time (`shouldExplodeAtIssue`) |
| **GL** `SaleReturnPostingHandler` | `SALES_REVERSAL` journal, **`source_ref` = the return uid**: Dr Sales revenue (net, same dimension/project tags), Dr VAT payable, Cr **Cash** | a partial reversal, so not `postReversalInNewTx`; unique index `uq_journal_entry_sales_source (company, source_type, source_ref)` cannot collide with a later invoice-level reversal; failure recorded as GL posting exception kind `SALE_RETURN` (re-postable) |
| **Cash book** `SaleCashBookHandler.Returned` | mirrors that journal's cash leg as `SALE_REFUND` (OUT) | the **only** route by which the refund reaches the cash book |

### Cash is counted once
The REFUND payout lowers *expected cash* only. It raises **no** journal and **no** `POS.CASH.MOVED` — as before for
every REFUND payout (`PosSessionServiceImpl.raisesGlEntry`) — because the cash credit belongs to the
`SALES_REVERSAL` journal above. The cash book therefore sees the refund exactly once, via that journal.

### Void interplay
`SalesInvoiceServiceImpl.doVoidInvoice` (the till reverse **and** the back-office void both land there) refuses a
sale that has item returns. A void reverses all revenue, VAT, cash and stock, so it would hand the returned goods'
money back a second time. The remaining items are returned with *Return items*.

### Refused cases (409, friendly text)
Not a POS sale · sale already reversed · session not OPEN · selected line not on the sale · quantity above what
remains / line already fully returned · AR allocations exist · sale not paid in full at the till (credit sale) ·
sale settled as **COMPLIMENTARY** (nothing was paid, so there is no cash to refund — a decision beyond the five
rulings; flagged for the owner) · nothing to refund (zero-value items).

### Reports (the original invoice stays FINALISED, so each report that sums invoices must net returns)
Netted by the **return's date** (the month/day the cash left), matching the GL. Fixed: VAT return (computation
and schedule) and the GL sales tie-out, X/Z-read turnover, BI branch sales, till dashboard, payment summary.
**Also netted (follow-up, done):** Sales Register, Sales Summary, Profitability and the restaurant reports and
insights. Same convention: the returned lines come off by the **return's date**, at the original invoice's stamped
rate, shared SQL in `PosReturnSql`.
- *Sales Register / Sales Summary / Profitability*: returned lines are the second half of a `UNION ALL` (negatives), so
  quantity, discount, VAT, net, gross and the tax split net out and a product/group only returned in the window shows a
  negative row; the invoice count is unchanged. **Cost** comes off from the return's `SALE_REVERSAL` stock movements
  (source type `POS_SALE_RETURN`, source = return uid, original issue cost) - what the ledger and GL carry, exact for kits
  - not pro-rated from the sold line. The movement posts when the outbox event is dispatched, a moment after the return.
- *Restaurant dashboard / Flash, hours (daypart), payments*: sales, spend per guest, discounts, days, hours, weekdays,
  departments and items (top ten re-ranked after returns) subtract returned lines by return date, under the same item
  and department filter at line grain. Receipt/order/guest counts are not reduced. Payments by type fall by the item
  returns' cash refunds (as a voided receipt's payments leave); "Refunds" (KPI and list) already includes item returns
  because each is a REFUND payout, so nothing is counted twice.
- *Restaurant waiter reports*: **bill grain** (waiter sales, bills, bills summary) shows a bill net of every return made
  against it, whenever made, bill count unchanged; the bills list adds an optional `returned` field
  (`RestaurantBillRowDto`), the summary's CASH column falls by the refunds. **Line grain** (waiter items, the
  dashboard's waiters) nets by return date, so the dashboard's waiters add up to its sales. The two bases differ only when
  a return falls in a different report window from its sale.
- *Still not netted, by design*: AR credit notes (a separate document, as before); report items that count receipts,
  orders or guests.

## Consequences
- Partial returns are possible at the till with the same controls as a reverse.
- `grossTotalAmount` on the sale is unchanged; consumers wanting "net of returns" use `returnedGross`.
- `cost_amount` on return lines is intentionally unfilled; COGS is reversed from the stock ledger.
- Foreign-currency POS sales: the GL converts the return at the posting-date rate while the VAT return converts at
  the invoice's stamped rate — identical for base-currency sales, a known difference for foreign ones.

## Verification
`PosReturnAmountsTest`, `PosSaleReturnServiceImplTest`, `SalesInvoiceServiceImplTest` (void-after-return),
`PosSaleReturnServiceIT` (sale → partial return → payout/expected cash → journal accounts → stock on hand → cash
book → remainder exact → over-return 409 → reverse 409 → idempotent replay → dashboard and VAT negatives).
