# 0068 — Offline POS: sell while the server is unreachable, sync later

- **Status:** Accepted (2026-11-07). The business rules below are the owner's rulings from the
  2026-11-07 discussion. The DDL in Appendix A (`V114__offline_pos.sql`) and the permission seed in
  Appendix B were approved by the owner the same day.
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

## Appendix A — DDL: `V114__offline_pos.sql` (approved 2026-11-07)

```sql
-- ============================================================================
-- V114 — Offline POS, phase 1 (ADR-0068). DDL approved by the owner 2026-11-07.
--
-- A till that is allowed to (pos_tills.offline_allowed) may keep selling while the server is
-- unreachable; its sales arrive later through a separate sync path. This migration adds:
--   (1) the per-till switch                       pos_tills.offline_allowed
--   (2) shifts first opened offline on the till    pos_sessions.opened_offline / offline_client_key
--   (3) offline sales                              sales_invoices.sold_offline / offline_number / synced_at
--   (4) offline periods (who switched, why, when)  pos_offline_periods
--   (5) the manager review list                    pos_offline_reviews
--
-- Additive only: every new column on an existing table is nullable or defaulted, every new CHECK
-- holds for every existing row (all false / NULL), so no backfill and no behaviour change for any
-- till until an admin allows offline selling on it.
--
-- Plain transactional CREATE INDEX, not CONCURRENTLY: this repo has no non-transactional migration
-- wiring (see V102). The partial unique indexes cover no existing rows (the columns are new and
-- NULL), so they build instantly; the lock timeout below makes any held lock fail fast instead.
-- ============================================================================

SET LOCAL lock_timeout = '10s';

-- (1) D-1: which tills may sell offline. Default off: no till changes behaviour.
ALTER TABLE pos_tills
    ADD COLUMN offline_allowed BOOLEAN NOT NULL DEFAULT false;

-- (2) D-4: a shift first opened offline on the till, created once at sync.
ALTER TABLE pos_sessions
    ADD COLUMN opened_offline     BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN offline_client_key VARCHAR(64) NULL,
    ADD CONSTRAINT chk_pos_session_offline_key
        CHECK (opened_offline = false OR offline_client_key IS NOT NULL);
CREATE UNIQUE INDEX ux_pos_sessions_offline_client_key
    ON pos_sessions (company_id, offline_client_key)
    WHERE offline_client_key IS NOT NULL;

-- (3) D-3/D-5: offline sales. finalised_at stays the business date (time SOLD);
--     synced_at is when the server received it.
ALTER TABLE sales_invoices
    ADD COLUMN sold_offline   BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN offline_number VARCHAR(40) NULL,
    ADD COLUMN synced_at      TIMESTAMPTZ NULL,
    ADD CONSTRAINT chk_sales_invoice_offline
        CHECK (sold_offline = false OR (offline_number IS NOT NULL AND synced_at IS NOT NULL));
CREATE UNIQUE INDEX ux_sales_invoices_offline_number
    ON sales_invoices (company_id, offline_number)
    WHERE offline_number IS NOT NULL;

-- (4) D-1/D-8: each offline period of a till (who switched, why, how long).
CREATE TABLE pos_offline_periods (
    id              BIGSERIAL PRIMARY KEY,
    uid             VARCHAR(26) NOT NULL,
    company_id      BIGINT      NOT NULL,
    branch_id       BIGINT      NOT NULL,
    pos_till_id     BIGINT      NOT NULL,
    pos_session_id  BIGINT,
    client_key      VARCHAR(64) NOT NULL,          -- idempotent upload from the till
    reason          VARCHAR(16) NOT NULL,          -- UNREACHABLE | MANUAL
    switched_by     BIGINT      NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL,
    ended_at        TIMESTAMPTZ,
    sales_count     INTEGER     NOT NULL DEFAULT 0,
    synced_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      BIGINT,
    updated_at      TIMESTAMPTZ,
    updated_by      BIGINT,
    CONSTRAINT uq_pos_offline_period_uid        UNIQUE (uid),
    CONSTRAINT uq_pos_offline_period_client_key UNIQUE (company_id, client_key),
    CONSTRAINT fk_pos_offline_period_company    FOREIGN KEY (company_id)     REFERENCES companies(id),
    CONSTRAINT fk_pos_offline_period_branch     FOREIGN KEY (branch_id)      REFERENCES branches(id),
    CONSTRAINT fk_pos_offline_period_till       FOREIGN KEY (pos_till_id)    REFERENCES pos_tills(id),
    CONSTRAINT fk_pos_offline_period_session    FOREIGN KEY (pos_session_id) REFERENCES pos_sessions(id),
    CONSTRAINT fk_pos_offline_period_switched   FOREIGN KEY (switched_by)    REFERENCES app_users(id),
    CONSTRAINT chk_pos_offline_period_reason    CHECK (reason IN ('UNREACHABLE','MANUAL')),
    CONSTRAINT chk_pos_offline_period_times     CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT chk_pos_offline_period_sales     CHECK (sales_count >= 0)
);
CREATE INDEX ix_pos_offline_periods_company ON pos_offline_periods (company_id);
CREATE INDEX ix_pos_offline_periods_branch  ON pos_offline_periods (branch_id);
CREATE INDEX ix_pos_offline_periods_till    ON pos_offline_periods (pos_till_id);

-- (5) D-8: the manager review list (flagged or held offline sales).
CREATE TABLE pos_offline_reviews (
    id                BIGSERIAL PRIMARY KEY,
    uid               VARCHAR(26) NOT NULL,
    company_id        BIGINT      NOT NULL,
    branch_id         BIGINT      NOT NULL,
    pos_till_id       BIGINT      NOT NULL,
    pos_session_id    BIGINT,
    sales_invoice_id  BIGINT,                      -- NULL when HELD (not posted)
    offline_number    VARCHAR(40),
    kind              VARCHAR(24) NOT NULL,
    detail            VARCHAR(500),
    held_request      TEXT,                        -- the sale as rung, JSON, HELD only
    status            VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    acknowledged_by   BIGINT,
    acknowledged_at   TIMESTAMPTZ,
    acknowledge_note  VARCHAR(500),
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by        BIGINT,
    CONSTRAINT uq_pos_offline_review_uid      UNIQUE (uid),
    CONSTRAINT fk_pos_offline_review_company  FOREIGN KEY (company_id)       REFERENCES companies(id),
    CONSTRAINT fk_pos_offline_review_branch   FOREIGN KEY (branch_id)        REFERENCES branches(id),
    CONSTRAINT fk_pos_offline_review_till     FOREIGN KEY (pos_till_id)      REFERENCES pos_tills(id),
    CONSTRAINT fk_pos_offline_review_session  FOREIGN KEY (pos_session_id)   REFERENCES pos_sessions(id),
    CONSTRAINT fk_pos_offline_review_invoice  FOREIGN KEY (sales_invoice_id) REFERENCES sales_invoices(id),
    CONSTRAINT fk_pos_offline_review_ack_by   FOREIGN KEY (acknowledged_by)  REFERENCES app_users(id),
    CONSTRAINT chk_pos_offline_review_kind    CHECK (kind IN ('PRICE_DIFFERENCE','NEGATIVE_STOCK','CLOSED_PERIOD','HELD')),
    CONSTRAINT chk_pos_offline_review_status  CHECK (status IN ('OPEN','ACKNOWLEDGED')),
    CONSTRAINT chk_pos_offline_review_ack     CHECK (status <> 'ACKNOWLEDGED'
                                                     OR (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL)),
    CONSTRAINT chk_pos_offline_review_held    CHECK (kind <> 'HELD' OR (sales_invoice_id IS NULL AND held_request IS NOT NULL)),
    CONSTRAINT chk_pos_offline_review_posted  CHECK (kind = 'HELD' OR sales_invoice_id IS NOT NULL)
);
CREATE INDEX ix_pos_offline_reviews_company_status ON pos_offline_reviews (company_id, status);
CREATE INDEX ix_pos_offline_reviews_branch         ON pos_offline_reviews (branch_id);
CREATE INDEX ix_pos_offline_reviews_invoice        ON pos_offline_reviews (sales_invoice_id);
```

## Appendix B — Permission seed (edit to `R__seed_permissions.sql`, approved 2026-11-07)

```sql
('POS.OFFLINE.SWITCH', 'pos', 'Switch a till to offline selling by hand (server reachable but unusable)'),
('POS.OFFLINE.REVIEW', 'pos', 'Review offline periods and flagged or held offline sales'),
-- grants (CASHIER gets neither: a cashier's switch when the server is truly unreachable needs no
-- permission; syncing uses the existing POS.SALE.CREATE)
('SALES_MANAGER',  'POS.OFFLINE.SWITCH'), ('SALES_MANAGER',  'POS.OFFLINE.REVIEW'),
('BRANCH_MANAGER', 'POS.OFFLINE.SWITCH'), ('BRANCH_MANAGER', 'POS.OFFLINE.REVIEW'),
```

Restaurant offline (phase 2) adds its own migration later, again for approval.
