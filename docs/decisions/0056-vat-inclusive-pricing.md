# ADR-0056: VAT-inclusive pricing

- **Status:** Accepted (2026-07-05) — owner-decided design; `V86` DDL owner-approved before authoring.
- **Deciders:** Owner + Solutions Architect
- **Migration:** `V86__vat_inclusive_lines.sql` (additive, metadata-only on populated tables).
- **Related:** ADR-0008 (sales-invoice totals algorithm, D-4), ADR-0021 (SO/quotation totals reuse
  the invoice algorithm, D-9), ADR-0041 D5 (price-list pricing-resolution metadata —
  `price_lists.price_includes_vat` shipped but dormant), ADR-0048 (`resolveUnitListPrice`
  unit-aware resolution), ADR-0043 (schema freeze / durable DB — additive-only).

## Context

Retail/shelf pricing in Tanzania (and similarly VAT-jurisdictions) is usually **quoted VAT-inclusive**
("shelf price 1,180 TZS includes 18% VAT"), while B2B/wholesale price lists are usually quoted
**exclusive** ("1,000 + VAT"). The sales totals engine (`InvoiceTotalsCalculator` /
`SalesOrderTotalsCalculator`) has only ever supported the exclusive interpretation: it takes
`unit_price_amount` as a **net** amount and adds VAT on top. Fed an inclusive shelf price of 1,180
into that engine unchanged would over-tax the customer by 18% on top of what they were quoted.

The schema already carries `price_lists.price_includes_vat` (P2 D5 / ADR-0041) — plumbed through
`PriceList`/`PriceListDto`/`CreatePriceListRequest`/`UpdatePriceListRequest` — but **nothing in the
pricing or totals code path reads it**. This ADR activates it.

## Decision

### D-1 — Reuse the dormant per-list flag; do NOT add a new per-row override

The VAT-inclusive/exclusive **stance is a property of the price list**, not of an individual price
row or line. Reusing `price_lists.price_includes_vat` means:

- One flag per list, set once by whoever administers pricing policy (a retail list is inclusive, a
  wholesale list is exclusive) — matches how the business actually thinks about it.
- No new `product_prices` column, no new `CreatePriceListRequest`/`UpdatePriceListRequest` fields —
  the flag and its CRUD already exist and round-trip correctly (see
  `PriceListServiceImplIT#create_withMetadata_persistsCurrencyDatesVatDefaultAndScope`).

**Rejected: a new column on `product_prices` (per-price-row stance).** Would let two rows on the
*same* list disagree about VAT treatment — a policy question no one asked for, adds a migration
column no one asked for, and duplicates data the list already carries. The per-list flag is the
right granularity; the line-level `price_inclusive` snapshot (D-3) is where genuine per-transaction
immutability is needed, not per-price-row.

### D-2 — Inclusive-by-default lives in the UI; the service/DB default stays EXCLUSIVE

"New price lists are VAT-inclusive by default" is delivered as a **UI affordance**: the price-list
create form pre-checks the *Prices include VAT* toggle and sends `priceIncludesVat=true` explicitly.
The **service/DB default is left EXCLUSIVE** — `PriceListServiceImpl.create` honours whatever the
request carries and, when the field is omitted, falls through to the entity default `false`; it does
**not** force `true`.

Why the default is not pushed into the service layer:
- **Grandfathering.** The flag `price_lists.price_includes_vat` was dormant before this change (read
  by nothing in the pricing math), so any historical row's value carried no meaning and every list
  was authored under the only-ever-supported exclusive reading. A service default of `true` would
  not touch existing rows, but pairing it with the newly-live resolver is a footgun; keeping the
  default `false` means nothing an operator did before this change is silently reinterpreted.
- **Import safety.** A bulk price-import or direct-API caller that creates a list without specifying
  the flag gets EXCLUSIVE (the safe legacy reading) rather than silently having net prices re-read as
  gross. Humans who mean "shelf price includes VAT" express that through the UI, where the toggle is
  pre-checked.
- **Consistency.** The DB column default (`false`, V3) and the service default now agree.

