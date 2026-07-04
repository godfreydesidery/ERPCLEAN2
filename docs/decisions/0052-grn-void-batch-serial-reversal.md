# ADR-0052: Batch / serial (IMEI) reversal on Goods-Receipt void

- **Status:** Accepted (2026-07-04) — implemented in PR #205 (code-only, **no schema change**).
- **Deciders:** Owner + Solutions Architect
- **Deferred item:** D-2 (`docs/DEFERRED-ITEMS.md`). **Effort:** M. **Migration:** none — the enrichment rides existing DTOs; `stock_batches`/`stock_serials` already exist (V76).
- **Related:** ADR-0010 (stock movement ledger + posting primitive; `GOODS_RECEIPT` / `GOODS_RECEIPT_REVERSAL`), ADR-0011 (purchases — GRN void path + the `STOCK.RECEIPT.VOIDED` outbox event), ADR-0020 (moving-average valuation + GRNI reversal already done on void), ADR-0028 (inventory depth — lot/batch + serial sub-ledgers, the `writeBatchTracking` forward path), ADR-0009 (transactional outbox; soft-consumer discipline), ADR-0043 (schema freeze / durable DB — additive-only).

## Context

Goods receipt writes three things per line: (1) a `GOODS_RECEIPT` quantity movement on the stock
ledger, (2) a `stock_batches` row (lot / expiry) when the product is lot-tracked, and (3) one
`stock_serials` row per unit when the product is serial-tracked (IMEI). The batch/serial writes were
added in V76 (`GoodsReceiptStockHandler.writeBatchTracking` / the serial writer).

Voiding a receipt already reversed **quantity + GL** symmetrically — `GoodsReceiptReversalStockHandler`
posts an opposite-sign `GOODS_RECEIPT_REVERSAL` movement and backs the receipt out of the
moving-average (ADR-0020), posting DR GRNI (2150) / CR INVENTORY (1300). But it did **not** back out
the batch/serial rows: a voided lot receipt left the `stock_batches` on-hand permanently inflated, and
a voided IMEI receipt left the `stock_serials` rows sitting `IN_STOCK` forever. A `TODO` marked the gap
in `GoodsReceiptReversalStockHandler`. This ADR records how the reversal was made symmetric.

### The determinant facts (verified 2026-07-04 against shipped code)

1. **The reversal handler is a `STOCK.RECEIPT.VOIDED` outbox consumer, not the void call itself.** It
   runs off the ledger movements it can re-read (`findBySourceDocumentUidAndMovementType`) plus the
   event payload. The stock *movement* rows carry no lot number and no serial list — those live only
   on `stock_batches`/`stock_serials`, which the handler must not import cross-module by entity. So the
   lot/serial identity of each received line has to travel **on the payload**.

2. **`StockReceiptVoidedPayload` is a DTO, not a table.** It can be enriched freely (records + additive
   back-compat constructors) with **no migration** — which is exactly why D-2 is code-only. The
   producer (`GoodsReceiptServiceImpl.voidReceipt`) has the receipt lines and the `Product` in hand at
   void time and can stamp the lot/serial data.

3. **The forward path writes an `"UNTRACKED"` sentinel batch.** `writeBatchTracking`'s `shouldWrite`
   fires for **any** lot-tracked product, even one received with a blank lot number — it books the
   quantity under the literal lot `"UNTRACKED"`. A reversal that only fired when the payload carried a
   real lot/expiry would never target that sentinel batch, leaving it permanently inflated. Reversal
   therefore has to know `product.lotTracked()`, not just whether lot data was supplied.

4. **The stock movement finder has no `ORDER BY`, and two GRN lines can name the same product in
   different lots.** Pairing a movement's own quantity with a best-effort-matched payload line's lot
   could decrement the *wrong lot by the wrong amount*. The quantity used for a batch reversal must
   come from the **same payload line that supplies the lot**, not from the iterated ledger movement.

