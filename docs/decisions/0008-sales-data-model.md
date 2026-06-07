# 0008 — Sales data model: invoice header + lines + payments, tax-exclusive VAT, per-product VAT status, paid-in-full at finalise, void-only corrections, outbox-ready for Stock

- **Status:** Accepted
- **Date:** 2026-06-07
- **Deciders:** solutions-architect (owner-ratified Sales requirements 2026-06-07 + the two ADR-blocking rulings:
  tax-EXCLUSIVE entry OQ-SALES-03b, single per-company `INV-####` numbering OQ-SALES-12)
- **Context source:** [docs/requirements/sales.md](../requirements/sales.md) (RATIFIED 2026-06-07 — FR-SALES-01..25,
  BR-SALES-01..12, §10 accepted-risk on no-stock-movement / no-fiscalisation, the deferred list §2/§12, the
  non-blocking OQ log §11); [USER-STORIES.md](../../USER-STORIES.md) (US-SALES-01..09 + ACs);
  [ADR-0007](0007-products-data-model.md) (THE pattern to mirror — per-company masters, the generic `code_sequence`
  with `entity_kind`, `ScopeGuard.companyIdOf` target types, `companyUid`-in-create-body, audit emits with plural
  `target_type` table names, additive migration, uid/id + Long-as-string + `PageMeta`, the DB-can't/service-must
  split); [ADR-0006](0006-parties-data-model.md) (Customer/Agent masters Sales references; the enforcement-split
  table); [ADR-0005](0005-money-and-currency.md) (`Money` embeddable — `amount` NUMERIC(19,4) + `currency`
  VARCHAR(3); every monetary value is a `(amount,currency)` pair; HALF_UP boundary rounding; mixed-currency
  arithmetic is an error; wire `{amount:string,currency}`); ADR-0004 (audit emit points, `target_type`, JSONB
  `detail`); ADR-0002 (RBAC permission + scope, `ScopeGuard`); ADR-0001 (D-A tenancy, D-G uid/ULID);
  [ARCHITECTURE.md](../../ARCHITECTURE.md) §5 (tenant predicate + branch-override), §9 (transactional outbox —
  `domain_event`, the cross-module pattern; **not yet built**); [PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md)
  §2 (module layout), §3.2 (tenant predicate), §3.3 (uid/id); [DATA-MODEL.md](../../DATA-MODEL.md) (table style).
  Verified against the **shipped** [V1__baseline.sql](../../backend/src/main/resources/db/migration/V1__baseline.sql)
  / [V2__parties.sql](../../backend/src/main/resources/db/migration/V2__parties.sql) /
  [V3__products.sql](../../backend/src/main/resources/db/migration/V3__products.sql) /
  [V4__units_of_measure.sql](../../backend/src/main/resources/db/migration/V4__units_of_measure.sql) — the prose
  naming doc was stale; the SQL is ground truth.

This ADR is the **technical data model** for the Sales module. It translates the ratified business spec into tables,
columns, types, keys, indexes, constraints, and enforcement placement — concrete enough that the backend engineer
writes **`V5__sales.sql`** and the entities **without guessing a business rule**. It does **not** write production
code, entities, or the migration — that is the engineer's next step. The owner's ratified v1 decisions (invoice
channel only; tax-exclusive VAT; per-product VAT status; mandatory agent; price-list snapshot with permissioned
override; cash + mobile-money split tenders paid in full at finalise; void-only corrections; single per-company
`INV-####` at finalise; no stock movement but outbox-ready) are taken as given and designed to exactly. **Nothing
ratified is re-litigated.**

## Context

Sales is the first **money-bearing transactional module**, and the first **document with a header→lines→payments
shape** the system has built. Everything it consumes already exists or is one additive change away (sales.md §1):
IAM gives the tenant spine + `code_sequence` + audit; Parties gives `customers` and `agents` (incl. the
internal-agent→`app_user` link, ADR-0006 D-5); Products gives sellable products with `product_prices` on
`price_lists` and a `units_of_measure` FK (V3/V4 shipped); ADR-0005 gives `Money`. The central architectural force
is therefore **mirror the proven ADR-0007 patterns; resolve only the genuinely new modelling questions a
transactional document introduces**. Those new questions, and the forces around each:

- **A document is a header with children, not a master with attributes.** Parties/Products are *masters* (one row =
  one thing, edited in place). An invoice is a *document*: an immutable-once-finalised header that owns ordered
  lines and zero-or-more payments, with computed monetary roll-ups. This is a new shape for the codebase. Forces:
  the header/line/payment split is the boring, normal-form choice (one-to-many twice); the new questions are *where
  totals are computed* (DB-generated vs service), *how the price snapshot + override are captured*, and *how the
  lifecycle/immutability is enforced*. Resolved in D-2/D-3/D-4/D-7.

- **Tax-exclusive VAT per line, summarised by rate band (FR-SALES-09/11/12, BR-SALES-05).** Net = price×qty −
  discounts; VAT = net × rate (by the product's VAT status); gross = net + VAT; the document rolls up net, VAT (by
  band), and gross, and prints a VAT analysis. Forces: VAT must derive from a **per-product VAT status** that does
  not exist yet (resolves OQ-PROD-05); the rate must be **maintained, never hard-coded** (FR-SALES-10); the
  computed amounts must be **identical backend and frontend** (NFR-SALES-02) and stored once. New: the additive
  product change (D-5), the rate source (D-5), and the line tax-breakdown placement (D-3).

- **Price snapshot + permissioned override (FR-SALES-07/08, BR-SALES-09).** The line price defaults from the
  applicable price list but is **stored on the line at sale time** (prices change; a finalised invoice must not
  re-price). A permissioned manual override is **recorded and audited** (original list price vs applied price).
  Forces: snapshot = copy the value onto the line, do not FK-and-join to the live price; the override needs an
  audit-grade "what changed" capture on the line itself (D-3).

- **Mandatory agent, auto-defaulted (FR-SALES-14/15/16, BR-SALES-06).** Every invoice carries exactly one
  `agent_id` (NOT NULL); the service auto-defaults to the logged-in user's internal-agent record; commission is
  **recorded/derivable but NOT computed** (no commission tables in v1). New: the agent FK is mandatory (unlike a
  master attribute), and where commission slots later must be named (D-6).

- **Paid in full at finalise, split tenders (FR-SALES-17/18/19, BR-SALES-07).** A payments child holds cash +
  mobile-money tenders; the sum of tenders must cover the gross at finalise; there is **no partial/AR state** in
  v1. Forces: this is a service invariant (sum across child rows vs the header gross — a cross-row check no CHECK
  can express); the tender model must extend later to card/credit without reshaping (D-8).

- **Void is a status transition, not a delete (FR-SALES-03/22, BR-SALES-08).** A finalised invoice is immutable
  except a permissioned void; the number is assigned **at finalise** (OQ-SALES-11 default). Forces: a lifecycle
  enum + transition guards in the service; the DRAFT state means a draft has **no number yet** (the number column
  is nullable until finalise) — a new nullability pattern for the codebase (D-7).

- **No stock movement, but outbox-ready (FR-SALES-21, BR-SALES-11, NFR-SALES-07, §10 accepted risk).** v1 records
  sold quantities and deducts nothing; finalising must **later** emit a stock-deduction effect via the
  transactional outbox. The outbox table (`domain_event`) is reserved in ARCHITECTURE.md §9 but **not yet built**.
  Force: decide whether V5 builds the outbox now (write `SALE.FINALISED` at finalise) or only reserves the shape —
  resolved pragmatically in D-9.

- **Schema freeze / migration ordering.** IAM=V1, Parties=V2, Products=V3, Units=V4 — all frozen. Sales is a **new**
  module landing as a purely **additive `V5__sales.sql`**; it must not edit V1–V4. The one cross-module additive
  touch is the product VAT-status column, which V5 ALTERs onto the shipped `products` table (D-5, D-12).

## Decision

### D-1 — Module placement: one `com.erp.modules.sales` module; controllers flat in `com.erp.api`

The sales document lives in a **single** module `com.erp.modules.sales` with the standard internal layout:

```
com.erp.modules.sales
├── domain.entity   SalesInvoice, SalesInvoiceLine, SalesInvoicePayment
├── domain.dto      SalesInvoiceDto, SalesInvoiceSummaryDto, CreateSalesInvoiceRequest,
│                   UpdateSalesInvoiceRequest, SalesInvoiceLineDto, AddInvoiceLineRequest,
│                   UpdateInvoiceLineRequest, SalesInvoicePaymentDto, AddPaymentRequest,
│                   FinaliseInvoiceRequest, VoidInvoiceRequest, InvoiceTaxSummaryDto, …
├── domain.enums    InvoiceStatus (DRAFT|FINALISED|VOID), DocumentType (INVOICE; reserved POS|SALES_ORDER),
│                   TenderType (CASH|MOBILE_MONEY; reserved CARD|CREDIT), VatStatus (re-used from products.domain.enums)
├── repository      SalesInvoiceRepository, SalesInvoiceLineRepository, SalesInvoicePaymentRepository
└── service         SalesInvoiceService(+Impl), InvoiceTotalsCalculator (D-4), InvoiceNumberGenerator (D-7, via code_sequence),
                    SalesPricingResolver (D-3, reads products.domain.dto), VatRateResolver (D-5)
```

