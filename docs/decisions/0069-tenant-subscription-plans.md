# 0069 — Tenant subscription plans: tiers, add-ons, caps and the non-payment ladder

- **Status:** Accepted (2026-11-13). The business rules below are the owner's rulings from the
  2026-11-13 discussion. The DDL in Appendix A (`V115__tenant_subscriptions.sql`) and the four calls
  under *Calls made inside the rulings* were approved by the owner the same day.
- **Builds on:** ADR-0062 (organisation-as-tenant, platform operator, `organisations.status`
  suspension), MULTITENANCY-PLAN.md §D-6 (which left `subscription_plan` / `subscription_status`
  unread until this product decision was made), ADR-0068 (offline POS sync).
- **Scope:** tenants on the **shared instance** only. Self-hosted single-tenant installs (LAN jars —
  Kilimanjaro, SAM Electronix, …) stay on their existing contracts; enforcing a plan on a box the
  client runs is a licence-key problem and a separate design.

## Context

The shared instance now hosts several tenant organisations. Every tenant can use every module, add
unlimited users, branches and tills, and keeps working whether or not they have paid. The only
lifecycle lever is the platform operator's manual **Suspend** (`organisations.status`, checked at
login by `AuthServiceImpl.assertTenantIsOpen`).

`organisations.subscription_plan` (VARCHAR 40) and `subscription_status` (VARCHAR 20) have existed
since V1 with zero readers. §D-6 deliberately left them unread: "billing state that the application
enforces is a product decision nobody has made". This ADR is that decision.

Facts from the code that shape the design (verified 2026-11-13):

- **`is_root` is held only by the platform operator.** Tenant administrators hold `ORG_ADMIN`, an
  ordinary role. So every tenant user's authority is an enumerated permission set resolved by
  `PermissionResolver` — one choke point every `@PreAuthorize("@perm.has(…)")` goes through.
- **Clients already hide what a user cannot do.** The web menu, the POS app and OrbixHQ key off the
  permission codes in `/auth/me`. Removing a code from the resolved set hides the screen everywhere.
- **Permission groups are too coarse to be modules.** `RESTAURANT.*` sits in the `sales` group,
  `BOM.*` in `products`, the GL statements in `reporting`. The plan therefore classifies **codes**,
  not groups.
- **Background postings do not use user permissions.** Outbox handlers run as
  `Principal.system(…)` (no user), so GL, stock and AR postings keep happening whatever the plan.
- **The OrbixHQ app is indistinguishable on the wire** — it calls the same endpoints as the web and
  POS, with the same headers.
- `PlatformOrganisationWriteGuard` is already a `HandlerInterceptor` that refuses writes by path;
  the same shape serves the read-only stage.

## Decision

### D-1 · Four tiers, flat price, a catalogue in code

| | **Basic** | **Standard** | **Premium** | **Enterprise** |
|---|---|---|---|---|
| Modules | POS, products, stock, sales, customers & suppliers, basic reports, receiving stock | + purchasing (LPO, requisitions, RFQ, returns, landed cost), AR, AP, cash & bank, VAT & WHT returns, inter-branch transfers, approvals | + GL & financial statements, fixed assets, costing, budgeting, FX, BI dashboards, routes & van sales, CRM | + HR & payroll, manufacturing (BOM, work orders), projects |
| Users | 5 | 20 | 50 | unlimited |
| Branches | 1 | 5 | 15 | unlimited |
| POS tills | 3 | 10 | 30 | unlimited |
| Companies | 1 | 2 | 3 | unlimited |

The price is flat per plan; caps are the tier's limits, not metered billing. Each tier contains every
module of the tiers below it. The exact code-to-module table is Appendix B.

The catalogue (tiers, their modules, their caps) lives **in code** (`PlanCode` / `PlanModule` enums),
not in a table:

- the module side is code knowledge anyway — which permission codes make up "GL" changes only when
  code changes;
- the numbers were ruled on and change rarely; a change is a release, like any other product change;
- per-tenant exceptions are the **overrides** in D-3, which need no release.

### D-2 · Two paid add-ons, available on any tier

- **Restaurant** — kitchen/bar orders, bills, waiters, prep stations (`RESTAURANT.*` codes).
- **OrbixHQ** — the owner's mobile app.

### D-3 · Per-tenant overrides

