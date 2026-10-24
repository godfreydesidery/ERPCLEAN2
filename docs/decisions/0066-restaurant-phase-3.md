# 0066 — Restaurant phase 3: modifiers, courses, kitchen display, split and merge, waiter and kitchen roles

- **Status:** Accepted (2026-10-24). The owner asked for "all the phases" of ADR-0065 and approved
  the DDL in Appendix A and the seed in Appendix B the same day. They ship as
  `V108__restaurant_phase3.sql` and an edit to `R__seed_permissions.sql`.
- **Builds on:** ADR-0065 (restaurant orders), ADR-0058 (point-of-sale kit recipes), ADR-0042
  (step-up authority, split tenders).

## Context

ADR-0065 D-1 listed phase 3:

- modifiers
- split bill
- move or merge table
- waiter role and tablet layout
- kitchen display screen
- courses
- recipe/BOM ingredient deduction

Moving an order to another table shipped with phase 2. This ADR covers the rest.

## Decision

### D-1 — Recipes: already supported, no change

A menu item with **product components** (Products → item → Components) is a point-of-sale kit
recipe (ADR-0010 D-8, ADR-0058):

- **At sale:** `SaleIssueStockHandler` deducts its ingredients instead of the item itself. For
  example, a Latte deducts 0.25 L milk and 18 g coffee.
- **Negative stock:** `NegativeStockGuard` deliberately does not block a sale on a recipe item, so a
  café is never stopped at the till because the milk count is behind.
- **Restaurant orders:** payment is an ordinary POS sale, so they inherit all of this.

The only change is a regression test that pays a restaurant order containing a recipe item and
proves the ingredients were issued.

### D-2 — Split bill (no schema)

The standard model has two different splits:

| Split | Meaning | How |
|---|---|---|
| **By item** ("I'll pay for my tacos") | Some items go on their own check | `POST /restaurant/orders/uid/{uid}/split` with `[{lineUid, quantity}]`. The chosen items move to a NEW order at the same table, and each check is billed and paid on its own. Part of a line ("1 of the 3 teas") splits the line. A sent item keeps its send stamps, so the kitchen is not asked again. |
| **Evenly** ("split it three ways") | One sale, several payments | The payment sheet already takes several tenders (ADR-0042 D-3). It gains **÷2 ÷3 ÷4** buttons that fill in the share of what is left to pay. |

Splitting by item reopens a billed order, because its bill no longer matches. Both orders must be
live and in the same branch. The new order is opened by the caller and takes the same table and
order type.

### D-3 — Merge orders (no schema)

`POST /restaurant/orders/uid/{target}/merge` with `{fromOrderUid}`:

1. Moves every line of the source order onto the target, keeping each line's state, round and
   stamps.
2. Cancels the source with the reason "Merged into ORD-…".
3. Reopens the target if it was billed.

Typical uses are two tables joined together, or a takeaway that sat down. Both orders must be live
and in the same branch. No slip prints, because nothing changes for the kitchen.

### D-4 — Modifiers

A **modifier group** is a choice offered on menu items, for example "Milk" (Full / Skimmed / Oat),
"Extras" (Extra shot, Syrup) or "Cooking" (Rare / Medium / Well done):

- **Group rules:** `min_select` / `max_select` are the rules of the choice. Milk is exactly one;
  Extras is any number.
- **Options:** each option has a label, and optionally a **product**.
  - An option **with a product is a priced add-on**. It becomes a **child line** on the order (the
    new `parent_line_id`) for that product, with the parent's quantity. It is priced, taxed and
    stock-deducted like any line, so "Syrup extra" charges its own price and can itself be a recipe.
    J COB already sells "Syrup extra", "Extra souce" and "Takeaway container" as products; they
    become options.
  - An option **without a product is an instruction**, such as "Oat milk" or "No onions". It is
    added to the item's note and costs nothing.
- **Which items get which groups:** `product_modifier_group` attaches groups to menu items, for the
  whole company.
- **Child lines follow their parent.** They are sent, voided, split, merged and change quantity
  with it, and they print indented under it on slips and the bill. They cannot be added, changed or
  removed on their own.
- **Validation:** the server checks the group rules (minimum and maximum chosen) when the item is
  added.

**Endpoints:**

- `GET/POST/PUT /restaurant/modifier-groups…` (with their options) and
  `PUT /restaurant/products/uid/{uid}/modifier-groups`: `RESTAURANT.SETUP.MANAGE`; reads also
  `RESTAURANT.ORDER.MANAGE`.
- `GET /restaurant/modifier-groups/for-product/{productUid}`: the till asks this when an item is
  tapped.
- `AddRestaurantOrderLineRequest` gains `modifierOptionUids[]`.