**Why `sales`, not `invoicing` / `pos`:** the module is named for its dominant noun and the ratified channel — the
spec's umbrella term is "sale" and the v1 channel is the Invoice (sales.md §3). POS and Sales-Order are deferred
*channels of the same spine* (D-7 reserves the discriminator), so naming the module `invoicing` would mis-name it
the day POS lands; `sales` is the durable name. Controllers stay flat in `com.erp.api` — `SalesInvoiceController`
(and later `PosController` / `SalesOrderController` against the same services) — and touch only services
(PROJECT-CONVENTIONS §2; `ModuleBoundaryTest`).

> **Boundary note for `ModuleBoundaryTest`:** `sales` reads **DTOs only** from Products and Parties — it never
> imports `products.*.entity` / `parties.*.entity` or their repositories. The sale-time reads it needs (product
> sellable/branch/price/unit; customer; agent + internal-agent-by-user) go through **service-layer calls** into
> `ProductService` / `PriceListService` / `CustomerService` / `AgentService` returning `*.domain.dto`, exactly as
> ADR-0006/0007 anticipated ("Sales/Purchases/Stock consume `products.domain.dto`/`parties.domain.dto`"). Sales
> owns **no** FK *entity association* into another module's entity; the cross-module references it persists are
> **scalar `Long` id columns** (`customer_id`, `agent_id`, `product_id`, `unit_id`) with real DB FKs — the same
> SQL-only-FK / no-cross-module-`@ManyToOne` convention `agents.app_user_id` and `audit_logs.actor_user_id`
> already use. No new boundary-allowlist entry is required.

### D-2 — Three tables: `sales_invoices` (header) + `sales_invoice_lines` (child) + `sales_invoice_payments` (child)

A sales document is a header that owns ordered lines and zero-or-more payments. Three tables, all plural per the
shipped owned-child convention (`product_barcodes`, `product_prices`). The header extends the `UidEntity` shape;
the children carry their own `uid` (they are API-addressable child records — add/edit/remove a line, add/remove a
payment — the same reasoning that gives `product_barcodes`/`product_prices` rows a uid, and unlike pure junctions).

Every row of all three tables carries **`company_id` + `branch_id`** (NFR-SALES-01, BR-SALES-01) and participates
in the §3.2 tenant predicate — this is the first module whose tables carry **both** scope columns on the row,
because an invoice *belongs to a company and is raised at one branch* (a fixed pair, unlike a master that
associates with many branches). `company_id`/`branch_id` are **denormalised onto the child tables** (set from the
header at write time, immutable) so the tenant predicate filters every table without a join to the header — the
same set-once-from-parent denormalisation ADR-0007 D-5 justified for `product_barcodes.company_id`.

#### `sales_invoices` (header)

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal FK target |
| `uid` | VARCHAR(26) | NO | ULID; `uq_sales_invoice_uid`; URLs address by uid |
| `company_id` | BIGINT | NO | FK → `companies(id)`; tenant scope (BR-SALES-01); **never updated** |
| `branch_id` | BIGINT | NO | FK → `branches(id)`; the branch the sale was raised at (BR-SALES-01); **never updated** |
| `document_type` | VARCHAR(20) | NO | enum `INVOICE` (v1); reserved `POS` \| `SALES_ORDER`; CHECK below; DEFAULT `'INVOICE'` |
| `invoice_number` | VARCHAR(30) | YES | `INV-####`, assigned **at finalise** (D-7); NULL while DRAFT (OQ-SALES-11) |
| `status` | VARCHAR(20) | NO | enum `DRAFT` \| `FINALISED` \| `VOID`; DEFAULT `'DRAFT'`; CHECK below (FR-SALES-02) |
| `customer_id` | BIGINT | NO | FK → `customers(id)` (scalar, same company — service guard D-10); walk-in is a real customer row (BR-SALES-10) |
| `agent_id` | BIGINT | NO | FK → `agents(id)` (scalar); **mandatory** (BR-SALES-06, FR-SALES-14) |
| `currency` | VARCHAR(3) | NO | the **document currency** (ISO 4217); every `Money` on the invoice is in this currency (BR-SALES-04); = company base in practice |
| `doc_discount_amount` | NUMERIC(19,4) | YES | document-level discount **amount** in `currency` (D-3); NULL = no doc discount |
| `doc_discount_percent` | NUMERIC(9,4) | YES | document-level discount **percent** (alternative entry form, FR-SALES-09); NULL unless percent-entered; CHECK `>= 0 AND <= 100` |
| `net_total_amount` | NUMERIC(19,4) | NO | computed roll-up: sum of line nets after the doc discount (D-4); DEFAULT 0 |
| `vat_total_amount` | NUMERIC(19,4) | NO | computed roll-up: sum of line VAT (D-4); DEFAULT 0 |
| `gross_total_amount` | NUMERIC(19,4) | NO | computed roll-up: `net_total + vat_total` (D-4); DEFAULT 0 |
| `tax_summary` | JSONB | YES | the per-rate-band VAT analysis the invoice prints (FR-SALES-11/13), e.g. `[{"status":"STANDARD","rate":"0.1800","net":"…","vat":"…"}]`; computed at finalise (D-4) |
| `finalised_at` | TIMESTAMPTZ | YES | set at finalise |
| `finalised_by` | BIGINT | YES | FK → `app_users(id)`; the operator who finalised |
| `voided_at` | TIMESTAMPTZ | YES | set on void |
| `voided_by` | BIGINT | YES | FK → `app_users(id)` |
| `void_reason` | VARCHAR(255) | YES | captured on void (FR-SALES-22) |
| `notes` | VARCHAR(500) | YES | free-text invoice note |
| `version` | BIGINT | NO | optimistic lock, DEFAULT 0 |
| `created_at` / `created_by` / `updated_at` / `updated_by` | TIMESTAMPTZ / BIGINT | mixed | standard audit columns (`*_by` → `app_users.id`) |

**Constraints on `sales_invoices`:**
- `uq_sales_invoice_uid UNIQUE (uid)`.
- **`uq_sales_invoice_company_number UNIQUE (company_id, invoice_number)`** — `INV-####` unique per company
  (BR-SALES-12). Postgres treats NULLs as distinct, so the many DRAFT rows (NULL number) coexist; the constraint
  bites only once a number is assigned. This is the **single per-company series** (OQ-SALES-12); the backstop for
  D-7's generator, exactly as `uq_product_company_code` backstops the product generator.
- `fk_sales_invoice_company`, `fk_sales_invoice_branch`, `fk_sales_invoice_customer`, `fk_sales_invoice_agent`,
  `fk_sales_invoice_finalised_by` (→ `app_users`), `fk_sales_invoice_voided_by` (→ `app_users`).
- `chk_sales_invoice_doc_type CHECK (document_type IN ('INVOICE'))` — **v1 admits only INVOICE**; widen the IN-list
  additively when POS/SO land (D-7). (Keeping the CHECK tight now means a stray `POS` row can't appear before the
  channel is built; widening is a one-line additive ALTER.)
- `chk_sales_invoice_status CHECK (status IN ('DRAFT','FINALISED','VOID'))`.
- **`chk_sales_invoice_number_when_finalised CHECK ((status = 'DRAFT' AND invoice_number IS NULL) OR (status IN ('FINALISED','VOID') AND invoice_number IS NOT NULL))`**
  — a DRAFT has no number; a FINALISED/VOID invoice always has one (a voided invoice keeps its number — void is a
  transition, not a delete, FR-SALES-22). Single-row-expressible, so it lands at the DB.
- `chk_sales_invoice_doc_discount CHECK (doc_discount_percent IS NULL OR (doc_discount_percent >= 0 AND doc_discount_percent <= 100))`.
- `chk_sales_invoice_totals_nonneg CHECK (net_total_amount >= 0 AND vat_total_amount >= 0 AND gross_total_amount >= 0)`.

> **`currency` is a single header column, not a `Money` pair, by design.** The whole invoice is in one document
> currency (BR-SALES-04); every monetary column on the header/lines/payments shares it. Storing the code once on
> the header (and denormalising it onto child money rows for the `Money` embeddable, see below) is cheaper than a
> currency column beside every amount and makes "all amounts on one sale share a currency" structurally obvious.
> The per-amount currency columns still exist where a `Money` embeddable maps them (the children), and the service
> asserts they all equal the header `currency` (cross-row, service-enforced, D-10) — mixed currency on one invoice
> is forbidden (BR-CUR-07). **Foreign-currency base-equivalent triple (ADR-0005 D-5) is NOT added in v1** (document
> currency = base in practice, sales.md §9); it is the clean additive set ADR-0005 reserved when the FX engine
> lands — not precluded (NFR-SALES-07).

#### `sales_invoice_lines` (child of `sales_invoices`)

