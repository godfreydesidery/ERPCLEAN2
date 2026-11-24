# 0073 — Branch menu and branch prices

- **Status:** Accepted — owner 2026-11-24 ("yes, do, then build"); implemented on `feat/branch-menu-pricing`.
- **Date:** 2026-11-24
- **Deciders:** owner; design by the engineering assistant.
- **Migration:** none. Uses the existing `product_branch` table (V3) as it stands.
- **Numbering note:** 0070 is the (untracked) EFD file-drop ADR, 0071 the Orbix App PWA, 0072 POS item returns.

## Context

A restaurant client runs two branches in one company. Some items are sold at branch A and not at branch B, and
an item sold at both may cost a different price at each. Until now every branch of a company saw every item at
the one company price:

- the till loads the catalogue by company (`GET /products?companyId=`) — no branch dimension;
- the price resolver goes customer contract → customer list → company default list → legacy row — no branch;
- `product_branch.active` and `product_branch.branch_price` already exist (V3, "P3 branch-level overrides") and
  the web Product Master already edits them in its *Branch availability* table, but **nothing read them**.
  Clients could fill them in and see no effect.

The alternative — one company per branch — was rejected: it splits the books, VAT returns, reports and OrbixHQ,
makes shared items be maintained twice, removes branch stock transfers, and cannot be undone later.

## Decision

### D-1 Which branches sell an item

| `product_branch` rows for the item | Sold at branch X? |
|---|---|
| **none** | **yes** — every branch (the default; nothing changes for an item nobody configured) |
| some | **only if** there is a row for X with `active = true` |

This rule fits the data already on clients' databases: items created on the till, in OrbixHQ or by bulk import
have no rows (sold everywhere); Product Master with *All branches* on saved an active row for every branch (sold
everywhere that existed then); Product Master with *All branches* off saved rows only for the ticked branches
(sold only there — which is what the user asked for at the time).

Known consequence: an item that has rows is **not** sold at a branch created later until someone ticks it there.

### D-2 Branch price

`product_branch.branch_price` (when the row is active and the price is set) is the item's price **per base unit**
at that branch. Precedence for one line:

1. customer contract price (unchanged);
2. the customer's own default price list, when it prices the line (unchanged);
3. **branch price** × the unit's factor to base (a crate of 24 = 24 × the bottle's branch price);
4. company default list → legacy lowest-id row (unchanged).

- A branch price replaces the company list price for **every unit** of the item at that branch, including a
  pack that has an explicit non-linear company price: the explicit pack price is company-wide and would undercut
  or overcharge the branch.
- **VAT stance and currency** follow the company list price the line would otherwise have had; when the item has
  no company price at all, the company default price list's stance and currency; else VAT-exclusive (the
  system-wide default) and the document currency.
- The quote's source is the new `PriceSource.BRANCH_PRICE` (shown to the till by the batch price read and
  written into the invoice line's audit detail; the enum is stored nowhere with a CHECK, so no migration). The
  price-list name on the quote reads "Branch price". The posted line's `list_price_amount` is the branch price.
- Every sales document is priced at **its own branch**: POS sales and restaurant bills, and back-office
  quotations, sales orders and invoices. The till's batch price read (`POST /product-prices/resolve`) uses the
  caller's active branch (`X-Branch-Uid`), so the price shown is the price posted.
- Promotions targeted at a branch (`promotions.target_branch_id`) stay out of scope; the resolver that applies
  promotions is still unwired (ADR-0029).

### D-3 Where the menu is enforced

- **Catalogue reads** take `soldHere=true` (`GET /products`, `GET /products/categories`,
  `GET /products/barcode-lookup`): filtered to the caller's active branch. Without the flag nothing changes —
  web admin lists and pickers keep showing the whole company.
- **Till sale** (`POST /pos/sales`, via `PosSaleService.processTillSale`): refused (422 REJECTED, like every till
  sale rule) when a line is not sold at the branch the invoice is raised at (the caller's active branch, else
  the session's), with `errorCode` `NOT_ON_BRANCH_MENU` (the till then offers no discount approval) —
  unless the idempotency key already names a posted sale, which is answered as before.
- **Restaurant order line** (`POST /restaurant/orders/uid/{uid}/lines`): refused (409) for an item not sold at the
  order's branch. Priced add-ons are not checked — they belong to the item's choices.
- **Not enforced** (deliberately):
  - restaurant **payment** — the lines were checked when ordered; a menu change mid-service must not make an
    open table unpayable;
  - **offline** sales — the customer has already paid; the sale posts and any price difference is flagged for a
    manager as today (ADR-0068);
  - back-office quotations, orders and invoices — head office may sell anything the company owns; they are only
    **priced** at their branch (D-2).

### D-4 Editing

- Product Master keeps its *Branch availability* table, relabelled **Sold here** / **Price here**. It now saves
  every row (ticked and unticked), so un-ticking a branch is recorded; before, unticked rows were simply not sent.
- `AssignProductBranchRequest` gains `clearBranchPrice` (a blank price used to mean "leave as it was", so a
  branch price could never be removed).
- The till's *Add product* and OrbixHQ's *New item* create items with no rows (sold everywhere), as before.
- OrbixHQ *Edit item* shows *At <branch>* (Sold here / Price here, via `PUT /branch-menu`) only to users with two
  or more branches (owner 2026-11-24).
- A till's saved offline menu from before the update is company-wide until its first refresh after sign-in;
  accepted (owner 2026-11-24) — discarding it would leave a till updated while offline with no menu.

## Rollout check (before a client updates)

Values typed into *Branch price* or switched off under *Active* in the past take effect on update. Run the
read-only check in [docs/ops/branch-menu-precheck.sql](../ops/branch-menu-precheck.sql) on the client's database
and confirm the list with them first.

## Consequences

- One company, one set of books; each branch can have its own menu and prices.
- The till's offline catalogue is per branch; switching branch refreshes it.
- Every price read gains one indexed lookup on `product_branch (product_id, branch_id)` (unique index exists).