### D-5 — Courses, hold and fire

Each line gets a `course` (1 = starters / drinks, 2 = mains, 3 = dessert; default 1) and a `held`
flag:

- **Send** fires pending lines that are **not held**. Slips group items under "COURSE 2" headings
  when there is more than one course.
- `POST /restaurant/orders/uid/{uid}/fire` with `{course}` releases the held lines of that course
  (or all held lines) and sends them in one round. This is the waiter's "mains now".
- The till lets the waiter set the course while adding items (1 / 2 / 3), and hold or release a
  pending line.
- Billing and payment ignore `held`: an item is charged whether or not it was fired. The
  send-first prompt counts only items Send would actually send, so held items never trigger it.

### D-6 — Kitchen display (KDS)

A screen per station that replaces, or sits beside, the paper slip:

- `GET /restaurant/kitchen?stationUid=` returns the station's open tickets: SENT, not voided, not
  yet ready, grouped by order and round. Each ticket shows the table, the waiter, the age in
  minutes, the items with notes and children, and the course.
- `POST /restaurant/kitchen/ready` with `{lineUids}` "bumps" items: it sets `ready_at` /
  `ready_by`.
- `POST /restaurant/kitchen/recall` with `{lineUids}` undoes a bump made by mistake.
- **The display is a page in the ERP web app** (`/admin/restaurant/kitchen`), so any screen or TV
  with a browser in the kitchen works. It refreshes every 10 seconds. Items voided after sending
  show struck through until bumped away.
- **The till shows "ready"** on the item, and the floor marks the table "Food ready" when anything
  on it is ready and not yet paid.
- **Permission:** `RESTAURANT.KITCHEN.MANAGE`.

### D-7 — Roles

Two new shipped role bundles:

| Role | Holds | Does NOT hold |
|---|---|---|
| **WAITER** | `RESTAURANT.ORDER.MANAGE`, plus the reads the till needs to show the menu and prices | `POS.SALE.CREATE`, `POS.SESSION.OPEN`, any void authority: a waiter takes orders, sends and prints bills, and cannot take money or void a sent item |
| **KITCHEN** | `RESTAURANT.KITCHEN.MANAGE`, `BRANCH.VIEW` and the notification baseline | anything that sells, prices or voids |

`RESTAURANT.KITCHEN.MANAGE` is also granted to BRANCH_MANAGER.

### D-8 — Waiter mode and tablet layout on the till (no schema)

- **Waiter mode.** A signed-in user who may take restaurant orders but may not open a till session
  goes straight to the restaurant floor, with no shift screen. Send and Bill work. PAY is replaced
  by "Payment is taken at the cashier". The bill prints on the device's receipt printer if it has
  one, otherwise it shows on screen.
- **Tablet layout.** Below 900 px wide, the order screen becomes two tabs, **Menu** and
  **Order (n)**, instead of side by side. The floor grid already reflows.
- An Android tablet build of OrbixPOS is the waiter device. Network printing works from Android, so
  a waiter's Send prints at the kitchen directly.

### D-9 — Every set-up works without asking the client (no schema)

The owner's ruling (2026-10-24): don't ask the client how their kitchen is wired; make every common
set-up work and let them configure it themselves.

| Set-up | How it works |
|---|---|
| **Kitchen/bar printer on the network** | Its IP and port go on the station in the ERP. Every till prints to it directly (ADR-0065 D-6). |
| **Printer plugged into a PC by USB** | Leave the station's IP empty. On that PC, OrbixPOS → Settings: choose the printer for the station and tick *Print ALL slips here*. That device polls `GET /restaurant/kitchen/slips?stationUid=&since=` every 5 s and prints what any till sent or voided. Each slip carries its `lineUids`, so a 60-second overlap on every ask never prints twice, and a failed print is retried. A KITCHEN-role login on that PC gets a dedicated *Kitchen printer* screen. |
| **USB printer on the sending till itself** | Choose it for the station in that till's Settings (device override, ADR-0065 D-4). |
| **No printer** | Leave the IP empty and use the Kitchen Display. A till sending to a station with no address and no local printer hands the slip off: this is not an error. |
| **58 or 80 mm paper** | Paper width is set per station on each device. |
| **Waiters on their own devices** | A user with the WAITER role (D-7) signs in on a tablet or phone and goes straight to the floor (D-8). |
| **Waiters sharing the cashier's till** | *Who's serving?* on the floor (or always, with *Shared till: ask who is serving* in Settings). The waiter types their own username and password, verified by `/auth/verify-authority` for `RESTAURANT.ORDER.MANAGE`. The till stays signed in as the cashier. The waiter's uid travels on open/send/fire as `waiterUid`, and the server re-verifies it every time (`verifyAuthoriserUid`) before recording it as the order's server and naming it on slips and the bill. The waiter is signed out after 3 minutes idle. |
| **Items without a category** | They print at the default station. Restaurant Setup lists them, and anyone with `PRODUCT.MANAGE` can give each one a category (`PUT /products/uid/{uid}/category`). |
| **Tables** | Added one by one or as a run ("T1 to T20"). Takeaway works with no tables at all. |