One product on the sale (FR-SALES-04). Prices, discounts, and VAT are **snapshotted onto the line at sale time**
(FR-SALES-07/08) — the line never re-reads the live `product_prices` row after it is added.

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal key |
| `uid` | VARCHAR(26) | NO | ULID; `uq_sales_invoice_line_uid`; lines are uid-addressed child records |
| `invoice_id` | BIGINT | NO | FK → `sales_invoices(id)`; the owning header |
| `company_id` | BIGINT | NO | denormalised from header (tenant predicate, set-once-immutable) |
| `branch_id` | BIGINT | NO | denormalised from header (tenant predicate, set-once-immutable) |
| `line_no` | SMALLINT | NO | 1-based ordinal for stable display/print order; `uq_sales_invoice_line_no` per invoice |
| `product_id` | BIGINT | NO | FK → `products(id)` (scalar; sellable+branch+same-company checked at add-time, service, D-10) |
| `product_code` | VARCHAR(20) | NO | **snapshot** of the product code at sale time (the invoice prints what was sold even if the product is later renamed/archived) |
| `product_name` | VARCHAR(200) | NO | **snapshot** of the product name at sale time |
| `unit_id` | BIGINT | NO | FK → `units_of_measure(id)` (scalar); the unit the quantity is expressed in (base or a bulk-pack unit, FR-SALES-04) |
| `unit_name` | VARCHAR(60) | NO | **snapshot** of the unit name at sale time |
| `quantity` | NUMERIC(19,6) | NO | quantity in `unit_id` units; CHECK `> 0`; scale 6 = the shipped quantity convention (ADR-0007 D-3) |
| `qty_in_base` | NUMERIC(19,6) | NO | quantity converted to the product's base unit (qty × bulk-pack factor, FR-PROD-06) — **snapshotted** so the future stock-deduction outbox event (D-9) and any recipe explosion need no live conversion; CHECK `> 0` |
| `list_price_amount` | NUMERIC(19,4) | NO | **snapshot** of the applicable price-list net unit price (FR-SALES-07); the "original" for override audit (BR-SALES-09) |
| `unit_price_amount` | NUMERIC(19,4) | NO | the **applied** net unit price (= `list_price_amount` unless overridden, FR-SALES-08); the figure the math uses |
| `price_overridden` | BOOLEAN | NO | DEFAULT false; true when `unit_price_amount <> list_price_amount` by a permissioned manual override (BR-SALES-09) |
| `overridden_by` | BIGINT | YES | FK → `app_users(id)`; who overrode (NULL unless `price_overridden`) |
| `line_discount_amount` | NUMERIC(19,4) | YES | line-level discount **amount** in document currency (FR-SALES-09); NULL = none |
| `line_discount_percent` | NUMERIC(9,4) | YES | line-level discount **percent** (alternative entry, FR-SALES-09); CHECK `>= 0 AND <= 100` |
| `vat_status` | VARCHAR(20) | NO | **snapshot** of the product's VAT status at sale time (D-5): `STANDARD` \| `ZERO_RATED` \| `EXEMPT`; CHECK below |
| `vat_rate` | NUMERIC(9,4) | NO | **snapshot** of the rate applied (e.g. `0.1800` for standard, `0.0000` for zero/exempt) — the rate is maintained data, frozen onto the line at sale (FR-SALES-10, BR-SALES-05) |
| `net_amount` | NUMERIC(19,4) | NO | computed: `(unit_price × quantity) − line discount` (D-4); the tax-exclusive taxable base (FR-SALES-12); CHECK `>= 0` |
| `vat_amount` | NUMERIC(19,4) | NO | computed: `net_amount × vat_rate` (D-4); 0 for zero-rated/exempt; CHECK `>= 0` |
| `gross_amount` | NUMERIC(19,4) | NO | computed: `net_amount + vat_amount` (D-4); CHECK `>= 0` |
| `currency` | VARCHAR(3) | NO | document currency, denormalised from header (the `Money` embeddable column; service asserts = header currency) |
| `created_at` / `created_by` / `updated_at` / `updated_by` | TIMESTAMPTZ / BIGINT | mixed | audit columns |

**Constraints on `sales_invoice_lines`:**
- `uq_sales_invoice_line_uid UNIQUE (uid)`.
- `uq_sales_invoice_line_no UNIQUE (invoice_id, line_no)` — stable ordinal per invoice.
- `fk_sales_invoice_line_invoice` (→ `sales_invoices`), `fk_sales_invoice_line_product` (→ `products`),
  `fk_sales_invoice_line_unit` (→ `units_of_measure`), `fk_sales_invoice_line_overridden_by` (→ `app_users`),
  `fk_sales_invoice_line_company` (→ `companies`), `fk_sales_invoice_line_branch` (→ `branches`).
- `chk_sales_invoice_line_vat_status CHECK (vat_status IN ('STANDARD','ZERO_RATED','EXEMPT'))`.
- `chk_sales_invoice_line_qty CHECK (quantity > 0 AND qty_in_base > 0)`.
- `chk_sales_invoice_line_amounts CHECK (net_amount >= 0 AND vat_amount >= 0 AND gross_amount >= 0)`.
- `chk_sales_invoice_line_disc_pct CHECK (line_discount_percent IS NULL OR (line_discount_percent >= 0 AND line_discount_percent <= 100))`.

> **Why snapshot `product_code`/`product_name`/`unit_name`/`list_price`/`vat_rate` onto the line:** a finalised
> invoice is an immutable legal/commercial record (FR-SALES-03, BR-SALES-08). If a line only FK'd to the live
> product and re-read its name/price, then renaming a product, re-pricing it, or archiving it would silently mutate
> what a historical invoice *appears* to say. Snapshotting the human-facing facts at sale time makes the document
> honest forever, at the documented cost of a few duplicated columns. The `product_id`/`unit_id` FKs are kept
> **as well** (for joins, the future stock-deduction outbox event D-9, and reporting), but the printed truth lives
> on the line. This is the standard invoice-line discipline; do not "normalise it away."

#### `sales_invoice_payments` (child of `sales_invoices`)

A tender taken against the invoice (FR-SALES-17). Split payment = multiple rows; paid-in-full at finalise = the
sum across rows covers the header gross (D-8, service-enforced).

| column | type | null? | notes |
| --- | --- | --- | --- |
| `id` | BIGINT IDENTITY PK | NO | internal key |
| `uid` | VARCHAR(26) | NO | ULID; `uq_sales_invoice_payment_uid`; payments are uid-addressed child records |
| `invoice_id` | BIGINT | NO | FK → `sales_invoices(id)` |
| `company_id` | BIGINT | NO | denormalised from header (tenant predicate) |
| `branch_id` | BIGINT | NO | denormalised from header (tenant predicate) |
| `tender_type` | VARCHAR(20) | NO | enum `CASH` \| `MOBILE_MONEY` (v1); reserved `CARD` \| `CREDIT`; CHECK below (FR-SALES-17) |
| `amount` | NUMERIC(19,4) | NO | the tender amount in document currency (`Money` embeddable amount); CHECK `> 0` |
| `currency` | VARCHAR(3) | NO | document currency (the `Money` embeddable currency; service asserts = header currency, BR-SALES-07/BR-CUR-06) |
| `change_amount` | NUMERIC(19,4) | YES | cash over-tender returned as change (BR-SALES-07); NULL/0 for non-cash; CHECK `>= 0` |
| `reference` | VARCHAR(80) | YES | mobile-money transaction reference / cash drawer ref (optional) |
| `received_at` | TIMESTAMPTZ | NO | DEFAULT now(); when the tender was taken |
| `received_by` | BIGINT | NO | FK → `app_users(id)`; the operator who took payment |
| `created_at` / `created_by` | TIMESTAMPTZ / BIGINT | mixed | audit columns (payments are add/remove, minimal mutation) |

**Constraints on `sales_invoice_payments`:**
- `uq_sales_invoice_payment_uid UNIQUE (uid)`.
- `fk_sales_invoice_payment_invoice` (→ `sales_invoices`), `fk_sales_invoice_payment_by` (→ `app_users`),
  `fk_sales_invoice_payment_company` (→ `companies`), `fk_sales_invoice_payment_branch` (→ `branches`).
- `chk_sales_invoice_payment_tender CHECK (tender_type IN ('CASH','MOBILE_MONEY'))` — **v1 admits only cash +
  mobile money**; widen the IN-list additively for card/credit (D-8).
- `chk_sales_invoice_payment_amount CHECK (amount > 0)`.
- `chk_sales_invoice_payment_change CHECK (change_amount IS NULL OR change_amount >= 0)`.

> **There is no `settled` status; settled = FINALISED + tenders cover gross.** The spec describes a "settled" state
> (FR-SALES-02) but also rules **paid-in-full at finalise with no partial/AR state** (FR-SALES-18). With no
> outstanding-balance state possible in v1, a FINALISED invoice is *by construction* settled (the finalise
> transaction takes the full payment in the same TX — D-8). Modelling a separate `SETTLED` status would add a
> transition that can never be partial and never be skipped — ceremony with no state behind it. The lifecycle enum
> is therefore **DRAFT → FINALISED → VOID** (D-7); "settled" is a *derived* read (FINALISED with payments present),
> exposed as a boolean/label on the DTO, not a stored status. **When credit lands** (deferred), a real
> `PART_SETTLED`/`OUTSTANDING` state and an AR balance are added then — additively — and *that* is when a settled
> status earns its place. Recorded here so no one assumes v1 has an AR state.

### D-3 — Pricing snapshot, override capture, and discounts: stored on the line, resolved by the service

- **Snapshot, don't join-to-live.** When a line is added (DRAFT), `SalesPricingResolver` (service) reads the
  applicable price for the product via `PriceListService` returning a DTO: the **company default price list**, or
  the **customer's associated price list** if the invoice's customer has one (FR-SALES-07). It copies that net
  price into **both** `list_price_amount` (the original) and `unit_price_amount` (the applied). If the product has
  no price on the applicable list, the add is **rejected** (BR-SALES-03 / FR-SALES-05 — Products BR-PROD-11
  enforced at sale-time, here). The line then holds the price; later price-list changes never touch a created line.
- **Override capture (BR-SALES-09, FR-SALES-08).** A permissioned operator (`SALES.OVERRIDE`, D-11) may set
  `unit_price_amount` ≠ `list_price_amount`; the service sets `price_overridden = true` and `overridden_by`, and
  emits a `SALES.LINE.OVERRIDE` audit row carrying `{productUid, listPrice, appliedPrice}` (D-13). The original
  list price is preserved on the row (`list_price_amount`) so the override is always reconstructable — "original vs
  applied, who, when" is on the line + the audit trail. **The override threshold** above which supervisor approval
  is required (OQ-SALES-10) is a **non-blocking policy value** (flagged below); v1 ships the permissioned-override
  mechanism, and the threshold check slots into the service as a configurable percent without a schema change.