5. **Tracking reversal must never poison the qty/GL reversal.** The qty + moving-average + GRNI-GL
   reversal is the money-correct core; a lot/serial hiccup (missing batch row, a serial already issued,
   a deserialization edge) must degrade to a WARN-and-skip, not roll back the dispatch TX.

## Decision

### D-2.1 — Enrich the void payload (DTO), do not add schema

`StockReceiptVoidedPayload.LineItem` gains `lotNumber`, `manufactureDate`, `expiryDate`,
`serialNumbers`, and `lotTracked` — mirroring `StockReceivedPayload.LineItem` (the forward V76 shape).
All are nullable/empty and additive: back-compat constructors preserve every pre-D-2 (and pre-FIX-B)
caller/test. `GoodsReceiptServiceImpl.voidReceipt` populates them from the receipt lines + the
`Product` it already loads. **No table changes** — the sub-ledger tables and the outbox event row are
untouched.

### D-2.2 — Reverse `stock_batches` symmetrically via `StockBatchService.reverseReceiptQty`

New service method `reverseReceiptQty(companyId, branchId, locationId, productId, lotNumber, qty,
actorId)`:

- **Find-only, then negate.** It looks up the existing batch by
  `(company, branch, location, product, lotNumber)` and applies `−qty`. It **never creates** a batch
  and **never deletes** one — a missing batch is a WARN-and-skip (D-2.5). Reversing is `applyDelta(qty.negate())`.
- **Quantity is the matched payload line's own `qtyInBase`** (FIX A) — never the ledger movement's
  quantity — so multi-lot receipts decrement each lot by its own received amount.
- **Mirrors the `"UNTRACKED"` sentinel** (FIX B): the handler fires a batch reversal when the line is
  `lotTracked` **or** carries real lot/expiry data, and substitutes the `"UNTRACKED"` lot when the lot
  number is blank — exactly the forward `shouldWrite` logic — so the sentinel batch is reversed instead
  of orphaned.
- **Targets the receipt-time location** (FIX C): the handler uses the iterated `StockMovement`'s own
  `locationId`, falling back to the re-resolved branch default only when the movement carries none — so
  a branch default-location change between receipt and void does not make the reversal miss the batch.
- Guards `qty > 0` (throws on a non-positive reversal quantity; the handler only calls it for positive
  received quantities).

### D-2.3 — Reverse `stock_serials` via `StockSerialService.removeReceived` (delete only an IN_STOCK, this-receipt serial)

New service method `removeReceived(companyId, productId, serialNumber, receiptUid)` deletes a serial
row **only** when all three hold:

1. it exists (else WARN-skip),
2. its `serialStatus == IN_STOCK` — it refuses to delete a serial that has since moved on
   (`ISSUED`/`RETURNED`), because that unit has a downstream history the void must not erase, and
3. its `receivedDocumentUid` equals the receipt being voided — it never deletes a serial that some
   *other* receipt brought in (a re-used serial number across receipts).

Any failure is a per-serial WARN-skip. This makes serial reversal idempotent and safe: voiding a
receipt removes exactly the still-in-stock units that receipt introduced, and nothing else.

### D-2.4 — Correlate payload lines to ledger movements by product, best-effort, order-preserving

The handler groups payload lines by `productId` into per-product FIFO queues
(`groupLinesByProduct`) and, for each iterated `GOODS_RECEIPT` movement, polls the next unmatched line
for that product (`pollLineForProduct`). This is the honest best-effort correlation available: the
movement finder is unordered, so for the rare two-lines-same-product receipt the pairing is by
declaration order. Because FIX A takes the **quantity from the polled line itself** (not the movement),
each lot is still reversed by its own quantity even if the line↔movement pairing is imperfect.

### D-2.5 — All batch/serial reversal is SOFT (never poisons the qty/GL reversal TX)

The handler runs inside the mandatory dispatch TX. Every tracking write is wrapped: a lookup miss, a
moved-on serial, a null default location, or any thrown exception is logged at WARN and skipped. The
quantity movement, moving-average back-out, and GRNI-GL reversal (the money-correct core) always
proceed. This mirrors the outbox soft-consumer discipline (ADR-0009): a sub-ledger tracking anomaly is
a reconcilable data note, not a reason to fail a financial reversal.