The operator can override any of the four caps for one tenant (`NULL` = the plan's number). Enterprise's
"set per contract" is Enterprise with overrides.

### D-4 · Module gating through the permission resolver

`PermissionResolver` keeps caching the user's raw role grants exactly as today, and applies the
tenant's entitlement **on every read**: a code whose module is not in the tenant's plan (or whose
add-on is not bought) is removed from the resolved set. Consequences:

- every `@PreAuthorize` gate respects the plan with no change to any controller;
- `/auth/me` returns the filtered set, so web menus, POS and OrbixHQ hide unbought modules for free;
- a role editor still shows every code (roles are bundles shared across tiers; an upgrade lights
  them up immediately, with no role edits).

**Exempt:** the platform operator (`is_root`, including when they have entered a tenant — support
must see everything), the platform organisation, and system principals (outbox handlers).

**Background postings always run.** The GL, stock ledger, AR/AP sub-ledgers and every other posting
keep being written regardless of plan. A Basic or Standard tenant that upgrades to Premium finds
complete books from day one. Gating hides screens and refuses user actions; it never stops a posting.

### D-5 · Caps are checked when something is added, never by removing anything

Adding (or re-activating) a user, branch, company or POS till first counts the active ones in the
tenant; at or above the cap the request is refused with a friendly message naming the plan and the
cap. Nothing already present is ever disabled — a tenant that downgrades with 8 users on a 5-user plan
keeps all 8 and cannot add a 9th. Counted:

| Cap | Counts |
|---|---|
| Users | `app_users` in the organisation with status ACTIVE |
| Branches | non-archived branches of the organisation's companies |
| Companies | non-archived companies of the organisation |
| POS tills | active `pos_tills` of the organisation's companies |

Initial provisioning (`TenantProvisioningService`) creates the first company, branch, admin and till
without the check. The platform operator is **not** exempt from caps: raising one is a deliberate,
audited override, not a side effect of the operator happening to be signed in.

### D-6 · The non-payment ladder: grace → read-only → suspended

Each subscription has a **due date**: `paid_until` if any payment has been recorded, otherwise
`trial_ends_on`. If both are empty the subscription never lapses (contract / complimentary tenants).
With `today` in the organisation's time zone and `d = today − due`:

| `d` | Stage | Effect |
|---|---|---|
| — (in trial) | **TRIAL** | full plan; banner counts the days left from 7 days out |
| ≤ 0 | **ACTIVE** | full plan; banner from 7 days before the due date |
| 1 … grace | **GRACE** | full plan; red banner: "payment overdue — read-only on …" |
| grace+1 … grace+read-only | **READ_ONLY** | every write refused, POS included; reads, reports and printing still work |
| beyond | **SUSPENDED** | sign-in refused; open sessions refused on their next request |

- **Stage lengths are per tenant**, set by the operator; default **7 + 7** days.
- **Fully read-only** (owner ruling): the POS cannot sell either. Allowed writes in READ_ONLY: sign-in
  and session (`/api/v1/auth/**`), document rendering for reprints (`/api/v1/documents/render`), and
  the offline-sync upload (below).
- **Offline sales made before read-only began are accepted.** A till that was offline when the tenant
  entered READ_ONLY may still sync sales whose sold-at time is before the start of the first read-only
  day; later ones are refused. Those earlier sales really happened while the tenant was allowed to
  sell, and refusing them would lose real cash records.
- **The manual suspension** (`organisations.status`, ADR-0062) stays independent and wins: a tenant the
  operator suspends is suspended whatever its payments say.
- The stage is **computed**, never stored — no scheduler, no state drift. `subscription_status` stays
  unread (see D-10).

### D-7 · Trials

A new tenant starts on a **14-day trial of the plan chosen at provisioning** (`trial_ends_on = today +
14`), with the add-ons chosen. When the trial ends without a payment, it follows the same ladder as
an overdue payment (owner ruling).

### D-8 · Payments are recorded by the operator (v1)

The platform operator records each payment — amount, currency, date paid, reference, note, and the
new **paid-until** date — on Platform → Tenants. The payment row is append-only; recording one moves
`paid_until` forward and immediately returns the tenant to ACTIVE. The billing cycle
(monthly / quarterly / half-yearly / annual / custom) is a per-tenant field that pre-fills the
suggested paid-until date; it enforces nothing. No payment gateway in v1. Every subscription change and
every payment writes an audit row.

### D-9 · The OrbixHQ add-on needs the app to identify itself

The OrbixHQ app (from **1.5.0**) sends `X-Client-App: orbixhq` on every request. When a tenant without
the add-on sends it, the request is refused with "The OrbixHQ app is not part of your plan." Apps
older than 1.5.0 do not send the header and are therefore not gated; that is accepted (this is a
commercial gate, not a security boundary — every endpoint the app uses is equally open to the web
client). A minimum-app-version check can close the gap later if it matters.

### D-10 · Existing tenants and the old columns

- **No subscription row = unrestricted** (every module, both add-ons, no caps, never lapses). That is
  how every existing tenant behaves today, so shipping this changes nothing for anyone until the
  operator assigns a plan. No migration backfill (per-tenant data is provisioned by the application,
  not by migrations). The platform organisation never gets a row.
- `organisations.subscription_plan` / `subscription_status` **stay unread and unwritten**. The new
  table is the single source of truth; mirroring into the old columns would create two answers.

### D-11 · Authority

Reuse the platform codes, no seed edit: reading a tenant's subscription needs **`ORG.CREATE`** (as
the tenant list does); changing it or recording a payment needs **`ORG.SUSPEND`** (the lifecycle
lever). Tenant users see only the summary in `/auth/me` (plan, add-ons, stage, due date).