- **Discounts (FR-SALES-09).** Both line-level and document-level discounts are supported, each enterable as an
  **amount or a percent** (the two are alternative entry forms; the service resolves a percent to an amount at
  compute time and the **amount is what the math uses**). Discounts apply **before VAT** on the net (tax-exclusive)
  base. Line discount lives on the line; document discount lives on the header and is apportioned across lines'
  taxable bases at compute time (D-4) so the per-rate-band VAT summary stays correct after a document discount.
- **VAT breakdown placement — on the line, summarised on the header (no separate line-tax table).** Each line
  carries exactly one VAT status/rate (a product has one VAT status, D-5), so a line bears **one** tax figure —
  `vat_status` / `vat_rate` / `vat_amount` columns **on the line**, not a child `sales_invoice_line_taxes` table. A
  line-tax child table is justified only when a single line can bear *multiple* taxes (e.g. VAT + an excise/levy),
  which v1 does not have (TZ VAT only, fiscalisation deferred §10). The **document-level** "VAT analysis by rate
  band" the invoice prints (FR-SALES-11/13) is the roll-up `tax_summary` JSONB on the header (D-4), computed at
  finalise — a print/report artefact, not a normalised table. **If a future round adds multiple taxes per line**
  (e.g. excise), a `sales_invoice_line_taxes` child is the clean additive change; reserved, not built (Alternatives).

### D-4 — Totals & tax computation: **service-computed and stored** (`InvoiceTotalsCalculator`), not DB-generated

All monetary roll-ups — line `net`/`vat`/`gross`, header `net_total`/`vat_total`/`gross_total`, and the header
`tax_summary` — are **computed in the service and stored** as plain `NUMERIC` columns, **not** Postgres generated
columns and **not** computed on read.

**Why service-computed-and-stored (the recommendation), not DB `GENERATED ... STORED`:**
1. **Rounding is a correctness invariant that must be identical backend and frontend** (NFR-SALES-02): HALF_UP to
   the document currency's minor units (ADR-0005 D-2; TZS = 0 dp in practice, OQ-CUR-03). The rounding *mode* and
   *order* (round per line, then sum; document discount apportioned before VAT) is business logic the Angular layer
   must reproduce exactly. Encoding it in a Postgres generated-column expression splits the rule across SQL and
   Java — two places to keep in lockstep, the exact divergence NFR-SALES-02 forbids. **One** `InvoiceTotalsCalculator`
   in Java is the single source of the arithmetic; the web mirrors *that*, and an integration test asserts
   stored == recomputed.
2. **Generated columns can't apportion a document discount across lines** (a header value distributed over child
   rows is a cross-row computation a per-row `GENERATED` expression cannot express).
3. **The figures must be frozen at finalise** (FR-SALES-03 immutability). A stored column written once at finalise
   is immutable by the lifecycle guard (D-7); a generated/computed-on-read total would silently recompute if any
   input were ever touched — precisely what an immutable invoice must not do.
4. The cost — a service bug could store a wrong total — is contained by the `chk_*_nonneg` CHECKs and an
   integration test that recomputes and compares; this is the same "service owns the invariant, DB backstops what
   it cheaply can" split used throughout (ADR-0006 D-6, ADR-0007 D-9).

**Computation order (the authoritative algorithm the engineer and the web both implement), tax-exclusive
(FR-SALES-09/11/12, §7 flow):**
1. Per line: `lineNet = round(appliedUnitPrice × quantity) − lineDiscount` (discount before VAT).
2. Apportion the **document discount** across lines pro-rata to each line's `lineNet` (so each line's taxable base
   is reduced fairly); recompute each line's discounted taxable net.
3. Per line: `vat = round(discountedNet × vatRate)` (0 for zero-rated/exempt); `lineGross = discountedNet + vat`.
4. Header: `net_total = Σ discountedNet`; `vat_total = Σ vat`; `gross_total = net_total + vat_total`.
5. `tax_summary` = group lines by `vat_status`/`vat_rate`, summing net + vat per band (the printed VAT analysis).
6. Round once at each boundary (HALF_UP, currency minor units); never round between intermediate steps within a
   line (ADR-0005 D-2). Totals are **recomputed and re-stored on every DRAFT mutation** (add/edit/remove line,
   change a discount) and **frozen at finalise**; a VOID does not recompute (the figures stand as the record of
   what was voided).

### D-5 — Product VAT status: additive `vat_status` column on `products` + a maintained `tax_rate` per company (resolves OQ-PROD-05)

VAT derives per line from the **product's VAT status** (FR-SALES-10, BR-SALES-05), which does not exist on the
shipped `products` table. Two additive changes, both in `V5__sales.sql` (D-12):

**(a) `products.vat_status` — an additive column on the shipped `products` table (clean ALTER, does NOT break V3/V4):**

```sql
ALTER TABLE products
    ADD COLUMN vat_status VARCHAR(20) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE products
    ADD CONSTRAINT chk_product_vat_status
    CHECK (vat_status IN ('STANDARD','ZERO_RATED','EXEMPT'));
```

- `VARCHAR(20)` enum-as-string (`@Enumerated(STRING)`), matching every other enum column (`type`, `status`,
  `party_type`). New enum `VatStatus { STANDARD, ZERO_RATED, EXEMPT }` in `products.domain.enums` (the status is a
  **product** attribute; Sales re-uses the enum via `products.domain.enums`, no duplicate).
- **`DEFAULT 'STANDARD'` is deliberate and additive-safe:** existing product rows (V4 assumes zero product rows in
  dev/verify, but the default guarantees correctness even if rows exist) become standard-rated, the safe TZ default
  (most goods are standard-rated; a business explicitly marks zero-rated/exempt items). Owner-overridable per
  product via `PRODUCT.MANAGE`.
- This **mildly extends the Products module** — its entity/DTO/admin screen gain a `vatStatus` field. That is an
  additive Products change (one column, one enum, one form field), recorded here and in the short note appended to
  ADR-0007's flagged item (the products doc already reserved this slot in its OQ-PROD-05 flag). It is **not** a new
  Products ADR — it is the resolution of an OQ that ADR-0007 explicitly deferred to Sales.

**(b) The rate source — a lean per-company `tax_rates` table, NOT a hard-coded constant (FR-SALES-10, BR-SALES-05):**

The rate must be **maintained data, never hard-coded** (FR-SALES-10 is explicit; a hard-coded `0.18` literal would
also trip the ADR-0005 D-4 "no magic literal" stance). Recommendation: a **small per-company `tax_rates` table**
keyed by `(company_id, vat_status)` holding the current rate — the lean option, not a full tax-jurisdiction engine.

```
tax_rates
| column     | type          | null? | notes |
| id         | BIGINT IDENTITY PK | NO | |
| uid        | VARCHAR(26)   | NO    | ULID; uq_tax_rate_uid |
| company_id | BIGINT        | NO    | FK → companies(id); tenant scope |
| vat_status | VARCHAR(20)   | NO    | STANDARD | ZERO_RATED | EXEMPT; CHECK |
| rate       | NUMERIC(9,4)  | NO    | e.g. 0.1800 (18%), 0.0000; CHECK >= 0 AND < 1 |
| status     | VARCHAR(32)   | NO    | MasterStatus; DEFAULT 'ACTIVE' |
| version    | BIGINT        | NO    | DEFAULT 0 |
| audit cols | …             |       | created/updated by/at |
- uq_tax_rate_company_status UNIQUE (company_id, vat_status)
- fk_tax_rate_company; ix_tax_rates_company (company_id)
```

- **Seeded in V5** for every existing company (same CROSS JOIN pattern V4 used for units): `STANDARD = 0.1800`
  (TZ standard VAT 18%), `ZERO_RATED = 0.0000`, `EXEMPT = 0.0000`. New companies get the three rows seeded in Java
  (a `TaxRateSeeder` called from `BootstrapRunner` + `CompanyService.create`, mirroring `UnitOfMeasureSeeder`,
  V4 §2 precedent). The TZ rate is therefore **data**, owner-maintainable (`SETTING.MANAGE`-style permission,
  folded into a `TAXRATE.MANAGE`, D-11), not a code constant.
- `VatRateResolver` (service) reads the active `tax_rates` row for `(activeCompany, lineVatStatus)` at line-add /
  finalise and **snapshots `rate` onto the line** (`vat_rate`, D-2/D-3) so a later rate change never re-rates a
  historical invoice (immutability, FR-SALES-03).

> **Why a tiny `tax_rates` table over a single constant:** the spec is unambiguous — "the rate value comes from a
> maintained tax setting, **not hard-coded**" (FR-SALES-10), "**never a hard-coded rate**" (BR-SALES-05). A Java
> constant fails the requirement on its face and would need a code release to change when TRA moves the rate. A
> per-company table is per-tenant-correct (a future second company could differ), is the boring 8-column master,
> reuses the V4 seed-per-company mechanism, and snapshots cleanly onto the line. It is **not** a tax-jurisdiction
> engine (no effective-dated history, no product-tax-category matrix) — that is deferred with fiscalisation (§10);
> if rate history is later needed, an `effective_from` column is additive. This is the lean option the brief asked
> for, chosen over both the constant (fails FR) and the full engine (over-build).

