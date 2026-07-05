# ADR-0055: Bulk master-data operations (Excel import + mass price change)

- **Status:** Accepted (2026-07-04) — implemented on `feat/bulk-data-operations` (code + repeatable-seed only, **no schema change**).
- **Deciders:** Owner + Solutions Architect
- **Effort:** M. **Migration:** none — new company-scoped derived repository finders + four permission codes added to the repeatable `R__seed_permissions.sql` (convergent reference data); the frozen versioned schema is untouched (ADR-0043).
- **Related:** ADR-0006 (parties — Customer/Supplier masters + code generation), ADR-0007 (products — Product/PriceList/UnitOfMeasure, `product_prices`), ADR-0048 (per-unit prices), ADR-0018 D-9 (Apache POI already a dependency, for XLSX export), ADR-0002 (RBAC — permission-gated endpoints, deny-by-default coverage), ADR-0004 (audit-by-aspect), ADR-0022/outbox (cross-module side effects), the error-message-hygiene standing rule.

## Context

Operators need to load and maintain master data at volume: register hundreds of products, customers
and suppliers from a spreadsheet, and change prices across a whole price list in one action. Until
now every master record was created/updated one at a time through the module screens. There was **no
bulk/import capability anywhere** in the system.

### Determinant facts (verified 2026-07-04 against shipped code)

1. **Apache POI (`poi-ooxml`) is already a dependency** — used today only for XLSX *export*
   (`XlsxStatementRenderer`, reporting). The same library reads XLSX, so templates and uploads need
   **no new dependency**.
2. **Master DTOs are FK-heavy.** `CreateCustomerRequest`/`CreateProductRequest`/`CreateSupplierRequest`
   carry internal `Long` FK ids (`companyId`, `paymentTermsId`, `defaultPriceListId`, `baseUnitUid`,
   …) and enums. A spreadsheet a human fills in cannot reference internal ids — it must use business
   codes, resolved server-side.
3. **Permission checks live on controllers (`@PreAuthorize`), not services.** A bulk path that called
   a module service directly would bypass the permission gate — so the bulk endpoint itself must be
   gated, and must not invent a create/update path that skips the service's validation/scope/audit.
4. **`open-in-view` is off.** An entity loaded by a repository outside a transaction is detached; a
   later lazy access (e.g. `Product.baseUnit`) throws. Row processing that reads a loaded entity must
   run inside a transaction.
5. **The schema is frozen / durable (ADR-0043).** No new tables. Synchronous processing (no
   `import_job` table) keeps the feature schema-free; per-entity permission codes go in the repeatable
   `R__seed_permissions.sql`, which converges.

## Decision

### D-1 — A thin platform framework + per-module handlers; never bypass the module service

A new cross-cutting package `com.erp.platform.bulk` owns the mechanics (template generation, upload
parsing, validate→commit orchestration, per-row reporting) behind a small SPI:

```
interface BulkImportHandler { key(); displayName(); permissionCode(); columns(companyId); process(companyId, row, mode); }
```

Each module registers a `@Component` handler that maps **one spreadsheet row → its existing
`CreateXxxRequest`/`UpdateXxxRequest`** and calls the **existing module service**
(`ProductService.create/updateByUid`, `CustomerService`, `SupplierService`, `ProductService.setPrice`).
Consequence: every imported record goes through the identical validation, tenant-scope check
(`assertCanActIn`), code generation, audit-by-aspect and outbox events as a hand-entered one. The
framework depends only on the SPI interface (ArchUnit-clean — `ModuleBoundaryTest` passes); the
orchestrator collects handlers via `List<BulkImportHandler>` injection.

### D-2 — Server-generated XLSX templates; address FKs by human code, never internal id

`GET /api/v1/bulk/{key}/template` builds the workbook live from the handler's `ColumnSpec` list
(scoped to the active company, so it can bake live look-ups — e.g. existing unit-of-measure codes or
price-list codes — in as Excel dropdowns). A second "Instructions" sheet documents every column;
required columns are asterisked. The active **company is never a column** — it comes from
`RequestContext`. All other FKs are referenced by their business code (base unit code, price-list
code, product code) and resolved server-side, reporting a friendly row error on a typo.

### D-3 — Validate → Commit with true parity (validate = execute + roll back)

