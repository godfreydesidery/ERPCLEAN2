# 0007 — Products data model: one product master, per-company, multi-branch, with units / barcodes / price lists / single-level composition

- **Status:** Accepted
- **Date:** 2026-06-06
- **Deciders:** solutions-architect (owner-ratified requirements + owner rulings on every confirmed Products decision)
- **Context source:** [docs/requirements/products.md](../requirements/products.md) (RATIFIED — FR-PROD-01..24,
  BR-PROD-01..11, §9 accepted-risk on single-level BOM with no stock movement, the deferred list §2/§10,
  OQ-PROD-01..07); [ADR-0006](0006-parties-data-model.md) (THE pattern to mirror — per-company masters,
  singular link tables, `PartyBranchGuard` company-consistency, application-assigned per-(company,kind) numbering
  under `SELECT FOR UPDATE`, `ScopeGuard.companyIdOf` targetTypes, audit emits, permission seed + additive
  ORG_ADMIN grant, additive migration, uid/id + Long-as-string + `PageMeta`); [ADR-0005](0005-money-and-currency.md)
  (money is a `Money` `@Embeddable` — `amount` NUMERIC(19,4) + `currency` CHAR/VARCHAR(3); every price and cost is a
  `Money` pair; wire `{amount:string,currency}`); ADR-0004 (audit emit points, `target_type`, `detail` policy);
  ADR-0002 (RBAC permission + scope, `ScopeGuard`); ADR-0001 (D-A tenancy, D-G uid/ULID + internal-table rule);
  [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) §2 (module layout), §3.2 (tenant predicate), §3.3 (uid/id);
  [DATA-MODEL.md](../../DATA-MODEL.md) (table style); the shipped [V2__parties.sql](../../backend/src/main/resources/db/migration/V2__parties.sql)
  (table style, plural masters, singular links, partial-unique pattern, seed pattern) and
  [Money.java](../../backend/src/main/java/com/erp/platform/common/money/Money.java) (the existing embeddable to reuse).

This ADR is the **technical data model** for the Products module. It translates the ratified business spec into
tables, columns, types, keys, indexes, constraints, and enforcement placement — concrete enough that the backend
engineer writes **`V3__products.sql`** and the entities **without guessing a business rule**. It does **not** write
production code, entities, or the migration — that is the engineer's next step. The owner's confirmed decisions
(one product master per company, multi-branch association, type goods/service with independent sellable/stockable
flags, base unit + bulk packs, multiple barcodes one primary, named price lists + cost price, single-level
composition recording structure only) are taken as given and designed to exactly.

## Context

Products is the **catalogue** that Sales, Purchases, and Stock all consume; none of them can transact until it
exists (products.md §1). The module is a near-twin of Parties in shape — a per-company master, multi-branch
association, per-company human code, soft-delete/archive, audit — so the central architectural force is **mirror
ADR-0006's resolved patterns, do not invent new ones**, and resolve only the genuinely new modelling questions
Products introduces. Those new questions, and the forces around each:

- **Unit of measure (the one genuinely open modelling call).** A stockable product has a **base unit** (piece, kg,
  litre) and zero-or-more **bulk packs** with a conversion factor (FR-PROD-05/06/07, BR-PROD-03). The spec leaves
  open whether "unit" is a free string, a per-company unit master, or an enum (task §3). Forces: a master table buys
  referential integrity and a clean per-company pick-list but adds a table, a seed, and a numbering/lifecycle
  burden before the business has asked for unit administration; a free string is the boring, zero-ceremony choice
  but permits "pcs" vs "piece" drift; an enum is too rigid (every business invents its own units). Resolved in D-3.
- **Single product master vs split per concern.** Parties chose *four* masters because the owner ruled separate
  records (ADR-0006 D1). Products has **one** thing — a product — with flags and a type, not four kinds of record.
  One master is the obvious call; the only question is module naming (`products` vs `catalog`), resolved in D-1.
- **Barcodes and prices: child tables vs columns.** A product has *multiple* barcodes (one primary) and a price on
  *multiple* price lists — both inherently one-to-many, so both are child tables, not columns (D-5, D-7). The cost
  price is exactly one money value per product, so it is a `Money` pair **on the product row** (D-7), not a child.
- **Composition without mechanics.** v1 records the recipe structure only — no stock movement, no cost roll-up, no
  nesting (products.md §9, FR-PROD-17). The model is a self-referential child table with a self-reference guard and
  a same-company guard; multi-level/cycle handling is explicitly deferred (D-8).
- **Conditional invariant: service ⇒ non-stockable (BR-PROD-01).** A single-row CHECK can express it cleanly, so
  unlike most of Parties' conditional rules this one lands at the DB (D-2). The cross-entity rules (branch-company,
  component-company, self-composition) cannot be cheap DB constraints and live in service guards (D-9), exactly the
  DB-can't / service-must split ADR-0006 D-4 established.
- **Numbering.** Per-company product code, concurrency-safe (FR-PROD-23, BR-PROD-08). Parties solved the identical
  problem with `party_code_sequence` under `SELECT FOR UPDATE`; Products reuses the *mechanism*, and the only
  decision is whether to share that table or own one (D-6).
- **Schema freeze / migration ordering.** IAM is `V1` (frozen), Parties is `V2`. Products is a **new** module and
  lands as a purely **additive `V3__products.sql`** — never a V1/V2 edit (D-13).

## Decision

### D-1 — Module placement: one `com.erp.modules.products` module

The catalogue lives in a **single** module `com.erp.modules.products` with the standard internal layout:

```
com.erp.modules.products
├── domain.entity   Product, ProductBranch, ProductBarcode, ProductBulkPack,
│                   PriceList, ProductPrice, ProductComponent (link/child entities)
├── domain.dto      ProductDto, ProductSummaryDto, CreateProductRequest, UpdateProductRequest,
│                   ProductBarcodeDto, ProductBulkPackDto, PriceListDto, ProductPriceDto,
│                   ProductComponentDto, AssignProductBranchRequest, …
├── domain.enums    ProductType (GOODS | SERVICE)
├── repository      ProductRepository, ProductBranchRepository, ProductBarcodeRepository,
│                   ProductBulkPackRepository, PriceListRepository, ProductPriceRepository,
│                   ProductComponentRepository, ProductCodeGenerator-backed sequence repo
└── service         ProductService(+Impl), PriceListService(+Impl),
                    ProductCodeGenerator (D-6), ProductBranchGuard (D-9)
```