### D-6 — Agent link: mandatory `agent_id`, auto-defaulted; commission recorded-not-computed

- `sales_invoices.agent_id` is **NOT NULL** (BR-SALES-06, FR-SALES-14) — every invoice is attached to exactly one
  agent. A scalar FK to `agents(id)` (no cross-module `@ManyToOne`, D-1).
- **Auto-default (FR-SALES-15):** on draft create, if the logged-in user has an **internal** agent record (an
  `agents` row with `agent_kind='INTERNAL'` and `app_user_id = currentUserId`, in the active company), the service
  defaults `agent_id` to it. The lookup is an `AgentService` call returning a DTO — `AgentRepository` gains a
  projection `findInternalAgentIdByCompanyAndUser(companyId, appUserId)` (mirroring the existing
  `findCompanyIdByUid`). Overridable to another **selectable** agent with `SALES.OVERRIDE` (or a dedicated
  permission — folded into `SALES.OVERRIDE` for v1, D-11). A **disabled internal agent is not selectable**
  (BR-PARTY-10 / BR-SALES-06) — the selection query excludes agents whose referenced `app_user` is not ACTIVE
  (Parties already owns this rule; Sales consumes the selectable-agents DTO list).
- **Commission recorded-not-computed (FR-SALES-16):** the agent attachment **is** the captured record commission
  will later consume; v1 stores **no commission columns and no commission table** (no rate/tier exists —
  OQ-PARTY-03 deferred). **Where it slots later:** a `sales_commission` child/derived table keyed on
  `(invoice_id, agent_id)` computed by the future commission feature from `agent_id` + line nets — an additive
  table under its own ADR, fed by the data this model already captures (agent + net per line). Recorded so no one
  adds speculative commission columns now.

### D-7 — Lifecycle & numbering: `InvoiceStatus` enum, transitions in the service, `INV-####` from `code_sequence` at finalise

- **Status enum `DRAFT → FINALISED → VOID`** (D-2 `status` column + CHECK), the only legal transitions:
  - `DRAFT → FINALISED` (FR-SALES-02): the finalise operation, in **one transaction**, (a) recomputes + freezes
    totals (D-4), (b) allocates the `INV-####` number (below), (c) takes the full payment / asserts paid-in-full
    (D-8), (d) sets `status='FINALISED'`, `finalised_at/by`, `invoice_number`, `tax_summary`, (e) emits the audit
    row (D-13) and — when Stock lands — the `SALE.FINALISED` outbox event (D-9). Requires `SALES.CREATE` (+
    `SALES.SETTLE` for the payment step, D-11).
  - `FINALISED → VOID` (FR-SALES-22, BR-SALES-08): the void operation sets `status='VOID'`, `voided_at/by`,
    `void_reason`, emits the audit row, and (future) emits a `SALE.VOIDED` compensating outbox event. Requires
    `SALES.VOID`. **The invoice and its number are retained** (void ≠ delete). The void window (e.g.
    same-business-day vs unrestricted-with-permission, FR-SALES-22) is a **service policy** — recommended default:
    permissioned void within a configurable window; non-blocking (flagged).
  - **No other transitions.** A DRAFT may be edited freely (lines/discounts/customer/agent) or hard-deleted
    (discarding an unfinalised draft is allowed — it consumed no number); a FINALISED/VOID invoice is
    **commercially immutable** — the service rejects any line/payment/total mutation on a non-DRAFT invoice
    (BR-SALES-08), and the optimistic `version` + the `chk_..._number_when_finalised` CHECK backstop it.
- **Numbering — single per-company `INV-####` via the generic `code_sequence`, at finalise (BR-SALES-12,
  FR-SALES-23, OQ-SALES-11/12 RESOLVED).** `InvoiceNumberGenerator.next(companyId)` does `SELECT … FOR UPDATE` on
  the `code_sequence` row for `(company_id, 'SALES_INVOICE')` (creating it with `next_value = 1` on first use),
  formats `INV-%04d` (zero-padded, widening past 9999), increments, writes back — **inside the finalise
  transaction**. This is the **identical mechanism** ADR-0007 D-6 shipped (`code_sequence`, V3); Sales adds **no
  new numbering table** — only a new `entity_kind = 'SALES_INVOICE'`. The row lock serialises concurrent finalises
  for the same company (NFR-SALES-04 — two cashiers get distinct numbers); different companies don't contend. The
  `uq_sales_invoice_company_number` constraint (D-2) backstops any generator bug into a constraint violation.
  - **Number assigned at finalise, not create** (OQ-SALES-11 default): drafts hold `invoice_number = NULL` and
    consume no number; only finalise allocates one. This is why the number column is nullable and gated by the
    DB CHECK.
- **Channel/numbering forward-compat (NFR-SALES-07):** `document_type` reserves POS/SO; per-branch or per-channel
  series are a later **additive** change — a distinct `entity_kind` (e.g. `'SALES_INVOICE:POS'` or
  `'SALES_INVOICE:<branchCode>'`) with no schema change (the discriminator already supports it, ADR-0007 D-6).
  Fiscalisation (deferred §10) augments — does not replace — the number: a future `fiscal_receipt_number` /
  `fiscal_signature` column set is additive on the header. The v1 model precludes neither.

### D-8 — Payment / tender model & the paid-in-full invariant (service-enforced)

- **Tenders are child rows** (`sales_invoice_payments`, D-2): cash + mobile money (CHECK-restricted), split across
  rows (FR-SALES-17). Each row carries amount + currency + `received_by/at`.
- **Paid-in-full at finalise (FR-SALES-18, BR-SALES-07) — service-enforced, the headline invariant.** A finalise
  must satisfy `Σ payments.amount == gross_total_amount` (in the document currency). This is a **cross-row check
  against a header value** — no CHECK or generated column can express "sum of child rows equals a parent column",
  so it lives in `SalesInvoiceServiceImpl.finalise`, which:
  - rejects finalise if tenders under-cover the gross (the sale cannot finalise as paid — §7 unhappy path; no
    receivable is created, credit deferred);
  - for **cash over-tender**, accepts it and records the excess as `change_amount` on the cash payment row
    (`Σ amount − change == gross`), never a negative balance (BR-SALES-07);
  - asserts every payment's `currency == header.currency` (no cross-currency settlement, FR-SALES-19 / BR-CUR-06).
  Tendering happens in the **same transaction** as finalise (paid-in-full at finalise, no AR state), so a
  FINALISED invoice is always fully tendered — the "settled" derived read (D-2) is therefore always true for a
  v1 FINALISED invoice.
- **Forward-compat:** the `tender_type` CHECK widens additively for `CARD`; **credit/receivables** (deferred,
  FR-SALES-20) is the change that introduces a real outstanding-balance state and an AR module — *that* relaxes the
  paid-in-full invariant under its own ADR (the invariant is a v1 rule, not a structural one). The model does not
  preclude it (a future `amount_due`/`balance` is additive on the header). Card and credit are **reserved in the
  enum, not built**.

### D-9 — Stock coupling: NO deduction in v1; the `SALE.FINALISED` outbox event is **designed and reserved**, built when Stock lands

v1 **records sold quantities and deducts no stock** (FR-SALES-21, BR-SALES-11, §10 accepted risk — owner-signed).
The line carries `qty_in_base` + `product_id` + `unit_id` precisely so the future stock-deduction effect needs no
back-computation. The cross-module effect, when Stock lands, fires via the **transactional outbox** (ARCHITECTURE.md
§9 — the cross-module pattern; **never** in-memory `ApplicationEventPublisher`, which loses events on crash).

**Recommendation: V5 does NOT build the outbox table or write events; it reserves the design.** The outbox
(`domain_event`) is named in ARCHITECTURE.md §9 but **not yet built** (verified — no `domain_event` table, no
dispatcher in the codebase). Building it now would mean (a) creating a platform-events table + poller + dispatcher
that **no consumer exists for** (Stock is not built — there is nothing to deduct), and (b) writing events that
would accumulate undispatched. That is speculative infrastructure ahead of its consumer — the over-build the
"prefer the boring option" stance rejects. Instead:

- **The event is fully specified now** (so capturing it later is a service-method addition, not a redesign):
  - **Event type:** `SALE.FINALISED`, emitted in the finalise transaction (and `SALE.VOIDED` as the compensating
    event on void).
  - **Payload (JSONB):** `{ invoiceUid, companyId, branchId, finalisedAt, lines: [{ productId, productUid, unitId,
    qtyInBase, ... }] }` — exactly the per-line `product_id` + `qty_in_base` Stock needs to deduct on-hand, and
    the recipe-explosion input for composed products (Products §9 component deduction — also future).
  - **Mechanism when built:** the `domain_event` outbox table (`id, uid, event_type, aggregate_type, aggregate_id,
    company_id, branch_id, payload JSONB, occurred_at, dispatched_at, status`) written in the **same TX** as the
    finalise, polled and dispatched by a `platform.events` poller — its own migration + ADR when Stock is
    specified, additive on this schema.
- **What V5 ships toward it:** the line columns that make the event lossless (`qty_in_base`, `product_id`,
  `unit_id`). Nothing else. The finalise service method has a documented seam (`// TODO(stock): emit SALE.FINALISED
  outbox event — ADR-0008 D-9`) so the consumer-side wiring is a localised addition.

> If the owner prefers to build the `domain_event` outbox table **now** (to start accumulating finalise events
> before Stock exists, for audit/replay value), that is a defensible alternative — but it is a **platform-events
> ADR** of its own (the outbox is cross-cutting infrastructure, not a Sales table), not folded silently into V5.
> Recommendation stands: reserve now, build with Stock. Flagged below.

