# Requirements — Products / Items (the catalogue)

- **Module:** Products (the product/item master catalogue)
- **Status:** Draft for owner review (requirements only — no schema/design here)
- **Depends on:** IAM (org → company → branch, permissions), Parties (scoping pattern),
  multicurrency principle (ADR-0005 — money is always amount + currency)
- **Next step:** solutions-architect turns this into the data model + ADR-0007 (on owner go-ahead)

## 1. Business context & why now

The company **produces, buys, and sells** things. Before Sales, Purchases, or Stock can exist, the
business needs a **catalogue** of what those things are: a restaurant's *Ugali*, *Meat*, and the
*Ugali Meat* dish it sells; a shop's *bottled water* sold by the piece or by the crate; a workshop's
*repair service*. This module owns that catalogue — the **master definition** of each product/item —
and nothing else moves until it exists.

A product here is a **definition**, not a quantity. "How many crates of water are in the Dar branch
right now" is **stock-on-hand**, which belongs to the future **Stock** module. This module says *what
bottled water is* (a stockable good, base unit = piece, sold in crates of 12, two barcodes, on the
Retail and Wholesale price lists); the Stock module will later say *how much there is and where*.

### Vocabulary distinction (read this first)

- **Product / Item** — a catalogue entry: something the company produces, buys, or sells. Used
  interchangeably; "product" is the canonical term here.
- **Goods** — a tangible product (bottled water, ugali, a spare part).
- **Service** — an intangible product (a repair, a delivery, a restaurant dish-as-service).
- **Sellable** — may appear as a line on a customer sale.
- **Stockable** — quantities are tracked in inventory (has stock-on-hand, in the future Stock module).
  These two flags are **independent** of type and of each other (see §2).
- **Unit of measure (UoM)** — how the product is counted: a **base unit** (piece, kg, litre) and
  optional **bulk packs** (carton, crate) that convert to the base.
- **Barcode** — a scannable identifier on a product (a product may have several).
- **Price list** — a named set of selling prices (Retail, Wholesale, …); a product appears on lists.
- **Composition / Recipe / BOM** — the list of **component** products (and quantities) that make up a
  **composed** product (Ugali Meat = 1 Ugali + 1 Meat).
- **Stock-on-hand** — the quantity of a stockable product at a branch. **NOT this module** — it is
  the future Stock module. Products here are the catalogue, never the quantity.

## 2. Scope

### In scope (v1 — "define the catalogue")

- A **Product** master (create/view/list/search/update/archive/restore), scoped per-company and
  associated to branches (mirrors Parties).
- **Type**: goods or service.
- Independent **flags**: sellable / non-sellable, stockable / non-stockable.
- **Units of measure**: a base unit + optional bulk pack(s) with a conversion factor.
- **Multiple barcodes** per product (one primary).
- **Price lists**: a product on one or more named price lists, each price currency-aware; plus a
  cost price.
- **Single-level composition / recipe**: a composed product's components + quantities.
- Per-company scope + multi-branch association; search by name / code / barcode.

### Out of scope for v1 — Deferred (captured, not built)

- **Multi-level / nested BOM** — a component being itself a composed product, expanded recursively.
  v1 is single-level only.
- **Automatic stock deduction & cost roll-up on sale/production** — when a composed product is sold,
  v1 does **not** deduct component stock or compute cost from components. That is Stock/Sales work.
- **Date-effective pricing & quantity-break tiers** — prices have no valid-from/to and no "10+ units
  cheaper" rules in v1.
- **Arbitrary multi-unit** — more than base + bulk packs (e.g. an open-ended unit graph) is deferred;
  base + bulk pack(s) is the v1 shape.
- **Batch / serial / lot / expiry tracking** — a Stock-module concern.
- **Product variants / attributes** (size, colour, flavour as a variant matrix) — deferred.
- **Product categories / groups** — see OQ-PROD-04 (may be pulled into v1 if the owner wants it).

### Explicitly NOT this module

- **Stock-on-hand quantities** — the future Stock module. Products are definitions, not levels.
- **The act of buying/selling** — Purchases / Sales modules. This module is referenced by them.

## 3. The product catalogue

### 3.1 v1 concepts (built now)

| Concept | One-line definition | Notes |
|---|---|---|
| **Product (good)** | A tangible item the company stocks/buys/sells. | May be stockable & sellable, or stockable & non-sellable (a raw input), etc. |
| **Product (service)** | An intangible the company sells/provides. | Normally non-stockable (BR-PROD-01); may be sellable. |
| **Sellable flag** | May appear on a customer sale. | Independent of type. A raw good may be non-sellable. |
| **Stockable flag** | Inventory quantities are tracked. | Independent of type. Services are non-stockable. |
| **Base unit** | The smallest unit the product is counted in. | piece, kg, litre, … Stock is held in base units. |
| **Bulk pack** | A larger unit with a conversion to base. | carton = 24 pieces; crate = 12 bottles. Buy/sell in base or bulk. |
| **Barcode** | A scannable identifier. | A product may have several; one primary. Unique per company. |
| **Price list** | A named selling-price set. | Retail / Wholesale / Distributor. A product can be on several. |
| **Cost price** | What the product costs the company. | Tracked separately from selling prices. |
| **Composition / recipe** | Components + quantities of a composed product. | Single-level; Ugali Meat = 1 Ugali + 1 Meat. |