`GET /restaurant/prep-stations` also admits `RESTAURANT.KITCHEN.MANAGE`, so a KITCHEN login can choose
its station: on the Kitchen Display it previously got a 403.

## Consequences

- **One additive migration (V108).** It adds three new modifier tables and five nullable/defaulted
  columns on `restaurant_order_lines`, which is a table that only exists since V107. Plus three seed
  changes: one new permission code and two new role bundles.
- **Add-ons are real products.** They price, tax, report and deduct stock with no special case
  anywhere downstream.
- **The kitchen display is web-only.** A native display app is not planned.
- **Waiter tablets need network printers** to print at the kitchen (ADR-0065 consequence,
  unchanged).

## Alternatives considered

- **Modifiers as free text only:** rejected. A priced add-on then has no price, no VAT and no stock
  movement.
- **Modifiers as a JSON blob on the line:** rejected. It is invisible to pricing, the sale and
  reports, and every downstream module would need to learn it.
- **A separate "split check" table:** rejected. Moving lines to a new order reuses everything
  orders already do: bill, pay, void, slips.
- **A native kitchen display app:** rejected for now. The web page needs no install and works on a
  TV.

---

## Appendix A — DDL: `V108__restaurant_phase3.sql` (approved 2026-10-24)

```sql
-- V108 — Restaurant phase 3 (ADR-0066): modifier groups/options, the item → group link, and on
-- restaurant_order_lines: child lines for priced add-ons, course + hold, and the kitchen "ready" stamp.
-- Additive only. restaurant_order_lines was created by V107; every column added here is nullable or
-- carries a constant default (metadata-only in PostgreSQL 11+, no table rewrite).

SET LOCAL lock_timeout = '10s';  -- fail fast instead of queueing behind an open write (added 2026-10-24, owner)

-- (1) modifier_groups — a choice offered on menu items
CREATE TABLE modifier_groups (
    id           BIGSERIAL PRIMARY KEY,
    uid          VARCHAR(26)  NOT NULL,
    company_id   BIGINT       NOT NULL,
    name         VARCHAR(60)  NOT NULL,
    min_select   SMALLINT     NOT NULL DEFAULT 0,
    max_select   SMALLINT,                       -- null = no upper limit
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   BIGINT,
    updated_at   TIMESTAMPTZ,
    updated_by   BIGINT,
    CONSTRAINT uq_modifier_group_uid          UNIQUE (uid),
    CONSTRAINT uq_modifier_group_company_name UNIQUE (company_id, name),
    CONSTRAINT fk_modifier_group_company      FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT chk_modifier_group_min         CHECK (min_select >= 0),
    CONSTRAINT chk_modifier_group_max         CHECK (max_select IS NULL OR max_select >= GREATEST(min_select, 1)),
    CONSTRAINT chk_modifier_group_status      CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_modifier_groups_company_id ON modifier_groups (company_id);

-- (2) modifier_options — one choice; with a product it is a priced add-on (a child line)
CREATE TABLE modifier_options (
    id                 BIGSERIAL PRIMARY KEY,
    uid                VARCHAR(26)  NOT NULL,
    company_id         BIGINT       NOT NULL,
    modifier_group_id  BIGINT       NOT NULL,
    label              VARCHAR(60)  NOT NULL,
    product_id         BIGINT,                   -- null = an instruction only, added to the note
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         BIGINT,
    updated_at         TIMESTAMPTZ,
    updated_by         BIGINT,
    CONSTRAINT uq_modifier_option_uid         UNIQUE (uid),
    CONSTRAINT uq_modifier_option_group_label UNIQUE (modifier_group_id, label),
    CONSTRAINT fk_modifier_option_company     FOREIGN KEY (company_id)        REFERENCES companies(id),
    CONSTRAINT fk_modifier_option_group       FOREIGN KEY (modifier_group_id) REFERENCES modifier_groups(id),
    CONSTRAINT fk_modifier_option_product     FOREIGN KEY (product_id)        REFERENCES products(id),
    CONSTRAINT chk_modifier_option_status     CHECK (status IN ('ACTIVE','INACTIVE','ARCHIVED'))
);
CREATE INDEX ix_modifier_options_group_id ON modifier_options (modifier_group_id);

-- (3) product_modifier_group — which groups a menu item offers (junction, singular)
CREATE TABLE product_modifier_group (
    id                 BIGSERIAL PRIMARY KEY,
    company_id         BIGINT       NOT NULL,
    product_id         BIGINT       NOT NULL,
    modifier_group_id  BIGINT       NOT NULL,
    sort_order         INTEGER      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by         BIGINT,
    CONSTRAINT uq_product_modifier_group          UNIQUE (product_id, modifier_group_id),
    CONSTRAINT fk_product_modifier_group_company  FOREIGN KEY (company_id)        REFERENCES companies(id),
    CONSTRAINT fk_product_modifier_group_product  FOREIGN KEY (product_id)        REFERENCES products(id),
    CONSTRAINT fk_product_modifier_group_group    FOREIGN KEY (modifier_group_id) REFERENCES modifier_groups(id)
);
CREATE INDEX ix_product_modifier_group_group ON product_modifier_group (modifier_group_id);

-- (4) restaurant_order_lines — child lines, course + hold, kitchen ready stamp
ALTER TABLE restaurant_order_lines ADD COLUMN parent_line_id BIGINT;
ALTER TABLE restaurant_order_lines ADD COLUMN course         SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE restaurant_order_lines ADD COLUMN held           BOOLEAN  NOT NULL DEFAULT FALSE;
ALTER TABLE restaurant_order_lines ADD COLUMN ready_at       TIMESTAMPTZ;
ALTER TABLE restaurant_order_lines ADD COLUMN ready_by       BIGINT;

ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT fk_restaurant_order_line_parent   FOREIGN KEY (parent_line_id) REFERENCES restaurant_order_lines(id);
ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT fk_restaurant_order_line_ready_by FOREIGN KEY (ready_by)       REFERENCES app_users(id);
ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT chk_restaurant_order_line_course  CHECK (course BETWEEN 1 AND 9);
-- Only something already sent can be ready.
ALTER TABLE restaurant_order_lines
    ADD CONSTRAINT chk_restaurant_order_line_ready   CHECK (ready_at IS NULL OR status <> 'PENDING');

-- restaurant_order_lines exists only since V107 and is tiny at this point, so a plain index is safe here.
CREATE INDEX ix_restaurant_order_lines_parent ON restaurant_order_lines (parent_line_id);
```