### D-10 — Enforcement split: DB enforces unconditional/single-row; service enforces cross-row/cross-module

Consistent with ADR-0006 D-6 and ADR-0007 D-9 (the DB-can't / service-must split):

| rule | enforcement | mechanism |
| --- | --- | --- |
| BR-SALES-01 invoice belongs to one company, raised at one branch | **DB** | `company_id`/`branch_id` NOT NULL + FKs; never updated (service rejects re-home) |
| FR-SALES-02 status ∈ {DRAFT,FINALISED,VOID} | **DB CHECK** | `chk_sales_invoice_status` |
| BR-SALES-12 number unique per company; number⇔finalised | **DB** | `uq_sales_invoice_company_number` + `chk_sales_invoice_number_when_finalised` + `InvoiceNumberGenerator` |
| FR-SALES-04 quantity > 0; amounts ≥ 0 | **DB CHECK** | `chk_sales_invoice_line_qty`, `chk_sales_invoice_line_amounts`, header `chk_*_nonneg` |
| BR-SALES-05 VAT status ∈ enum; rate snapshot | **DB CHECK + service** | `chk_sales_invoice_line_vat_status`; `VatRateResolver` snapshots `vat_rate` |
| FR-SALES-10 rate maintained, not hard-coded | **service + data** | `tax_rates` table (D-5); `VatRateResolver` reads it |
| BR-SALES-02 product sellable, non-archived, branch-associated, same company | **service** | `SalesPricingResolver`/line-add validates via `ProductService` DTO (Products owns the facts) |
| BR-SALES-03 product must have a price on the applicable list | **service** | line-add rejects an un-priced product (Products BR-PROD-11, enforced here) |
| BR-SALES-04 / BR-CUR-07 all amounts share the document currency | **service** | finalise/line-add asserts every child `currency == header.currency` (cross-row) |
| BR-SALES-06 exactly one selectable agent; auto-default | **DB + service** | `agent_id` NOT NULL FK (DB); selectable + active-internal-user + auto-default (service, via `AgentService`) |
| BR-SALES-07 / FR-SALES-18 tenders cover gross at finalise (paid in full) | **service** | `finalise`: `Σ payments == gross` (cross-row vs header); cash over-tender → `change_amount` |
| FR-SALES-19 settlement in the sale's currency | **service** | finalise asserts payment currency == header currency |
| BR-SALES-08 / FR-SALES-03 finalised commercial content immutable | **service + DB backstop** | service rejects mutation on non-DRAFT; `version` + number CHECK backstop |
| BR-SALES-09 override permissioned, recorded, audited | **service + DB** | `SALES.OVERRIDE` gate; `list_price_amount`/`price_overridden`/`overridden_by` on the line; audit emit |
| totals correctness (NFR-SALES-02) | **service + test** | `InvoiceTotalsCalculator` (single source); IT asserts stored == recomputed |
| BR-SALES-11 no stock movement in v1 | **by design** | no stock write; `SALE.FINALISED` outbox reserved (D-9) |

**ScopeGuard addition (ADR-0002 / ADR-0007 D-10 follow-on):** `ScopeGuard.companyIdOf` gains one target type so the
2-arg `@PreAuthorize` gates resolve an invoice uid to its company:

```java
case "invoice" -> salesInvoices.findCompanyIdByUid(uid);
```

backed by a single-column projection `@Query("SELECT i.companyId FROM SalesInvoice i WHERE i.uid = :uid")` on
`SalesInvoiceRepository` (mirroring the eight existing cases — `product`, `customer`, `agent`, `unit`, …). This
adds a `SalesInvoiceRepository` constructor dependency to `ScopeGuard` — the same cross-cutting-spine pattern
already accepted for the product/party/unit repositories (ScopeGuard is the security spine, ArchUnit-allowed). **Not
optional** — without it the target-uid gates fail closed. Lines/payments are addressed *under* their invoice uid in
the API, so they need no own target type (the gate resolves on the parent invoice uid). The `tax_rates` master, if
admin-edited by uid, adds `case "taxrate"` similarly (D-11).

### D-11 — Permission catalogue additions (seeded in V5, module `sales`)

| code | module | description |
| --- | --- | --- |
| `SALES.INVOICE.VIEW` | sales | View and list/search sales invoices |
| `SALES.INVOICE.CREATE` | sales | Create and edit draft invoices; add/edit/remove lines; finalise |
| `SALES.INVOICE.SETTLE` | sales | Take payment / record tenders against an invoice at finalise |
| `SALES.INVOICE.OVERRIDE` | sales | Manually override a line's unit price / apply non-default discount; override the auto-defaulted agent |
| `SALES.INVOICE.VOID` | sales | Void a finalised invoice |
| `TAXRATE.VIEW` | sales | View company VAT rates |
| `TAXRATE.MANAGE` | sales | Maintain company VAT rates (the maintained rate source, FR-SALES-10) |

- **Naming mirrors the shipped catalogue** (`PRODUCT.VIEW`/`PRODUCT.MANAGE`, `PARTY.BRANCH.ASSIGN`): dot-separated,
  `MODULE.RESOURCE.ACTION`. I use the spec's exact verb set (FR-SALES-25 names `SALES.CREATE`, `SALES.VIEW`,
  `SALES.OVERRIDE`, `SALES.VOID`, `SALES.SETTLE`) qualified with `INVOICE` so POS/SO get their own resource codes
  later without colliding (`SALES.POS.CREATE` etc.). If the owner prefers the unqualified `SALES.*` forms, it is a
  trivial seed rename before build — flagged. **`TAXRATE.MANAGE` is separate** because maintaining the VAT rate is
  a finance/settings act distinct from selling (FR-SALES-10's "maintained tax setting").
- **Seeding (V5, idempotent):** `INSERT INTO permissions (code, module, description) VALUES (...) ON CONFLICT (code)
  DO NOTHING`, then the additive `INSERT INTO role_permission SELECT r.id, p.id FROM roles r CROSS JOIN permissions
  p WHERE r.code = 'ORG_ADMIN' AND p.module = 'sales' ON CONFLICT DO NOTHING` — the **exact** V2/V3/V4 pattern.
- **Gate shapes (ADR-0002, mirroring ADR-0007 D-11):**
  - `POST /sales-invoices` (create draft) → `@PreAuthorize("@perm.scoped(#request.companyUid, 'company', 'SALES.INVOICE.CREATE')")`
    (active company is the target — `companyUid` in the body, D-12).
  - `PUT /sales-invoices/uid/{uid}` and line/payment sub-resource mutations →
    `@PreAuthorize("@perm.scoped(#uid, 'invoice', 'SALES.INVOICE.CREATE')")`.
  - `POST /sales-invoices/uid/{uid}/finalise` → `@perm.scoped(#uid,'invoice','SALES.INVOICE.CREATE')` +
    `SALES.INVOICE.SETTLE` checked in-service for the payment step.
  - `POST /sales-invoices/uid/{uid}/payments` → `@perm.scoped(#uid,'invoice','SALES.INVOICE.SETTLE')`.
  - line price override → `@perm.scoped(#uid,'invoice','SALES.INVOICE.OVERRIDE')` (in-service on the override path).
  - `POST /sales-invoices/uid/{uid}/void` → `@perm.scoped(#uid,'invoice','SALES.INVOICE.VOID')`.
  - `GET /sales-invoices` (list/search) → `@PreAuthorize("hasAuthority('SALES.INVOICE.VIEW')")`, results scoped by
    the tenant predicate + active branch.
  - `PUT /tax-rates/uid/{uid}` → `@perm.scoped(#uid,'taxrate','TAXRATE.MANAGE')`.

### D-12 — API / uid / companyUid discipline (mirror ADR-0007 D-12)

- **uids in URLs; ids (as JSON strings) in bodies for joins.** The header is addressed by uid
  (`/sales-invoices/uid/{uid}`); lines and payments are addressed under it
  (`/sales-invoices/uid/{uid}/lines/{lineUid}`, `/.../payments/{paymentUid}`). The cross-module references in
  request bodies are **uids** — `customerUid`, `agentUid`, and per line `productUid`, `unitUid` — which the service
  resolves to the scalar `Long` ids it persists (the same resolve-uid-to-id the product create does for `unitUid`).
- **`companyUid` (String) in the create body** (ADR-0007 D-12, the convention-consistent choice — Products already
  ships it): `CreateSalesInvoiceRequest` carries `companyUid`; the service resolves it and runs
  `ScopeGuard.assertCanActIn`. The active **branch** comes from `RequestContext` (the `X-Branch-Uid` header /
  default branch, ARCHITECTURE.md §5) — the invoice is raised at the operator's active branch; the create body does
  **not** carry a `branchUid` (it would let an operator raise a sale at a branch they are not in — the active
  branch is authoritative).
- **`ApiResponse<T>` envelope** everywhere; list/search paged via `PageMeta`
  (`page,size,totalElements,totalPages,hasNext`) — invoice list at the counter is a paged read (NFR-SALES-05).
- **Money on the wire** is `{ "amount": "1500.0000", "currency": "TZS", "display"?: "…" }` with `amount` a **string**
  (ADR-0005 D-7), via the shared `MoneyDto` (promote to `platform.common.money` per ADR-0007 D-12 if not yet done;
  Sales must not import `parties`/`products` `MoneyDto` — boundary). Every monetary field on the invoice/line/payment
  DTO serialises this way; the line discount, doc discount, tax-summary bands, and tenders included.
- **Enums on the wire:** the string name (`DRAFT`, `FINALISED`, `VOID`, `INVOICE`, `CASH`, `MOBILE_MONEY`,
  `STANDARD`, `ZERO_RATED`, `EXEMPT`).
- **Derived read fields on the DTO** (not stored columns): `settled` (boolean — FINALISED with payments, D-2),
  `amountTendered`, `changeGiven` (sums over payments) — computed in the service for the response, never persisted.

### D-13 — Audit (ADR-0004): emit points and `target_type` strings (plural table names)

Sales' mutating service emits via the existing `AuditService.record(...)` (MANDATORY, same-TX, append-only —
NFR-SALES-03). `target_type` strings are the **plural table names** (the shipped V2/V3 convention; the `audit_logs`
read filter reads naturally on the table name):

