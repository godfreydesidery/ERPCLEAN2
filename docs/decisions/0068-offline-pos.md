# 0068 — Offline POS: sell while the server is unreachable, sync later

- **Status:** Proposed (2026-11-07). The business rules below are the owner's rulings from the
  2026-11-07 discussion. The DDL in Appendix A is a **proposal** and is **not approved** — it goes to
  the owner before any migration is written.
- **Builds on:** ADR-0029 (POS tills, sessions, quick sale — which deferred offline POS), ADR-0042
  (POS idempotency, tenders, step-up authority), ADR-0065/0067 (restaurant orders, table ownership).
- **Requested by:** Cafe Tulia (tulia Ltd), 2026-11-07. Their server is hosted in the cloud, so an
  internet outage stops every till. They declined the alternatives (see *Alternatives considered*).

## Context

The POS is online-only by design. Everything that makes a sale correct runs on the server at the
moment of sale: price resolution and VAT, the negative-stock block, discount policy and manager
step-up, the till-shift age limit (V113), invoice numbering. The till keeps only:

- the one in-flight sale and its Idempotency-Key (`pending_sale_store.dart`), so a crash never
  charges twice (ADR-0042 D-1);
- parked baskets (no prices — re-priced on recall);
- a receipt journal for reprints.

The catalogue, prices and stock are fetched live on purpose (`catalog_cache.dart`,
`price_cache.dart`). The till already ships a local store (`hive_ce`).

Facts from the code that shape this design (verified 2026-11-07):

- An invoice's business date is `sales_invoices.finalised_at`; the GL posts on it
  (`SalesPostingHandler`: `postingDate = calendar.dateOf(companyId, finalisedAt)`). It is set to
  `now()` at finalise.
- The invoice number is assigned at finalise (`codeGen.next(companyId)`).
- `PosSaleServiceImpl.processSale` refuses, on purpose: a basket captured more than
  `erp.pos.sale.max-age` (12 h) ago, a session open longer than its limit (V113), and — via
  `NegativeStockGuard` — a sale that would take stock negative where the company blocks it. A
  late-arriving offline sale would hit all three.
- A posting into a closed fiscal period fails into the existing GL posting-exception flow
  (`GlPostingExceptionService`), not into the sale.
- The till can already print kitchen/bar slips itself (`restaurant_printing.dart`).

## Decision

### D-1 — Two switches

1. **Admin, per till:** `pos_tills.offline_allowed` (default **false**), set in the web under
   POS › Tills. A till that is not allowed never goes offline; companies that do not want offline
   selling see no change.
