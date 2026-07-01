# Product master-data ownership — who creates a SKU vs who consumes it

> Status: **RATIFIED 2026-06-28.** Decides ISSUE-006 from the 2026-06-28 business-operations
> simulation (see [docs/simulation/ISSUES-REGISTER.md](../simulation/ISSUES-REGISTER.md)). Verdict:
> **works-as-designed** — the product already enforces this rule; the simulation's expectation
> (production creates product master data) was wrong, not the product.
>
> Scope: a focused governance note, not a full re-spec of the Products module. The full Products
> requirements are in [products.md](products.md); this note pins down **only** the create/consume
> ownership question the simulation surfaced.

---

## 1. Business context

The simulation cast had two production personas — Editha Mhagama (Production Manager) and Editrude
Mwakalukwa (Production Supervisor) — on the `PRODUCTION_OFFICER` role. The world bible expected
Editha to "register manufactured products" (Tembo Cooking Oil, Bar Soap, etc.). The role holds
`PRODUCT.VIEW` but not `PRODUCT.MANAGE`, so the create form was hidden and registration was
"blocked." The question routed to system-analyst: **should a Production Officer be able to create
product master data (new SKUs)?**

A product (item) is **master data**: a controlled, shared, reference record — its identity (code,
barcode), unit-of-measure and conversion, sellable/stockable flags, VAT status, price-list links,
and cost basis. Master data is created once and consumed everywhere (sales, purchases, stock,
manufacturing, GL). A duplicate or mis-configured SKU is not a local production error; it pollutes
every downstream module — wrong VAT on every sale of it, a parallel stock balance, a broken
price list, a costing gap. That is the classic ERP master-data governance failure.

## 2. The ruling

**Product master-data creation is a controlled master-data function, owned by a master-data owner
(in this org, procurement). Production *views and consumes* products; it does not *create* master
data.**

| Capability | Permission | Who holds it |
|---|---|---|
| List / select / look up products; read a product on a work order, BOM, sale, PO | `PRODUCT.VIEW` | Everyone who consumes products: production, stores, sales, purchasing, finance (read floor — a within-tenant member can read the product list, finding F21) |
| Create a new product (SKU); edit a product; manage barcodes, pricing, status, composition | `PRODUCT.MANAGE` | The master-data owner — **procurement** in this org (a small, accountable set), **not** production |

This holds for **both** sourced (bought-to-resell) **and** manufactured (made-in-factory) SKUs.
A manufactured SKU is no less master data than a traded one — same downstream blast radius — so the
same control applies. Production does not get a side-door "manufacturing create" that bypasses the
master-data gate.

### Why production does not own product creation

- **Duplicate / inconsistent SKUs.** Uncontrolled creation across shop-floor users is the primary
  source of duplicate items ("Tembo Oil 1L" vs "Tembo Cooking Oil 1 litre") — each then carries its
  own stock balance, price, and tax, and the two never reconcile.
- **Wrong tax / costing / unit setup.** A SKU's VAT status, base unit + bulk-pack conversion, and
  cost basis drive sales VAT, stock quantities, and (later) valuation. These are finance/master-data
  decisions, not shop-floor ones.
- **Separation of duties.** The party who *runs* work orders against an item should not also be the
  party who *defines* the item, for the same control reason procurement does not approve its own POs.
- **Single accountable owner.** Master data needs one accountable owner per company so it stays
  clean; spreading create rights across operational roles defeats that.

### What production *can* do (and should)

- **View and select** any product on a work order or BOM (`PRODUCT.VIEW`).
- **Request** a new manufactured SKU when a new product line is introduced — production specifies
  what the product is (name, unit, intended composition); the master-data owner creates the SKU with
  the correct tax/costing/unit setup, then production builds work orders and BOMs against it.

The **request path** is, in v1, an out-of-band ask (a message / ticket / a row in the open-questions
log if it needs governance) — there is **no in-app "request a SKU" workflow** in v1. If a formal
in-app product-request workflow is wanted later, it is a new requirement (logged below).

## 3. The product already enforces this (works-as-designed)

This is not a change to build — it is the product's existing, deliberate design:

- **Web.** The Products list shows to any `PRODUCT.VIEW` holder, but the **New Product** create
  form is gated `@if (canManage())`, where `canManage = session.hasPermission('PRODUCT.MANAGE')`
  (`web/src/app/features/admin/products/product-list.component.html` and `product-list.component.ts`).
  A `PRODUCT.VIEW`-only user sees the list (to select), never the create form.
- **API.** `ProductController` gates `list` / `get` / lookups on `PRODUCT.VIEW`, and every
  mutating endpoint (`create`, `update`, barcode/price/status/composition management) on
  company- or product-scoped `PRODUCT.MANAGE`. Both codes are seeded in `R__seed_permissions.sql`.

The simulation block was therefore the role-grant composition question, not a product defect. The
**verdict for ISSUE-006 is works-as-designed**: a `PRODUCTION_OFFICER` correctly cannot create
product master data; it should hold `PRODUCT.VIEW` (so it can list/select on its manufacturing
screens), and `PRODUCT.MANAGE` belongs to the procurement / master-data role.

## 4. Functional requirements

- **FR-PMDO-01** A user with `PRODUCT.VIEW` can list, search, and select products on any screen that
  consumes products (work orders, BOMs, sales, purchase orders, stock), but cannot create or edit a
  product.
- **FR-PMDO-02** Creating a new product (SKU) requires `PRODUCT.MANAGE`, scoped to the active
  company; editing a product, its barcodes, prices, status, or composition requires `PRODUCT.MANAGE`
  scoped to the product. This applies identically to sourced and manufactured products.
- **FR-PMDO-03** The Products UI shows the create form only to `PRODUCT.MANAGE` holders; a
  `PRODUCT.VIEW`-only user sees the read/select list with no create affordance.
- **FR-PMDO-04** `PRODUCT.MANAGE` is granted to the master-data owner role (procurement in the
  reference org), not to the production role. The production role holds `PRODUCT.VIEW` only with
  respect to the product master.

## 5. Business rules

- **BR-PMDO-01** A product is master data: created once by a single accountable owner per company,
  consumed read-only by every other module.
- **BR-PMDO-02** Production consumes products; it does not create or edit them. There is no
  manufacturing-side create path that bypasses `PRODUCT.MANAGE`.
- **BR-PMDO-03** The same gate (`PRODUCT.MANAGE`) governs creation of sourced and manufactured SKUs
  alike — a manufactured item is master data with the same downstream reach.

## 6. Out of scope (v1)

- An in-app "production requests a new SKU" workflow (approval, status, notify the master-data
  owner). v1's request path is out-of-band. Logged as an open question if/when wanted.
- Splitting `PRODUCT.MANAGE` into finer codes (e.g. a separate price-management or
  composition-management permission). Single `PRODUCT.MANAGE` stands in v1.

## 7. Open questions

- **OQ-PMDO-01** Does the org want a formal in-app product-request workflow (production submits a
  proposed SKU → master-data owner reviews/creates → production notified)? Decider: owner. Does
  **not** block build — the gate is correct today; this would be additive. Deferred unless requested.

## 8. Consequences for the simulation

The world bible is being corrected to match this ruling (this is a scenario fix, not a code fix):
Editha **requests** new manufactured-product SKUs; procurement (Rehema Salum / Yusuf Mbwana) owns
**product master-data creation for both sourced and manufactured goods**; `PRODUCT.MANAGE` sits with
procurement, not production. See [docs/simulation/COMPANY-SCENARIO.md](../simulation/COMPANY-SCENARIO.md).