### 3.2 Deferred concepts (recognised, NOT in v1)

Multi-level/nested BOM · auto stock-deduction & cost roll-up · date/qty-tier pricing · arbitrary
multi-unit · batch/serial/lot/expiry · variants & attribute matrices · categories/groups (OQ-PROD-04).

## 4. Actors / personas

- **Catalogue administrator / manager** — creates and maintains products, units, barcodes, prices,
  recipes; assigns products to branches. (Holds a `PRODUCT.MANAGE`-style permission.)
- **Branch operator (sales / stock / purchasing officer)** — selects products on transactions; sees
  only products associated with their active branch. (Holds a `PRODUCT.VIEW`-style permission.)
- **Restaurant / production user** — defines composed products (recipes) from components.

## 5. Functional requirements

> IDs are `FR-PROD-NN`. Each is a crisp, testable statement. "Product" = any catalogue item unless a
> specific type (good/service) or kind (composed/simple) is named.

### Core record & lifecycle

- **FR-PROD-01** The system maintains a **Product** master: create, view, list/search, update,
  archive (soft-delete), and restore. A product carries its definition (type, flags, units, barcodes,
  prices, composition) independently of any stock quantity.
- **FR-PROD-02** Each product is **soft-deletable**: archiving sets it inactive without destroying
  history; an archived product is excluded from selection on new transactions (BR-PROD-10) but remains
  on historical documents and remains restorable.

### Type & flags

- **FR-PROD-03** Each product has a **type**: `goods` or `service`. The type is recorded on the product.
- **FR-PROD-04** Each product carries two **independent flags**: **sellable** (may appear on a sale)
  and **stockable** (inventory is tracked). The flags are independent of the type and of each other,
  subject to the validations in §6 (e.g. a service is non-stockable, BR-PROD-01).

### Units of measure

- **FR-PROD-05** A **stockable** product has a **base unit** (e.g. piece, kg, litre) — the unit its
  stock is held and counted in. A non-stockable product may still carry a unit for pricing/labelling
  but has no stock semantics (BR-PROD-02).
- **FR-PROD-06** A product may define **one or more bulk packs**, each a named larger unit (e.g.
  *carton*, *crate*) with a **conversion factor** to the base unit (e.g. carton = 24 pieces). Buying
  and selling may be expressed in the base unit or any defined bulk pack; quantities convert to base.
- **FR-PROD-07** A bulk pack's **conversion factor must be greater than zero** (BR-PROD-03); the base
  unit's factor is implicitly 1.

### Barcodes

- **FR-PROD-08** A product may have **multiple barcodes** (e.g. for different packagings or supplier
  barcodes). Exactly one may be marked **primary**.
- **FR-PROD-09** A **barcode is unique within the company** (BR-PROD-07): scanning a barcode resolves
  to at most one product in that company. Search/selection supports **lookup by barcode**.

### Pricing

- **FR-PROD-10** The system maintains named **price lists** (e.g. Retail, Wholesale, Distributor) per
  company. A product may appear on **several price lists**, with a price per list.
- **FR-PROD-11** Every product price is **currency-aware**: a price is an amount **plus a currency**
  (per the multicurrency principle / ADR-0005); never a bare number.
- **FR-PROD-12** A product tracks a **cost price** (currency-aware), separate from its selling prices,
  for margin/valuation purposes.
