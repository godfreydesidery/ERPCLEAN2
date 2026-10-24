# 0065 — Restaurant orders: open checks, prep-station tickets, bill, then payment

- **Status:** Proposed (2026-10-24). Design approved in principle by the owner ("build it the
  standard way"). **The DDL in Appendix A and the permission seed in Appendix B still need explicit
  approval before any migration is written.**
- **Builds on:** ADR-0029 (POS tills, sessions, quick sale), ADR-0042 (POS idempotency, tenders,
  step-up authority), ADR-0044 (POS supermarket readiness).
- **Requested by:** J COB (CAFE TULIA, tulia Ltd), 2026-10-24. A second food business they spoke to
  described the same process.

## Context

### What the client asked for

> *Kwenye restaurant kwanza unatoa order ina print either bar au kitchen inategemea order ya nini,
> baada ya hapo inaprint bill ambayo inatoka sehemu moja tu kwa cashier bili ambayo anapelekewa
> mteja, baada ya mteja kulipa ndio unaprint payment ambayo ndio ina clear hiyo order.*

In a restaurant, the order is placed first and prints at the bar or the kitchen, depending on
what was ordered. Then a bill prints, at the cashier only, and is taken to the customer. After the
customer pays, the payment prints, and that clears the order.

> *printer ziko mbili, moja jikoni moja hapa kwa barista … oda moja ya chakula na kinywaji
> inajitenga ukiprint.*

There are two printers, one in the kitchen and one at the barista. One order containing food and a
drink splits when printed: each printer gets its own slip.

They also asked for the menu tiles to show the **price** instead of the item code. That needs no
backend change and ships separately (see D-12).

### What OrbixPOS does today

The restaurant register (`pos_app/lib/features/register/restaurant_register.dart`) is the demo
stub:

- **"Send to kitchen" prints nothing.** It sets a local flag and shows a toast.
- **A table is a note on the sale.** There is one cart per till, so Table 1 cannot stay open while
  Table 2 is served.
- **There are 12 hard-coded tables,** each labelled "seats 4".
- **There is no bill step.** PAY rings the sale immediately.
- **Only one printer can be set up per till** (the receipt printer).

The backend has no concept of an open order. There is no park or hold on the server; the
supermarket register parks sales on the device only.

### The industry standard

Toast, Square for Restaurants, Lightspeed, Oracle MICROS and Odoo POS Restaurant converge on the
same model, and this ADR adopts it:

1. **The open order (the "check") lives on the server,** not on a till. It survives a till crash
   and can be opened from any device.
2. **Items are routed to prep stations,** decided centrally by menu group.
3. **Station printers are network printers addressed by IP,** taking raw ESC/POS on port 9100.
4. **"Send" fires only items not yet sent.** Voiding a sent item prints a VOID slip, needs a
   manager and is audited. Kitchen slips carry no prices.
5. **The bill is a non-fiscal pre-check.** Payment is the real sale and frees the table.
6. **Tables are a per-branch floor plan.**

## Decision

### D-1 — Scope and phasing

| Phase | Content | Backend |
|---|---|---|
| **1** | Price on the menu tiles (D-12) | None |
| **2 (this ADR)** | Floor and tables, server-side open orders, prep stations with category routing, fire and void slips, bill, payment closes the order, ERP setup screens | V107 + permission seed |
| **3 (later)** | Modifiers, split bill, move or merge table, waiter role and tablet layout, kitchen display screen, courses, recipe/BOM ingredient deduction | Additive on top of phase 2 |

Nothing in phase 2 precludes phase 3. Split bill, for example, becomes "pay a subset of lines",
which the line-level design already allows.

### D-2 — Module placement: `sales`

Restaurant orders live in the `sales` module, beside the POS tills, sessions and the POS sale
(ADR-0029).

- **Payment turns a check into a POS sale** (D-8). Keeping both in one module makes that a
  same-module service call inside one transaction, with no cross-module side effect and no outbox
  hop.
- **Cross-module references are scalar ids,** with no JPA `@ManyToOne` across modules (ADR-0029
  D-15). `products` is read through its DTOs and the existing `LinePriceResolver` path.
- **New code follows the module layout:**
  - Entities: `com.erp.modules.sales.domain.entity.{RestaurantTable, PrepStation, PrepStationCategory, RestaurantOrder, RestaurantOrderLine}`
  - Services: `RestaurantSetupService`, `RestaurantOrderService` (interface + `Impl`)
  - Controllers: `RestaurantSetupController`, `RestaurantOrderController`, flat under `com.erp.api`

A separate `hospitality` module was considered and rejected. It would have to call `sales` to post
the sale, turning the core action into a cross-module side effect for no gain.

### D-3 — Floor: `restaurant_tables`

Tables are set up **per branch**:

- Fields: `name` (free text such as "T1", "Terrace 3", "Bar stool 2"), `seats`, `area` (an optional
  grouping label such as "Inside" or "Terrace"), `sort_order` and `status`.
- **Names are unique per branch.** Deactivating a table hides it from the floor; it is never deleted
  (soft-delete masters, invariant 9).
- **A table's live status is derived, not stored:** *free*, *occupied* (it has an OPEN order) or
  *billed* (it has a BILLED order). This avoids a second source of truth that can drift.
- **One table may hold more than one open order** (separate parties at one table), the standard
  behaviour. The floor shows a count.

### D-4 — Prep stations and routing: `prep_stations` + `prep_station_categories`

A **prep station** is a place that prepares items and gets a slip, such as Kitchen or Bar/Barista.
Stations are set up per branch:

- `name`
- `printer_host` + `printer_port` (default 9100): a network ESC/POS printer
- `is_default`: the catch-all station; at most one per branch, enforced by a partial unique index
- `status`

**Routing is by product category.** `prep_station_categories` maps a category label (the existing
free-text `products.category`) to a station within a branch, for example `Drinks → Bar` and
`Coffee → Bar`. When an item is sent:

1. If the product's category is mapped in this branch, the item goes to that station.
2. Otherwise it goes to the branch's default station.
3. If the branch has no stations at all, the item is marked sent and **no slip prints.** This suits
   a counter-service café with no kitchen printer, where Send just locks the items.

The match is case- and whitespace-insensitive, and the stored label is normalised: trimmed, with
inner whitespace collapsed.

Why category rather than a new `products.prep_station_id` column:

- **No change to the `products` table and no cross-module FK.**
- **It is what Odoo and Square do** (category → printer).
- **The client can already fill it in bulk.** The product import has a "Department" column that
  writes `category` (`ProductImportHandler`).
- An item-level override can be added later (phase 3) without breaking this.

The station resolved for a line is **copied onto the line when it is sent** (`prep_station_id`).
Changing the mapping later never rewrites history, and a slip reprint goes to the station that
actually got it.

**Device override:** a till may map a station to a **local Windows printer name** (a USB printer on
that PC) in its Setup screen. This is stored on the device, like the receipt printer today. The
network address on the server is the default, and the device override wins.

### D-5 — The open order: `restaurant_orders` + `restaurant_order_lines`

**Header** (`restaurant_orders`):

- `order_number`: `ORD-#####` per company, allocated from the existing `code_sequence` with the new
  entity kind `RESTAURANT_ORDER`, created lazily on first use with no seed row.
- `order_type`: `DINE_IN` (table required) or `TAKEAWAY` (no table)
- `restaurant_table_id`, `guest_count`
- `customer_id`: optional; the till's walk-in customer is used at payment when null
- `opened_by`: the server/waiter
- `status` and its timestamps
- `billed_total_amount`, `bill_print_count`
- `sales_invoice_id` / `sales_invoice_uid`: set at payment
- `cancel_reason`, `notes`, `@Version`

**Lines** (`restaurant_order_lines`):

- `line_no`, `product_id`, `unit_id`, `quantity`
- `note` (≤ 200 chars, free text, for example "no sugar", "well done"; it prints on the slip)
- `status`, `fire_round`, `prep_station_id`, and the send and void stamps

**Prices are not stored on lines.** Pricing stays server-authoritative and is resolved at bill time
and again at payment (D-7, D-8).

#### Header state machine

```
OPEN ──bill──▶ BILLED ──pay──▶ PAID          (terminal)
  ▲              │
  └──add/void────┘   (changing a billed order reopens it; the bill must be reprinted)
OPEN/BILLED ──cancel──▶ CANCELLED           (terminal; see D-9)
```

#### Line rules

| Line status | What can happen |
|---|---|
| `PENDING` (added, not sent) | Change quantity, change note, or delete outright. Deleting is a hard delete: nothing was prepared. |
| `SENT` | **Immutable.** Only voiding is possible (D-9). A further "+1" of a sent item creates a new PENDING line. |
| `VOIDED` | Terminal. |

The order is not bound to a POS session. Open checks carry across a shift change, as in every
standard system. Payment binds to the **paying** till's open session (D-8).

**Closing a session while the branch has open orders is allowed but warned:** the close response
lists them. Refusing would strand a cashier at the end of a shift for a table someone else is
serving.

### D-6 — Send (fire)

`POST /api/v1/restaurant-orders/uid/{uid}/send`

The server does the following under a `SELECT … FOR UPDATE` lock on the header, so two devices
pressing Send cannot fire the same lines twice:

1. Takes every `PENDING` line.
2. Resolves each line's station (D-4).
3. Sets `status = SENT`, `fire_round = max(fire_round) + 1`, `sent_at` and `sent_by`.
4. Returns **one slip per station.** Each slip carries:
   - the station name and printer address
   - the order number, table, guest count, server name and time
   - the round number
   - the lines: name, quantity, note
   - **no prices**

**The till prints the slips** over raw TCP to `printer_host:printer_port`, or through the device
override. The server never prints:

- The ERP server may be in the cloud and unable to reach a LAN printer.
- The device that pressed Send is the one standing next to the printers.

**Print failures do not undo the send.** The food may already be cooking from the other slip. The
till shows "Kitchen slip failed" with a **Reprint** action:

`GET /api/v1/restaurant-orders/uid/{uid}/slips?round=N&station=…`

This rebuilds the slip from the stored lines and marks it **REPRINT**.

### D-7 — Bill

`POST /api/v1/restaurant-orders/uid/{uid}/bill`

1. Prices every non-voided line through **the same path the sale uses** (`LinePriceResolver` + the
   invoice tax computation), for the order's customer or the walk-in customer.
2. Stores the gross total in `billed_total_amount`.
3. Increments `bill_print_count` and sets status `BILLED`.
4. Returns the bill: lines with price, VAT summary and total.

The till prints it on the **receipt printer** (the cashier's), headed **"BILL — NOT A RECEIPT"**.
Printings after the first say **"REPRINT n"**, a standard anti-fraud marker.

**The bill is non-fiscal.** Per the owner's standing rule, a fiscal block appears only from real
fiscalisation, and only on the payment receipt.

Unsent (`PENDING`) lines may be billed: some cafés never fire drinks they pour at the till. This is
allowed, and the bill response tells the till which lines were never sent, so it can prompt
"Send first?".

### D-8 — Pay: the check becomes a POS sale, atomically

`POST /api/v1/restaurant-orders/uid/{uid}/pay`

The request carries `sessionUid`, `tenders[]`, `tenderedAmount`, `expectedTotal` and an
`Idempotency-Key` header. In **one transaction** the server:

1. Locks the order header. The order must be `OPEN` or `BILLED`, with at least one non-voided line.
2. Builds a `PosSaleRequest` from the non-voided lines, with notes like `Table T4 · ORD-00012`, and
   calls the existing `PosSaleService.processSale` with the same idempotency key. **Every existing
   POS guard applies unchanged:** session open, tender cover, age restriction, below-cost policy,
   negative-stock policy and the stale-replay guard.
3. If the posted gross ≠ `expectedTotal` (the total the customer was shown), it **rolls back** and
   refuses with `409 PRICE_CHANGED`. The till reprints the bill. A customer is never charged a
   figure they were not shown.
4. Sets `status = PAID`, `paid_at`, `sales_invoice_id` and `sales_invoice_uid`.

The receipt is the normal POS receipt. The table becomes free because its derived status (D-3) no
longer finds an open order.

**Stock and GL are touched only here,** through the existing sale. Nothing posts on send or bill.

**Replays:**

- A retry with the same key returns the same invoice (ADR-0042 D-1) and finds the order already PAID
  against it. That is a success, not an error.
- A PAID order paid again with a *different* key → `409 ALREADY_PAID`.

### D-9 — Void and cancel

- **Voiding a SENT line** (`POST …/lines/uid/{lineUid}/void`, with a `reason`):
  - The caller needs `RESTAURANT.ORDER.VOID`, **or** a manager step-up `authoriserUid` from the
    existing `POST /auth/verify-authority` flow (ADR-0042), checked against that same code.
  - The line becomes `VOIDED` with `voided_by`, `void_authorised_by` and `void_reason` recorded.
  - The response carries a **VOID slip** for the line's station, so the kitchen stops cooking it.
- **Cancelling an order:**
  - With no SENT lines it is free (nothing was prepared).
  - With SENT lines, cancelling voids them all under the same rule and needs a reason.
- **Both are audited by the aspect** (invariant 7).

### D-10 — Endpoints and permissions

| Endpoint | Permission |
|---|---|
| `GET/POST/PATCH /restaurant-tables…`, `/prep-stations…`, `/prep-station-categories…` (writes) | `RESTAURANT.SETUP.MANAGE` |
| `GET` of the same (floor, stations, mapping; needed by every till) | `RESTAURANT.ORDER.MANAGE` **or** `RESTAURANT.SETUP.MANAGE` |
| `GET /restaurant-orders?status=&tableUid=` · `GET …/uid/{uid}` | `RESTAURANT.ORDER.MANAGE` |
| `POST /restaurant-orders` · lines add/patch/delete · `/send` · `/slips` · `/bill` | `RESTAURANT.ORDER.MANAGE` |
| `POST …/lines/uid/{lineUid}/void` · `POST …/cancel` (when SENT lines exist) | `RESTAURANT.ORDER.VOID` (or step-up) |
| `POST …/pay` | `RESTAURANT.ORDER.MANAGE` **and** `POS.SALE.CREATE` |

**Grants:**

| Role | Codes |
|---|---|
| CASHIER | `ORDER.MANAGE` |
| BRANCH_MANAGER, SALES_MANAGER | `ORDER.MANAGE`, `ORDER.VOID` |
| BRANCH_MANAGER (ORG_ADMIN already holds every code) | `SETUP.MANAGE` |

The exact rows are in Appendix B.

The other guards follow the house rules:

- The Angular route guards equal the endpoint codes plus a `ScopeGuard` key (route-guard parity
  rule).
- **Tenancy is checked from the loaded entity** (company + branch), never from a caller-supplied id.
- `EndpointAuthorizationTest` and `ModuleBoundaryTest` must pass.
- RBAC tests run as a **non-root** cashier.

### D-11 — Clients

**OrbixPOS restaurant register** (rewrite of the stub):

- **Floor view.** Server tables grouped by area, coloured free / occupied / billed, plus a
  "Takeaway" button.
- **Order screen.** The menu grid on the left; on the right, the check with lines grouped as
  *Sent (round n)* and *New*.
- **Buttons:** **Send**, **Bill**, **Pay** and **Void** (with step-up).
- **Printing:**
  - A raw-TCP ESC/POS sender using `dart:io` `Socket`, alongside the existing spooler printer.
  - Slips and the bill reuse the receipt text encoder.
- **Setup screen:** per-station printer test plus the device override (D-4).
- **The till re-reads the order from the server** after every action. The server is the only copy,
  and nothing about an open check is kept on the device.

**ERP web, under Sales → Restaurant:**

- **Tables:** list and form.
- **Prep stations:** list and form, with a "Print test" action that is instructional only. The
  server can't reach the printer; a real test print is done from a till.
- **Category routing:** a category → station grid built from the distinct product categories in
  use.
- **Open orders:** a read-only monitor with a cancel action for managers.

### D-12 — Phase 1: price on the menu tiles (no backend)

The menu grid shows the **VAT-inclusive unit price** under each item name, in place of the code:

- Prices for the visible page are fetched with **one** batch `POST /product-prices/resolve`, the
  same call the cart already uses.
- The VAT-inclusive conversion is the cart's existing one.
- An item with no price shows "No price" rather than 0.
- The code stays searchable but is no longer displayed.

## Consequences

- **Five new tables in `sales`,** additive (V107), plus three permission codes in the repeatable
  seed. No existing table or column changes.
- **Open checks survive a till restart** and are visible from any device, which is the precondition
  for waiter tablets in phase 3.
- **Stock and GL behave exactly as for any POS sale:** one invoice per paid order, at payment.
  Restaurant sales therefore appear in every existing sales, VAT, cash-up and Z report with no
  report changes.
- **Routing quality depends on categories being filled in.** An item with no category goes to the
  default station, so nothing is lost, but drinks could go to the kitchen. Onboarding a restaurant
  means a category import pass and a category → station mapping.
- **The bill is computed on the server,** so the till never shows a total the server will not
  charge. A price change between bill and payment is caught (`PRICE_CHANGED`) rather than silently
  charged.
- **Hardware assumption:** station printers are network ESC/POS printers. A USB printer on another
  PC is not reachable; it needs a network printer, or a till on that PC using the device override.
  This must be confirmed per client before go-live.

## Alternatives considered

- **Keep open orders on the till** (like parked sales): no migration. Rejected because a till crash
  or restart loses every open table mid-service, and it rules out a second device. It is also not
  how any standard system works.
- **Reuse `sales_orders` as the check:** rejected. Sales orders carry approval, reservation and
  delivery semantics that a restaurant check must not trigger, and an open check would pollute the
  order-to-cash reports.
- **The server prints station slips:** rejected because a cloud-hosted ERP cannot reach a LAN
  printer. A local print agent is a possible phase-3 option for kitchen display screens.
- **A `products.prep_station_id` column:** rejected for now (D-4). It changes `products`, adds a
  cross-module FK, and the client cannot bulk-fill it today.

## Questions to confirm with the client before go-live

1. **Are the kitchen and barista printers network printers** (Ethernet or Wi-Fi with an IP), or
   USB? (D-4 / Consequences)
2. **Who takes orders?** Only the cashier at the till, or waiters on other devices too? Phase 2
   supports both, but waiter devices need tills or tablets provisioned.
3. **Their table list** (names, seats, areas) and **which categories are drinks.**

---

## Appendix A — Proposed DDL: `V107__restaurant_orders.sql` (NOT YET APPROVED)

Style mirrors `V37__pos.sql`: plural tables, singular constraint roots, `ix_<plural table>_…`
indexes. Every table is new and empty, so there is no populated-table risk. No ALTER on any
existing table.

```sql
-- V107 — Restaurant orders (ADR-0065): floor tables, prep stations + category routing,
-- open orders (checks) and their lines. Additive only: five new tables, no ALTER.

-- (1) restaurant_tables — the per-branch floor
CREATE TABLE restaurant_tables (
    id            BIGSERIAL PRIMARY KEY,
    uid           VARCHAR(26)   NOT NULL,
    company_id    BIGINT        NOT NULL,
    branch_id     BIGINT        NOT NULL,
    name          VARCHAR(40)   NOT NULL,
    seats         SMALLINT      NOT NULL DEFAULT 4,
    area          VARCHAR(40),
    sort_order    INTEGER       NOT NULL DEFAULT 0,
    status        VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_at    TIMESTAMPTZ,
    updated_by    BIGINT,
    CONSTRAINT uq_restaurant_table_uid         UNIQUE (uid),
    CONSTRAINT uq_restaurant_table_branch_name UNIQUE (branch_id, name),
    CONSTRAINT fk_restaurant_table_company     FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_restaurant_table_branch      FOREIGN KEY (branch_id)  REFERENCES branches(id),
    CONSTRAINT chk_restaurant_table_seats      CHECK (seats > 0),
    CONSTRAINT chk_restaurant_table_status     CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_restaurant_tables_company_id ON restaurant_tables (company_id);
CREATE INDEX ix_restaurant_tables_branch_id  ON restaurant_tables (branch_id);

-- (2) prep_stations — kitchen / bar; where slips print
CREATE TABLE prep_stations (
    id            BIGSERIAL PRIMARY KEY,
    uid           VARCHAR(26)   NOT NULL,
    company_id    BIGINT        NOT NULL,
    branch_id     BIGINT        NOT NULL,
    name          VARCHAR(60)   NOT NULL,
    printer_host  VARCHAR(255),
    printer_port  INTEGER       NOT NULL DEFAULT 9100,
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    status        VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by    BIGINT,
    updated_at    TIMESTAMPTZ,
    updated_by    BIGINT,
    CONSTRAINT uq_prep_station_uid         UNIQUE (uid),
    CONSTRAINT uq_prep_station_branch_name UNIQUE (branch_id, name),
    CONSTRAINT fk_prep_station_company     FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_prep_station_branch      FOREIGN KEY (branch_id)  REFERENCES branches(id),
    CONSTRAINT chk_prep_station_port       CHECK (printer_port BETWEEN 1 AND 65535),
    CONSTRAINT chk_prep_station_status     CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
-- At most one ACTIVE default (catch-all) station per branch.
CREATE UNIQUE INDEX ux_prep_station_one_default
    ON prep_stations (branch_id) WHERE is_default AND status = 'ACTIVE';
CREATE INDEX ix_prep_stations_company_id ON prep_stations (company_id);
CREATE INDEX ix_prep_stations_branch_id  ON prep_stations (branch_id);

-- (3) prep_station_categories — product category label -> station, per branch
CREATE TABLE prep_station_categories (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26)   NOT NULL,
    company_id       BIGINT        NOT NULL,
    branch_id        BIGINT        NOT NULL,
    prep_station_id  BIGINT        NOT NULL,
    category         VARCHAR(60)   NOT NULL,  -- normalised: trimmed, inner spaces collapsed
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    updated_by       BIGINT,
    CONSTRAINT uq_prep_station_category_uid     UNIQUE (uid),
    CONSTRAINT fk_prep_station_category_company FOREIGN KEY (company_id)      REFERENCES companies(id),
    CONSTRAINT fk_prep_station_category_branch  FOREIGN KEY (branch_id)       REFERENCES branches(id),
    CONSTRAINT fk_prep_station_category_station FOREIGN KEY (prep_station_id) REFERENCES prep_stations(id)
);
-- A category routes to exactly one station within a branch (case-insensitive).
CREATE UNIQUE INDEX ux_prep_station_category_branch_category
    ON prep_station_categories (branch_id, lower(category));
CREATE INDEX ix_prep_station_categories_station_id ON prep_station_categories (prep_station_id);

-- (4) restaurant_orders — the open check
CREATE TABLE restaurant_orders (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)    NOT NULL,
    company_id           BIGINT         NOT NULL,
    branch_id            BIGINT         NOT NULL,
    order_number         VARCHAR(30)    NOT NULL,
    order_type           VARCHAR(20)    NOT NULL DEFAULT 'DINE_IN',
    restaurant_table_id  BIGINT,
    guest_count          SMALLINT,
    customer_id          BIGINT,
    status               VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    opened_by            BIGINT         NOT NULL,
    opened_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    billed_at            TIMESTAMPTZ,
    billed_total_amount  NUMERIC(19,4),
    bill_print_count     INTEGER        NOT NULL DEFAULT 0,
    paid_at              TIMESTAMPTZ,
    sales_invoice_id     BIGINT,
    sales_invoice_uid    VARCHAR(26),
    cancelled_at         TIMESTAMPTZ,
    cancelled_by         BIGINT,
    cancel_reason        VARCHAR(255),
    notes                VARCHAR(500),
    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_restaurant_order_uid            UNIQUE (uid),
    CONSTRAINT uq_restaurant_order_company_number UNIQUE (company_id, order_number),
    CONSTRAINT fk_restaurant_order_company        FOREIGN KEY (company_id)          REFERENCES companies(id),
    CONSTRAINT fk_restaurant_order_branch         FOREIGN KEY (branch_id)           REFERENCES branches(id),
    CONSTRAINT fk_restaurant_order_table          FOREIGN KEY (restaurant_table_id) REFERENCES restaurant_tables(id),
    CONSTRAINT fk_restaurant_order_customer       FOREIGN KEY (customer_id)         REFERENCES customers(id),
    CONSTRAINT fk_restaurant_order_opened_by      FOREIGN KEY (opened_by)           REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_cancelled_by   FOREIGN KEY (cancelled_by)        REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_invoice        FOREIGN KEY (sales_invoice_id)    REFERENCES sales_invoices(id),
    CONSTRAINT chk_restaurant_order_type          CHECK (order_type IN ('DINE_IN','TAKEAWAY')),
    CONSTRAINT chk_restaurant_order_status        CHECK (status IN ('OPEN','BILLED','PAID','CANCELLED')),
    CONSTRAINT chk_restaurant_order_table_needed  CHECK (order_type <> 'DINE_IN' OR restaurant_table_id IS NOT NULL),
    CONSTRAINT chk_restaurant_order_guests        CHECK (guest_count IS NULL OR guest_count > 0),
    CONSTRAINT chk_restaurant_order_paid          CHECK (status <> 'PAID' OR (sales_invoice_id IS NOT NULL AND paid_at IS NOT NULL)),
    CONSTRAINT chk_restaurant_order_cancelled     CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);
-- The floor and the open-orders monitor read only live checks.
CREATE INDEX ix_restaurant_orders_branch_live ON restaurant_orders (branch_id, status)
    WHERE status IN ('OPEN','BILLED');
CREATE INDEX ix_restaurant_orders_company_id  ON restaurant_orders (company_id);
CREATE INDEX ix_restaurant_orders_table_id    ON restaurant_orders (restaurant_table_id);
CREATE INDEX ix_restaurant_orders_invoice_id  ON restaurant_orders (sales_invoice_id);

-- (5) restaurant_order_lines
CREATE TABLE restaurant_order_lines (
    id                   BIGSERIAL PRIMARY KEY,
    uid                  VARCHAR(26)    NOT NULL,
    restaurant_order_id  BIGINT         NOT NULL,
    company_id           BIGINT         NOT NULL,
    branch_id            BIGINT         NOT NULL,
    line_no              INTEGER        NOT NULL,
    product_id           BIGINT         NOT NULL,
    unit_id              BIGINT         NOT NULL,
    quantity             NUMERIC(19,4)  NOT NULL,
    note                 VARCHAR(200),
    status               VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    fire_round           SMALLINT,
    prep_station_id      BIGINT,
    sent_at              TIMESTAMPTZ,
    sent_by              BIGINT,
    voided_at            TIMESTAMPTZ,
    voided_by            BIGINT,
    void_authorised_by   BIGINT,
    void_reason          VARCHAR(255),
    version              BIGINT         NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_at           TIMESTAMPTZ,
    updated_by           BIGINT,
    CONSTRAINT uq_restaurant_order_line_uid        UNIQUE (uid),
    CONSTRAINT uq_restaurant_order_line_no         UNIQUE (restaurant_order_id, line_no),
    CONSTRAINT fk_restaurant_order_line_order      FOREIGN KEY (restaurant_order_id) REFERENCES restaurant_orders(id),
    CONSTRAINT fk_restaurant_order_line_product    FOREIGN KEY (product_id)          REFERENCES products(id),
    CONSTRAINT fk_restaurant_order_line_unit       FOREIGN KEY (unit_id)             REFERENCES units_of_measure(id),
    CONSTRAINT fk_restaurant_order_line_station    FOREIGN KEY (prep_station_id)     REFERENCES prep_stations(id),
    CONSTRAINT fk_restaurant_order_line_sent_by    FOREIGN KEY (sent_by)             REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_line_voided_by  FOREIGN KEY (voided_by)           REFERENCES app_users(id),
    CONSTRAINT fk_restaurant_order_line_void_auth  FOREIGN KEY (void_authorised_by)  REFERENCES app_users(id),
    CONSTRAINT chk_restaurant_order_line_qty       CHECK (quantity > 0),
    CONSTRAINT chk_restaurant_order_line_status    CHECK (status IN ('PENDING','SENT','VOIDED')),
    -- Only a sent line can be voided (an unsent line is deleted), so SENT and VOIDED both carry the send stamp.
    CONSTRAINT chk_restaurant_order_line_sent      CHECK (status = 'PENDING' OR (fire_round IS NOT NULL AND sent_at IS NOT NULL)),
    CONSTRAINT chk_restaurant_order_line_voided    CHECK (status <> 'VOIDED' OR (voided_at IS NOT NULL AND void_reason IS NOT NULL))
);
CREATE INDEX ix_restaurant_order_lines_order_id ON restaurant_order_lines (restaurant_order_id);
CREATE INDEX ix_restaurant_order_lines_product  ON restaurant_order_lines (product_id);
```

## Appendix B — Proposed permission seed (edit to `R__seed_permissions.sql`, NOT YET APPROVED)

```sql
-- permissions
('RESTAURANT.ORDER.MANAGE', 'sales', 'Open restaurant orders, add items, send to prep stations, print the bill and take payment'),
('RESTAURANT.ORDER.VOID',   'sales', 'Void an item already sent to a prep station, or cancel an order with sent items'),
('RESTAURANT.SETUP.MANAGE', 'sales', 'Manage restaurant tables, prep stations and category routing'),

-- grants (same role-code pattern as the POS.* grants)
('CASHIER',        'RESTAURANT.ORDER.MANAGE'),
('BRANCH_MANAGER', 'RESTAURANT.ORDER.MANAGE'),
('BRANCH_MANAGER', 'RESTAURANT.ORDER.VOID'),
('BRANCH_MANAGER', 'RESTAURANT.SETUP.MANAGE'),
('SALES_MANAGER',  'RESTAURANT.ORDER.MANAGE'),
('SALES_MANAGER',  'RESTAURANT.ORDER.VOID'),
-- ORG_ADMIN needs no rows: the seed's CROSS JOIN grants it every permission in the catalogue.
```

**Rollout gate (from the 2026-08-15 outage):** before V107 ships, boot the release against a
restored copy of a customer database, not only a fresh one.
