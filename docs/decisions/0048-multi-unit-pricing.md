# ADR-0048: Multi-unit / per-pack pricing (`product_prices.unit_id`)

- **Status:** Proposed (2026-07-04) — awaits owner sign-off on the DDL (migration-approval standing rule) and on the two flagged decisions below.
- **Deciders:** Owner + Solutions Architect
- **Deferred item:** D-1 (`docs/DEFERRED-ITEMS.md`). **Effort:** L. **Migrations:** `V80`, `V81` (provisional).
- **Related:** ADR-0007 (products module; `product_prices`, `product_bulk_packs`), ADR-0029 (deterministic price resolver — `PriceResolutionService`), ADR-0021 (SO/quotation totals), ADR-0008 (invoice totals), ADR-0043 (schema freeze / durable DB — additive-only).

## Context

`product_prices` is `UNIQUE(product_id, price_list_id)` (V3) — exactly **one price per (product, price
list)**, with no unit column. Units of measure (V4) and `product_bulk_packs` (`(product, unit,
factor_to_base)`, unique on `(product_id, unit_id)`) already model pack sizes, but pricing ignores
them. So a 500 g pack cannot be priced at 5,000 while a 1,000 g pack is 6,000 (non-linear) — the pack
is forced to a linear multiple of a single per-row price.

Two facts from the code drive this decision (verified 2026-07-04):

1. **`unitPriceAmount` is already a per-line-unit price, not a per-base-unit price.** All three totals
   calculators compute `lineNet = unitPriceAmount × quantity` where `quantity` is in the **line's
   selected unit** (`InvoiceTotalsCalculator.recompute` line 66; `SalesOrderTotalsCalculator.compute`
   line 100 — uses `getQtyOrdered()`, not `getQtyOrderedBase()`). `qtyInBase` is used only for
   stock/fulfilment/COGS, never for pricing. The natural place for a pack price is therefore the line
   unit price itself.

2. **Two divergent pricing paths exist, and the sophisticated one is dead.**
   - The **live** path: each of `SalesOrderServiceImpl.resolveListPrice` (line 762),
     `SalesInvoiceServiceImpl.resolveListPrice` (line 978), `QuotationServiceImpl.resolveListPrice`
     (line 324) grabs the **first `product_prices` row for the company** — ignoring price list **and**
     unit. POS reuses the invoice path (`PosSaleServiceImpl.addLine` → `invoiceService.addLine`, line
     160). Because the selected unit is ignored, a **pack-unit line is currently under-charged**: a
     BOX (factor 12) line stores `unitPriceAmount = base price` and the total is `base × qty_boxes`,
     not `base × 12 × qty_boxes`. This is a latent revenue bug, not just a missing feature.
   - The **dead** path: `PriceResolutionService.resolve` (customer price > promo > tier > list, ADR-0029)
     is **not injected or called anywhere** (grep: only its own interface/impl reference it). Its
     `resolveListOrTier` calls the now-to-be-ambiguous `findByProductIdAndPriceListId`.

   Wiring the dead resolver would *activate* customer prices, promotions and quantity tiers — a large
   behaviour change out of scope for D-1 (see "Decisions requiring the owner").

## Decision

### 1. Key per-unit pricing on `unit_id` (nullable), not `bulk_pack_id`

Add nullable `product_prices.unit_id` (FK → `units_of_measure(id)`).
- `unit_id IS NULL` = the **base-unit price** — every existing row, so back-compat is automatic.
- `unit_id` set = a **per-unit (pack-specific, possibly non-linear) price**.

Rationale for `unit_id` over `bulk_pack_id`:
1. The sales line already carries `unit_id` (`AddSalesOrderLineRequest.unitUid` → `SalesOrderLine.unitId`).
   Resolution is a direct `(product, unit_id)` lookup with no hop through `product_bulk_packs`.
2. `bulk_pack_id` cannot represent the base unit (base has no pack row) — it would still need a NULL
   base row, so it buys nothing there while adding a join.