## Appendix B — Seed (edit to `R__seed_permissions.sql`, approved 2026-10-24)

```sql
-- permission
('RESTAURANT.KITCHEN.MANAGE', 'sales', 'View the kitchen display and mark items ready'),

-- roles (global, is_system; fixed synthetic uids continuing the sequence)
('0000000000000000000000000D', 'WAITER',  'Waiter',  'Takes restaurant orders, sends them to the kitchen and bar, prints bills. Takes no payment.', true),
('0000000000000000000000000E', 'KITCHEN', 'Kitchen', 'Kitchen and bar display: sees what to make and marks it ready.', true),

-- grants
('WAITER','RESTAURANT.ORDER.MANAGE'),
('WAITER','PRODUCT.VIEW'),
('WAITER','UOM.VIEW'),
('WAITER','PRICELIST.VIEW'),
('WAITER','TAXRATE.VIEW'),
('WAITER','CUSTOMER.VIEW'),
('WAITER','CURRENCY.VIEW'),
('WAITER','BRANCH.VIEW'),
('WAITER','NOTIFICATION.VIEW'),
('WAITER','NOTIFICATION.PREFERENCE.MANAGE'),
('KITCHEN','RESTAURANT.KITCHEN.MANAGE'),
('KITCHEN','BRANCH.VIEW'),
('KITCHEN','NOTIFICATION.VIEW'),
('KITCHEN','NOTIFICATION.PREFERENCE.MANAGE'),
('BRANCH_MANAGER','RESTAURANT.KITCHEN.MANAGE'),
```

`DefaultRoleBundlesSeededTest` gains the two roles, with anchors WAITER → `RESTAURANT.ORDER.MANAGE`
and KITCHEN → `RESTAURANT.KITCHEN.MANAGE`.

**Implementation notes (no change to what was approved):**

- **The WAITER and KITCHEN grants are a statement of their own,** joined to the GLOBAL roles only
  (`r.organisation_id IS NULL`). These role codes are new, so a customer may already have a role of
  their own called WAITER or KITCHEN. The existing grant statement joins by code alone, and would
  have silently handed that customer role these permissions.
- **`TenancyReconciler.SEED_ROLE_UIDS` lists the two new uids.** Without them every boot logs a
  false "the seed has adopted a customer role" warning for the shipped bundles.
