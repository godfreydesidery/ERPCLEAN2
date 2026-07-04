# ADR-0053: Requisition "Convert" that actually creates the RFQ / PO

- **Status:** Accepted (2026-07-04) — implemented in PR #205 (code-only, **no schema change**).
- **Deciders:** Owner + Solutions Architect
- **Deferred item:** D-3 (`docs/DEFERRED-ITEMS.md`). **Effort:** M. **Migration:** none — the RFQ/PO tables and `purchase_requisitions.converted_to_uid` / `converted_to_type` already exist (V26 / V27).
- **Related:** ADR-0027 (procurement depth — requisition → RFQ → PO flow, `RfqService`, `createFromQuote`), ADR-0011 (purchases data model — `purchase_orders`, `purchase_order_lines`, `sourceRequisitionUid`), ADR-0006 (parties — `Supplier`, company-scoped master), ADR-0005 (money & currency — company base currency), ADR-0043 (schema freeze / durable DB — additive-only).

## Context

A purchase requisition can be *converted* to either an RFQ (get quotes from several suppliers) or a
purchase order (order directly). The **Convert** action existed in the UI and set the requisition to
`CONVERTED` with a success toast — but `RequisitionServiceImpl.convert` **never created the target
document** and never set `convertedToUid`. The subsequent "View RFQ/PO" link was a dead end pointing at
a document that did not exist. Persona **Yusuf** flagged this in the round-2 interviews.

This ADR records making Convert honest: it creates the real RFQ or PO in the same transaction, stamps
the back-link, and returns the created uid.

### The determinant facts (verified 2026-07-04 against shipped code)

1. **The schema was already there.** `purchase_requisitions.converted_to_uid` and `converted_to_type`
   exist (V26/V27), as do the `rfqs` / `purchase_orders` tables and their line children. The gap was
   purely behavioural — nothing to migrate. This is why D-3 is code-only.

2. **A requisition carries no usable supplier.** `preferredSupplierId` / `suggestedSupplierId` exist on
   the header/line but are never populated by the current create flow. RFQ needs a set of suppliers to
   invite; a PO needs exactly one supplier to order from. Therefore **Convert must take the supplier(s)
   as input** at the point of conversion — it cannot derive them.

3. **A parallel create-from-source path already existed for POs.** `PurchaseOrderServiceImpl` already
   had `createFromQuote` — the copy-lines-from-a-source-document idiom (resolve supplier, copy lines,
   stamp `sourceRequisitionUid`, recompute totals, audit). The requisition→PO path can mirror it rather
   than invent a new shape. Similarly `RfqService.create` already accepts a `sourceRequisitionUid`, so
   the RFQ branch can reuse it wholesale.

4. **Requisition line cost is an *estimate*, and may be null.** `PurchaseRequisitionLine.estimatedUnitCost`
   is optional. A PO line's `unitCost` runs through `assertCostValid`, whose rule is "a zero cost needs
   a note". So a null estimate must be defaulted to ZERO **with an auto-note** to satisfy the invariant.

## Decision

### D-3.1 — `convert(uid, ConvertRequisitionRequest)` creates the real target in one TX

`ConvertRequisitionRequest{ targetType, supplierUids, supplierUid, currency }`. `convert` requires the
requisition to be `APPROVED`, validates `targetType ∈ {RFQ, PURCHASE_ORDER}`, creates the target,
then — **in the same transaction** — sets `status = CONVERTED`, `convertedToType`, `convertedToUid`,
`convertedAt`, and audits (`REQUISITION_CONVERT`). It **returns the created document's uid**, so the UI
can navigate straight to a document that now exists. Atomicity matters: the status flip and the
document creation succeed or fail together — no more "CONVERTED but nothing created".

### D-3.2 — Supplier is prompted and validated (the requisition has none)

- **RFQ:** `supplierUids` is required non-empty (else a friendly `IllegalArgumentException`). Each
  invited supplier is resolved up front, **company-scoped**, rejecting unknown / foreign-company /
  ARCHIVED suppliers *before* the RFQ is created (FIX E — the RFQ branch originally let bad suppliers
  through silently while the PO branch already rejected them; the two branches now validate
  symmetrically).
- **PO:** `supplierUid` is required non-blank; `createFromRequisition` resolves it via the same
  company-scoped `resolveSupplier` the manual create path uses (rejects ARCHIVED / foreign).