- **FR-PROD-13** Selling-price selection at sale-time picks the **applicable price list** (the Sales
  module's job). This module only defines which lists a product is on and at what price.

### Composition / recipe (single-level)

- **FR-PROD-14** A product may be **composed**: it defines a **single-level list of component
  products**, each with a **quantity** (e.g. *Ugali Meat* = 1 × *Ugali* + 1 × *Meat*). The components
  are existing products in the same company.
- **FR-PROD-15** A composed product is itself a normal catalogue product: it has a type (e.g. a
  restaurant dish is a **service**), is **sellable**, and carries its **own price** on price lists
  (OQ-PROD-06 — independently priced in v1; components are recorded for display and for the future
  stock-deduction feature, not to derive the price).
- **FR-PROD-16** A product **cannot be a component of itself** (BR-PROD-05). v1 composition is
  single-level, so deeper cycles cannot arise; if/when nesting is added, cycle prevention extends.
- **FR-PROD-17** v1 records the **recipe structure only**: defining a composition does **not** move or
  deduct component stock and does **not** roll up cost from components. (Auto stock-deduction & cost
  roll-up are deferred to Stock/Sales — see §2 and the accepted-risk note §9.)

### Per-company scope & multi-branch association

- **FR-PROD-18** Every product **belongs to exactly one company** and carries that company association;
  a product is never company-less and its company never changes by edit (BR-PROD-02).
- **FR-PROD-19** Product lists, searches, and selection are **scoped by company**: a user working in a
  company sees only that company's products.
- **FR-PROD-20** A product is **associated with one or more branches of its company** — a many-to-many
  *business association* (a product is sold/stocked at specific branches; a branch has many products).
  Describe this as a relationship, not a table.
- **FR-PROD-21** An administrator can **browse a product's branch associations and add or remove
  branches** (within the product's company), so the product becomes usable at, or hidden from, a branch.
- **FR-PROD-22** Product selection on transactions is **filtered by the active branch**: a branch
  operator sees only the products associated with their active branch (and only their company's —
  FR-PROD-19).

### Identification & search

- **FR-PROD-23** Each product has a human-usable **code unique within its company** for selection and
  reference on documents (BR-PROD-08, numbering scheme OQ-PROD-01). The system supports **search by
  name, code, and barcode**.

### Permissions (gating)

- **FR-PROD-24** All product operations are gated by IAM permissions (e.g. a `PRODUCT.MANAGE`-style
  permission to create/edit and manage prices/recipes/branch-associations; a `PRODUCT.VIEW`-style
  permission to view/select). Exact codes are seeded with the module; this FR only fixes that product
  operations are permission-gated per IAM.

## 6. Business rules (invariants)

- **BR-PROD-01** A **service is non-stockable** — a product of type `service` cannot be marked
  stockable. (Goods may be stockable or not.)
- **BR-PROD-02** **Only a stockable product has stock/unit semantics.** A non-stockable product carries
  no base-unit stock meaning (it may still have a unit for pricing/labelling, but no stock-on-hand will
  ever exist for it in the Stock module).
- **BR-PROD-03** A bulk-pack **conversion factor must be > 0**; the base unit's factor is 1. Two bulk
  packs of the same product must have distinct names.
- **BR-PROD-04** A product's **price currency must be a valid, active currency** (ADR-0005); a price
  has both an amount and a currency, never an amount alone.
- **BR-PROD-05** A product **cannot be its own component** (no self-composition). Components of a
  composed product must be existing, non-archived products.
- **BR-PROD-06** A composed product's **components must belong to the same company** as the composed
  product (mirrors BR-PROD-09 / BR-PARTY-01 tenancy rule).
- **BR-PROD-07** A **barcode is unique within the company** (OQ-PROD-02 covers whether org-wide is ever
  needed). At most one barcode per product is primary.
- **BR-PROD-08** A product's **code is unique within its company** (numbering per OQ-PROD-01).
- **BR-PROD-09** A product's **associated branches must all belong to the product's company.** A
  product cannot be associated with a branch of another company (mirrors BR-PARTY-01).
- **BR-PROD-10** An **archived product is not selectable** on new transactions (sales, purchases, stock
  moves) but remains on historical documents and is restorable.
- **BR-PROD-11** A **sellable product should have at least one price** (on some list) **before it can
  be sold** — enforced at **sale-time** (the Sales module), **not** at product-create time. A product
  may be created without a price and priced later (OQ-PROD-03). This rule is recorded here as the
  expectation Sales will enforce.

## 7. Non-functional

- **NFR-PROD-01** Barcode lookup must be fast (a scan at POS) — effectively an indexed, exact lookup
  within the company.
- **NFR-PROD-02** Product list/search must page and remain responsive with large catalogues.
- **NFR-PROD-03** All persisted timestamps are UTC; money is currency-aware per ADR-0005.
- **NFR-PROD-04** Operations are auditable consistent with the IAM audit trail (create/update/archive,
  price changes, branch-association changes leave an audit record).

## 8. Assumptions

- Products are the **catalogue**; **stock-on-hand** is a separate future module — no inventory math,
  levels, or movements live here.
- The first money-bearing concepts (prices, cost) use the existing `Money` (amount + currency)
  building block introduced with Parties (ADR-0005), defaulting to TZS but never hard-coded.
- Base + bulk-pack units cover the owner's "qty + bulk qty"; richer unit graphs are deferred.

## 9. Accepted risk / assumption — single-level recipe, no stock movement (v1)

v1 captures a composed product's **recipe structure** and sells the composed product as a **single
priced line** (e.g. "Ugali Meat — 5,000 TZS"). It does **NOT** yet:

- deduct the component products' stock when the composed product is sold/produced, or
- roll the composed product's cost up from its components, or
- expand nested recipes (components that are themselves composed).

This is deliberate: inventory mechanics belong to the Stock module. **No one should assume that
selling a composed product moves component inventory in v1.** When Stock/production lands, the recipe
captured here becomes the input to component deduction and cost roll-up — so capturing it now is the
right preparation, with the mechanics deferred. (Owner-confirmed: composition is 1-level, no
auto-stock in v1.)

## 10. Out of scope for v1 (deferred — restated)

Multi-level/nested BOM · automatic stock deduction & cost roll-up on sale/production · date-effective
& quantity-break pricing · arbitrary multi-unit graphs · batch/serial/lot/expiry tracking · product
variants & attribute matrices · product categories/groups (pending OQ-PROD-04) · per-product
tax/VAT-applicability (pending OQ-PROD-05, likely needed for Sales).