## Consequences

- One choke point (`PermissionResolver`) gives module gating to every endpoint and every client.
- Upgrading is instant and lossless; downgrading or lapsing is lossless.
- Basic tenants lose the web BI dashboard (BI is a Premium module). OrbixHQ's dashboard reads the
  sales and stock report endpoints, which are Basic, so the OrbixHQ add-on works on every tier.
- A new permission code must be classified (Appendix B); `PlanModuleCoverageTest` fails the build on
  an unclassified code, the same way `PermissionCodesSeededTest` guards the seed.
- Caps add one `COUNT(*)` to five low-frequency write paths.
- Each request from a tenant user adds an in-memory lookup of the tenant's subscription (cached per
  organisation, evicted on change, 60 s TTL backstop).

## Calls made inside the rulings — approved 2026-11-13

1. **EFD/fiscal receipts and tax rates are Basic.** Option A put "tax/EFD" in Standard, but a POS
   cannot legally sell without fiscal receipts and VAT rates. Only VAT/WHT **returns** are Standard.
2. **Direct goods receipt is Basic.** A shop must be able to get stock in. LPOs, requisitions, RFQ,
   purchase returns and landed cost are Standard.
3. **Currencies (view) stay Basic** — pickers across core screens read them; FX revaluation and
   currency management are Premium.
4. **Inter-branch transfers are Standard** (Basic has one branch). Van reconciliation goes with
   routes (Premium); BOM goes with manufacturing (Enterprise).

## Alternatives considered

- **Catalogue in a table** with an editor — rejected for v1: the module side is code anyway, and
  overrides cover per-tenant exceptions. Easy to add later without changing D-4..D-6.
- **Gating by permission group** — rejected: groups are not modules (restaurant sits in `sales`).
- **A stored, scheduler-updated status** — rejected: a computed stage cannot drift, and needs no job.
- **Blocking modules in each controller / client** — rejected: hundreds of checks, and the clients
  would still disagree with the server.
- **Straight to suspension on non-payment** — owner chose the read-only stage.

## Appendix A — DDL (`V115__tenant_subscriptions.sql`) · approved 2026-11-13