Upload runs in two modes. `POST /bulk/{key}/validate` returns a per-row **preview** writing nothing;
the operator fixes and re-uploads, then `POST /bulk/{key}/commit` performs the writes. Both return an
`ImportReport` (`total/created/updated/skipped/errors` + `RowOutcome[]`).

**Validate must be trustworthy** — a "0 errors" preview has to mean the commit will succeed. An early
version made validate *structural only* (FK/enum/format), which let deep business rules
(BR-PARTY-04 "a business needs a TIN", SERVICE-not-stockable, unique constraints) pass validate and
then fail commit — a persona flagged this as a "can't-trust-it" defect (Sabina, 2026-07-05). So both
modes now execute the **same** handler code (the real module create/update, so every rule runs); the
only difference is the transaction outcome. `BulkImportService` runs each **validate** row inside a
transaction it **always rolls back** (via a sentinel exception that carries the outcome out), so the
full business logic runs but nothing persists. A **commit** row runs in the handler's own transaction
and commits. Verified: a successful validate reports the intended create but leaves no row behind.

Processing is **synchronous** and capped at 2000 data rows per file (`XlsxRowReader.MAX_ROWS`) — covers
real master-data volumes and avoids an `import_job` table (a schema change, and the durable-DB rule).

### D-4 — Upsert by natural code; partial-merge on update; per-row transaction

Create-vs-update is decided by each entity's business code: a **blank code creates** (the service
auto-assigns the code); a code that **matches** an existing record **updates** it (a blank optional
cell keeps the current value — partial merge from the current DTO); a code that doesn't match creates
with that code (products) or errors. This makes re-runs idempotent. Each handler is `@Transactional`
(open-in-view is off, determinant §4): a row runs in its own transaction so the loaded entity's lazy
fields are readable, the create/update joins it, and a **failed row rolls back alone** — the
orchestrator is deliberately non-transactional and catches per-row exceptions so one bad row never
poisons the batch.

### D-5 — Per-entity import permissions, gated via a dynamic SpEL bean

New codes `PRODUCT.IMPORT`, `CUSTOMER.IMPORT`, `SUPPLIER.IMPORT`, `PRICE.MASS_UPDATE` are seeded in
`R__seed_permissions.sql` (ORG_ADMIN gets them via the existing cross-join). Bulk import is a distinct,
**higher-privilege** capability than single-record `*.MANAGE`, so a role can be granted manual entry
but not mass import. Because the endpoint is generic (`/bulk/{key}/…`), the required permission varies
by path variable; a `@Component("bulkAccess")` bean resolves the handler for `key` and checks its
`permissionCode()` — referenced as `@PreAuthorize("@bulkAccess.canImport(#key)")`, which also
satisfies the deny-by-default coverage gate (`EndpointAuthorizationTest`). The entity-listing endpoint
uses `@bulkAccess.canImportAny()`. The rule-based price endpoint uses the static
`@perm.has('PRICE.MASS_UPDATE')`.

### D-6 — "Price change" is two operations

- **Round-trip price upload** (`PriceImportHandler`, key `prices`): each row sets one product's price
  on a named price list (by codes; optional Unit column for a pack price), upserting through
  `ProductService.setPrice` — same validation/scope/audit as the single-price screen. For arbitrary
  per-product edits.
- **Rule-based mass change** (`PriceMassChangeService`, `POST /api/v1/prices/mass-change`): apply a
  `PERCENT` / `AMOUNT` / `SET` rule to **every** price on a list, with `dryRun` preview (counts +
  before/after samples) before committing. Scope is asserted from the **loaded** price list's company
  (confused-deputy rule), and the whole run is one transaction (all-or-nothing). Prices are
  **overwritten in place** — the current model has no price-history versioning; effective-dated price
  versions are a deliberate future enhancement (`product_prices.effective_from/to` already exist).

### D-7 — No migration

No new tables or columns. The only persistence-layer additions are **company-scoped derived finders**
(`findByCompanyIdAndCode` on Customer/Supplier/PriceList/UnitOfMeasure, `findByCompanyIdAndPriceListId`
and `findByCompanyIdAndPrimaryTrue` on ProductPrice/ProductBarcode) — the tenant-safe finder pattern,
not confused-deputy `findById`. Permission codes go in the repeatable seed. The frozen versioned schema
(ADR-0043) is untouched.