**Why `products`, not `catalog`:** the spec, the requirements file, the vocabulary, and the owner all say
"product/item" (products.md §1, "product is the canonical term"). `catalog` is a vaguer umbrella that would later
compete for naming with categories/groups (OQ-PROD-04) and pricing as separate concerns. Keeping the module name
equal to the dominant noun is the boring, legible choice and matches the Parties precedent (the module is named for
its masters). Controllers stay flat in `com.erp.api` — `ProductController`, `PriceListController` — and touch only
services (PROJECT-CONVENTIONS §2; `ModuleBoundaryTest`).

**Why one module, not products + pricing:** price lists and prices are attributes of the catalogue, share its
tenant spine (`company_id`), its uid/id discipline, and its audit, and have no independent consumer that would
justify a boundary. Splitting them now would force a shared sequence/guard into `platform` prematurely (the exact
anti-pattern ADR-0006 D-1 rejected). Sales/Purchases/Stock are **transaction** modules that *consume*
`products.domain.dto` (price lookup, product selection) — they are not the home of the catalogue.

> Boundary note for `ModuleBoundaryTest`: `products` is **self-contained** — it reads IAM only through the existing
> tenant/scope spine (`RequestContext`, `ScopeGuard`), the same as Parties. Unlike Parties (which reaches IAM for
> the agent→user link), Products has **no cross-module entity dependency** at all. There is no new boundary
> allowlist entry. Sales/Purchases/Stock will depend on `products.domain.dto`, never on entities or repositories.

### D-2 — One master table `products` (plural), `UidEntity`-style, per-company

A single master table **`products`** extending the `UidEntity` shape (id + uid + version + audit columns +
`status`), plural per the entity-table convention shipped in V2. Every row carries `company_id BIGINT NOT NULL`
(FR-PROD-18, BR-PROD-02) and participates in the §3.2 tenant predicate. There is **no `branch_id` on the master
row** — a product is company-scoped and *associated with many branches* via `product_branch` (D-4); a single
`branch_id` would contradict the many-to-many. This is the same per-table stance Parties documented:
**company-scoped at the row, branch-scoped via association.**

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal FK target |
| `uid` | VARCHAR(26) | NO | ULID; `uq_product_uid`; URLs address by uid (ADR-0001 D-G) |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope (FR-PROD-18, BR-PROD-02); **never updated** |
| `code` | VARCHAR(20) | NO | per-company sequence value, e.g. `PROD-0001` (D-6); `uq_product_company_code` |
| `name` | VARCHAR(200) | NO | the name shown on selection lists / documents (FR-PROD-01, search target FR-PROD-23) |
| `description` | VARCHAR(500) | YES | optional long description / label text |
| `type` | VARCHAR(20) | NO | enum `GOODS` \| `SERVICE` (FR-PROD-03); CHECK `IN ('GOODS','SERVICE')` |
| `sellable` | BOOLEAN | NO | DEFAULT true; may appear on a sale (FR-PROD-04) |
| `stockable` | BOOLEAN | NO | DEFAULT true; inventory tracked (FR-PROD-04); gated by CHECK below (BR-PROD-01) |
| `base_unit` | VARCHAR(40) | NO | base unit-of-measure label, e.g. `piece`, `kg`, `litre` (D-3, FR-PROD-05); factor 1 implicit |
| `cost_amount` | NUMERIC(19,4) | YES | `Money` pair with `cost_currency` (ADR-0005 D-1/D-2); cost price (FR-PROD-12) |
| `cost_currency` | VARCHAR(3) | YES | ISO 4217 code; null/non-null **together** with `cost_amount` (CHECK + `Money` embeddable) |
| `status` | VARCHAR(32) | NO | `MasterStatus` ACTIVE \| INACTIVE \| ARCHIVED; archive = soft-delete (FR-PROD-02, BR-PROD-10) |
| `version` | BIGINT | NO | optimistic lock, DEFAULT 0 |
| `created_at` / `created_by` / `updated_at` / `updated_by` | TIMESTAMPTZ / BIGINT | mixed | standard audit columns (`*_by` → `app_user.id`) |

**Constraints on `products`:**
- `uq_product_uid UNIQUE (uid)` — ULID, global.
- `uq_product_company_code UNIQUE (company_id, code)` — code unique per company (BR-PROD-08); backstop for D-6.
- `fk_product_company FOREIGN KEY (company_id) REFERENCES companies (id)`.
- `chk_product_type CHECK (type IN ('GOODS','SERVICE'))` — D-3 enum-as-VARCHAR, same as Parties D-3.
- **`chk_product_service_stockable CHECK (NOT (type = 'SERVICE' AND stockable = true))`** — BR-PROD-01, a service
  cannot be stockable. This is **unconditional and single-row-expressible**, so it is a DB CHECK (unlike Parties'
  conditional rules); the service layer gives the friendly 422 message. (Goods may be stockable or not — no CHECK.)
- `chk_product_cost_pair CHECK ((cost_amount IS NULL AND cost_currency IS NULL) OR (cost_amount IS NOT NULL AND cost_currency IS NOT NULL))`
  — the `Money` null-together rule (ADR-0005 D-1), mirroring `chk_customer_credit_pair` in V2.

> **Why one master, not a master + a `service`/`goods` subtype table:** `type` + two boolean flags is the whole of
> the type/flag model (FR-PROD-03/04); a subtype split would add a join for two booleans and a discriminator that
> already lives in one column. One table is the boring, normal-form choice. The accepted nuance: a non-stockable
> good and a service differ only by flags, which is exactly what the spec wants (independent flags, §2).

### D-3 — Unit of measure: **free-text base unit on the product + a `product_bulk_pack` child table** (no unit master, no enum) for v1

The base unit is a **free-text `VARCHAR(40)` column on `products`** (`base_unit`, NOT NULL); bulk packs are rows in
a child table **`product_bulk_pack`**, each a named larger unit with a conversion factor to base. **No per-company
unit master table, no enum.**

`product_bulk_pack` (child of `products`):

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal key |
| `uid` | VARCHAR(26) | NO | ULID; `uq_product_bulk_pack_uid` — bulk packs are uid-addressed under their product (API edits them) |
| `product_id` | BIGINT | NO | FK → `products(id)`; ON DELETE not relied on (products soft-delete, never hard-delete) |
| `name` | VARCHAR(40) | NO | bulk-pack unit name, e.g. `carton`, `crate` (FR-PROD-06) |
| `factor_to_base` | NUMERIC(19,6) | NO | units of base per one pack, e.g. 24 (carton of 24 pieces); CHECK `> 0` (BR-PROD-03, FR-PROD-07) |
| `created_at` / `created_by` / `updated_at` / `updated_by` | TIMESTAMPTZ / BIGINT | mixed | audit columns |