```sql
SET LOCAL lock_timeout = '10s';   -- the FKs below lock organisations briefly

CREATE TABLE organisation_subscriptions (
    id                      BIGSERIAL PRIMARY KEY,
    uid                     VARCHAR(26)  NOT NULL,
    organisation_id         BIGINT       NOT NULL,
    plan_code               VARCHAR(20)  NOT NULL,           -- BASIC | STANDARD | PREMIUM | ENTERPRISE
    addon_restaurant        BOOLEAN      NOT NULL DEFAULT false,
    addon_orbixhq           BOOLEAN      NOT NULL DEFAULT false,
    max_users_override      INTEGER,                         -- NULL = the plan's cap
    max_branches_override   INTEGER,
    max_tills_override      INTEGER,
    max_companies_override  INTEGER,
    billing_cycle           VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY',
    trial_ends_on           DATE,
    paid_until              DATE,
    grace_days              INTEGER      NOT NULL DEFAULT 7,
    read_only_days          INTEGER      NOT NULL DEFAULT 7,
    notes                   VARCHAR(500),
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ,
    updated_by              BIGINT,
    CONSTRAINT uq_org_subscription_uid  UNIQUE (uid),
    CONSTRAINT uq_org_subscription_org  UNIQUE (organisation_id),
    CONSTRAINT fk_org_subscription_org  FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    CONSTRAINT chk_org_subscription_plan
        CHECK (plan_code IN ('BASIC','STANDARD','PREMIUM','ENTERPRISE')),
    CONSTRAINT chk_org_subscription_cycle
        CHECK (billing_cycle IN ('MONTHLY','QUARTERLY','HALF_YEARLY','ANNUAL','CUSTOM')),
    CONSTRAINT chk_org_subscription_overrides CHECK (
        (max_users_override     IS NULL OR max_users_override     >= 0) AND
        (max_branches_override  IS NULL OR max_branches_override  >= 0) AND
        (max_tills_override     IS NULL OR max_tills_override     >= 0) AND
        (max_companies_override IS NULL OR max_companies_override >= 0)),
    CONSTRAINT chk_org_subscription_ladder
        CHECK (grace_days BETWEEN 0 AND 365 AND read_only_days BETWEEN 0 AND 365)
);

-- Append-only: a correction is a new row, never an edit.
CREATE TABLE subscription_payments (
    id               BIGSERIAL PRIMARY KEY,
    uid              VARCHAR(26)   NOT NULL,
    organisation_id  BIGINT        NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    currency_code    VARCHAR(3)    NOT NULL,
    paid_on          DATE          NOT NULL,
    paid_until       DATE          NOT NULL,                -- the subscription's new paid-until
    reference        VARCHAR(100),
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by       BIGINT,
    CONSTRAINT uq_subscription_payment_uid UNIQUE (uid),
    CONSTRAINT fk_subscription_payment_org FOREIGN KEY (organisation_id) REFERENCES organisations(id),
    CONSTRAINT chk_subscription_payment_amount CHECK (amount >= 0)
);
CREATE INDEX ix_subscription_payments_org ON subscription_payments (organisation_id, paid_on DESC);
```

Additive only: two new tables, no change to any existing column or row, no backfill. Both FKs point
at `organisations` (a handful of rows); the lock timeout makes a held lock fail fast.

## Appendix B — permission code → module

A code not matched below is **CORE** (every tier). The first matching rule wins.

| Module | Tier / add-on | Codes |
|---|---|---|
| RESTAURANT | add-on | `RESTAURANT.*` |
| PURCHASING | Standard | `PURCHASE.*` except `PURCHASE.RECEIVE.DIRECT`, `PURCHASE.GOODS_RECEIPT.VIEW` |
| RECEIVABLES | Standard | `AR.*` |
| PAYABLES | Standard | `AP.*` |
| CASH_BANK | Standard | `CASH.*`, `CHEQUE.*`, `PETTY_CASH.*` |
| TAX_RETURNS | Standard | `VAT.*`, `WHT.*` |
| TRANSFERS | Standard | `STOCK.TRANSFER.*` |
| APPROVALS | Standard | `APPROVALS.*` |
| GENERAL_LEDGER | Premium | `GL.*`, `REPORT.BS.VIEW`, `REPORT.PL.VIEW`, `REPORT.CASHFLOW.VIEW`, `REPORT.LEDGER.VIEW` |
| FIXED_ASSETS | Premium | `FA.*` |
| COSTING | Premium | `COSTING.*` |
| BUDGETING | Premium | `BUDGETING.*` |
| FX | Premium | `FX.*`, `CURRENCY.MANAGE` |
| BI | Premium | `BI.*` |
| ROUTES | Premium | `ROUTE.*`, `STOCK.VAN_RECON.*` |
| CRM | Premium | `CRM.*` |
| HR_PAYROLL | Enterprise | `HR.*` |
| MANUFACTURING | Enterprise | `MANUFACTURING.*`, `WORKORDER.*`, `BOM.*` |
| PROJECTS | Enterprise | `PROJECTS.*` |
| CORE | all | everything else — IAM, parties, products, stock, sales, POS, fiscal, tax rates, documents, notifications, `REPORT.VIEW`, `REPORT.EXPORT`, `CURRENCY.VIEW`, platform codes |