Trade-off (accepted): a list created via raw API/import without the flag is exclusive, so
"inclusive by default" is an invariant of the **user-facing** create flow, not of every possible
entry point. A future import that wants inclusive lists must pass the flag (or a company-level
default setting can be added later). `updateByUid` is untouched — an explicit value is still required
to change an existing list; omitting the field keeps whatever the list already has.

Deploy note (grandfathering): before enabling this in an environment, audit that
`price_lists.price_includes_vat` is all-`false` (expected everywhere, since the flag was dormant). Any
row intentionally set `true` will now be read as inclusive; if that is not intended, reset it with a
one-off data `UPDATE` (a data correction on the durable DB, not a schema migration).

### D-3 — GROSS-PRESERVING (exact), snapshotted per line

Each sales line (`sales_invoice_lines`, `sales_order_lines`, `quotation_lines`) gains a
`price_inclusive BOOLEAN NOT NULL DEFAULT false` column, set once at line-add time from the
resolved price's originating list (immutable snapshot — mirrors the existing
`list_price_amount`/`vat_status`/`vat_rate` snapshot columns; the invoice/order must keep printing
what was true when the line was added, even if the list's flag changes later).

The chosen reconciliation is **gross-preserving / exact**: for an inclusive line, the entered price
is treated as the **gross** (VAT-inclusive) unit price, and VAT is *stripped out* of it —
`net = round(gross / (1 + rate))`, `vat = gross − net` — so `net + vat` reproduces the entered gross
**exactly**, to the shilling, never off by a rounding cent. This was chosen over a "net-first"
alternative (see Alternatives) because the headline promise of VAT-inclusive pricing is "the
customer pays exactly the shelf price" — a net-first approach that rounds net then adds VAT on top
can miss the shelf price by ±1 in the last digit, which is the one thing inclusive pricing must not
do.

### D-4 — Resolver threads the flag through every price-source branch

`PriceResolutionServiceImpl.resolveUnitListPrice` (the live path every `addLine` calls) changes its
return type from bare `BigDecimal` to `UnitListPriceDto(BigDecimal amount, boolean vatInclusive)`:

- **Explicit per-unit (pack) override row** → `vatInclusive` comes from **that row's own**
  `product_price.price_list.price_includes_vat` — a pack override on an inclusive list is itself
  inclusive, independent of what the base row's list says (a product can be priced on several
  lists with different stances).
- **Base row × `factor_to_base`** → `vatInclusive` comes from the base row's own price list, same
  reasoning.

The dormant `resolve(...)` / `ResolvedPriceDto` path (customer-price → promotion → tier → list,
ADR-0029 D-6 — still not wired into any caller) is updated too, cheaply: `ResolvedPriceDto` gains a
`vatInclusive` field; `listPrice`/`tier` carry the originating list's flag (tiers/list rows resolve
their price list by id); `customerPrice` defaults `false` (a customer contract price has no price
list when unattached — soft-FK `priceListId` nullable — so there is no stance to inherit; if the
contract price *was* derived from a list, that list's flag is used); a promotion discount inherits
the underlying list price's flag (a promotion is a modifier on top of a list price, not a new
stance). This keeps the dead path honest without inventing new behaviour for code nothing calls.

### D-5 — Totals calculators branch PER LINE on the snapshotted flag

`InvoiceTotalsCalculator` (sales invoice) and `SalesOrderTotalsCalculator` (sales order **and**
quotation — same algorithm, ADR-0021 D-9) branch per line:

- **EXCLUSIVE line (`price_inclusive = false`): algorithm UNCHANGED.**
  `rawNet = round(unitPrice × qty)`; line discount + apportioned doc discount taken off net;
  `vat = round(discountedNet × rate)`; `gross = net + vat`. Byte-identical to pre-`V86` output
  (regression-pinned, T4).
- **INCLUSIVE line (`price_inclusive = true`):** `unitPrice` is read as **gross**.
  `rawGross = round(unitPrice × qty)`; line discount + apportioned doc discount taken off **gross**
  (same discount-resolution code as the exclusive branch — it already operates on
  `unitPrice × qty` regardless of what that product represents); then
  `net = round(discountedGross / (1 + rate))`, `vat = discountedGross − net`. By construction
  `net + vat = discountedGross` exactly.