| action | target_type | when | detail (fact-only per ADR-0004 D-6) |
| --- | --- | --- | --- |
| `SALES.INVOICE.CREATE` | `sales_invoices` | on draft create | `customerUid`, `agentUid`, `documentType` |
| `SALES.INVOICE.UPDATE` | `sales_invoices` | on draft edit (customer/agent/discount/notes) | minimal/fact-only |
| `SALES.INVOICE.LINE.ADD` / `LINE.UPDATE` / `LINE.REMOVE` | `sales_invoice_lines` | line change (DRAFT only) | `productUid`, `quantity`, `unitPrice` |
| `SALES.INVOICE.LINE.OVERRIDE` | `sales_invoice_lines` | price/discount override | `productUid`, `listPrice`, `appliedPrice` (BR-SALES-09 — recorded) |
| `SALES.INVOICE.FINALISE` | `sales_invoices` | DRAFT → FINALISED | `invoiceNumber`, `grossTotal`, `vatTotal` |
| `SALES.INVOICE.PAYMENT.ADD` | `sales_invoice_payments` | tender taken | `tenderType`, `amount` |
| `SALES.INVOICE.VOID` | `sales_invoices` | FINALISED → VOID | `invoiceNumber`, `voidReason` (NFR-SALES-03) |
| `TAXRATE.UPDATE` | `tax_rates` | rate maintained | `vatStatus`, before/after `rate` |

- **Finalise, settle (payment), and void ARE audited** (NFR-SALES-03 names them explicitly), as are price/discount
  overrides (BR-SALES-09). The audit trail + the on-line `list_price_amount`/`overridden_by` together give the
  full "original vs applied, who, when".
- **The `SALE.FINALISED` outbox event (D-9) is distinct from audit** — audit is the human/security trail (always
  written, in `audit_logs`); the outbox is the cross-module effect channel (`domain_event`, future). Finalise emits
  **both** once Stock lands; v1 emits only the audit row.

### D-14 — Migration: additive `V5__sales.sql`, never a V1–V4 edit

IAM=V1, Parties=V2, Products=V3, Units=V4 — all frozen. Sales is a **new** module → purely **additive
`V5__sales.sql`**. It **must not** edit V1–V4. Ordering within V5 (mirrors ADR-0007 D-14):

1. **`ALTER TABLE products ADD COLUMN vat_status …`** + `chk_product_vat_status` (the additive product change,
   D-5a — done first so the seed/everything after sees it; this is the only edit to an existing table and it is a
   pure additive ALTER with a safe default, not a V3 rewrite).
2. **`tax_rates`** master (D-5b) with FK to `companies`.
3. **Seed `tax_rates`** for every existing company (CROSS JOIN over `companies`, V4 §2 pattern; STANDARD=0.18,
   ZERO_RATED=0, EXEMPT=0; deterministic seed-uid).
4. **`sales_invoices`** (header) with FKs to `companies`, `branches`, `customers`, `agents`, `app_users`; the
   doc-type / status / number-when-finalised / discount / totals-nonneg CHECKs.
5. **`sales_invoice_lines`** (child) with FKs to `sales_invoices`, `products`, `units_of_measure`, `app_users`,
   `companies`, `branches`; the vat-status / qty / amounts / discount-pct CHECKs.
6. **`sales_invoice_payments`** (child) with FKs to `sales_invoices`, `app_users`, `companies`, `branches`; the
   tender / amount / change CHECKs.
7. **Indexes** (D-15 below).
8. **Permission seed + additive ORG_ADMIN grant** (D-11).

All non-Sales FK targets (`companies`, `branches`, `customers`, `agents`, `products`, `units_of_measure`,
`app_users`, `code_sequence`, `roles`, `permissions`, `role_permission`) **already exist** in frozen V1–V4 — no
dependency on un-frozen schema. **No new numbering table** (`code_sequence` exists; Sales adds the
`entity_kind='SALES_INVOICE'` row at runtime, D-7). **No outbox table** (D-9 reserves it).

### D-15 — Indexes (lookup + tenant + counter-responsiveness, NFR-SALES-05)

```
-- sales_invoices
CREATE INDEX        ix_sales_invoices_company          ON sales_invoices (company_id);
CREATE INDEX        ix_sales_invoices_company_branch   ON sales_invoices (company_id, branch_id);            -- active-branch list (FR-SALES-24)
CREATE INDEX        ix_sales_invoices_customer         ON sales_invoices (customer_id);                       -- a customer's invoices
CREATE INDEX        ix_sales_invoices_agent            ON sales_invoices (agent_id);                          -- an agent's invoices (commission input)
CREATE INDEX        ix_sales_invoices_status           ON sales_invoices (company_id, status);                -- draft/finalised filters
CREATE INDEX        ix_sales_invoices_created_at       ON sales_invoices (company_id, created_at);            -- date-range list/report
-- (uq_sales_invoice_company_number already indexes number lookup)

-- sales_invoice_lines
CREATE INDEX        ix_sales_invoice_lines_invoice     ON sales_invoice_lines (invoice_id);                   -- read a document's lines
CREATE INDEX        ix_sales_invoice_lines_product     ON sales_invoice_lines (product_id);                   -- "what sold" / future stock + commission
CREATE INDEX        ix_sales_invoice_lines_company     ON sales_invoice_lines (company_id);                   -- tenant-scoped reporting

-- sales_invoice_payments
CREATE INDEX        ix_sales_invoice_payments_invoice  ON sales_invoice_payments (invoice_id);                -- a document's tenders
CREATE INDEX        ix_sales_invoice_payments_company  ON sales_invoice_payments (company_id);                -- tenant-scoped reporting

-- tax_rates
CREATE INDEX        ix_tax_rates_company               ON tax_rates (company_id);
```

Native SQL is permitted for the heavier sales reports (e.g. VAT-by-band summaries, daily sales) where JPQL can't
express an aggregate cleanly, kept behind a clearly-named repository method (PROJECT-CONVENTIONS — native allowed
for reports/bulk).

## Consequences

**Easier / safer:**
- **Currency-safe, tenant-safe, immutable-by-design from day one:** every amount is a `Money` pair (ADR-0005);
  header + lines + payments carry `company_id`/`branch_id` under the §3.2 predicate; finalised totals are frozen
  stored columns gated by the lifecycle guard. The retrofit traps (bare-number money, add-tenancy-later,
  mutable-finalised-doc) cannot occur.
- **One authoritative tax-exclusive algorithm** (`InvoiceTotalsCalculator`, D-4) that the web mirrors and a test
  pins (stored == recomputed) — NFR-SALES-02 (identical backend/frontend rounding) is structurally addressed, not
  hoped for.
- **VAT is maintained data, not a constant** (`tax_rates`, D-5) — FR-SALES-10/BR-SALES-05 met literally; a TRA rate
  change is a data edit (`TAXRATE.MANAGE`), not a release. The per-product `vat_status` resolves OQ-PROD-05 with a
  single safe-default additive column.
- **Numbering reuses the shipped `code_sequence`** (D-7) — no new sequence table, concurrency-safe per-company
  `INV-####` at finalise (NFR-SALES-04), drafts consume no numbers.
- **Snapshotted lines make a finalised invoice an honest historical record** — renaming/repricing/archiving a
  product never mutates what a past invoice says.
- **Stock-ready without over-building** (D-9): `qty_in_base` + `product_id` per line make the future
  `SALE.FINALISED` outbox event lossless; the outbox infra is reserved for when Stock (its consumer) exists.
- **Sales stays decoupled** — it consumes `products.domain.dto`/`parties.domain.dto` and persists scalar-id FKs;
  no cross-module entity import; one new `ScopeGuard` case; one new `code_sequence` kind.

**Harder / to watch:**
- **The totals invariant is service-owned** (D-4) — a calculator bug stores a wrong total that no CHECK catches.
  **Must have** an integration test that recomputes every total/VAT-band from the lines and asserts equality, and a
  property test over discount/VAT-band combinations. Highest-discipline surface in the module.
- **The paid-in-full invariant is service-owned** (D-8) — `Σ payments == gross` is cross-row; needs IT coverage
  (under-tender rejected; cash over-tender → change; mixed-currency tender rejected).
- **Currency consistency is service-owned** (D-2/D-10) — every child `currency` must equal the header; a row whose
  currency disagrees is a defect no CHECK catches (the header stores currency once, children denormalise it). IT
  must assert they always match.
- **`vat_rate`/`list_price`/`product_name` snapshots can drift from the live master** — *by design* (immutability),
  but reviewers must not "fix" a snapshot by re-reading the live product. Documented; do not normalise away.
- **The additive `products.vat_status` ALTER is the one cross-module schema touch** (D-5/D-12) — it slightly
  extends the Products entity/DTO/admin screen. Coordinated, not silent: recorded here and noted against ADR-0007's
  OQ-PROD-05 flag.