### D-2.6 — Two data-integrity bugs the adversarial review caught + fixed (recorded)

The pre-merge review surfaced two **real** correctness bugs in the first cut, both now fixed and
covered by tests:

- **Wrong lot ↔ quantity pairing on multi-lot receipts (FIX A).** The first version reversed each
  batch by the iterated ledger movement's quantity while taking the lot from a best-effort-matched
  payload line. Because the movement finder has no `ORDER BY`, a receipt with two lines for the same
  product in different lots could decrement the wrong lot by the wrong amount. Fixed by sourcing the
  reversal quantity from the **same line** that supplies the lot (`matchedLine.qtyInBase()`).

- **The `"UNTRACKED"` sentinel batch was never reversed (FIX B).** The forward path writes an
  `"UNTRACKED"` batch for any lot-tracked product even when the received line carries no lot data. The
  first reversal only fired on lines with real lot/expiry data, so that sentinel batch stayed
  permanently inflated after a void. Fixed by threading `product.lotTracked()` onto the payload
  (`lotTracked`) and firing the reversal (against the `"UNTRACKED"` lot) whenever it is set — mirroring
  the forward `shouldWrite`.

A third fix (FIX C — receipt-time vs void-time location) hardens against a branch default-location
change between receipt and void.

Coverage: `GoodsReceiptReversalStockHandlerTest` plus the two service unit tests
(`StockBatchServiceImplTest`, `StockSerialServiceImplTest`).

## Consequences

- **Positive:** voiding a GRN is now fully symmetric with the receipt — quantity, moving-average, GRNI
  GL, **and** both tracking sub-ledgers. Lot on-hand and serial status stay truthful after a void. No
  migration, no boundary-rule change; the enrichment is DTO-only and every pre-D-2 caller/test still
  compiles via additive constructors.
- **Soft by design (understood limitation):** a tracking reversal that cannot find its batch/serial (or
  finds a serial that has since been issued) is logged and skipped, not retried — the qty/GL reversal
  is authoritative and a sub-ledger anomaly is a WARN, not a rollback. Operations should treat repeated
  WARNs as a reconciliation signal.
- **Correlation is best-effort for the two-lines-same-product edge:** pairing is by product + payload
  declaration order; FIX A ensures each lot is still reversed by its own quantity even so.
- **Contract additions:** `StockReceiptVoidedPayload.LineItem` gains five fields (+ back-compat ctors);
  `StockBatchService.reverseReceiptQty`; `StockSerialService.removeReceived`; the reversal handler
  gains a `StockLocationRepository` + `StockBatchService`/`StockSerialService` dependency.

## Alternatives considered

- **Delete batch rows instead of negating quantity** — rejected: a batch row can accumulate quantity
  from more than the voided receipt (later receipts into the same lot), so a delete would destroy
  unrelated on-hand. `reverseReceiptQty` negates by the received amount and leaves the row.
- **Reverse serials by quantity like batches** — rejected: serials are per-unit rows with a lifecycle
  (`IN_STOCK`→`ISSUED`→`RETURNED`), not a running quantity. The correct reversal is a *conditional
  delete* of the still-in-stock, this-receipt rows — hence `removeReceived`, not a quantity delta.
- **Carry lot/serial identity on the stock movement rows** instead of the payload — rejected: it would
  require a schema change to the append-only `stock_move` table (frozen; ADR-0043) and duplicate data
  the sub-ledgers already own. The DTO payload is the additive, no-migration seam.
- **Fail the void when a batch/serial reversal can't complete** — rejected: it would let a sub-ledger
  data note block a money-correct financial reversal, against the outbox soft-consumer discipline
  (ADR-0009). Soft WARN-skip keeps qty/GL authoritative.
- **Reverse against the void-time branch default location** — rejected (FIX C): a default-location
  change between receipt and void would make the reversal miss the batch. Use the movement's own
  receipt-time location.