3. `unit_id` is stable across pack-row churn (a `product_bulk_pack` row can be deleted and recreated
   with a new surrogate id; the unit id is stable).
4. It matches the existing schema idiom — `product_barcodes.uom_id` (V3) already keys a child on a unit.
5. `product_bulk_packs` remains the **single source of truth for conversion** (`factor_to_base`); we
   only decline to key the *price* row on the pack's surrogate id.

**Invariant:** a per-unit price row's `unit_id` must be the product's base unit (→ coerced to the NULL
base row) **or** a configured `product_bulk_pack` unit. A price for a unit with no factor is unusable —
`computeQtyInBase` already rejects such a unit at line-add — so `ProductServiceImpl.setPrice` enforces
this and keeps pricing aligned with conversion.

### 2. Resolution + fallback (unit-aware list price)

`unitPriceAmount` continues to mean **"price for one of the line's selected unit."** Resolution for
`(product, unit)`:

1. If `unit` is a configured pack **and** a `product_prices` row exists for `(product, unit_id=unit)`
   → use that amount (explicit, non-linear).
2. Else use the base row (`unit_id IS NULL`) `amount × factor_to_base(unit)` — `factor = 1` when
   `unit` is the base unit (linear fallback; `factor_to_base` from `product_bulk_packs`).
3. If `unit` is neither the base nor a configured pack → reject (same message/path as
   `computeQtyInBase`).

This fixes the under-charge bug (pack lines now scale by factor) *and* enables non-linear overrides.
`qtyInBase` semantics are unchanged.

### 3. Own the resolution in the products module; do **not** wire the full resolver

The unit-aware list-price resolution (per-unit row → base×factor fallback, plus the `product_bulk_packs`
factor lookup) is centralised in the **products** module (which owns both tables) as one narrow method,
and the three sales callers delegate to it — replacing their three copies of `resolveListPrice`. D-1
deliberately does **not** activate `PriceResolutionService.resolve` (customer/promo/tier); that is a
separate decision with its own blast radius. The narrow method is shaped so a later unification can
absorb it, and `ResolvePriceRequest`/`resolveListOrTier` are made per-unit-correct at the same time so
the dead path is not left with a latent bug for whoever eventually wires it.

`ModuleBoundaryTest` permits this: it enforces controller↛repository, service↛controller and the audit
append-only rule only; sales already depends on `products` repositories/entities (the ADR-0029 intent
was for sales to call `PriceResolutionService`).

### 4. Migration (additive, two parts — DDL pending owner approval)

**V80** (transactional): add the column + FK. All-NULL on the populated table, so the FK validates
instantly.

```sql
ALTER TABLE product_prices ADD COLUMN unit_id BIGINT NULL;
ALTER TABLE product_prices
    ADD CONSTRAINT fk_product_price_unit
        FOREIGN KEY (unit_id) REFERENCES units_of_measure (id);
```

**V81** (transactional): repartition uniqueness into two partial unique indexes. Postgres treats
NULLs as DISTINCT, so a single `UNIQUE(product, price_list, unit)` would permit many base rows — hence
the split. **Plain (transactional) index builds** — this repo has **no non-transactional migration
pattern** (no `.conf` sidecars / `executeInTransaction` wiring; V78's header documents this and
deliberately avoids `CREATE INDEX CONCURRENTLY` for the same reason). `product_prices` is small, so a
brief share-lock build is fine, and running the whole migration in one transaction makes the
constraint→index swap atomic (no uniqueness gap). `DROP INDEX IF EXISTS` guards keep it idempotent.

> **Correction (build, 2026-07-04):** the original draft used `CREATE INDEX CONCURRENTLY` +
> `-- flyway:executeInTransaction=false`. A throwaway-DB boot proved Flyway rejects it here —
> *"Detected both transactional and non-transactional statements within the same migration"* — because
> this project doesn't enable the non-transactional/mixed mode. Switched to plain index builds per the
> V78 precedent.