**Constraints / indexes on `product_bulk_pack`:**
- `uq_product_bulk_pack_uid UNIQUE (uid)`.
- `uq_product_bulk_pack_name UNIQUE (product_id, name)` — two bulk packs of the same product must have distinct
  names (BR-PROD-03 last sentence).
- `fk_product_bulk_pack_product FOREIGN KEY (product_id) REFERENCES products (id)`.
- `chk_product_bulk_pack_factor CHECK (factor_to_base > 0)` — BR-PROD-03 / FR-PROD-07 at the DB.
- `ix_product_bulk_pack_product (product_id)` — list a product's packs.

**Why free-text base unit, not a unit master, for v1 (the recommendation):**
1. The owner has **not** asked for unit administration (no FR for managing a unit catalogue; units appear only as a
   property of a product). Introducing a `unit` master now means another master table, another per-company seed,
   another numbering/lifecycle/permission surface — speculative ceremony the spec doesn't justify. "Prefer the
   boring option."
2. `factor_to_base` makes conversion **self-contained on the product** (carton = 24 base), which is all FR-PROD-06
   needs. A unit master would not be consulted for the math; it would only constrain the *label*.
3. The accepted cost is label drift ("pcs" vs "piece") and no global pick-list — recorded honestly. The mitigation:
   the service may offer a *suggestion list* of common units (UI affordance, not a constraint), and a future
   `unit_of_measure` master is a clean **additive** migration (add the table, add a nullable `base_unit_id`
   alongside the text, backfill, then tighten) if unit standardisation is later wanted. Reserving the upgrade path
   costs nothing now.
- **`factor_to_base` scale is NUMERIC(19,6)** (quantity precision, not money) — 6 decimals gives headroom for
  fractional conversions (e.g. a 0.5 kg pack of a kg-based product). This ties to **OQ-PROD-07** (fractional unit
  precision): the same scale choice governs `product_component.quantity` (D-8). Flagged below — if the owner wants
  a different quantity scale, it is one edit before V3 ships, not after.

> Bulk packs carry a `uid` (unlike branch link rows) because they are **independently editable child records** the
> API addresses (add/rename/re-factor a pack), the same reasoning that gives price-list rows a uid. Branch
> associations (D-4) do not, because they are pure existence links addressed by product-uid + branch-uid.

### D-4 — Branch association: one link table `product_branch` (singular), company-consistency in the service

Per the singular link-table convention and the Parties precedent, one association table **`product_branch`**
realises the many-to-many between a product and the branches of its company (FR-PROD-20/21/22).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal key |
| `product_id` | BIGINT | NO | FK → `products(id)` |
| `branch_id` | BIGINT | NO | FK → `branches(id)` |
| `assigned_at` | TIMESTAMPTZ | NO | DEFAULT `now()` |
| `assigned_by` | BIGINT | NO | FK → `app_users(id)` |

- **No `uid`, no `status`/`version`** (DATA-MODEL junction convention, same as `customer_branch`) — an association
  exists or is removed (hard delete of the link row is the "remove branch" op, FR-PROD-21).