The web convert form gained the supplier picker: an RFQ multi-invite list, or a single-supplier picker
+ currency for a PO.

### D-3.3 — RFQ branch REUSES `RfqService.create` (no duplicate creation logic)

`createRfqFromRequisition` maps requisition lines → `CreateRfqRequest.RfqLineRequest`
(`productId` / `unitId` / `requestedQty`, **no price** — an RFQ solicits prices), passes the
requisition uid as `sourceRequisitionUid`, and calls `rfqService.create`. Cross-module creation goes
through the owning service (not by touching another module's repository), keeping every RFQ invariant
in one place.

### D-3.4 — PO branch: new `createFromRequisition`, mirroring `createFromQuote`

`PurchaseOrderServiceImpl.createFromRequisition(requisitionUid, supplierUid, currency)`:

- resolves the requisition (scope-guarded), resolves the supplier (company-scoped), and copies each
  requisition line → PO line with **company-scoped** `Product`/`UnitOfMeasure` lookups (never a bare
  `findById` — F15 / `TenantScopingRulesTest`);
- maps `requestedQty → orderedQty` and `estimatedUnitCost → unitCost`, with **null estimate → ZERO plus
  an auto-note** ("Estimated cost not provided on the requisition line; defaulted to zero at
  conversion.") so `assertCostValid` passes;
- stamps `sourceRequisitionUid` on the PO and `convertedToPoLineUid` on each requisition line (P2
  traceability — the requisition line points at the PO line it produced);
- recomputes totals and audits `PURCHASE_ORDER_CREATE`.

### D-3.5 — Currency defaults to the company base

`resolveCurrencyForConversion` returns the requested currency when supplied, else the company's
`baseCurrency` (falling back to `"TZS"` if unset). RFQ carries no currency at solicitation time; only
the PO branch takes the optional `currency` override.

### D-3.6 — No migration

The RFQ/PO tables and the `converted_to_uid` / `converted_to_type` columns already existed (V26/V27),
and `ConvertRequisitionRequest` is a DTO. Nothing to migrate — D-3 is behaviour + a request-body field
+ web form, on the frozen schema (ADR-0043).

## Consequences

- **Positive:** the Convert action does what it claims — the requisition transitions to `CONVERTED`
  **and** a real RFQ or PO exists, back-linked both ways (`convertedToUid` / `sourceRequisitionUid` +
  per-line `convertedToPoLineUid`), so "View RFQ/PO" works. One TX, so no orphaned CONVERTED state.
- **Supplier is now an explicit input**, validated on both branches (unknown/foreign/ARCHIVED rejected)
  — surfaced as a friendly prompt in the UI rather than a silent success over a missing document.
- **Estimate-cost gap is handled, not hidden:** a requisition line with no estimated cost produces a
  zero-cost PO line with an explanatory auto-note, which the buyer edits before placing the PO.
- **Reuse over duplication:** RFQ reuses `RfqService.create`; PO mirrors `createFromQuote`. No
  new creation logic diverging from the canonical paths.
- **Contract additions:** `ConvertRequisitionRequest` gains `supplierUids` / `supplierUid` / `currency`;
  `PurchaseOrderService.createFromRequisition`; the requisition-detail web component gains the supplier
  picker.

## Alternatives considered

- **Derive the supplier from the requisition** — impossible: the create flow never populates the
  preferred/suggested supplier fields. Supplier must be prompted at conversion (D-3.2).
- **Create the target in a separate TX (or asynchronously via the outbox)** — rejected: the requisition
  status flip and the document creation must be atomic, or a crash between them re-opens the exact
  "CONVERTED but nothing created" bug this ADR closes. Same-TX creation is correct here (a *side effect*
  in another module would use the outbox, but this is a direct, user-initiated creation the caller
  needs the result of).
- **A generic "convert" that infers RFQ-vs-PO** — rejected: the two targets need different inputs
  (many suppliers vs one + currency) and produce different documents; an explicit `targetType` with
  branch-specific validation is clearer and matches the UI's two distinct actions.
- **Add a migration to persist the auto-note on the PO line** — rejected: the note only exists to
  satisfy `assertCostValid` at creation and is not a durable field; the schema stays frozen and the
  buyer re-enters a real cost + note before placing the order.
