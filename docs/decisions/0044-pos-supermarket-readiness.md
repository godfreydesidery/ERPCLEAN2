# 0044 — POS supermarket-readiness: grocery gap analysis & proposed models

- **Status:** Proposed
- **Date:** 2026-06-20
- **Deciders:** owner, solutions-architect, system-analyst, backend-engineer (per-item ratification before build)
- **Context source:** the supermarket POS design review (2026-06-20); builds on ADR-0042 (POS 4-gap
  closure) and ADR-0043 (schema freeze / additive-only). Client-facing summary in
  [docs/integration/pos/12-known-limitations.md](../integration/pos/12-known-limitations.md) §7.

## Context

ADR-0042 closed the POS **transaction-integrity** gaps (sale idempotency, whole-sale reversal,
multi-tender, server-authoritative pricing). A design review then assessed the POS specifically
through a **supermarket / grocery** lens and found the remaining gaps cluster into a small number of
themes. This ADR catalogues them, proposes a data-model/API direction for each, and recommends a
phasing — so the work can be ratified and built deliberately rather than discovered piecemeal while
the external POS client is being written.

Two framings shape the whole analysis and must be stated up front:

1. **Client-state basket.** A POS sale is one `POST /api/v1/pos/sales` that finalises a complete
   basket; the cart is **client-side state** until that call. Therefore cashier-ergonomics features
   (scan loop, void-a-line / edit-qty before submit, suspend-and-recall, price-check, ×N multiplier,
   no-sale/open-drawer, peripheral integration) are the **external client's** responsibility, not
   backend gaps. The ERP ships the primitives. These are recorded as explicit **non-asks** in D-7 so
   they are not mis-scoped as backend work.
2. **An orphaned promotion engine.** A `Promotion` model exists
   (`com.erp.modules.products`: `Promotion`, `PromotionEffect` = `PERCENT_DISCOUNT`/`AMOUNT_DISCOUNT`/
   `OVERRIDE_PRICE`, `PromotionTarget` = `PRODUCT`/`CATEGORY`/`ALL`, `PricingRuleService`,
   `PromotionUsage`), configurable via the `/admin/pricing-rules` UI — **but the sale pricing path
   (`PosSaleServiceImpl` → `SalesInvoiceServiceImpl.addLine` → `resolveListPrice`) never references
   it.** Promotions are neither evaluated nor applied at sale time, and the model cannot express the
   multi-buy shapes grocery depends on. This is the single highest-leverage gap (D-2).

The transaction-integrity work is done; what remains for **grocery-grade** POS is item
identification, promotions, retail compliance, returns depth, loyalty, and tender depth.

## Decision

Adopt the following prioritised backend/design directions for supermarket-readiness. Each is a
**proposed direction**, additive-only (new fields/tables/endpoints + new `V<n>` migrations per
ADR-0043), to be ratified individually before build. Recommended phasing is in *Consequences*.

### D-1 — Grocery item identification

- **D-1a Embedded weight/price barcodes (EAN-13 type-2).** Deli/produce/butchery scale labels embed
  a **weight or price** in the barcode; the trailing digits vary per package, so the full string
  never matches a stored barcode and the current exact-match `barcode-lookup` **404s on every such
  scan**. Propose: a per-company **symbology config** (variable-prefix ranges, e.g. `02`/`20–29`;
  item-code field offset/length; embedded-value field offset/length + semantics weight-vs-price;
  check-digit handling), new `BarcodeType` values `EMBEDDED_WEIGHT` / `EMBEDDED_PRICE`, and a
  lookup that detects the prefix, extracts the **item code**, resolves the product, and returns the
  **embedded weight** (→ line `quantity`) or **embedded price** (→ line amount) for the client to
  ring. Server stays price-authoritative for fixed items; embedded-price items are the explicit,
  scale-printed exception.
- **D-1b First-class weighed goods (sell-by-weight).** The math already works (price-per-kg ×
  `quantity` via a `WEIGHT`/fractional `UnitOfMeasure`), but there is no product type that *requires*
  weighing, no **tare**, and no scale-division rounding. Propose: a `Product.isWeighed` (or
  sale-unit dimension = `WEIGHT`) flag, optional `tareWeight`, and a rounding rule, so the client and
  server agree on how a weighed line is priced and validated.
- **D-1c PLU master.** No PLU exists (`Product` has no `plu`; `barcode-lookup` is exact-string only;
  the only workaround is typing the SKU `code`). Propose a short numeric **PLU** on `Product` (or a
  dedicated PLU lookup endpoint) for ring-by-number of un-barcoded loose produce.

### D-2 — POS-applied promotions engine (highest leverage)

Two parts, both required:

- **Extend the model** to express grocery promotions the current effects cannot: **multi-buy /
  mix-and-match / BOGO / "3 for 2" / threshold ("spend X get Y")**. Propose a promotion *kind*
  (LINE vs BASKET), buy-quantity / get-quantity / get-effect, and basket-threshold fields, layered
  additively onto the existing `Promotion` model (keep `PERCENT_DISCOUNT`/`AMOUNT_DISCOUNT`/
  `OVERRIDE_PRICE` for the simple cases).
