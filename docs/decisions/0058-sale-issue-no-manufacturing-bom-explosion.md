# ADR-0058: A stockable good is issued as itself at sale — a manufacturing BOM is not a point-of-sale kit

- **Status:** Accepted (2026-07-06) — correctness fix to the sale-time stock-issue path
  ([`SaleIssueStockHandler`](../../backend/src/main/java/com/erp/modules/stock/events/SaleIssueStockHandler.java)).
  Code-only, no schema/seed change; corrects the overreach in ADR-0026 D-7.
- **Deciders:** Owner (surfaced by a POS busy-day cashier-feedback run) + root-cause investigation.
- **Effort:** S. **Migration:** none.
- **Related:** ADR-0026 (perpetual inventory / sale-issue posting — the arm being narrowed),
  ADR-0010 D-8 (`product_components` point-of-sale kit recipe — the *legitimate* explode-on-sale case),
  manufacturing work-order / `PRODUCTION_RECEIPT` flow (where a BOM is actually consumed).

## Context

When a sale is finalised, `SaleIssueStockHandler` posts the outbound `SALE_ISSUE` stock movements.
For each line it decided *what* to decrement with `RecipeExplosionResolver.isComposed(productUid)`,
which returns true when the product has **either**:

1. a point-of-sale kit recipe (`product_components`, ADR-0010 D-8) — a non-stockable phantom/combo
   that legitimately explodes into its parts at the till; **or**
2. an ACTIVE **manufacturing BOM** (`boms`) — a *production* recipe.

The check ran **before** the `stockable` check, so any product with an active manufacturing BOM was
exploded at sale — including a **make-to-stock finished good**.

That is wrong. A manufacturing BOM is consumed **once**, at production time: the work order issues the
components and receives the finished good into stock via `PRODUCTION_RECEIPT`. Selling that finished
good must relieve **the finished good's own on-hand**. Re-exploding it at sale:

- decremented the components a **second** time (double consumption), and
- **never** relieved the finished good — its on-hand drifts up forever.

Observed in a POS busy-day run: 17 units of a manufactured finished good were sold; its on-hand stayed
put while a BOM component was driven negative by the phantom `SALE_ISSUE` rows.

## Decision

At issue time (sale and delivery), a line **explodes into components only** when the product has:

1. a point-of-sale **kit recipe** (`product_components`, ADR-0010 D-8) — stockable or not; **or**
2. a manufacturing **BOM** *and* is **non-stockable** (an assemble-to-order phantom, ADR-0026 D-7).

A **stockable** finished good whose only recipe is a manufacturing BOM is **make-to-stock** and is
**issued as itself** — its BOM is a *production* recipe, already consumed by the work order that
received it into stock. This rule lives in `RecipeExplosionResolver.shouldExplodeAtIssue(uid,
stockable)`, called by both `SaleIssueStockHandler` and `DeliveryIssueStockHandler` in place of the
old `isComposed(uid)`-first branch:

- `shouldExplodeAtIssue` → explode into components (via BOM for a non-stockable phantom, or via
  `product_components` for a kit).
- else, non-stockable → skip (no movement).
- else (stockable) → `processSimpleLine` — decrement the product itself.

This **narrows** ADR-0026 D-7: a manufacturing BOM is still explodable at issue for a *non-stockable*
phantom, but never for a *stockable* make-to-stock finished good.

## Consequences

- **Fixed:** selling/delivering a manufactured, stocked finished good now relieves that finished good;
  its components are no longer double-consumed.
- **Preserved:** `product_components` kits (stockable or not) still explode (the ADR-0010 D-8 case,
  covered by `StockServiceImplIT` tests 3–4), and non-stockable BOM phantoms still explode (D-7).
- **Blast radius:** behaviour changes for exactly one shape — a **stockable** product whose only
  recipe is an **ACTIVE manufacturing BOM**. Everything else issues exactly as before.
- **Modelling guidance:** a sold bundle that should relieve its parts is a **non-stockable** phantom
  (a `product_components` kit, or a BOM phantom), **not** a stockable product with a manufacturing BOM.
- **Data reconciliation (forward-only).** This fix is prospective. Movements already mis-posted before
  the fix (components over-issued, finished goods over-stated) do **not** self-heal; posting tables are
  append-only (ADR-0009), so any environment that sold a BOM-bearing stockable finished good needs a
  separate, owner-approved correcting stock adjustment. Flagged to the owner as a follow-up, not done
  here.