- **`tax_summary` as JSONB** is a print/report artefact, not a queryable normalised table — fine for the invoice
  printout; if VAT-band *reporting across invoices* later needs SQL aggregation, query the lines (indexed on
  `company_id`), not the JSONB. Recorded so no one builds reports off the JSONB.

**Migration / delivery cost:**
- 1 additive Flyway file (`V5__sales.sql`): 1 ALTER on `products` (+1 CHECK), 1 master (`tax_rates`) + seed, 3
  document tables (`sales_invoices`, `sales_invoice_lines`, `sales_invoice_payments`) = **4 new tables**, their
  FKs/uniques/CHECKs, ~11 indexes, 7 permission rows + 1 additive grant. **No** new numbering table, **no** outbox
  table, **no** trigger.
- Backend: the `sales` entity set (3 entities + DTOs + 3 repositories + `SalesInvoiceService`/Impl +
  `InvoiceTotalsCalculator` + `InvoiceNumberGenerator` + `SalesPricingResolver` + `VatRateResolver`); the
  `tax_rates` set (+ `TaxRateSeeder` mirroring `UnitOfMeasureSeeder`); the `ScopeGuard` `invoice` (+ `taxrate`)
  case (D-10); the additive `products.vatStatus` field on the Products entity/DTO/form (D-5); `AgentRepository`
  internal-agent-by-user projection (D-6). The finalise method carries the documented outbox seam (D-9).
- Web: a sales-invoice screen (customer/agent pick, line add with product+unit+qty, price + permissioned override,
  line/doc discount, live tax-exclusive totals mirroring `InvoiceTotalsCalculator`, finalise + split-tender
  payment, VAT invoice print with the per-band summary, void) — reusing the Products/Parties admin patterns and the
  ADR-0005 `Money` input; a small `tax_rates` settings screen; a `vatStatus` field on the product form.
- Deployment risk: low — additive migration; the `products.vat_status` default makes the ALTER safe on a populated
  table; no outbox infra to operate. The one operational note: ensure `tax_rates` is seeded (V5 seed + Java seeder
  for new companies) before the first finalise, else `VatRateResolver` has no rate (fails closed — finalise blocked
  with a clear message, never a silent zero).

## Alternatives considered

- **DB `GENERATED ... STORED` columns (or computed-on-read) for line/header totals** instead of service-computed
  stored values. Keeps the arithmetic in one place (SQL), can't drift from inputs. **Rejected (D-4):** the rounding
  rule must be identical in Java *and* Angular (NFR-SALES-02) — encoding it in SQL splits it across SQL/Java/web
  (three places), and a generated expression cannot apportion a document discount across lines (cross-row) nor
  freeze figures at finalise (immutability). One Java calculator the web mirrors + a stored frozen value + an
  equality test is the boring, correct choice.
- **A normalised `sales_invoice_line_taxes` child table** (one row per tax per line) instead of `vat_status`/
  `vat_rate`/`vat_amount` columns on the line. Future-proof for multiple taxes per line. **Rejected for v1 (D-3):**
  v1 is single-tax (TZ VAT only; fiscalisation/excise deferred §10), so a line bears exactly one tax — a child
  table is a join for one row. The columns are the normal-form choice for one-tax-per-line; the child table is the
  clean **additive** upgrade if excise/multi-tax ever lands. Reserved, not built.
- **A separate `SETTLED` lifecycle status** (DRAFT → FINALISED → SETTLED → VOID). Matches the spec's prose
  vocabulary. **Rejected (D-2):** with paid-in-full-at-finalise and no AR state (FR-SALES-18), a FINALISED invoice
  is always settled — a SETTLED status would be a transition that can never be partial or skipped. "Settled" is a
  derived read; a real settled/outstanding state earns its place only when credit (deferred) introduces a balance.
- **A hard-coded standard-VAT constant (e.g. `0.18`) or a single global setting row** instead of a per-company
  `tax_rates` table. Fewer tables. **Rejected (D-5):** the spec mandates the rate be "maintained, **not
  hard-coded**" (FR-SALES-10, BR-SALES-05) — a constant fails the requirement and needs a release to change. A
  per-company table is per-tenant-correct, reuses the V4 seed mechanism, and is still the lean option (not a tax
  engine). Chosen.
- **Build the `domain_event` outbox table + dispatcher now and write `SALE.FINALISED` from v1.** Most forward. 
  **Rejected as the default (D-9):** the consumer (Stock) does not exist, so events would accumulate undispatched —
  infrastructure ahead of its consumer. The event is fully *specified* now (lossless line data captured) so building
  it with Stock is an addition, not a redesign. If the owner wants the outbox now for replay/audit value, that is a
  **platform-events ADR** of its own, not folded into V5. Reserved.
- **A polymorphic `sales_documents` table for invoice + future POS + SO** sharing one table with a `document_type`
  discriminator vs three per-channel tables. **Resolved as one table with a reserved discriminator (D-2/D-7):**
  the channels share the spine (customer/agent/lines/tax/totals/tenders) and differ in lifecycle/UX, not in
  persisted shape — one `sales_invoices` table with `document_type` (INVOICE now; POS/SO reserved) is the boring
  choice that lets POS/SO layer on additively (widen the CHECK, add channel-specific columns nullable). Three
  divergent tables would duplicate the whole spine; rejected.

## Open / flagged items (do NOT block the build; recommended defaults stand)

These are the non-blocking detail OQs from sales.md §11 plus minor policy choices on top of this schema. Each has a
recommended default the architect has modelled to; none requires a schema change to confirm.

1. **OQ-SALES-10 — override / approval threshold value.** The permissioned override mechanism is built (D-3);
   the **threshold above which supervisor approval is required** (e.g. discount > X% or price below cost) is open.
   **Recommended default:** a single configurable percent threshold, owner-set, checked in `SalesInvoiceServiceImpl`;
   ship the permissioned override regardless. **Blocks build:** NO — additive (a config value + a service check; no
   schema change). Confirm before go-live.
2. **OQ-SALES-11 — number-assignment point.** Modelled to the recommended default: **number at finalise** (DRAFT
   holds NULL, D-7). **Blocks build:** NO — the default stands; the model is built to it.
3. **OQ-CUR-03 — rounding mode & TZS decimals.** Modelled to the recommended default: **HALF_UP, TZS = 0 dp**
   (ADR-0005 D-2; `InvoiceTotalsCalculator` D-4). **Blocks build:** NO for the model (NUMERIC(19,4) storage carries
   any decimals); **must confirm before go-live** (totals must round identically backend/frontend, NFR-SALES-02) —
   it is a calculator-config value, not a schema change.
4. **Void window** (FR-SALES-22) — recommended default: permissioned void within a configurable window, service
   policy (D-7). **Blocks build:** NO.
5. **Permission code spelling** (D-11) — I qualified the spec's `SALES.*` verbs with `INVOICE`
   (`SALES.INVOICE.CREATE` etc.) to leave room for `SALES.POS.*`/`SALES.SO.*`. If the owner prefers unqualified
   `SALES.CREATE/VIEW/SETTLE/OVERRIDE/VOID`, it is a seed rename before build. **Blocks build:** NO.
6. **Build the outbox now vs with Stock** (D-9) — recommended: reserve now, build with Stock (its consumer). If the
   owner wants the `domain_event` outbox in v1, it is a separate platform-events ADR. **Blocks build:** NO.
7. **`products.vat_status` is an additive Products change** (D-5) — recorded here; it lightly extends the Products
   entity/DTO/admin screen. Coordinate with whoever owns the Products screen so the `vatStatus` field lands with
   Sales. **Blocks build:** NO (the column + default ship in V5; the Products UI field is a small additive form
   change).

No FR/BR is ambiguous enough to halt implementation; the items above are policy values and minor naming, all
defaulted here and overridable without a schema change.

## Summary

This ADR specifies the Sales data model as a three-table sales document — `sales_invoices` (header) +
`sales_invoice_lines` + `sales_invoice_payments` — scoped per company and per raising-branch, with tax-EXCLUSIVE
VAT computed by a single Java `InvoiceTotalsCalculator` and stored/frozen at finalise; per-product VAT derived from
an additive `products.vat_status` column and a maintained per-company `tax_rates` table (resolving OQ-PROD-05);
price-list prices snapshotted onto the line with a permissioned, audited override; a mandatory auto-defaulted agent
with commission recorded-not-computed; cash + mobile-money split tenders enforced paid-in-full at finalise; a
DRAFT → FINALISED → VOID lifecycle with single per-company `INV-####` numbering allocated at finalise from the
shipped `code_sequence`; and the `SALE.FINALISED` transactional-outbox event fully specified and the line data
captured (`qty_in_base`, `product_id`) so the future Stock module's deduction is a lossless addition — built when
Stock (its consumer) exists, not before. Every invariant is placed deliberately (DB CHECK for unconditional /
single-row, service guard for cross-row / cross-module / conditional), mirroring ADR-0006/0007. The migration is a
single additive `V5__sales.sql` (one safe ALTER on `products`, `tax_rates` + seed, three document tables, indexes,
permission seed) that never edits V1–V4, and the API follows the established uid/`companyUid`/`ApiResponse<T>`/
`Money`-as-string discipline. **The model is ready for project-manager sequencing and backend build:** no
ADR-blocking question remains (the §11 OQs are policy values with defaults the model is built to), every FK target
already exists in frozen schema, the numbering and money primitives are reused not reinvented, and the one
cross-module touch (the `products.vat_status` ALTER) is additive and named.