- **Wire it into the sale pricing path.** Evaluate applicable, active, in-window promotions
  **server-side** during `addLine`/`finalise` (the same place price + VAT are derived), apply the
  resulting line/basket discounts, and record `PromotionUsage`. This keeps pricing
  **server-authoritative** (consistent with ADR-0042 D-4) — the client never computes promo prices.

### D-3 — Retail compliance

- **D-3a Age-restricted products.** No restriction concept exists. Propose a product
  restriction flag (e.g. `restrictedKind` = `AGE_18`/`AGE_21`/`NONE`), and a POS gate: a sale
  containing a restricted line must carry an **age-verification acknowledgement**; the server can
  flag/record it (and optionally block without it). Legal prerequisite for alcohol/tobacco.
- **D-3b Fiscal / EFD receipt.** A VAT retailer in Tanzania/EAC is **legally required** to issue a
  fiscal-device (TRA **VFD**) signed receipt with a verification code. Propose a **pluggable,
  per-country fiscal adapter** invoked at finalise that obtains the signed fiscal payload + code and
  stores it on the invoice. This is locale-specific and sizeable — likely its **own ADR**; flagged
  here as a P1 blocker for a real TZ supermarket.

### D-4 — Returns depth

- **Partial / line-level refund** (return 1 of 5 items, or partial qty) — promote the already-deferred
  §12 #5: a credit-note-by-line path off a POS sale. **Refund-to-original-tender** (card→card,
  mobile-money→mobile-money). **No-receipt / blind return** policy + permission. Whole-sale reverse
  (ADR-0042 D-2) covers the all-or-nothing case; partial is the common grocery return.

### D-5 — Loyalty (phase-later)

No loyalty subsystem exists (no member/points/member-pricing). Propose a loyalty module — member
identity, points accrual/redemption, member pricing — as a **separate ADR** when prioritised; it is
a whole subsystem, not a POS tweak.

### D-6 — Tender depth

- **Gift card / store credit** — issue (sell/load as a line) **and** redeem (a tender type backed by
  a balance). **Vouchers / meal-vouchers**. Lower: foreign-currency tender + change, round-up
  donation, deposit/returnable-container items. The four shipped tenders (CASH/CARD/MOBILE_MONEY/
  CHEQUE, split) already cover the common case.

### D-7 — Client-responsibility boundary (explicit non-asks)

Recorded so they are **not** mis-scoped as backend work: the scan-to-line loop and throughput,
void-a-line / edit-qty / ×N multiplier / repeat-last-item **before submit**, **suspend / park &
recall**, **price-check** (a read-only `barcode-lookup` + price read), **no-sale / open-drawer**,
**supervisor-override UX**, peripheral integration (scanner / receipt printer / cash drawer /
**weighing scale** / customer-facing display), and **e-receipt rendering/delivery** are the external
client's responsibility — the ERP exposes the primitives (`barcode-lookup`, price/stock reads, the
single-call sale, the permission model). *Possible future exception:* optional **server-side
persistence of a suspended basket** (a held-sale draft) if cross-device recall is wanted — note for
a later decision, not a current ask.

## Consequences

- Every item is **additive** (new fields / tables / endpoints + new `V<n>` migrations), consistent
  with the ADR-0043 frozen-schema / durable-DB discipline — no edits to shipped migrations.
- The **promotion-engine wiring (D-2)** is the single highest-leverage change for grocery economics;
  it also retires the "orphaned engine" inconsistency.
- **Recommended phasing (proposed — the owner re-plans deliberately):**
  - **P1 — legal + core grocery (can't operate a TZ supermarket without these):** D-3b fiscal/EFD,
    D-3a age-restriction, D-1a embedded barcodes, D-1b weighed goods.
  - **P2 — margin + service:** D-2 promotions-on-POS, D-4 partial refunds.
  - **P3 — retention + tenders + convenience:** D-5 loyalty, D-6 gift cards/tenders, D-1c PLU.
- **Do not undo by accident:** D-7 records that cashier-ergonomics features are intentionally
  client-side — adding server endpoints for them would duplicate state the client already owns.
- Two items (D-3b fiscal, D-5 loyalty) are large enough to warrant **their own ADRs** when
  scheduled; this ADR records the gap and direction, not the full design.

## Alternatives considered

- **Embedded barcodes: client-parses-only vs server-aware (proposed).** A client-only parse keeps the
  backend dumb but forces every client (till, SCO, mobile) to re-implement symbology parsing and
  trust client-supplied weight/price — breaking server-authoritative pricing. Server-aware lookup
  keeps one source of truth and works for embedded-**weight** (still server-priced) cleanly.
- **Promotions: client-computed vs server-applied (proposed).** Client-computed promo prices
  contradict ADR-0042 D-4 (the client price is never trusted) and would diverge across clients.
  Server-applied at `addLine`/`finalise` is consistent and auditable via `PromotionUsage`.
- **Fiscal: hard-coded TZ VFD vs pluggable per-country adapter (proposed).** EAC has several fiscal
  regimes; a pluggable adapter avoids re-engineering the sale path per country.
- **Do nothing / "cash-and-carry only" scope.** Viable for a controlled non-fiscal pilot, but a real
  VAT supermarket selling weighed produce and age-restricted goods cannot run without P1 — so the
  gaps are recorded rather than waved off.