### D-8 — Product barcode column, uniqueness validated at the validate step

The product template carries an optional `Barcode` column (owner decision: **column-only** — a filled
value becomes the product's primary barcode; a blank one invents nothing). Because `product_barcodes`
is unique per company, a bad import could otherwise fail deep in commit with a raw constraint error. So
the handler validates the barcode **up-front, in both modes**: it rejects a value already used by a
*different* product in the company, and — via the per-run `ImportContext` — a value that appears on
more than one row of the *same file*. Both surface as friendly per-row errors before anything is
written. On update, a barcode the product already has is a no-op (idempotent).

### D-9 — Download → edit → re-upload (export round-trip); blank price = SKIP

`GET /bulk/{key}/export` fills the template with the entity's **current rows** (same columns), so the
"download everything, edit, re-upload to update" workflow the owner asked for works for every entity —
including bulk price maintenance. The `prices` export is product-centric and price-list-scoped
(`?priceList=<code>`, one row per product with its current price or blank); with no param it falls back
to the company default list. To make partial edits ergonomic, a **blank `Amount` on re-upload is a
`SKIP`** (the price is left unchanged, reported as a no-op, not an error) — so a user can export all
products, price only the ones they want, and upload the whole sheet. `RowAction` gains `SKIP` and
`ImportReport` a `skipped` count. (For master entities, a blank optional cell already keeps the current
value via the D-4 partial merge.)

## Consequences

- **Positive:** operators can mass-load products/customers/suppliers and mass-change prices from Excel,
  with a dry-run preview and a per-row error report, without any weakening of the invariants —
  tenancy, validation, code generation, audit and outbox all still fire because every row goes through
  the module service.
- **Safe by construction:** company from context (never a column); FKs by code (never internal id);
  per-entity permission gate that fails closed on an unknown key; per-row transaction so a bad row
  fails alone; user-safe row messages only (no exception text leaks — error-message-hygiene rule).
- **Bounded:** 2000 rows/file, synchronous — simple, no job table, no async infra; the cap is
  reported, not silent.
- **Contract additions:** `com.erp.platform.bulk.*`; `BulkImportController`
  (`/bulk/entities`, `/bulk/{key}/template|export|validate|commit`); `PriceMassChangeController`
  (`/prices/mass-change`); handlers in products/parties; web Bulk-Import and Mass-Price-Change screens.
- **Scope of v1 (deferred):** child collections (contacts, addresses, supplier bank accounts, extra
  barcodes) are not in the templates; a default sales-agent / default price-list column on the customer
  template (persona-requested, ADR follow-up); async jobs for very large files; effective-dated price
  versioning; friendlier messages for raw DB-constraint violations (currently a safe generic message).

## Alternatives considered

- **Structural-only validate (cheaper dry-run)** — rejected after a persona hit it: it let deep business
  rules pass validate and fail commit, so "0 errors" could not be trusted. Executing the real service in
  a rolled-back transaction is heavier per row but makes validate authoritative (D-3).
- **Bulk-insert past the services (batch SQL / direct repository saves)** — rejected: it would skip
  validation, tenant scope, code generation, audit and outbox. The per-row-through-the-service design
  is slower but correct; master-data volumes make the cost irrelevant.
- **One umbrella `DATA.IMPORT` permission** — rejected: coarser than needed. Per-entity codes let a
  role import products but not customers; the dynamic `@bulkAccess` bean makes per-entity gating work
  on a generic endpoint while still satisfying the coverage test.
- **Async import jobs with a status table** — rejected for v1: needs a new table (schema change on a
  frozen, durable DB) and infra for no benefit at master-data scale. Synchronous with a row cap covers
  the real cases; async can be added later behind the same SPI.
- **CSV instead of XLSX** — rejected: business users expect Excel; XLSX gives typed cells, dropdown
  data-validation, and a separate instructions sheet, and POI is already on the classpath.
- **Client-supplied internal ids in the template** — rejected: users can't know `Long` ids and they
  are not stable/portable. Human codes resolved server-side are the only workable key.
- **Effective-dated price versioning for the mass change** — deferred: the columns exist but versioned
  price history is a larger design (resolution precedence, scheduling) than this ADR; overwrite-in-place
  matches the current `setPrice` upsert and is auditable via `audit_log`.