2. **On the POS, an Online/Offline switch:**
   - **Server truly unreachable** (the till's own reachability check fails): the till offers
     "No connection — switch to offline?" and **any signed-in cashier** may accept. A morning
     outage with no manager present does not stop sales.
   - **Server reachable but slow / manual switch:** needs a **manager or supervisor** holding the new
     permission `POS.OFFLINE.SWITCH`, confirmed by their **password** (not the 4-digit PIN — see D-7).
   - **Coming back:** when the server answers again, the till says so, finishes the current basket
     offline, then returns to online and starts syncing — at the latest after a **grace period of
     5 minutes**. A till cannot stay offline on a working connection.
   - Every switch is recorded (who, when, which till, reason `UNREACHABLE` / `MANUAL`) and listed on
     the review screen (D-8).

### D-2 — What the till keeps locally (refreshed while online)

Stored in `hive_ce`, refreshed on sign-in, on a timer while online, and on demand:

- products, units (with factors), barcodes, categories;
- **selling prices as resolved by the server** (`POST /product-prices/resolve`, in pages of 200) and
  the VAT stance of each price — the till never computes a price of its own;
- VAT rates, tender types, the walk-in customer;
- company rules it needs offline: tender list, the till-shift limit, discount policy (to know that
  discounts are blocked offline anyway);
- restaurant set-up (phase 2): tables, modifier groups, category → prep-station routing, printers.

The screen shows the age of the data ("prices as of 07:40"). Offline sales go to the **walk-in
customer only**, in every phase; named customers with their own price lists are not cached.

The queue of unsynced sales (D-3) and the sign-in verifiers (D-7) are **encrypted at rest**, with
the key in the device's secure key store; the product and price cache is not.

### D-3 — An offline sale

- Priced from the cached server prices. **The price the customer paid stands** at sync; any
  difference from the server's price at sync is flagged (D-8).
- **Tenders offline: cash, mobile money, card.** Mobile money and card **require a reference** (the
  transaction reference / terminal approval number). **Blocked offline:** credit sales,
  complimentary, discounts, reversals and refunds — anything that needs a manager's approval.
- Numbered **provisionally per till**: `OFF-<till code>-<sequence>`, e.g. `OFF-T2-000153`. The
  receipt is printed marked **OFFLINE** with that number.
- Written to a **durable on-device queue** before the receipt prints, each with its own
  Idempotency-Key (the `pending_sale_store` principle, extended from one slot to a queue). Clearing
  app data with unsynced sales is refused with a warning.
- **Limit:** a till may sell offline for at most **24 hours** from its first offline sale; after
  that it refuses new sales until it has reached the server and synced.

### D-4 — Shifts

- An offline sale belongs to the shift open on the till. If **no shift is open** when the till is
  offline, the till opens a **provisional offline shift** with the opening float and a client key;
  the server creates it as a real `pos_sessions` row at sync (idempotent on the client key), and the
  offline sales land inside it.
- **Closing a shift (Z-report) is only possible after its offline sales have synced** — the till
  blocks Close while its queue is not empty, so the Z-report is always complete.
- The V113 till-shift limit does not apply to sales arriving through the offline path; the 24-hour
  offline limit bounds them instead.

### D-5 — Sync: a separate server path for offline sales

New endpoint, e.g. `POST /api/v1/pos/offline-sales` (batch, each sale with its Idempotency-Key).
For each sale the server:

1. **Replays safely:** a key already seen returns the original invoice (as ADR-0042 D-1).
2. Creates the invoice from the **lines and prices as charged**, with `finalised_at` = **the time the
   sale was made on the till**, so reports, the Z-read and the GL see it on the right day and hour;
   `synced_at` records when it arrived. The real invoice number is assigned at sync; the offline
   number is kept on the invoice and printed on reprints.
3. **Does not apply** the online-only guards — basket age (12 h), shift age (V113) and the
   negative-stock block. The sale already happened and the customer has paid; refusing it would
   lose real revenue. Instead it records review flags (D-8):
   - `PRICE_DIFFERENCE` — charged price ≠ server price at sync;
   - `NEGATIVE_STOCK` — stock went below zero (also covers two tills selling the last item);
   - `CLOSED_PERIOD` — the sold date falls in a closed fiscal period (the GL posting goes to the
     existing posting-exception flow).
4. Still **refuses** what offline selling never permits (a credit sale, a complimentary or discount
   line, a till without `offline_allowed`, a cashier who is not a user of that branch) — such a
   sale is held on the review list for a manager, never silently posted or dropped.

The till shows a sync screen ("12 sales waiting", "all synced", and any held sales).

### D-6 — Restaurant offline (phase 2)

**One till runs the restaurant** while offline: it opens orders, prints kitchen/bar slips
**directly** to the network printers, bills and takes payment. Waiter tablets show "offline — use the
till". Several devices cannot share orders without the server; making tablets work offline would
need a server in the café (declined). Orders opened offline sync as restaurant orders with their
payment, so waiter and bill reports stay complete. The detailed design is written before phase 2.

### D-7 — Signing in offline

- Only users who have **signed in on that till while online before** can sign in offline.
- The till derives its **own** verifier on the device at each successful online sign-in (a slow
  key-derivation of the password or PIN with a per-device salt). The server's password hashes are
  **never** sent to a device.
- A user blocked or removed on the server drops off the till's list at the next sync.
- Users may sign in offline with the **PIN** or password they use online (owner ruling: the PIN's
  guessing weakness is accepted for sign-in, since offline selling allows no approvals).
- The manual Online→Offline switch (D-1) needs a manager's **password**: a 4-digit PIN verifier
  copied off a device falls to 10,000 guesses, and the switch relaxes the rules for the whole till.

### D-8 — Review list

A web screen (permission `POS.OFFLINE.REVIEW`) listing, per branch: offline periods (who switched,
when, how long, how many sales), and every flagged or held sale with its reason. A manager
acknowledges each item; acknowledgement is audited. Nothing on it blocks the books — flagged sales
are already posted.

## Consequences

- During an outage the owner sees nothing in OrbixHQ or the web until the tills sync.
- With several tills offline, each sells against its own picture of stock: overselling is possible
  and shows up as `NEGATIVE_STOCK` flags.
- Prices can be up to 24 hours old offline; the customer pays the till's price.
- Invoice numbers of offline sales are assigned at sync, so they are not in time order with online
  invoices; the offline number links the receipt to the invoice.
- The cache adds a full catalogue + price download per till; large catalogues need paging and an
  incremental refresh.
- New permissions (`POS.OFFLINE.SWITCH`, `POS.OFFLINE.REVIEW`) must be seeded and granted
  (`R__seed_permissions.sql`), and tested as a non-root role.
- Fiscal (TRA EFD) is not integrated today; when it is, offline sales need store-and-forward
  fiscalisation (ADR-0049 future item 3).

## Phasing

| Phase | Scope | Rough size |
|---|---|---|
| 1 | Quick sales offline: cache, switch, offline sale + queue, provisional offline shift, sync endpoint, review list, migration | 2–3 weeks |
| 2 | Restaurant on a single till offline | ~2 weeks |
| 3 | Offline sign-in hardening, manager-password switch, incremental cache refresh | ~1 week |

Each phase ships on its own and is rehearsed on a restored client copy before it reaches a client.

## Alternatives considered

- **Server on site (LAN package, `dist/lan`).** Recommended first: an internet outage then stops
  nothing, including the restaurant flow. Declined by the client.
- **Emergency cash-only offline sales.** Smaller, but no restaurant flow offline. Declined in favour
  of the fuller design.
- **Re-price at the server on sync.** Rejected: revenue would no longer match the cash taken.
- **Hold oversold sales until stock is fixed.** Rejected: the money is in the drawer; the books would
  lag behind reality.

## Resolved questions (owner, 2026-11-07)

1. **Offline sign-in with a PIN is allowed.** The 4-digit PIN verifier can be guessed from a copied
   device, but sign-in alone only lets someone sell — discounts, complimentary, voids and refunds are
   blocked offline (D-3) and every offline sale is reviewed (D-8). The manual Online→Offline switch
   still needs a manager's **password** (D-1).
2. **Walk-in customer only**, in every phase. Credit sales are blocked offline, so named customers
   and their price lists are not cached.
3. **Encrypt the queue and the sign-in verifiers** at rest, with the key in the device's secure key
   store (Android Keystore / Windows DPAPI). Product and price data stay unencrypted — not secret,
   and refreshed often.

## Appendix A — Proposed DDL (NOT approved)

Version number to be assigned at approval (next free after V113).

```sql
SET LOCAL lock_timeout = '10s';

-- D-1: which tills may go offline
ALTER TABLE pos_tills
    ADD COLUMN offline_allowed BOOLEAN NOT NULL DEFAULT false;

-- D-4: a shift first opened offline on the till, created idempotently at sync
ALTER TABLE pos_sessions
    ADD COLUMN offline_client_key VARCHAR(64) NULL,
    ADD COLUMN opened_offline     BOOLEAN     NOT NULL DEFAULT false;
-- unique per company where present (partial unique index, CONCURRENTLY in its own migration)

-- D-3/D-5: offline sales
ALTER TABLE sales_invoices
    ADD COLUMN offline_number VARCHAR(40)  NULL,
    ADD COLUMN sold_offline   BOOLEAN      NOT NULL DEFAULT false,
    ADD COLUMN synced_at      TIMESTAMPTZ  NULL;
-- unique (company_id, offline_number) where present (partial unique index, CONCURRENTLY)

-- D-1/D-8: offline periods and the review list
CREATE TABLE pos_offline_periods (
    id             BIGSERIAL PRIMARY KEY,
    uid            VARCHAR(26) NOT NULL UNIQUE,
    company_id     BIGINT      NOT NULL REFERENCES companies(id),
    branch_id      BIGINT      NOT NULL REFERENCES branches(id),
    pos_till_id    BIGINT      NOT NULL REFERENCES pos_tills(id),
    switched_by    BIGINT      NOT NULL,
    reason         VARCHAR(16) NOT NULL,            -- UNREACHABLE | MANUAL
    started_at     TIMESTAMPTZ NOT NULL,
    ended_at       TIMESTAMPTZ NULL,
    sales_count    INTEGER     NOT NULL DEFAULT 0
);

CREATE TABLE pos_offline_reviews (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26) NOT NULL UNIQUE,
    company_id       BIGINT      NOT NULL REFERENCES companies(id),
    branch_id        BIGINT      NOT NULL REFERENCES branches(id),
    sales_invoice_id BIGINT      NULL REFERENCES sales_invoices(id),
    offline_number   VARCHAR(40) NULL,
    kind             VARCHAR(24) NOT NULL,          -- PRICE_DIFFERENCE | NEGATIVE_STOCK | CLOSED_PERIOD | HELD
    detail           VARCHAR(500),
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN',   -- OPEN | ACKNOWLEDGED
    acknowledged_by  BIGINT      NULL,
    acknowledged_at  TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Foreign keys to `sales_invoices`, `pos_tills`, `companies` and `branches` lock those tables briefly,
hence the lock timeout (CI rule 3). Indexes on the new tables follow the module's conventions and
are settled with the final DDL.