Both branches reuse the **identical** raw-amount / line-discount / doc-discount-apportionment code —
the only difference is the final net/vat/gross derivation step, keyed on the line's own
`priceInclusive`. Doc-discount apportionment stays pro-rata by each line's own raw amount (net for
an exclusive line, gross for an inclusive one) across **all** lines on the document — documents are
practically single-stance in normal use (one price list per document), so this does not need to be
smarter; the per-line identity `net + vat = gross` holds regardless (T5).

`ZERO_RATED`/`EXEMPT` lines (`rate = 0`) are the identity case for the inclusive branch:
`net = gross / 1 = gross`, `vat = 0` — no division anomaly (T3).

`tax_summary` (banded by `vat_status`/`vat_rate`) and header totals are summed from each line's
**derived** net/vat, so bands and header totals are correct regardless of the per-line stance mix.

### D-6 — DTO exposure

`SalesInvoiceLineDto`/`SalesOrderLineDto`/`QuotationLineDto` gain a `priceInclusive` field (from the
snapshot column) so the web can label an inclusive line ("VAT incl.") without recomputing anything.
`PriceListDto` already exposes `priceIncludesVat` (unchanged).

## Consequences

- **Positive:** shelf/retail pricing now reproduces the quoted price exactly; wholesale/B2B
  exclusive pricing is untouched byte-for-byte; the stance travels with the price list an
  administrator already manages, no new master data concept.
- **Inclusive-by-default is a UI-flow behaviour, not a service default** (D-2): the create form
  sends `priceIncludesVat=true`; a caller that omits the flag on `CreatePriceListRequest` still gets
  the exclusive entity default. So no existing test fixture, import path, or direct-API caller
  changes meaning — only the human create flow defaults to inclusive.
- **Mixed-stance documents** (a doc with both inclusive and exclusive lines) are not forbidden —
  each line's `net + vat = gross` identity holds independently — but doc-discount apportionment
  pro-rates across a mixed net/gross basis; acceptable per the owner's call that documents are
  practically single-stance.
- **No cross-module boundary change:** all of this lives inside the `products` (resolver, price
  list) and `sales` (line snapshot, totals) modules; `products` still does not import `sales`'
  `TaxRate` (VAT **rate** resolution stays a sales-side concern via `TaxRateRepository`, snapshotted
  onto the line as `vat_rate` exactly as before) — only the boolean **stance** flows from `products`
  to `sales` via the existing `PriceResolutionService` interface.

## Alternatives considered

- **Net-first (round net, then add VAT) for inclusive lines** — rejected: reintroduces exactly the
  off-by-a-cent drift VAT-inclusive pricing exists to avoid; the customer could be charged 1,181 for
  a 1,180 shelf price. Gross-preserving (D-3) removes this by construction.
- **New per-row `product_prices.vat_inclusive` column** — rejected (D-1): wrong granularity: VAT
  stance is a price-*list* policy, not a per-price-row choice; the list flag already exists and is
  wired through CRUD.
- **Reinterpret existing `price_lists`/lines as inclusive** — rejected: a silent, invisible change to
  every historical total; violates the additive/backward-compat rule and would corrupt closed-period
  reporting. Existing data keeps its current (exclusive) reading; only the *UI default for new lists*
  changes.
- **Force the inclusive default in `PriceListServiceImpl.create` (service layer)** — rejected: it
  reinterprets every price list created without the flag, including ~29 integration-test fixtures and
  any future bulk-price import of exclusive prices, as gross — the exact silent-reinterpretation this
  ADR set out to avoid. The default is therefore placed at the UI create flow, where "shelf price
  includes VAT" is the operator's actual mental model (D-2).
- **Company-level global VAT-inclusive toggle** instead of per-list — rejected: real businesses run
  a retail list (inclusive) and a wholesale list (exclusive) side by side; a single company-wide
  toggle cannot express that.