```sql
DROP INDEX IF EXISTS uq_product_price_base;
CREATE UNIQUE INDEX uq_product_price_base
    ON product_prices (product_id, price_list_id)
    WHERE unit_id IS NULL;

DROP INDEX IF EXISTS uq_product_price_pack;
CREATE UNIQUE INDEX uq_product_price_pack
    ON product_prices (product_id, price_list_id, unit_id)
    WHERE unit_id IS NOT NULL;

-- old table-wide unique is now subsumed by uq_product_price_base
ALTER TABLE product_prices DROP CONSTRAINT IF EXISTS uq_product_price;
```

This supersedes the `bulk_pack_id` drafts in `docs/proposed-migrations/V80*/V81*` (which are revised to
`unit_id` and to the gap-free ordering). Versions are provisional — re-verify next-free vs `origin/develop`
at build time (current max applied = V79).

## Consequences

- **Positive:** non-linear per-pack pricing; the pack-unit under-charge bug is fixed; existing rows
  (all NULL unit) behave exactly as today (zero backfill, zero regression for base-unit sales); one
  resolution method instead of three copies; pricing stays owned by `products`.
- **Behaviour change (flagged):** any line currently entered in a **pack unit** starts pricing at
  `base × factor` (or the explicit pack price) instead of `base × 1`. This is a correction, but it
  changes historical-style totals for pack-unit sales — needs owner acknowledgement + a UAT pass.
- **Contract changes:** `ProductPriceDto` gains `unitUid/unitCode/unitName` (null = base);
  `SetProductPriceRequest` gains optional `unitUid`; the delete endpoint gains an optional `unitUid`
  query param (absent = base row, back-compatible). `ProductPriceRepository.findByProductIdAndPriceListId`
  (now ambiguous) is replaced by base/per-unit variants — the `setPrice`/`removePrice` upsert and the
  dead resolver are updated accordingly.
- **Watch:** brief maintenance-window index build (small table); `CREATE INDEX CONCURRENTLY` recovery
  needs the `DROP INDEX IF EXISTS` guards (included).
- **Out of scope (separate follow-ups):** activating `PriceResolutionService` (customer/promo/tier);
  price-list-aware selection in sales (today's live path ignores price list entirely); the UoM-seeder
  fix (GRAM/KG/LITRE/ML seeded as COUNT / `decimal_places = 0`).

## Decisions requiring the owner

1. **Confirm the two-path stance.** Keep `PriceResolutionService` dead in D-1 (recommended), or fold
   its activation into this work (much larger blast radius — customer prices, promotions, tiers all go
   live). Recommendation: keep out; separate ADR.
2. **Confirm the under-charge correction** is acceptable as part of D-1 (it changes totals for any
   pack-unit line).
3. **Approve the V80/V81 DDL + versions** before the migration files are created (migration-approval
   standing rule).
4. **UoM-seeder secondary fix:** recommend **OUT** of this PR (separate, unrelated data-seed change).

## Alternatives considered

- **Key on `bulk_pack_id`** (the original draft) — rejected: adds a join, cannot key the base price,
  couples the price row to pack-row lifecycle. `unit_id` is strictly simpler for the line's needs.
- **Non-linear via a `pricing_mode` + tier table per pack** — over-built; quantity tiers already exist
  (`price_tiers`) and a per-unit row on `product_prices` is the minimal expression of "this pack costs
  X."
- **Store the price per base unit and multiply by factor in totals** — rejected: it contradicts the
  existing totals math (`unitPrice × qty_in_line_unit`) and cannot express non-linear pack prices at
  all. It would also require rewriting all three totals calculators.
- **Wire the full `PriceResolutionService` now and add `unitId` to `ResolvePriceRequest`** — correct
  long-term direction, but it activates dormant customer/promo/tier pricing, which is a distinct
  behaviour change; deferred to its own ADR (this ADR makes `ResolvePriceRequest` per-unit-ready so
  that work is unblocked).