- `uq_product_branch_pair UNIQUE (product_id, branch_id)` — a product is associated with a branch at most once.
- `fk_product_branch_product`, `fk_product_branch_branch`, `fk_product_branch_by` (→ `app_users`).
- `ix_product_branch_product (product_id)` (list a product's branches — FR-PROD-21) and
  `ix_product_branch_branch (branch_id)` (the hot path: "products usable at my active branch" — FR-PROD-22, POS
  selection).

**Company-consistency (BR-PROD-09) — DB FK + service guard, same split as ADR-0006 D-4:** SQL cannot cheaply assert
`branch.company_id == product.company_id` (cross-row subquery a plain FK can't express; triggers rejected by the
owner principle). Therefore the DB enforces the two halves it can (both FKs real, `uq_*_pair` no duplicates), and a
new **`ProductBranchGuard`** (in `products.service`, modelled on `PartyBranchGuard`) asserts on every association
add that `branch.company_id == product.company_id`, else throws (mapped to 422/403). It also delegates to
`ScopeGuard.assertCanActIn(principal, product.companyId)` so the caller may only manage associations within their
active company. **FR-PROD-22 selectability** ("usable only at associated branches") is a selection-time query rule
(the selection query joins `product_branch` and filters `status='ACTIVE'`, BR-PROD-10), not a NOT NULL — a product
with zero associations is valid but appears in no branch's selection list.

### D-5 — Barcodes: child table `product_barcode`, one primary, unique-per-company (partial unique index)

Multiple barcodes per product, exactly one primary (FR-PROD-08), unique within the company (FR-PROD-09, BR-PROD-07).
Child table **`product_barcode`**:

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal key |
| `uid` | VARCHAR(26) | NO | ULID; `uq_product_barcode_uid` — barcodes are uid-addressed child records (add/remove/set-primary) |
| `product_id` | BIGINT | NO | FK → `products(id)` |
| `company_id` | BIGINT | NO | **denormalised** from the product — see note below; the per-company-uniqueness anchor and POS lookup index column |
| `barcode` | VARCHAR(64) | NO | the scannable value (EAN/UPC/QR/supplier code), opaque string |
| `is_primary` | BOOLEAN | NO | DEFAULT false; exactly one true per product (partial unique below) |
| `created_at` / `created_by` | TIMESTAMPTZ / BIGINT | mixed | audit columns (barcodes are add/remove, minimal mutation) |

**Constraints / indexes:**
- `uq_product_barcode_uid UNIQUE (uid)`.
- **`uq_product_barcode_company_value UNIQUE (company_id, barcode)`** — a barcode resolves to at most one product in
  the company (BR-PROD-07, FR-PROD-09). A plain (not partial) unique — every barcode row must be company-unique.
- **`uq_product_barcode_primary UNIQUE (product_id) WHERE is_primary`** — at most one primary barcode per product
  (FR-PROD-08), the established Postgres partial-unique pattern (cf. `uq_branch_company_default`,
  `uq_user_branch_default` in IAM).
- `fk_product_barcode_product`, `fk_product_barcode_company` (→ `companies`).
- **`ix_product_barcode_company_value (company_id, barcode)`** — satisfied by the unique constraint above; it *is*
  the fast exact-lookup index for POS scanning (NFR-PROD-01). A scan resolves with
  `WHERE company_id = :activeCompany AND barcode = :scanned` — a single index probe.
- `ix_product_barcode_product (product_id)` — list a product's barcodes.

> **Why `company_id` is denormalised onto `product_barcode`:** the per-company uniqueness (BR-PROD-07) and the POS
> lookup (NFR-PROD-01) are both **company-scoped**, and the barcode value lives on the child, not the product. To
> enforce "unique per company" as a DB constraint and to index the hot lookup without a join to `products`, the
> `company_id` must be a column on `product_barcode`. It is **set from the product at write time and immutable**
> (a product's company never changes — BR-PROD-02), so the denormalisation cannot drift. This is a deliberate,
> documented denormalisation for a hard uniqueness rule + a hot path, not incidental duplication. The same applies
> to `product_price` (D-7). The service sets it; a CHECK cannot assert it equals the parent's, so it is a service
> invariant (set-once-from-parent), backstopped by the FK to `companies`.

### D-6 — Per-company numbering: a generic `code_sequence` table, allocated under `SELECT FOR UPDATE`

Per-company product code (`PROD-0001`), unique per company, concurrency-safe (FR-PROD-23, BR-PROD-08). Reuse the
**mechanism** ADR-0006 D-7 proved (a locked counter row, not a Postgres `SEQUENCE` per company, not `MAX(code)+1`).

**Recommendation: introduce a generic `code_sequence` table** that Products uses, rather than reusing
`party_code_sequence` or minting a `product_code_sequence`:

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | |
| `company_id` | BIGINT | NO | FK → `companies(id)` |
| `entity_kind` | VARCHAR(30) | NO | the counter's domain, e.g. `PRODUCT` (future: `PRICELIST`, then Sales/Stock kinds) |
| `next_value` | BIGINT | NO | next suffix to assign; DEFAULT 1 |
| `version` | BIGINT | NO | optimistic lock |

- `uq_code_sequence UNIQUE (company_id, entity_kind)`; `fk_code_sequence_company`.
- **Allocation:** `ProductCodeGenerator.next(companyId, 'PRODUCT')` does `SELECT ... FOR UPDATE` on the
  `(company_id, 'PRODUCT')` row (creating it with `next_value = 1` on first use), reads `next_value`, formats
  `PROD-%04d` (zero-padded, widening past 9999), increments, writes back — **inside the same transaction** as the
  product insert. The row lock serialises concurrent creates for the same company; different companies/kinds don't
  contend. Concurrency-safe, gap-free, per-company.
- **Backstop:** `uq_product_company_code` turns any generator bug into a constraint violation, not a silent
  duplicate (same defence as Parties).
- **Code immutability:** `code` is assigned once, not user-editable; the service rejects updates to `code`.

**Why a generic `code_sequence`, not reuse `party_code_sequence` and not a per-module table:** `party_code_sequence`
has a `CHECK (party_kind IN ('CUSTOMER','SUPPLIER','AGENT','OTHER'))` and a parties-specific name — Products writing
`PRODUCT` into it would either break the CHECK or pollute a parties table with a foreign kind (a boundary smell: a
`products` service touching a `parties`-owned table). Minting a `product_code_sequence` works but, as Sales/Stock
arrive, repeats the same 4-column table per module — sprawl. A **generic `code_sequence` keyed by
`(company_id, entity_kind)`** is the boring consolidation: one shared, cross-cutting numbering primitive that lives
in `platform.common` (alongside `Money`), is owned by no business module, and every module's `*CodeGenerator` calls
into. Parties' existing `party_code_sequence` is **left as-is** (it shipped in V2, frozen-by-convention); the
generic table starts clean with Products. (A later, optional housekeeping ADR could migrate Parties onto
`code_sequence`; not done now — additive, not a rewrite, and out of this ADR's scope.)

> **Numbering open question OQ-PROD-01** (single `PROD-####` sequence vs category-prefixed) is resolved for v1 as
> **single per-company `PROD-####`** — categories are deferred (OQ-PROD-04), so a category prefix has nothing to
> hang on yet. If categories land and the owner wants `FOOD-0001`, the `entity_kind` discriminator already supports
> per-prefix counters additively (e.g. `entity_kind = 'PRODUCT:FOOD'`), with no schema change. Flagged below.

### D-7 — Pricing: `price_list` master (per company) + `product_price` child; cost price is a `Money` pair on `products`

**`price_list`** — a named selling-price set per company (FR-PROD-10), a small master in `UidEntity` style:

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | |
| `uid` | VARCHAR(26) | NO | ULID; `uq_price_list_uid`; URLs address by uid |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope |
| `code` | VARCHAR(20) | NO | short code, e.g. `RETAIL`, `WHOLESALE`; `uq_price_list_company_code` |
| `name` | VARCHAR(120) | NO | display name (Retail / Wholesale / Distributor) |
| `status` | VARCHAR(32) | NO | `MasterStatus`; archive hides a list from new pricing |
| `version` | BIGINT | NO | optimistic lock |
| audit cols | | | created/updated by/at |

- `uq_price_list_uid UNIQUE (uid)`; `uq_price_list_company_code UNIQUE (company_id, code)`;
  `fk_price_list_company`; `ix_price_list_company (company_id)`.
- Price-list `code` is **user-supplied** (short mnemonic like `RETAIL`), not auto-numbered — there are few lists per
  company and they want meaningful codes; unique-per-company enforces no collision. (No `code_sequence` row for
  price lists in v1; if auto-numbering is later wanted, `entity_kind='PRICELIST'` is ready.)

**`product_price`** — a product's price on a list (FR-PROD-10/11), a child with a `Money` pair:

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | |
| `product_id` | BIGINT | NO | FK → `products(id)` |
| `price_list_id` | BIGINT | NO | FK → `price_list(id)` |
| `company_id` | BIGINT | NO | **denormalised** (D-5 rationale) — both parents are same-company; column anchors same-company integrity + scoped reads |
| `amount` | NUMERIC(19,4) | NO | `Money.amount` (ADR-0005 D-2) |
| `currency` | VARCHAR(3) | NO | `Money.currency`, ISO 4217 code (ADR-0005 D-3); a price is always amount + currency (FR-PROD-11, BR-PROD-04) |
| `created_at` / `created_by` / `updated_at` / `updated_by` | TIMESTAMPTZ / BIGINT | mixed | price changes are audited (NFR-PROD-04) |

- `uq_product_price UNIQUE (product_id, price_list_id)` — a product has at most one price per list (no date/tier in
  v1 — deferred, products.md §2).
- `fk_product_price_product`, `fk_product_price_pricelist`, `fk_product_price_company`.
- The `Money` pair is **NOT NULL together** here (a price row exists only to hold a price), so no null-together
  CHECK is needed — but the `Money` embeddable still maps `amount`/`currency` (no `@AttributeOverride` to
  `*_amount`; the columns are bare `amount`/`currency` because the row *is* a single price, matching `Money`'s own
  default column names in [Money.java](../../backend/src/main/java/com/erp/platform/common/money/Money.java)).
- `ix_product_price_product (product_id)` (a product's prices across lists); `ix_product_price_list (price_list_id)`
  (a list's prices); `ix_product_price_company (company_id)` (scoped reads).

**Cost price** is exactly one money value per product, so it is the `Money` pair **`cost_amount` / `cost_currency`
on the `products` row** (D-2), not a child table. Rationale: cost has no multiplicity (one cost per product, unlike
selling prices which span lists), so a child table or a "cost price list" would be a join for a single value —
gratuitous (the same reasoning ADR-0005 used to reject a `Money` table). It maps via `@AttributeOverride` to
`cost_amount`/`cost_currency` exactly as `creditLimit` does on `customers`.

> **BR-PROD-11** ("a sellable product should have a price before it can be sold") is **not** enforced at
> product-create (a product may be created and priced later — OQ-PROD-03); it is a **sale-time** rule the Sales
> module enforces. The schema permits a sellable product with zero `product_price` rows. Recorded here as the
> expectation Sales consumes; no constraint in V3.

### D-8 — Composition: self-referential child `product_component`, structure only, with self-/same-company guards

Single-level composition records a composed product's components + quantities (FR-PROD-14), structure only — **no
stock movement, no cost roll-up, no nesting** (FR-PROD-17, products.md §9). Self-referential child
**`product_component`**:

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | |
| `composed_product_id` | BIGINT | NO | FK → `products(id)` — the product that is made up of others |
| `component_product_id` | BIGINT | NO | FK → `products(id)` — a constituent product |
| `quantity` | NUMERIC(19,6) | NO | how many base units of the component per one composed (FR-PROD-14); CHECK `> 0` |
| `created_at` / `created_by` | TIMESTAMPTZ / BIGINT | mixed | audit columns |

- `uq_product_component UNIQUE (composed_product_id, component_product_id)` — a component appears once per recipe
  (adjust quantity, don't duplicate the row).
- `fk_product_component_composed`, `fk_product_component_component` (both → `products`).
- **`chk_product_component_not_self CHECK (composed_product_id <> component_product_id)`** — BR-PROD-05
  no self-composition, at the DB (a cheap single-row CHECK — Postgres can compare two columns of the same row).
- `chk_product_component_qty CHECK (quantity > 0)`.
- `ix_product_component_composed (composed_product_id)` (read a recipe); `ix_product_component_component
  (component_product_id)` ("where is this product used as a component" — supports future impact analysis).
- **`quantity` is NUMERIC(19,6)** — same quantity scale as `factor_to_base` (D-3), governed by **OQ-PROD-07**.

**Guards (service, BR-PROD-05/06):** beyond the self-reference CHECK, the cross-entity rules are service-enforced
(DB-can't / service-must, ADR-0006 D-4):
- **BR-PROD-06 same-company:** the `ProductBranchGuard` (or a sibling `ProductCompositionGuard`) asserts
  `component.company_id == composed.company_id` on every component add — a cross-row check no FK expresses.
- **Components non-archived** (BR-PROD-05 last sentence): the service rejects adding an `ARCHIVED` product as a new
  component; existing components that are later archived are handled by selection-time rules (Stock/Sales), not by
  retroactively breaking the recipe.
- **Multi-level / cycle prevention is DEFERRED** (products.md §9, FR-PROD-16): v1 is single-level, so a cycle
  (A→B→A) cannot arise — there is no expansion. The self-reference CHECK is the only structural guard v1 needs.
  When nesting is introduced under a future ADR, cycle detection (a recursive CTE walk or a closure table) is added
  then. The model deliberately does **not** carry a "is_composed" flag — a product is composed iff it has
  `product_component` rows (derive, don't denormalise a flag that can drift).

### D-9 — Enforcement split: DB enforces the unconditional/single-row, service enforces the cross-entity

Consistent with ADR-0006 D-6 and ADR-0005:

| rule | enforcement | mechanism |
| --- | --- | --- |
| BR-PROD-01 service ⇒ non-stockable | **DB CHECK** | `chk_product_service_stockable` (single-row, unconditional) + friendly service message |
| BR-PROD-03 bulk-pack factor > 0; distinct names | **DB** | `chk_product_bulk_pack_factor`, `uq_product_bulk_pack_name` |
| BR-PROD-05 no self-composition | **DB CHECK** | `chk_product_component_not_self` (two columns of one row) |
| BR-PROD-05 components non-archived | **service** | `ProductService.validate`: reject adding an ARCHIVED component |
| BR-PROD-06 components same company | **service** | composition guard, cross-row company check |
| BR-PROD-07 barcode unique per company | **DB** | `uq_product_barcode_company_value` |
| BR-PROD-08 code unique per company | **DB** | `uq_product_company_code` + `ProductCodeGenerator` |
| BR-PROD-09 associated branch same company | **service** | `ProductBranchGuard`, cross-row company check |
| BR-PROD-10 archived not selectable | **service / query** | selection queries filter `status='ACTIVE'` |
| BR-PROD-11 sellable needs a price | **Sales (sale-time)** | not enforced in Products; recorded expectation |
| one primary barcode per product | **DB** | `uq_product_barcode_primary` partial unique |
| cost / price money is (amount,currency) | **DB CHECK + Money** | `chk_product_cost_pair`; `product_price` columns NOT NULL together |

### D-10 — `ScopeGuard.companyIdOf`: add `product` (and `pricelist`) target types

The 2-arg `@perm.scoped(#uid,'product','PRODUCT.MANAGE')` gate needs `ScopeGuard.companyIdOf` to resolve a product
uid to its company — exactly the extension ADR-0006 D-10 made for the four party kinds (see
[ScopeGuard.java](../../backend/src/main/java/com/erp/platform/security/ScopeGuard.java) lines 64–73). The engineer
adds:

```java
case "product"   -> products.findCompanyIdByUid(uid);
case "pricelist" -> priceLists.findCompanyIdByUid(uid);
```

backed by single-column JPQL projections on `ProductRepository` / `PriceListRepository`
(`@Query("SELECT p.companyId FROM Product p WHERE p.uid = :uid")`), mirroring `CustomerRepository.findCompanyIdByUid`.
This means `ScopeGuard` gains constructor dependencies on `ProductRepository` and `PriceListRepository` — the same
cross-cutting-spine pattern already accepted for the four party repositories (ScopeGuard is the security spine, not
a peer module; ArchUnit note in ADR-0002 / ADR-0006 D-10). **Not optional — wired with the controllers, or the
target-uid gates fail closed.** Child records (barcode, bulk-pack, price, component) are addressed *under* their
product uid in the API, so they need no own target type — the gate resolves on the parent product's uid.

### D-11 — Permission catalogue additions (seeded in V3, module `products`)

| code | module | description |
| --- | --- | --- |
| `PRODUCT.VIEW` | products | View and select products |
| `PRODUCT.MANAGE` | products | Create, update, archive products; manage units, barcodes, prices and recipes |
| `PRICELIST.VIEW` | products | View price lists |
| `PRICELIST.MANAGE` | products | Create, update, archive price lists |
| `PRODUCT.BRANCH.ASSIGN` | products | Associate/dissociate a product with branches of its company |

- **`PRODUCT.MANAGE` covers units, barcodes, prices, and recipes** as one bundle — FR-PROD-24 describes a single
  catalogue-administrator persona managing all of a product's sub-objects (products.md §4). Splitting
  price-management or recipe-management into separate permissions is premature granularity; if the owner later
  wants a "may edit prices but not the product" role, it splits additively. (Flagged minor below.)
- **`PRICELIST.*` is separate** from `PRODUCT.*` because a price *list* is a company-level master (who may create
  the Wholesale list) distinct from pricing an individual product (which is `PRODUCT.MANAGE`). This matches the
  spec's separation of "maintain named price lists" (FR-PROD-10) from "manage a product's prices" (FR-PROD-24).
- **`PRODUCT.BRANCH.ASSIGN`** mirrors `PARTY.BRANCH.ASSIGN` (ADR-0006 D-10) — one association permission, the same
  administrative act regardless of what is being associated.
- **Seeding (V3, idempotent):** `INSERT INTO permissions (...) ... ON CONFLICT (code) DO NOTHING`, then an
  **additive** `INSERT ... SELECT ... WHERE p.module='products' ON CONFLICT DO NOTHING` granting the new
  permissions to `ORG_ADMIN` — the same pattern V2 used for `parties`. A **data** seed in V3, not a V1/V2 edit.
- **Gate shapes (ADR-0002):**
  - `POST /products` → `@PreAuthorize("@perm.scoped(#request.companyUid, 'company', 'PRODUCT.MANAGE')")` (active
    company is the target — see D-12 on `companyUid` in the body).
  - `PUT /products/uid/{uid}` → `@PreAuthorize("@perm.scoped(#uid, 'product', 'PRODUCT.MANAGE')")`.
  - `GET /products` (list/search) → `@PreAuthorize("hasAuthority('PRODUCT.VIEW')")`, results scoped by the tenant
    predicate + active branch (D-5/D-4 selection).
  - `POST /products/uid/{uid}/branches` (associate) → `@PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.BRANCH.ASSIGN')")`,
    plus `ProductBranchGuard` company-consistency.
  - `POST /price-lists` → `@PreAuthorize("@perm.scoped(#request.companyUid,'company','PRICELIST.MANAGE')")`;
    `PUT /price-lists/uid/{uid}` → `@perm.scoped(#uid,'pricelist','PRICELIST.MANAGE')`.

### D-12 — API / uid discipline; **recommend `companyUid` in create bodies** (fix the Parties wart)

- **uids in URLs, ids (as JSON strings) in bodies for joins.** Master endpoints address by uid:
  `/products/uid/{uid}`, `/price-lists/uid/{uid}`. Child records are addressed under the product uid
  (`/products/uid/{uid}/barcodes`, `/.../bulk-packs`, `/.../prices`, `/.../components`, `/.../branches`); the link
  row's own id/uid is not the URL key.
- **`ApiResponse<T>` envelope** everywhere; list/search paged via `PageMeta` (`page,size,totalElements,totalPages,hasNext`
  in `ApiResponse.meta`) — product search at POS is a paged read (NFR-PROD-02).
- **Money on the wire** is `{ "amount": "1500.0000", "currency": "TZS" }` with `amount` a **string** (ADR-0005 D-7),
  reusing the existing `MoneyDto` shape ([MoneyDto.java](../../backend/src/main/java/com/erp/modules/parties/domain/dto/MoneyDto.java)).
  Both `product.cost` and each `productPrice` serialise this way. (`MoneyDto` currently lives in
  `parties.domain.dto`; reusing it across modules would create a `products → parties` import — a boundary smell.
  **Recommendation:** promote `MoneyDto` to `platform.common.money` alongside `Money` so every money-bearing module
  shares one wire DTO. Flagged below as a small additive refactor the engineer should do when wiring Products;
  until then Products may declare its own `MoneyDto` in `products.domain.dto`, but the shared-home is the right
  end-state.)
- **Enums on the wire:** the string name (`GOODS`, `SERVICE`).
- **RECOMMENDATION — create requests take `companyUid`, not `companyId`:** Parties' `CreateCustomerRequest` takes
  `@NotNull Long companyId` (see
  [CreateCustomerRequest.java](../../backend/src/main/java/com/erp/modules/parties/domain/dto/CreateCustomerRequest.java)),
  which is a **convention wart** — it leaks the internal numeric id into the request body as the addressing key,
  contrary to the "URLs/bodies address by uid; Long ids are for body *joins*, not external addressing"
  discipline (PROJECT-CONVENTIONS §3.3, ADR-0001 D-G). For Products, **the create request should carry
  `companyUid` (String)**; the service resolves it to the company id and runs `ScopeGuard.assertCanActIn`. This is
  the convention-consistent choice and makes the `@perm.scoped(#request.companyUid,'company',...)` gate natural.
  Flagged to the owner as a deliberate divergence from the Parties precedent — Products does it right; whether to
  retrofit Parties' create DTOs to `companyUid` is a separate, optional cleanup (not this ADR's scope).

### D-13 — Audit (ADR-0004): emit points and `target_type` strings (plural table names)

Products' mutating services emit via the existing `AuditService.record(...)` (MANDATORY, same-TX, append-only).
`target_type` strings are the **plural table names** (the shipped convention — V2 uses `customers`, `audit`
filters read naturally on the table name):

| action | target_type | when | detail (fact-only per ADR-0004 D-6) |
| --- | --- | --- | --- |
| `PRODUCT.CREATE` | `products` | on create | `code`, `type`, `sellable`, `stockable` |
| `PRODUCT.UPDATE` | `products` | on profile edit | minimal/fact-only |
| `PRODUCT.ARCHIVE` / `PRODUCT.RESTORE` | `products` | status transition | before/after `status` |
| `PRODUCT.BRANCH.ADD` / `PRODUCT.BRANCH.REMOVE` | `products` | association change | `branchUid` added/removed |
| `PRODUCT.BARCODE.ADD` / `PRODUCT.BARCODE.REMOVE` / `PRODUCT.BARCODE.SETPRIMARY` | `product_barcode` | barcode change | `barcode`, `isPrimary` |
| `PRODUCT.PRICE.SET` / `PRODUCT.PRICE.REMOVE` | `product_price` | price change | `priceListUid`, the `Money` set (NFR-PROD-04 — price changes audited) |
| `PRODUCT.COMPONENT.ADD` / `PRODUCT.COMPONENT.REMOVE` | `product_component` | recipe change | `componentUid`, `quantity` |
| `PRICELIST.CREATE` / `PRICELIST.UPDATE` / `PRICELIST.ARCHIVE` | `price_list` | list lifecycle | `code`, `name` |

- **Price and branch-association changes ARE audited** (NFR-PROD-04 names them explicitly). Bulk-pack edits emit
  `PRODUCT.UPDATE` against `products` (a unit definition is a product-shape change), to keep the action catalogue
  lean — split into `PRODUCT.UNIT.*` later only if needed.
- **Profile-field edits are fact-only** (no old→new field capture) per ADR-0004 D-6.
- **No outbox event in v1** (no cross-module async effect yet — Sales/Purchases/Stock read product DTOs
  synchronously). If a later module needs "product archived → react" or "price changed → invalidate cache", that is
  an additive outbox event under its own decision, not built now (the outbox is the cross-module pattern, not
  in-memory `ApplicationEventPublisher`).

### D-14 — Migration: additive `V3__products.sql`, never a V1/V2 edit

IAM is `V1` (frozen); Parties is `V2`. Products is a **new** module → purely **additive `V3__products.sql`**. It
**must not** edit `V1__baseline.sql` or `V2__parties.sql`. Ordering within V3:
1. `products` (master) with FK to `companies`, the `type`/`service-stockable`/`cost-pair` CHECKs.
2. `code_sequence` (generic numbering, D-6).
3. `price_list` (master) with FK to `companies`.
4. Child tables: `product_bulk_pack`, `product_barcode`, `product_price`, `product_component`, `product_branch`
   (FKs to `products`, `price_list`, `branches`, `app_users`, `companies` — all of which already exist in V1/V2).
5. Indexes incl. partial (`uq_product_barcode_primary`) and the lookup/scoped indexes.
6. Permission seed + additive ORG_ADMIN grant.

All FK targets (`companies`, `branches`, `app_users`) already exist in frozen V1; no dependency on un-frozen schema.

## Consequences

**Easier / safer:**
- **Currency-safe and tenant-safe from day one:** cost and every price are `Money` pairs (ADR-0005), `products`
  is `company_id`-scoped under the §3.2 predicate, associations are company-consistent by `ProductBranchGuard`.
- **POS barcode scan is a single index probe** (`uq_product_barcode_company_value` doubles as the lookup index) —
  NFR-PROD-01 met without a join, at the documented cost of a `company_id` denormalised onto `product_barcode`.
- **BR-PROD-01 (service ⇒ non-stockable) and BR-PROD-05 (no self-composition) are DB-true** as cheap single-row
  CHECKs — a code bug becomes a constraint violation; the cross-entity rules (branch/component company,
  archived-component) live in service guards where they are legible and evolvable.
- **Numbering is concurrency-safe and consolidated:** the generic `code_sequence` is the one numbering primitive
  Sales/Stock reuse, ending the per-module sequence-table sprawl Parties started.
- **Sales/Purchases/Stock stay decoupled:** they consume `products.domain.dto` (product selection, price lookup),
  never import product entities or repositories.
- **The composition model is ready for Stock** (FR-PROD-17 / §9): the recipe rows captured now are exactly the
  input the future component-deduction + cost-roll-up needs, with the mechanics cleanly deferred.

**Harder / to watch:**
- **`company_id` is denormalised onto `product_barcode` and `product_price`** for per-company uniqueness + hot
  lookup. It is **set-once-from-parent and immutable** (the product's company never changes), so it cannot drift —
  but this is a service invariant the implementation must honour and test (a barcode/price row whose `company_id`
  disagrees with its product is a defect no CHECK catches; an integration test should assert they always match).
- **BR-PROD-06 / BR-PROD-09 are service-enforced** (the composition same-company guard and `ProductBranchGuard`) —
  the highest-discipline surfaces. Must have unit/IT coverage: a component of company A cannot be added to a
  composed product of company B (422/403); a branch of company B cannot be associated with a product of company A.
- **`ScopeGuard` gains two more repository dependencies** (`Product`, `PriceList`) and two `companyIdOf` cases —
  not optional; the target-uid gates fail closed without them (D-10).
- **Free-text base unit permits label drift** ("pcs" vs "piece"); accepted for v1 (D-3) with an additive upgrade
  path to a unit master reserved. No cross-product unit consistency in v1.
- **`MoneyDto` home:** reusing the Parties `MoneyDto` would breach the module boundary; it should be promoted to
  `platform.common.money` (D-12). Until then Products carries its own — a small, knowingly-temporary duplication.
- **No `is_composed` flag:** "is this product composed" is derived from `product_component` rows, so any read that
  needs it does a (cheap, indexed) existence check rather than reading a flag — correct, but reviewers should not
  "optimise" by adding a denormalised flag that can drift.

**Migration / delivery cost:**
- 1 additive Flyway file (`V3__products.sql`): 1 master (`products`) + 1 master (`price_list`) + 1 numbering table
  (`code_sequence`) + 5 child/link tables (`product_bulk_pack`, `product_barcode`, `product_price`,
  `product_component`, `product_branch`) = **8 tables**, their FKs/uniques/CHECKs, ~3 indexes on `products` plus
  the child/link indexes, 5 permission rows + 1 additive grant.
- Backend: the `products` entity set (entity + DTOs + repository + service interface/Impl + controller) and the
  `price_list` set, sharing a `ProductCodeGenerator` (on the new `code_sequence`) and a `ProductBranchGuard`
  (+ composition guard); the ADR-0002 `ScopeGuard` extension (D-10); the `MoneyDto` promotion (D-12); the
  `companyUid`-in-create-body convention (D-12).
- Web: a product master-admin screen (list/search incl. barcode scan, create/edit/archive) with sub-screens for
  units (base + bulk packs), barcodes (set primary), prices (per list, `Money` input from ADR-0005), recipe
  (component picker), and branch association — reusing the Parties admin-screen patterns. A small price-list admin
  screen.
- No outbox, no new infra, no DB triggers.

## Alternatives considered

- **Per-company unit master table (`unit_of_measure`) instead of a free-text `base_unit` + `factor_to_base`
  child.** Buys referential integrity and a clean per-company pick-list, removes "pcs vs piece" drift. **Not chosen
  for v1 (D-3):** the spec asks for no unit administration; a master adds a table, seed, numbering, lifecycle, and
  permission surface the business hasn't requested, and conversion math lives on `factor_to_base` regardless of
  whether a master constrains the label. The upgrade path (add `unit_of_measure`, add nullable `base_unit_id`,
  backfill, tighten) is a clean additive migration if standardisation is later wanted — so reserving it costs
  nothing now. An **enum** of units was rejected outright (every business invents its own units; an enum is too
  rigid and would need a code change per new unit).

- **A "cost price list" (cost as a `product_price` row on a reserved COST list) instead of a `Money` pair on the
  product.** Symmetric with selling prices, one pricing mechanism. **Not chosen (D-7):** cost has no multiplicity
  (one cost per product), so it would be a join + a reserved-list convention for a single value — gratuitous, and
  it muddies "price lists are *selling* prices" (FR-PROD-12 separates cost from selling prices). The `Money` pair
  on the product is the boring, join-free choice, exactly as ADR-0005 chose an embeddable over a money table.

- **Barcode and price as columns on `products` (e.g. `barcode`, `unit_price`) instead of child tables.** Fewer
  tables. **Rejected:** both are inherently one-to-many (multiple barcodes one primary, FR-PROD-08; a price per
  list across many lists, FR-PROD-10) — columns cannot represent the multiplicity without repeating-group hacks.
  Child tables are the normal-form, boring choice. (Cost, which *is* single-valued, correctly stays a column-pair —
  D-7.)

- **Reuse `party_code_sequence` for product numbering, or mint a `product_code_sequence`.** Reuse breaks the
  parties-specific `CHECK (party_kind IN (...))` and has a `products` service writing a `parties`-owned table (a
  boundary smell); a per-module table repeats the same 4 columns for every future module (sprawl). **Chosen
  instead (D-6): a generic `code_sequence` keyed by `(company_id, entity_kind)`** in `platform.common` — one
  cross-cutting numbering primitive every module reuses, owned by no business module. Parties' existing table is
  left frozen as-is.

- **A single shared `party_branch`-style polymorphic association, or folding products into a generic "catalogable"
  table.** Fewer tables across modules. **Rejected (same reasoning as ADR-0006's polymorphic-link rejection):** a
  polymorphic FK cannot have a real constraint, sacrificing the referential integrity the DB is best at. A dedicated
  typed `product_branch` with real FKs is the boring, safe, well-indexed choice and stays consistent with the
  Parties precedent.

## Open / ambiguous items flagged to owner (do not block modeling; noted for closure)

These do **not** block the engineer building the tables above; they are policy refinements layered on this schema.

1. **OQ-PROD-05 (per-product VAT / tax-applicability) — likely needed for Sales; reserve the slot now.** The spec
   defers it (products.md §10) but flags it as probably required when Sales lands (tax on a sale line derives from
   the product's tax treatment). **Where it slots in:** an additive set of columns on `products` —
   `tax_applicable BOOLEAN` + a `tax_code VARCHAR(20)` (or a `tax_rate_id` FK once a `tax_rate` master exists) — or,
   if multiple taxes per product are possible, a `product_tax` child table. **Recommendation:** do **not** add it in
   V3 (no ratified tax requirement yet); when the owner confirms tax treatment, it is a clean **additive** column
   set or child table on the already-shaped `products`, non-breaking. Calling it out so the engineer leaves room and
   nobody assumes Products already carries tax. **Mildly blocking for Sales, not for the Products schema.**
2. **OQ-PROD-01 (numbering scheme) — resolved as single per-company `PROD-####` for v1** (D-6). Category-prefixed
   numbering is deferred with categories (OQ-PROD-04); the `code_sequence.entity_kind` discriminator already
   supports per-prefix counters additively if/when the owner wants them. **Not blocking.**
3. **OQ-PROD-07 (fractional unit precision) — affects the quantity scale.** This ADR sets quantity scale to
   **NUMERIC(19,6)** for both `product_bulk_pack.factor_to_base` and `product_component.quantity` (6 decimals,
   distinct from money's scale 4 — quantities are not money, ADR-0005). If the owner needs finer (e.g. 8 decimals
   for tiny-unit recipes) or coarser precision, it is **one edit before V3 ships**. **Confirm the quantity scale**
   — recommend 6 unless a domain reason argues otherwise. **Mildly blocking (set the scale before the migration is
   written).**
4. **OQ-PROD-04 (product categories / groups)** — out of v1 scope. If the owner pulls categories into v1, it is a
   `product_category` master + a `category_id` FK (or a `product_category` link if many-to-many) — additive, and it
   would also unlock category-prefixed numbering (item 2). **Not blocking v1.**
5. **Minor: `PRODUCT.MANAGE` granularity** (D-11) — one bundle covers product + units + barcodes + prices + recipes.
   If the owner wants a price-only or recipe-only role later, the permission splits additively. **Not blocking.**
6. **Minor: `companyUid`-in-create-body** (D-12) — Products does it the convention-consistent way; whether to
   retrofit Parties' `companyId` create DTOs to `companyUid` is a separate optional cleanup, owner's call. **Not
   blocking Products.**

No FR/BR is ambiguous enough to halt implementation; the items above are policy refinements on top of a schema this
ADR fully specifies. The one input the engineer needs **before writing V3** is the OQ-PROD-07 quantity scale
(item 3) — defaulted here to NUMERIC(19,6), overridable by the owner.
