# ERP — Multi-Tenancy Plan

> **Status: DRAFT — living document. Not approved. No code or migration has been written.**
> Target model settled; decisions still open (§3). Implementation must not begin before the §3
> decisions are closed, the §5.1 DDL is approved, and the §0 production constraints are honoured.
>
> Created 2026-08-13 against `develop` @ `441ebc7c`. Revise freely — see §13 for the change log.
> **For how this actually ships — environments, stages, and when the customer is told — see §12.**
>
> **⚠ 2026-08-14 — read §0 first.** A 14-agent verification workflow refuted the premise that this
> plan's changes are behaviourally inert on a one-organisation install, and found two items that
> break *live single-tenant production* independent of tenancy. §5.1 and the phase list below have
> been revised accordingly.

This plan moves the ERP from **multi-company inside one installation** to **multi-customer on one
installation**: several independent client organisations sharing a single deployment and a single
database, isolated from each other.

Everything in §2 was verified against shipped code by a 12-agent read-only audit (5 independent
lenses, each finding put to a skeptic instructed to refute it; 43 findings raised, 37 survived).
Gap IDs `G1`–`G15` below are that register's IDs. **Where this plan and the older foundational docs
disagree, this plan is newer** — `ARCHITECTURE.md` and `DATA-MODEL.md` describe the IAM spine as
designed for a single organisation.

---

## 0. Production constraints — settled 2026-08-14

These bound every decision below. Where an earlier section of this plan conflicts with one, the
constraint wins and the section is stale.

| Constraint | Consequence for this plan |
|---|---|
| **Production is live, with paying customers on it.** | No change may alter behaviour on an install holding one organisation — and that must be *demonstrated per change*, not assumed. See §0.1. |
| **Strictly one codebase.** No fork, no product-line branch, no build variant, **and no tenancy-mode flag.** | Single-tenant installs and the shared instance run the same artefact. Differences are carried by **data, permissions and `.env`** — never by a switch read at runtime. |
| **D-1 = (a)** — existing installs are never merged; the shared instance is a **new deployment**. | Deletes the `id`+`uid` remap across 204 tables, the only irreversible item in the programme. |
| **No username rewrite.** Legacy bare usernames stay valid forever. | Deletes §5.1 backfill step 4 and its ⚠ box. See §0.2. |
| **Every release is rehearsed against a restored production dump** before it ships. | See §6. |

**Why no fork** (verified, not preference): the Flyway line is linear `V1…V98` and two lines
authoring `V<n>` collide with nothing to arbitrate; the ArchUnit freeze store is **207 line-anchored
entries** that cannot be merged once two stores drift; the build is a single Maven artefact with
**zero `@Profile` uses in the backend** and one image per version+arch, never per client.

**Why no tenancy-mode flag** (also verified): any mode assertion over data requires a `DataSource`,
so it can only run *after* Flyway — a refusal therefore means "schema migrated, no server". And
`dist/bundle/orbixerp.sh` dispatches only `start|stop|restart|status|logs|backup|restore|update|
version|config` — **no psql, exec, shell or repair command** — while `restore` reinstates the very
rows that tripped the check. On an offline customer-owned box that is an unrecoverable outage.
Separately `infra/qa` is one host with one durable volume: it can be SINGLE or SHARED, never both,
and once it holds a second organisation it can never verify an estate release again.

### 0.1 The "behaviourally inert at one organisation" premise — **REFUTED**

The intuition was that with one `organisations` row every new org predicate is a tautology. It is
not, because **not every principal has a user**:

- `LoginAttemptService.java:94-101` writes `LOGIN.FAIL` with `actorUserId = null`.
- **Seventeen outbox handlers** construct
  `new RequestContext.Principal(null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null)`
  — `SalesPostingHandler.java:73`, `GoodsReceiptStockHandler.java:98`, `SaleIssueStockHandler.java:123`,
  `ArSalePostedHandler.java:103`, `PayrollPostingHandler.java:105`, plus twelve more. Note
  `root = false`: they pass the 763 `assertCanActIn` sites **only** because `companyId` matches
  (`ScopeGuard.java:646`).

After P2-1 those principals have **no organisation**. Two consequences at `organisations.count() == 1`:

- **Certain (audit).** `audit_logs.organisation_id` is NULL **by construction, permanently** — not
  merely historically — for the whole pre-auth trail and every system-written row; the §5.1 backfill
  only derives it "where the actor is non-null". P3-8 then replaces `AuditReadService.java:57`'s root
  exemption with an org predicate, which removes rows nothing else removed. Today root — i.e. the
  customer's own admin, made root at `BootstrapRunner.java:137` — is the only principal who can see
  unknown-username login failures; afterwards nobody can. A silent regression in an append-only
  security trail, on live production.
- **Precedented (posting).** If the org dimension reaches `ScopeGuard.canActIn` — which P3-1 says to
  apply "unconditionally" — all seventeen handlers deny. `SalesPostingHandler.java:77-86` catches
  `Exception` and **marks the event processed anyway** to avoid a poison transaction, so a denied GL
  posting is a WARN that is never retried. This is `G14` verbatim, the shape that already cost this
  product a feature.

**Consequence for the plan:** Phase 3 is gated on a new **Phase 2.5** (below), and the rollout
mechanism splits in two — see §0.3.

### 0.2 Usernames — legacy names are never rewritten

Only tenants provisioned on the shared instance get `<user>@<alias>`. Existing users keep
`rootadmin`, `jkomba`, `saloyce` indefinitely, or rename on their own schedule.

This is safe *by construction*, not by tolerance: a new tenant can never mint a bare name, because
the server always composes from `principal.organisationId()` and rejects `@` in the local part
(P2-2c). `findByUsername` therefore stays a **global exact-match lookup** — nothing is parsed,
resolved or disambiguated, so the ambiguity-tolerant login this plan rejects is *not* reintroduced.

P2-2b narrows accordingly: **if** a username contains `@`, its suffix must equal the organisation's
alias; a bare username is conforming, not drift. This also simplifies D-7e — a suffix-less platform
operator becomes a normal case rather than an exception.

### 0.3 Shadow mode covers guards only — filters need a parity harness

"Log would-deny, allow" exists only where there is a **guard** to intercept. Four Phase-3 items are
**query filters**, where a tightening silently returns fewer rows — there is nothing to allow and
nothing to log:

| Site | Item |
|---|---|
| `findByRootFalseOrderByUsername` | P3-4 |
| `RoleServiceImpl.java:65 list()` | P3-5 |
| `OrganisationServiceImpl.java:23 current()/list()` | P3-7 (feeds the 146 components in P6-1) |
| `AuditReadService:57`, `CompanyServiceImpl:150`, `UserServiceImpl:166/181`, `ProductStockReportQuery:393` | P3-8 |

Those sites need a **row-count parity harness** — same query, with and without the predicate, assert
identical counts on a one-organisation database — not shadow logging. Note also that **there is no
telemetry egress from a customer box**: no log shipping, no metrics export anywhere in `dist/`,
`infra/` or the backend. Shadow and parity evidence can only ever be collected from vendor-operated
prod and QA, and the rollout's confidence must be priced as "verified on two boxes we own".

---

## 1. The target model (settled)

| Question | Decision |
|---|---|
| What is a tenant? | An **organisation**. One customer = one `organisations` row. |
| Isolation | **Shared database, shared schema.** Not schema-per-tenant, not DB-per-tenant. |
| Multi-company | **Kept, inside a tenant.** A group customer owns several companies and many branches. |
| Login identity | **Username + password**, one field. No tenant code box, no subdomain, no email-as-identity. |
| Username form | **`<user>@<org-alias>`** — e.g. `smith@jambobora`. |
| Organisation alias | A short, unique, human-readable handle per tenant (`jambobora`). New column `organisations.alias`. |
| Username uniqueness | **Global**, and collision-free *by construction* — the alias suffix partitions the namespace. |
| Tenant resolution | **Derived from the authenticated user** (`app_users.organisation_id`), never from caller input, **and never by parsing the username string** (§5, P2-2). |
| Credential quality | Strong-password policy enforced. **MFA deferred** — columns already exist, see P2-5. |

The `@alias` suffix does two jobs at once and neither is authorisation:

1. **Uniqueness** — every customer can have their own `admin@…`, `cashier1@…`, `smith@…`, so the
   shared-namespace friction of plain global usernames disappears while `findByUsername` stays a
   single global lookup and `uq_app_user_username` stays untouched.
2. **Legibility** — the user sees which tenant they are signing into, in one field a password
   manager treats as one credential.

> **Security invariant — state this in the ADR.** The suffix is a *naming convention*, not an input.
> Scope is always taken from `app_users.organisation_id` **after** authentication. Parsing the
> string to decide scope would re-introduce exactly the caller-supplied tenant input this design
> avoids, and is the shortcut an implementer is most likely to take.

### How a username is entered

Asymmetric by design, and both halves matter:

| Where | What is typed | What happens |
|---|---|---|
| **Creating a user** | the local part only — `smith` | The form shows `@jambobora` as a fixed, non-editable suffix. **The server composes the stored username from `principal.organisationId()` → alias**, never from anything the client sends. |
| **Logging in** | the full `smith@jambobora` | No org context exists before authentication, so the whole string is required. |

Two consequences worth stating explicitly:

- **The composition is a second place the tenant must not come from input.** If the API accepted a
  full `smith@othertenant`, an administrator could mint an account that reads as another tenant's.
  The create endpoint therefore takes the **local part only** and rejects any `@` in it — otherwise
  `smith@evil` composes to `smith@evil@jambobora`. See P2-2c.
- **D-7's cross-tenant enumeration concern is eliminated.** Because the uniqueness check now runs on
  the *composed* name, a collision can only ever be another user in the caller's own organisation.
  `"Username already exists"` can no longer reveal anything about another customer — the generic
  message is no longer needed, and the honest specific one is better UX.

Because the two halves differ, **the creation screen must show the composed username** and the
credential handout must carry the full `smith@jambobora` — the admin who typed `smith` is not the
person who will later type the whole thing.

Rejected, with reasons, so they are not re-litigated:

- **Tenant code + username + password** *(chosen 2026-08-13, reversed same day)* — a tenant code is
  a third thing to distribute, support and mistype, and it buys nothing here: with globally unique
  usernames the tenant is already implied by the identity. Keeping usernames global also leaves
  `uq_app_user_username` and `uq_app_users_email` untouched, which removes the riskiest DDL in the
  plan (see §5.1). **Security note in its favour:** deriving the tenant from the authenticated user
  is strictly stronger than accepting a typed code — there is no tenant input to spoof, confuse, or
  forget to validate.

- **Tenant = company** — would have been the smallest change, but collapses the group-customer
  case the product already serves (a customer with several legal entities), and throws away the
  `organisation` node that already exists.
- **Schema-per-tenant** — strong isolation and per-tenant restore, but Flyway would have to run
  per schema, which breaks the `migration-hygiene` CI gate and the frozen-schema discipline.
- **DB-per-tenant** — is effectively what ships today (see D-1); rejected as the *target* because
  it does not reduce the per-customer operational cost, which is the point of the exercise.

### 1.1 Global vs tenant-scoped — the classification *(added 2026-08-14)*

Not everything is a tenant's. Some rows are **platform-wide**: one copy, shared by every
organisation. The plan handled this implicitly, one table at a time; stating it as a rule is what
stops the next implementer from scoping something that must not be scoped, or vice versa.

**The rule.** A row is **global** when its meaning is fixed by the product or by the outside world,
and **tenant-scoped** when its meaning is chosen by a customer. Vocabulary the code depends on is
global; anything a customer authors is theirs.

| Global — one copy, all tenants | Why |
|---|---|
| `permissions` | The product defines them; a permission code is part of the API contract (`uq_permission_code`). |
| **`roles` where `is_system = true`** — the 12 shipped bundles (ADR-0057) | Product-defined authority sets. `organisation_id IS NULL` marks them (V99). |
| `currencies` | ISO vocabulary, set by the outside world. No write endpoint exists — `CurrencyController` is list+get only. |
| `processed_events` | Infrastructure bookkeeping, not business data. |
| `organisations` | The tenant register itself. |
| The RS256 signing key, the schema, the migration line | One deployment, one of each. |

| Tenant-scoped | Marked by |
|---|---|
| `roles` authored by a customer | `organisation_id = <tenant>` |
| `app_users` | `organisation_id` (V99) |
| `companies` | `organisation_id` (already, `V1__baseline.sql:44`) |
| Everything transactional — the 182 tables | `company_id`, which rolls up to an organisation |
| Reference data a customer configures — UoM, tax rates, payment terms, chart of accounts, GL configs, document templates, notification types, dimensions, price lists, PAYE bands | `company_id NOT NULL` on every one (re-verified 2026-08-14) |

**Two invariants that follow, and both need enforcing in code:**

> **I-1 · A global row is readable by every tenant and writable by none of them.**
> Only a platform operator may create, edit or archive a global row. For roles this is *already
> shipped and correct*: `RoleServiceImpl.updateByUid` throws `"System role cannot be modified"` when
> `role.isSystem()`, and per its own comment `setPermissions` and `archiveByUid` do the same (BR-7,
> security audit 2026-06-25). Do not regress it while adding org scoping.

> **I-2 · Every tenant-scoping predicate must be NULL-tolerant, or it hides the global rows.**
> The scoped read is `organisation_id = :caller **OR organisation_id IS NULL**`, never a plain
> equality. This is the same failure shape as §0.1's audit finding, and it recurs wherever a global
> and a scoped population share a table. **Assume it is the default mistake** — see P3-5 and P2.5-3.

Note the two are in tension by design: tenants must **see** and **use** the shipped bundles without
being able to **change** them — and, per D-3, must also be able to **grant** them to their own staff,
which is the one thing they currently cannot do.

### 1.2 Role classification *(added 2026-08-14)*

**There are 13 shipped `is_system` roles, not 12.** `ORG_ADMIN` is seeded separately in
`V1__baseline.sql:289-292`; the twelve ADR-0057 bundles are seeded in `R__seed_permissions.sql:288-299`.
Everywhere this plan says "the twelve shipped bundles" it is undercounting by the most powerful one.

**Global is not one thing.** Being platform-wide (`organisation_id IS NULL`) says who *owns* a role;
it does not say who may *confer* it. Those are separate axes and conflating them is what produces
either an unusable product or a privilege-escalation hole.

| Tier | Roles | `organisation_id` | `is_system` | Who may confer |
|---|---|---|---|---|
| **1 · Global, tenant-grantable** | the 12 ADR-0057 bundles | `NULL` | `true` | any tenant admin, to a user **in their own organisation** |
| **2 · Global, ceiling-bound** | `ORG_ADMIN` | `NULL` | `true` | a tenant admin **who holds it**, within their own organisation |
| **3 · Global, never tenant-grantable** | `PLATFORM_OPERATOR` *(does not exist yet — D-2)* | `NULL` | `true` | **only another platform operator** |
| **4 · Tenant-scoped** | anything a customer authors | `= <org id>` | `false` | the owning tenant only |

#### Worked example — shared instance, two tenants

```
organisations
 id | name             | alias
----+------------------+--------------
  1 | Platform         | system        <- sentinel (D-2); not a customer
  2 | Jambo Bora Ltd   | jambobora
  3 | Kilimo Fresh Co  | kilimofresh

roles
 id | code                 | organisation_id | is_system | tier
----+----------------------+-----------------+-----------+------
  1 | ORG_ADMIN            | NULL            | t         | 2
  2 | SALESPERSON          | NULL            | t         | 1
  3 | CASHIER              | NULL            | t         | 1
  4 | FIELD_SALES_AGENT    | NULL            | t         | 1
  5 | STOREKEEPER          | NULL            | t         | 1
  6 | ACCOUNTANT           | NULL            | t         | 1
  7 | SALES_MANAGER        | NULL            | t         | 1
  8 | BRANCH_MANAGER       | NULL            | t         | 1
  9 | PROCUREMENT_OFFICER  | NULL            | t         | 1
 10 | PROCUREMENT_MANAGER  | NULL            | t         | 1
 11 | HR_PAYROLL_MANAGER   | NULL            | t         | 1
 12 | FINANCE_DIRECTOR     | NULL            | t         | 1
 13 | PRODUCTION_MANAGER   | NULL            | t         | 1
 14 | PLATFORM_OPERATOR    | NULL            | t         | 3   <- new, D-2
 20 | SUPERVISOR           | 2               | f         | 4
 21 | TILL_SUPERVISOR      | 2               | f         | 4
 22 | SUPERVISOR           | 3               | f         | 4   <- same code, different tenant
 23 | WEIGHBRIDGE_CLERK    | 3               | f         | 4
```

Rows 20 and 22 are the point of scoping, and **both are rejected today** — `uq_role_code` is still
global on `(code)` alone (retained deliberately, §5.1). P4-1c is what unblocks them.

```
user_role — what the ceiling does with each grant
 granter         | grantee            | role                 | today          | after P4-2
-----------------+--------------------+----------------------+----------------+-----------
 admin@jambobora | jkomba@jambobora   | CASHIER    (tier 1)  | ✗ Forbidden    | ✓
 admin@jambobora | amwanga@jambobora  | ACCOUNTANT (tier 1)  | ✗ Forbidden    | ✓
 admin@jambobora | newadmin@jambobora | ORG_ADMIN  (tier 2)  | ✗ Forbidden    | ✓ holds it
 admin@jambobora | anyone             | PLATFORM_OPERATOR    | ✗ Forbidden    | ✗ always
 admin@jambobora | saloyce@jambobora  | SUPERVISOR (org 2)   | ✓ subset only  | ✓
 admin@jambobora | someone            | SUPERVISOR (org 3)   | ✓ ← leak (G2)  | ✗
```

The first three rows are the product being unusable on day one:
`AuthorityCeiling.assertCanConferRole` throws `ForbiddenException.notPermitted()` for **any** non-root
caller when `roleIsSystem`. The last row is `G2` — role lookup has no organisation predicate at all.

#### Two rules this classification forces

> **R-1 · A tenant role code must not collide with a global role code.** V100's two partial indexes
> cover *different partitions*, so once `uq_role_code` drops, `(NULL,'CASHIER')` and `(2,'CASHIER')`
> can coexist. That is exactly the ambiguity that makes `ApprovalEngineImpl:301`'s `findByCode` throw
> and lets `StepApproverResolver:78-84`'s **string** match cross tiers. No pair of partial indexes can
> express this — it needs a service check **and** a trigger, so a seeder cannot bypass it. → **P4-1d**

> **R-2 · Platform capabilities must never be ordinary permission rows.**
> `R__seed_permissions.sql:267-274` grants `ORG_ADMIN` every row in `permissions` via a `CROSS JOIN`,
> and says so: *"ORG_ADMIN always holds every permission, including ones added above later."* It is a
> **repeatable** migration. *(Correction 2026-08-14: it re-runs when its **checksum changes**, not on
> every deploy — measured, it has run three times ever on the live customer. That does not weaken this
> rule: adding `ORG.*` codes **means editing the seed**, which changes the checksum and re-runs the
> CROSS JOIN. The trigger is the edit, and the edit is exactly what P3-10 is.)* The moment P3-10 adds
> `ORG.*` codes, **every tenant's ORG_ADMIN silently gains them on the next deploy** — including
> anything meaning "create a tenant". Either exclude a platform module from the join
> (`WHERE p.module <> 'platform'`) or keep platform capability out of `permissions` entirely.

#### Decisions — **RATIFIED 2026-08-14**

**ORG_ADMIN stays tier 2.** Every comparable product does this: an AWS account admin can create
admins, an Azure Global Administrator can appoint Global Administrators, a Google Workspace Super
Admin can appoint Super Admins. The tenant's top admin is the customer's to delegate; making it
tier 3 turns the vendor into a ticket queue for *"please add another admin"* — which is precisely
the operational pressure that produces §8's `setRoot(true)`. Standards do not answer this by
withholding the capability; they answer it with compensating controls (ISO 27001 A.9.2.3 privileged
access management, A.9.2.5 access review, NIST AC-6 least privilege, AC-5 separation of duties):

- **MFA required for tiers 2 and 3.** MFA is deferred generally (P2-5) — **un-defer it for privileged
  roles only.** Every framework requires MFA on privileged accounts; the `mfa_enabled` column exists
  and is unread. This is the single highest-value control on this page.
- **Never-zero-admins invariant.** The last `ORG_ADMIN` in an organisation cannot be removed or
  demoted. Azure and Google both enforce exactly this.
- **Every tier-2/tier-3 grant and revoke is a high-severity audit event**, reviewable by the tenant
  admin themselves (MT-2.9).
- **Narrow the CROSS JOIN** per R-2 — an admin role holding *every future permission automatically*
  is a least-privilege anti-pattern independent of tenancy.

**`PLATFORM_OPERATOR` becomes a real role, and `is_root` is re-bounded — in that order.**
A boolean cannot express graduated platform capability: `ScopeGuard.java:646`'s `root ||`
short-circuits everything, so "may provision a tenant" and "may read a tenant's ledger" are
inseparable, which fails separation of duties outright. A role is a row with a granter, a timestamp
and an audit trail; a flag is a column flip. Staged so nothing breaks:

1. **Now — re-bound `is_root`.** Keep the flag and all 24 `.root()` call sites, but make the
   organisation predicate apply **unconditionally, root included** (P3-1, P3-2). `is_root` then means
   *"full authority inside the organisation I belong to"*, not *"crosses tenants"*.
   **This is what dissolves §8's sharpest risk** — once root is organisation-bounded, the
   `setRoot(true)` provisioning shortcut becomes *harmless*, because it makes the new admin powerful
   inside their own tenant, which is what was wanted anyway.
2. **Then — add `PLATFORM_OPERATOR`** as a tier-3 global role, held only by users in the platform
   sentinel organisation, carrying explicit `ORG.*` / `TENANT.*` codes, excluded from the CROSS JOIN.
3. **Then — make crossing into a tenant an explicit, time-boxed, audited support grant** against one
   named tenant, rather than a standing capability. This is the answer to the first hosted buyer's
   *"can your staff see our books?"* — and it is the missing half of D-2 and of §9's support-access
   note.

---

## 2. What we verified

### 2.1 The good news — the company spine is real and reusable

- **182 `company_id` columns and 99 `branch_id` columns across 204 tables.** The scoping spine is
  physically present nearly everywhere.
- **No transactional table needs a new tenant column.** Three lenses re-derived the table census
  independently. Everything lacking `company_id` is the tenant tree itself, a legitimately global
  vocabulary (`permissions`, `currencies`, `processed_events`), or a child of a company-scoped
  parent.
- **Document numbering is already per-tenant** — `code_sequence` and `party_code_sequence` are both
  `UNIQUE (company_id, …)` under `PESSIMISTIC_WRITE`. No cross-tenant number collisions.
- **`G15` — confirmed clean, spend nothing here.** The outbox (`domain_event.company_id` is
  `nullable = false`, handlers establish a system context from the event), both in-memory caches
  (keyed on globally-unique `app_users.id`), POS idempotency (a DB row keyed
  `UNIQUE (company_id, idem_key)`), blob storage (zero filesystem writes in backend main), and
  sequences.

### 2.2 The bad news — two working assumptions were wrong

**Claim A — "only `app_users` and `roles` need a tenant column" — confirmed with one live exception.**
The *classification* holds; the *column list* was short. `audit_logs` needs its own org column
(`G10`) because its `company_id` is NULL by construction on the pre-auth path.

Two further exceptions the audit raised were **retired by the global-username decision** (§1) and are
recorded here only so they are not re-introduced:

- `organisations.code` is no longer required — nothing types a tenant code. It survives in §5.1 as an
  *operational* convenience (support, log filtering, billing), not a login dependency, and it never
  needs to be `NOT NULL`.
- `uq_app_users_email` (V69) — the third global unique the first assessment missed — **is now correct
  as it stands.** Global usernames and global emails are consistent with each other; neither
  constraint is touched. This removes the riskiest DDL from the plan: `app_users` becomes
  purely additive.

**Claim B — "company-scoping already implies org isolation" — REFUTED.**

The *conclusion* survives: no lens found a transactional table needing a new predicate. The
*reasoning* is false five ways, and the reasoning is what would be used to justify skipping work.

> **Accurate restatement, to be used everywhere in place of the original:**
> The 182 company-scoped tables need no new predicate — **for a non-root principal whose company
> membership was not forged.** Both qualifiers do real work.

| # | Break | Evidence |
|---|---|---|
| B1 | `root \|\|` short-circuits the company equality; `is_root` is deployment-global and the JWT carries no org claim | `ScopeGuard.java:646`, `:654`, `PermissionResolver.java:110`, `AuditReadService.java:57`, `JwtService.java:34-46` |
| B2 | The principal's company is chosen per-request from a caller-supplied header, resolved by a global uid lookup, with the assignment check skipped for root | `JwtRequestContextFilter.java:144-154` |
| B3 | The membership premise is writable by the attacker before it is read | `UserCompanyServiceImpl.java:131,134`, `UserServiceImpl.java:280`, `AppUserRepository.java:105` |
| B4 | `app_users` and `roles` carry no company predicate to inherit from | `RoleServiceImpl.java:34-42`, `:142-143` |
| B5 | Creation paths run before any company exists, so no predicate can reach them | `CompanyServiceImpl.java:79-82`, `ScopeGuard.java:636` |

### 2.3 The load-bearing consequence

**The columns are ~10% of the work.** `G1`–`G6` are each *code* that no column delivers — a new
column adds no predicate to a `findByUid`, to a derived query, or to a `root ||` disjunct.

---

## 3. Open decisions — these gate implementation

Nothing in Phase 1 should start until all eight are closed. Record the answer inline and date it.

**Status as of 2026-08-14:**

| Resolved | Still open |
|---|---|
| **D-1** (a) new customers only · **D-2** sentinel org + tier-3 role + re-bounded `is_root` · **D-3** bundles stay global, ceiling rule replaced · **D-7** `<user>@<org-alias>` · **D-9** aggregate-root hedge · **D-10** email scoped to `(organisation_id, email)` | **D-4** RLS backstop · **D-5** per-tenant restore · **D-6** tenant lifecycle · **D-8** per-tenant fiscalisation |

**Six of ten closed, and every decision that gates Phase 1 is now closed** — D-9 and D-10 were the
last two. D-4, D-5, D-6 and D-8 gate Phase 8 and tenant #2, not the schema.

**D-5 is the day-one operational blocker** and **D-8 is a legal gate on onboarding tenant #2 in
Tanzania** — those two are the critical path, not D-4.

Still outstanding as *measurements* rather than decisions: **P0-1c** and **P1-0**, both covered by
[`docs/ops/multitenancy-phase0-measurements.sql`](docs/ops/multitenancy-phase0-measurements.sql).

### D-1 · What happens to the existing customers? — **RESOLVED 2026-08-14: (a) new customers only**

`dist/README.md` documents the shipped product as **one installation per client, with their own
database and their own organisation**; QA and production are separate instances again.

- **(a) New customers only — CHOSEN.** Existing installs stay single-tenant and are never merged;
  the shared instance is a **new deployment**. Existing installs keep receiving the same releases,
  they simply never acquire a second tenant. Migration of an existing customer is offered later, as
  a priced service, once the shared instance has run for two quarters.
- **(b) Merge existing installs.** ~~Rejected.~~ It is not "remap surrogate ids" — it is remap `id`
  **and `uid`**, and `uid` is the *external* identifier. **Twenty-four migrations mint deterministic
  uids from `company_id`** because ULIDs cannot be generated in portable SQL (e.g.
  `V4__units_of_measure.sql:51` `'SEED' || lpad(c.id::text,10,'0') || rpad(u.code,12,'_')`;
  `V14:341`, `V52:179`, `V63:195` and twenty more). Both installs bootstrapped at `company_id = 1`,
  so chart of accounts, GL configs, UoM, tax rates, leave types, notification types, dimensions,
  document templates, PAYE bands, stock locations and cash/bank accounts **collide row-for-row** on
  `uq_<table>_uid`, as do the twelve literal-uid role bundles at `R__seed_permissions.sql:288-299`.
  And `uid` appears in every URL, on printed documents, in POS client state, in **~110 `*_uid` soft
  references across 68 column names with no FK to find them**, and inside four JSONB columns
  (`domain_events.payload`, `audit_logs.detail`, `generated_documents.source_params`, `…tax_summary`).
  Post-merge activity interleaves, so it is irreversible.

Choosing (a) deletes the entire id/uid remap, shrinks the Phase 1 backfill to a single organisation
in a deployment we control, and forecloses nothing.

### D-2 · Platform-root vs tenant-admin — **RESOLVED 2026-08-14**

Today `is_root` is a single deployment-wide superuser flag (24 `.root()` call sites across 14
files). Under org-tenancy it crosses tenants. We need at least two concepts: a **platform
operator** (us) and a **tenant administrator** (the customer's own admin).

This decision determines whether `app_users.organisation_id` can be `NOT NULL` — a platform
operator row needs either NULL or a sentinel organisation — and therefore the exact DDL of §5.1.
**Deciding this after V99 means re-migrating.** See also `G4` and §8.

> **DECISION (ratified 2026-08-14) — see §1.2 for the reasoning.**
> **A sentinel platform organisation, plus a tier-3 `PLATFORM_OPERATOR` role, and `is_root`
> re-bounded rather than removed.** In three stages so nothing breaks:
>
> 1. Keep `is_root` and all 24 `.root()` sites, but apply the organisation predicate
>    **unconditionally, root included**. `is_root` then means *"full authority inside my own
>    organisation"*, not *"crosses tenants"*. This alone dissolves §8's sharpest risk: once root is
>    organisation-bounded, `setRoot(true)` in provisioning is no longer a catastrophe.
> 2. Add `PLATFORM_OPERATOR` (tier 3) on the sentinel organisation with explicit `ORG.*` / `TENANT.*`
>    codes, excluded from the `ORG_ADMIN` CROSS JOIN (R-2).
> 3. Make crossing into a tenant a **time-boxed, audited support grant** against one named tenant,
>    not a standing capability — the answer to *"can your staff see our books?"*.
>
> The sentinel keeps `app_users.organisation_id` `NOT NULL` and every predicate total, avoiding the
> null-org wildcard shape that §0.1 shows is the fail-open route.
>
> **Consequences now fixed for §5.1:** `app_users.organisation_id` **can** be `NOT NULL` in V101 (the
> platform operator belongs to the sentinel organisation, not to NULL). Stage 1 is P3-1 + P3-2 and is
> a **prerequisite of P5-1**, not a parallel task — see §8.

### D-3 · Do the shipped role bundles stay global? — **RESOLVED 2026-08-14: yes, and the ceiling rule changes**

`AuthorityCeiling` permits only root to grant an `is_system` role, and all twelve shipped bundles
(ADR-0057) are `is_system`. So **a tenant admin who is not root cannot grant any shipped role to
their own staff.** Either the bundles stay global and the ceiling rule changes, or they become
per-tenant clones and `is_system` semantics change. This is the pincer described in §8.

> **Effectively forced to "stay global" as of 2026-08-14** — and that matters, because it closes one
> of the two escapes. `roles.organisation_id` cannot be `NOT NULL` (the repeatable seed inserts
> without it, §5.1), and V100's `uq_role_code_global` requires those NULLs to persist. So the shipped
> bundles keep `organisation_id IS NULL`, per-tenant clones are off the table, and **the ceiling rule
> at `AuthorityCeiling.java:103-104` must change** — there is no longer an alternative route.
>
> Which makes §8's pincer sharper, not softer: the tenant admin still cannot grant a CASHIER bundle,
> and `setRoot(true)` is still the one-line fix that makes provisioning "work". **Land P3-1 and P3-2
> before P5-1**, exactly as §8 says.
>
> **The ceiling rule — RESOLVED 2026-08-14.** `assertCanConferRole` no longer refuses a non-root
> caller outright. The new rule, in order:
>
> 1. The grantee must be in the **caller's own organisation** (P3-3 asserts this first, before the
>    membership oracle is consulted).
> 2. A **tier-1 or tier-2** global role may be conferred by a caller who **holds it themselves**, or
>    holds a strict superset of its permissions — ADR-0059's subset + reserved-floor checks survive
>    unchanged; they are the only thing between `ROLE.MANAGE` and self-elevation.
> 3. A **tier-3** role (`PLATFORM_OPERATOR`) is never conferrable by a tenant caller, at any
>    authority level. This is a flat refusal, not a ceiling comparison.
> 4. Tier-2 and tier-3 grants require MFA on the caller (P4-2b) and write a high-severity audit row.
>
> Note what this preserves: the refusal that exists today was *correct in intent* — it stopped
> `ORG_ADMIN` being handed out by someone who did not hold it. Replacing "non-root ⇒ refuse" with
> "must hold it yourself, in your own organisation" keeps that property and makes the product usable.

### D-4 · Row-level security as a backstop — accept or reject deliberately

Zero hits for `ROW LEVEL SECURITY` / `CREATE POLICY` / `current_setting` across all 99 migrations.
Every one of the 182 predicates is application-side only, with **no composite FKs** behind them.
Today a missed predicate leaks inside one client's own installation; afterwards it is a
cross-customer breach. RLS keyed on a session GUC is the standard shared-schema backstop.
Rejecting it is fine — rejecting it by omission is not. **Open.**

### D-5 · Per-tenant backup and restore

`infra/prod/backup.sh` is a whole-database `pg_dump`; `infra/prod/restore.sh` is
`pg_restore --clean --if-exists`, documented as dropping and recreating all objects.
**Restoring one tenant to yesterday destroys every other tenant's day.** Acceptable under
DB-per-client; a day-one operational blocker and a contract question under a shared database.
**Open.**

### D-6 · Tenant lifecycle policy

`organisations` already has `status`, `subscription_plan` and `subscription_status` columns with
**zero readers** outside the entity. What are the rules for suspending a non-paying tenant, and
what happens on departure — export, retain, or delete? There is no export path and no cascade;
deletion today means hand-deleting from 204 tables in FK order. **Open.**

### D-7 · Username convention — **RESOLVED 2026-08-13**: `<user>@<org-alias>`

The collision problem the shared namespace would have created is solved by construction (§1).
Four sub-questions remain, all small but none safe to leave to the implementer:

- **D-7a · Alias format and reserved words.** Proposed: `^[a-z0-9][a-z0-9-]{1,19}$` — lowercase,
  no `@`, no dots, no spaces. Reserve `admin`, `root`, `system`, `api`, `support`. Enforce as a
  `CHECK` constraint (§5.1) *and* in the service, so a seeder cannot bypass it. **Open.**
- **D-7b · Is the alias immutable?** If a customer rebrands, does `jambobora` become `jambo`?
  Renaming rewrites **every username in that tenant**, invalidates saved credentials on every till
  and browser, and orphans the audit trail's readability. **Recommendation: immutable once set**,
  with a display name (`organisations.name`) free to change. **Open.**
- **D-7c · Separator.** `@` reads naturally and matches a well-understood pattern (Windows UPN,
  Azure AD). The cost: `smith@jambobora` *looks* like an email while `app_users.email` is a
  separate column with its own global unique. Two consequences to accept deliberately —
  set `autocomplete="username"` (not `email`) on the login field so password managers don't
  autofill a real address, and **never also enable email-as-login**, or the two namespaces
  collide. Alternatives if that is unwelcome: `smith.jambobora`, `jambobora\smith`.
  **CLOSED 2026-08-14 — `@` it is.** Every worked example, DDL comment and client item in this
  document assumes it; the two consequences above stand as requirements, not options.
  *(Unresolved interaction: **P0-1b**'s `uq_app_users_email` question is the "never also enable
  email-as-login" clause meeting a globally-unique `email` column — the same namespace collision
  arriving from the other side.)*
- **D-7d · Case handling.** Already safe: `AuthServiceImpl.java:79` and `UserServiceImpl.java:75`
  both `toLowerCase()` before the lookup. The DB unique is case-sensitive, so the *only* exposure is
  a write path that bypasses the service (a seeder, a fixture, a manual insert). Confirm no seeder
  does, or add a `LOWER(username)` expression index. **Open — verification, not design.**
- **D-7e · What is the platform operator's username?** *(new — falls out of the composition rule)*
  A platform-operator account may have no organisation at all (D-2), and therefore no alias to
  compose with. Options: a reserved alias such as `@system` that no customer can claim (add it to
  **CLOSED 2026-08-14 by D-2.** The platform operator belongs to the **sentinel platform
  organisation**, whose alias is `system` — so the operator is `admin@system` and needs no exception:
  `app_users.organisation_id` stays `NOT NULL` and every predicate stays total. The reserved-word
  list (D-7a) must therefore reserve `system`. Original options, retained for the record:
  a reserved alias such as `@system` that no customer can claim (add it to
  D-7a's reserved list); or allow suffix-less usernames **only** where `organisation_id IS NULL`,
  which keeps the invariant expressible as a rule rather than an exception. This is small but it
  blocks P5-1 — the provisioning service has to mint the first account of every tenant, and today's
  `rootadmin` has neither an alias nor a tenant. **Open, and coupled to D-2.**

> Resolved by the creation flow (§1): the earlier worry that `"Username already exists"` leaks
> another customer's data no longer applies — the check runs on the composed name, so a collision is
> always within the caller's own organisation.

**Credential standards** (the second half of the original decision): password policy, and whether
`must_change_password` / `password_expires_at` / `mfa_enabled` become live. All three exist as
columns and are **never read on the login path** today. MFA is explicitly deferred — see P2-5, and
note it is also the real mitigation for `G11`.

### D-8 · Per-tenant fiscalisation (EFD / TRA) — **new 2026-08-14; blocks onboarding tenant #2**

`FiscalisationProperties` binds **one JVM-wide `erp.fiscal.provider`**, and
`FiscalisationConfig.java:28-46` selects **one provider bean per process** (its javadoc names
`tra (future)`). But every Tanzanian customer has their own TIN/VRN and their own TRA VFD device
registration. `V82__fiscal_receipts.sql` stores *results* per company (`company_id`, `device_serial`,
`fiscal_number`) — the **provider and its credentials are per-process**.

On a shared instance every tenant would fiscalise through one adapter with one set of device
credentials. That is a legal defect, not a configuration inconvenience. The provider and its
credentials must become **per-tenant data**.

Noted with some irony: this class is the repo's cleanest `@ConditionalOnProperty` + fail-fast
validation pattern and was held up as the model for a runtime tenancy knob — and the template itself
breaks under multi-tenancy. **Open.**

### D-9 · `organisation_id` on aggregate roots — **the last one-way door**

> Raised 2026-08-14. §9 currently rejects this **by omission**, which is precisely the standard this
> plan rightly refuses to accept for D-4. Reject it deliberately or accept it — but decide it.

`organisation_id` exists on **exactly one table**: `companies` (`V1__baseline.sql:44`). Behind it:
205 tables, 608 foreign keys, **zero composite FKs, zero `ON DELETE CASCADE`.**

> #### ⚠ RATIONALE CORRECTED 2026-08-14 — the original justification was wrong
>
> This section previously claimed that without the hedge, every per-tenant operation is *"a 204-table
> topological traversal"* and that per-tenant restore, export and deletion are *"permanently
> bespoke"*. **Measured against the restored production copy, that is false:**
>
> ```
> total tables                : 205
> with company_id             : 182
> WITHOUT company_id          :  23
> ```
>
> and `companies.organisation_id` is `NOT NULL`. So **182 of 205 tables are already ONE JOIN from
> their organisation** — `... JOIN companies c ON c.id = t.company_id WHERE c.organisation_id = :org`.
> Per-tenant export, restore and delete are *awkward* today, not impossible.
>
> The 23 exceptions are the tenant tree itself, the global vocabularies (`permissions`, `currencies`,
> `processed_events`, `flyway_schema_history`) and children of company-scoped parents
> (`product_branch`, `product_components`, `cash_count_denominations`, the `*_branch` link tables,
> `van_reconciliation_lines`). **None of them is an aggregate root, so none is on the D-9 list** —
> the hedge does not address them, and does not need to: they are reachable via their parent.
>
> **What the columns therefore buy is one saved join.** For extraction, that is all.

**What D-9 is actually worth, stated honestly.** Two things, both real:

1. **RLS without a correlated subquery — the significant one, and it couples to D-4.** Without a
   local `organisation_id`, every policy becomes
   `company_id IN (SELECT id FROM companies WHERE organisation_id = current_setting(...))`: a
   subquery on every row-scan of every protected table. This is precisely the cost that makes D-4
   expensive, and the D-9 columns are what removes it.
2. **A partition key**, should tenant-partitioning ever be wanted.

**And the decision still stands, on cost asymmetry rather than on capability:**

- **Now:** metadata-only `ADD COLUMN`, and a backfill measured at **163 ms** for all 31 tables on a
  copy of the live customer's database.
- **Later:** the same backfill is an `UPDATE` touching every row, which **rewrites the heap**. Trivial
  at today's 38 MB; a genuine maintenance window on a three-year-old database, on a system with no
  rollback worth the name.

That asymmetry is why the hedge is still right, and it is a much narrower claim than the one this
section used to make. Do not restate the traversal argument; it will not survive contact with anyone
who runs the query above.

- **(a) Do nothing.** Accept that per-tenant recovery, export and relocation are permanently manual.
  Legitimate — but it must be said out loud to whoever signs the hosted contract.
- **(b) The cheap hedge — `organisation_id` on the ~20 aggregate-root tables only**, not on lines:
  `sales_invoices`, `purchase_orders`, `goods_receipts`, `journal_batches`, AR/AP invoices,
  `stock_move`, `payroll_runs`, POS sales, `products`, `customers`, `suppliers`, and the like. That
  gives one-hop extraction and a partition key if ever needed, without touching 182 tables. It is
  additive, nullable-then-backfilled, and costs nothing on a legacy install where every row shares
  one organisation.
- **(c) All 182 tables.** Contradicts §2.1's headline finding and turns Phase 1 from S–M into L.

**RESOLVED 2026-08-14: (b) the aggregate-root hedge — but DEFERRED out of Phase 1, to be decided with D-4.**

> **⚠ CUT FROM THE PHASE 1 RELEASE 2026-08-14**, on the v2 review's measured evidence. Two reasons:
> **(1) The cost-asymmetry argument does not survive.** It said "backfill 163 ms now, or rewrite the
> heap later" — but nothing populates the columns for new rows until P2-1, so the backfill must be
> re-run later anyway on larger tables. Doing it now adds a second heap rewrite rather than avoiding
> one. **(2) The 31-table boundary is the wrong shape for the surviving rationale.** With extraction
> withdrawn, what remains is an RLS predicate without a correlated subquery plus a partition key —
> both need the column on the relation being scanned. Measured: the 31 chosen tables hold **3,839
> rows / 960 kB**; the excluded company-scoped tables hold **12,373 rows / 3,120 kB**. `journal_lines`
> (1,742 rows) is larger than every included table and is exactly what a GL report row-scans.
>
> Backfilling them would also have made all 31 read "100% attributed" while decaying immediately —
> ~1,150 invoices and ~3,600 stock movements NULL within 30 days at the customer's measured rate,
> with nothing reporting it. **An honestly-empty column beats a falsely-full one.**
>
> The 31 tables were verified sound (all exist, `company_id NOT NULL` on every one, each with a
> validated FK to `companies`) — this is a sequencing and boundary call, not a correctness one.
> **Decide D-9 with D-4 (RLS), the only thing that needs the columns.**

> **Boundary settled 2026-08-14 after attempting to derive it.** "Aggregate root" is **not** a schema
> property: **169 of 205 tables** carry both `company_id NOT NULL` and a `uid`, line tables such as
> `journal_lines` and `sales_invoice_lines` included. The list is therefore a judgement call, made
> once and recorded here: **23 transactional document roots + 8 master-data roots = 31.** Everything
> excluded remains reachable in one further hop through its parent's foreign key. The full list is in
> [`docs/ops/multitenancy-v99-v101-ddl-draft.sql`](docs/ops/multitenancy-v99-v101-ddl-draft.sql), and
> the backfill was verified total on real production data (461/461 invoices, 1,449/1,449 stock
> movements, 601/601 products).

Columns are added **nullable in V99 and left unconstrained in Phase 1**. They are populated going
forward by application code from Phase 2, and existing rows are backfilled by a **bounded background
pass**, exactly like `audit_logs`. They are *not* added to V101's `NOT NULL` set — the entire point of
cutting `audit_logs` out of Phase 1 was to keep the migration window to seconds, and backfilling
twenty transactional tables would give it straight back.

Aggregate roots only — never line tables. Verified against the shipped schema (204 tables):

```
sales_invoices      purchase_orders   goods_receipts    journal_batches
ar_invoices         ar_receipts       ar_credit_notes   ar_write_offs
ap_payments         ap_debit_notes    supplier_bills    supplier_quotes
stock_movements     payroll_runs      products          customers
suppliers           journal_entries
```

> **Naming correction:** the table is `stock_movements`, not `stock_move` as §9's hedge paragraph and
> CLAUDE.md invariant 9 both say. Trust the shipped SQL — see the DB-naming note in the project
> memory. Confirm the POS sales root's real name before authoring; the only POS table matching a
> `pos_*` prefix in the migration line is `pos_sale_idempotency`.

### D-10 · `uq_app_users_email` — **RESOLVED 2026-08-14: scope it to `(organisation_id, email)`**

*(Formerly the unnumbered P0-1b.)* `V69__unique_identifiers.sql:25-27` makes `email` unique across the
whole deployment. Under multi-tenancy that stops one person — a shared bookkeeper, an outsourced
accountant, a group IT admin — from holding an account in two tenants, which is the normal case in
this market, and it leaves the 409 on `POST /users` as a working cross-tenant email-existence oracle.

**Consequence, stated plainly: `app_users` is no longer "purely additive".** §5.1's headline payoff is
reduced — `uq_app_user_username` still stands untouched, but the email unique is now swapped.

**Sequencing, and why it is safe:** the swap must come *after* `organisation_id` is `NOT NULL`, or a
composite unique would permit duplicates while the column is still nullable. So it lands in **V101,
after the `SET NOT NULL`**, in the same transaction:

```sql
DROP INDEX  uq_app_users_email;
CREATE UNIQUE INDEX uq_app_users_org_email
    ON app_users (organisation_id, email) WHERE email IS NOT NULL;
```

**Inert at one organisation** — with a single organisation, `(organisation_id, email)` is equivalent
to `(email)`, so no live install sees any behaviour change. That is the same property the rest of
Phase 1 relies on.

> **⬤ MEASURED 2026-08-14: it is not merely inert, it is a literal no-op.** **Zero users have an
> email** on QA *or* production (`users_with_email = 0` on both). The partial index
> `WHERE email IS NOT NULL` therefore covers no rows at all today, and `uq_app_users_email` is
> currently constraining nothing. Zero risk to apply; the decision is about the shape of the
> namespace going forward, not about existing data.

---

## 4. Phase overview

| Phase | Name | Gates | Size |
|---|---|---|---|
| 0 | Decisions + ADR | §3 D-1…D-8 | S |
| 1 | Schema — **one release**: V99 + V100 + self-sufficient V101 + reconciler | Owner approval of §5.1 DDL | S–M |
| 2 | Principal + tenant derivation | Phase 1 | S |
| **2.5** | **Scope enforcer + parity harness** — *new; gates Phase 3* | Phase 2 | **M** |
| 3 | **IAM scope repair** — the actual security work | **Phase 2.5** | L |
| 4 | Roles per organisation | Phase 3, D-3, org-aware role-code resolution | M |
| 5 | Tenant provisioning + lifecycle | Phase 3, D-6 | M |
| 6 | Clients (web + POS) | Phase 2 | **XL** |
| 7 | Verification | Phases 3–6 | L |
| 8 | Operations | D-4, D-5 | M |

**Phase 2.5 is new as of 2026-08-14** and is not optional: without it, Phase 3 denies seventeen
outbox handlers and silently hides the pre-auth audit trail on installs that will never host a
second tenant (§0.1).

Phases 3 and 6 dominate. **Phase 6 is the single largest item and was invisible until the audit**
(§6). Phase 7 is where schedule risk actually lives — a two-org probe will find things.

---

## 5. The phases

### Phase 0 — Decisions and ADR

- [x] **P0-1** Close the decisions that gate Phase 1 (§3). **Done 2026-08-14** — D-9 and D-10 were the
      last two. D-4, D-5, D-6 and D-8 remain open but gate Phase 8 and tenant #2, not the schema.
- [x] **P0-1b** → renumbered **D-10**, resolved: scope email to `(organisation_id, email)`.
- [x] **P0-1c / P1-0** — **RUN 2026-08-14 on both environments, 19 blocks, zero errors.**
      Script: [`docs/ops/multitenancy-phase0-measurements.sql`](docs/ops/multitenancy-phase0-measurements.sql).
      A fresh backup was taken on each box first (QA via `pg_dump`; production via the supported
      `orbixerp.sh backup`, which also pruned to its 14-day retention).

| Measurement | QA | **Production — "Kilimanjaro"** | Reading |
|---|---|---|---|
| **`organisations`** | 1 | **1** | ✅ the go/no-go gate passes; the single-organisation invariant holds |
| `companies` (orphans) | 4 (0) | 1 (0) | — |
| Flyway | 98 / 98, 0 failed | 98 / 98, 0 failed | both estates on the same schema |
| Postgres | 15.18 | 15.18 | — |
| Database size | 34 MB | 38 MB | Phase 1's window is milliseconds, not minutes |
| `app_users` (root) | 13 (1) | 12 (1) | — |
| **users with an email** | **0** | **0** | **D-10's index swap is a literal no-op** |
| `roles` | 21 = 13 sys + 8 custom | 15 = 13 sys + 2 custom | exactly the 13 shipped system roles on both |
| **`is_system` rows with a non-seed uid** | **0** | **0** | ✅ the seed has adopted nothing yet — nothing to clean up |
| `audit_logs` | 4,162 / 2.3 MB | 6,265 / 3.4 MB | small on both |
| **`ROOT.BYPASS` share** | **1,161 = 28%** | **not in the top 15** | see the correction in §5.1 |
| audit rows unattributable | 25 | **36** | NULL-tolerance is a live requirement, not theoretical |
| **rows COALESCE adds over actor-only** | +86 | **+545 (8.7%)** | validates the §5.1 step-3 key correction |
| derivation pass (a) / (b) / (c) / (d) | 12 / 0 / 0 / **1** | 12 / 0 / 0 / **0** | on production the sole-org fallback carries **nobody** |
| conflicting-org users | 0 | 0 | — |
| `user_branch` revoked / inactive | 0 / 0 | 0 / 0 | ✅ **H-5 is safe to ship** — nobody is working through a revoked row |
| **app role holds UPDATE+DELETE on `audit_logs`** | **yes** | **yes** | ❌ the append-only invariant was never applied — **P8-9 confirmed on a live client** |
| Phase 1 write volume | 13 users / 21 roles / 1 org | 12 / 15 / 1 | trivial |

> **Production topology, discovered while measuring and worth recording:** the live client runs
> **`orbixerp-api:1.6.1` from a `dist/` bundle with NATIVE HOST Postgres** (`ERP_DB_MODE=host`),
> installed at `/opt/orbixerp` — *not* the `infra/prod` compose topology. **So the offline-estate
> analysis in §5.1 and §10 applies to a real paying customer, not hypothetically:** `cmd_update`'s
> missing version-ordering check, the absent repair path, and H-12/H-13 are all live concerns for
> this box. (H-11's broken `OrbixERP.cmd` is Windows-only, so it does not affect this Linux install.)
> Automated backups are running there daily and retained 14 days.
- [ ] **P0-2** Write the ADR. It **supersedes ADR-0001 D-A** (roles org-wide), which was correct
      when there was one organisation. **Next free number is ADR-0062** — 0060
      (`sale-at-or-below-cost-policy`) and 0061 (`pos-tls-trust-private-ca`) are already taken, so the
      plan's earlier "ADR-0060+" was stale. It must carry §1.2's four-tier
      classification and the two rules R-1 / R-2 verbatim — they are the part most likely to be
      re-derived incorrectly from memory.
- [ ] **P0-3** Record the D-2 root model in the ADR explicitly, **including that P3-1 and P3-2 are
      prerequisites of P5-1**. §8's resolution is conditional on those having landed; until then
      `setRoot(true)` is exactly as dangerous as §8 originally described. This is the decision most
      likely to be silently reversed later by an implementation shortcut.
- [ ] **P0-4** Record the D-3 ceiling rule as an amendment to **ADR-0059** (grant ceiling), not only
      in the new ADR — an implementer reading ADR-0059 alone would restore the blanket
      "non-root ⇒ refuse" behaviour and break every tenant admin.

### Phase 1 — Schema

> **Nothing here has been authored.** Per the standing rule, the DDL below is *presented for
> approval*; no `V99`/`V100`/`V101` file exists. Latest applied migration is **V98**.
> Rules that apply: additive only, never edit an applied migration, expand → backfill → constrain,
> `CREATE INDEX CONCURRENTLY` in its own non-transactional migration, DB never wiped in any
> environment.

#### 5.1 Proposed DDL — **requires owner approval before authoring**

> ## ⚠ SUPERSEDED 2026-08-14 — approve the draft file, not this section
>
> **The statements below are the v1 shape and are NOT what would be authored.**
> The approvable artefact is
> **[`docs/ops/multitenancy-v99-v101-ddl-draft.sql`](docs/ops/multitenancy-v99-v101-ddl-draft.sql)**,
> revised after two adversarial reviews and tested against a restored copy of the live customer's
> database. Approving this section would approve one set of statements while a different set ships.
>
> **What the draft changes versus everything written below:** the two foreign keys are removed (they
> broke `pg_restore --clean`); every statement is replay-safe; `SET NOT NULL`, the column DEFAULT and
> the stored function are all gone (the release is expand + backfill only, constraining moves to
> P2-1); `SET LOCAL lock_timeout` opens each file; the D-9 list is **31 tables, backfilled in V101**;
> `ix_audit_logs_org_at` is dropped and the company index renamed; and the V101 tripwire is a
> `RAISE WARNING` preceded by an unconditional summary.
>
> The text below is retained only as the record of how the design got here.

> **Simplified 2026-08-13 by the global-username decision (§1).** `app_users` is now **purely
> additive** — no unique constraint on it is dropped or replaced. Only `roles` needs a uniqueness
> change, because role codes are a small vocabulary where cross-tenant collisions are near-certain
> (every customer wants a `SUPERVISOR`), unlike usernames where a shared namespace is workable.

**V99 — expand.** All nullable; no constraint on populated data. **Every constraint added `NOT
VALID`** — an immediate `ADD CONSTRAINT ... FOREIGN KEY` scans and locks both tables, which on a
live install is an outage for the duration.

```sql
-- The tenant alias. Named `alias`, not `code` — `code` is already taken on companies,
-- branches and roles, and this one is user-facing.
ALTER TABLE organisations ADD COLUMN alias VARCHAR(20);
ALTER TABLE app_users     ADD COLUMN organisation_id BIGINT;
ALTER TABLE roles         ADD COLUMN organisation_id BIGINT;   -- NULL = shipped/global role
ALTER TABLE audit_logs    ADD COLUMN organisation_id BIGINT;   -- G10; nullable, no FK

-- NOT VALID: registered for new rows immediately, existing rows validated separately in V101.
ALTER TABLE app_users ADD CONSTRAINT fk_app_user_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisations (id) NOT VALID;
ALTER TABLE roles     ADD CONSTRAINT fk_role_organisation
    FOREIGN KEY (organisation_id) REFERENCES organisations (id) NOT VALID;

-- D-7a. NOT VALID so it cannot fail on existing rows; VALIDATE in V101 once the
-- backfill has populated every alias.
ALTER TABLE organisations ADD CONSTRAINT ck_organisation_alias
    CHECK (alias ~ '^[a-z0-9][a-z0-9-]{1,19}$') NOT VALID;

-- NOTE: the two audit_logs indexes that were briefly specified here have been REMOVED.
-- See the "audit_logs is out of Phase 1" box below. The ADD COLUMN above stays — it is
-- metadata-only and free.
```

> #### ⚠ `audit_logs` is OUT of Phase 1 — **but the urgency was overstated; see the measured note below**
>
> Phase 1's unbounded work was never V101. It was **two `CREATE INDEX` on `audit_logs` plus reconciler
> step 3's whole-table `UPDATE`** — all of it inside the window a health check is judging.
>
> `audit_logs` is the fastest-growing, never-purged table in the schema, and the concern was that it
> is inflated well beyond ordinary audit volume: `ScopeGuard.java:677` writes a `ROOT_BYPASS` row on
> **every** root scope assertion across 763 `assertCanActIn` sites — and on a client box the shop
> owner **is** the root admin (`BootstrapRunner.java:137`). There is no purge path anywhere.
>
> > **⬤ MEASURED 2026-08-14 — the inflation is a QA artefact, not a production reality.**
> > QA's audit log is **28% `ROOT.BYPASS`** (1,161 of 4,162). **Production has none in its top 15
> > actions** — it is dominated by real business activity (`SALES.INVOICE.LINE.ADD` 816,
> > `PRODUCT.PRICE.SET` 663, `GL.JOURNAL.POST` 555, `POS.SALE.FINALISE` 460). At **6,265 rows /
> > 3.4 MB**, both indexes build in milliseconds and the whole table would rewrite in seconds.
> >
> > **So this cut is no longer justified by evidence — it is hygiene, not necessity, and the two
> > indexes could safely ride in V99 after all.** Keeping them out is still defensible (it keeps the
> > migration minimal and the index is a live win worth shipping on its own as H-8), but the
> > reasoning must not be restated as "the table is huge". It is not, on this client, today.
> >
> > **What would change that:** a box where the customer's admin is root *and* transacts heavily.
> > Kilimanjaro's single root user evidently does not. Re-measure before assuming it holds elsewhere.
>
> On the offline estate every escape from a long boot is missing: `wait_healthy` is called with no
> argument so the timeout is a hard-coded 900s (`orbixerp.sh:143`, `:377`); no health knob exists in
> the script's entire `env_get` surface, and `.env` is explicitly never merged by `cmd_update`, so one
> cannot be delivered by a future release either; the container flips to `unhealthy` at ~330s
> (start_period 180s / interval 30s / retries 5) **while the backfill is succeeding**, and
> `container_health` returns a state `wait_healthy`'s case statement does not handle, so it spins
> silently to 900s and dies. `TROUBLESHOOTING.md:104` then sends the customer to `restore`, which
> destroys every committed chunk and re-runs the whole thing into another 900s window — with
> `ERP_VERSION` already advanced. A treadmill entered by following the manual.
>
> **What justifies cutting it:** §5.1 already concedes that pre-auth and system-written rows stay
> NULL **permanently**, and that P3-8's predicate must tolerate NULL regardless. If the predicate has
> to be NULL-tolerant anyway, a backfilled column buys nothing the tolerant predicate does not already
> require.
>
> **Keep** `ALTER TABLE audit_logs ADD COLUMN organisation_id BIGINT` (metadata-only).
> **Defer** the index build (→ **H-8**, a genuine live-production win in its own right) and any
> derivation to an explicitly bounded, resumable, **post-readiness background pass that logs
> progress** — never inside a window a timeout is judging.
>
> With `audit_logs` removed, Phase 1's remaining work is `app_users` (tens of rows), `roles` (~15) and
> `organisations` (1). Milliseconds on every box in the estate; the timeout risk disappears rather
> than being documented around.
>
> **Measure before authoring V99:** `SELECT count(*), pg_total_relation_size('audit_logs')` on a
> restored production dump. That number decides whether this is urgent or merely prudent, and it is
> the one claim here that is inferred rather than read off the schema.

`app_users.username` is `VARCHAR(80)` — ample for `smith@jambobora` (a 40-char local part plus a
20-char alias is 61). **No column widening needed.**

**Backfill — app code, not SQL** (standing rule: provisioning over data migrations). An idempotent
every-boot reconciler:

1. Set `organisations.alias` for the single existing organisation (derived from its name, then
   validated against D-7a). **The derivation must be total and must never throw** — a slugified name
   can yield 1 character, empty, or a 20-char truncation ending in `-`, all of which fail
   `ck_organisation_alias` (min length 2, no trailing-hyphen exclusion). Throwing means production
   does not boot; skipping means the alias stays NULL forever **and `VALIDATE CONSTRAINT` still
   passes**, because NULL is not FALSE — so nothing would ever detect that the uniqueness partition
   every future tenant username depends on was never set. Require an explicit `org-<id>` fallback and
   a log line.
2. Stamp every existing user with that organisation, and every role that is not a shipped bundle.
   **Do not classify by `is_system`** — see the box below; it is already unreliable on live data.
   Classify by the hard-coded seed uid list (`V1__baseline.sql:289`, `R__seed_permissions.sql:288-299`)
   and **log every `is_system = true` role whose uid is not a seed uid** for owner review: those are
   customer-authored roles that the seed has already adopted.
3. ~~Derive `audit_logs.organisation_id`.~~ **REMOVED from Phase 1 2026-08-14** — see the box above.
   When it is eventually done as a background pass, the key is
   **`COALESCE(company_id → companies.organisation_id, actor_user_id → app_users.organisation_id)`**,
   not the actor alone. Actor-only derivation discards the entire system/outbox trail — the GL and
   stock rows — where the actor is NULL but `company_id` is present and `companies.organisation_id`
   is `NOT NULL` (`V1__baseline.sql:44`). It is also wrong on principle: the actor's organisation is a
   mutable present-tense attribute being applied retroactively to an append-only ledger, so on the
   shared instance a platform operator's actions inside a tenant would be stamped with the *sentinel*
   organisation, and P3-8 would then hide vendor activity from the very customer whose data was touched.
4. ~~Rewrite every existing username to `<username>@<alias>`.~~ **DELETED 2026-08-14** — legacy bare
   usernames are never rewritten (§0.2). This removes the only deliberate-outage item in Phase 1.

> #### ⚠ `is_system` is not a reliable discriminator, and there is no other one
>
> `R__seed_permissions.sql:301` ends `ON CONFLICT (code) DO UPDATE SET … is_system = true`, with the
> conflict target `uq_role_code` (global). So **any customer-created role whose code collided with a
> bundle code was already flipped to `is_system = true`** and had the shipped grants unioned onto it.
>
> No fallback exists: `Role.createdBy` (`Role.java:57-58`) is **never set** — `RoleServiceImpl.create()`
> does not set it, there is no `setCreatedBy` call anywhere in IAM, and there is no
> `@EnableJpaAuditing` in the codebase. It is NULL for shipped and customer roles alike.
>
> Stamping such a role `organisation_id = NULL` makes it **global** — visible and grantable in every
> tenant under P3-5 / P4-1. The mirror case is worse: on the shared instance, a future seed edit
> adding a code a tenant already uses flips *that tenant's* role to `is_system = true` while leaving
> `organisation_id = <tenant>`, unioning shipped permissions onto a role their admin never granted —
> and `RoleServiceImpl` blocks update, `setPermissions` and archive on system roles, so it is
> **undeletable**.
>
> **Therefore P1-6 (fix the seed so it stops setting `is_system = true` on conflict) is a
> PREREQUISITE of the role stamping, not a "ship it whenever" follow-up.**

> #### ⚠ The reconciler template is not safe to copy as-is
>
> `UserCompanyBackfill.java:38-40,59-101` — the stated model — is one `@Transactional` method doing
> `findAll()` over all users and role grants with an N+1 per user, no advisory lock and no leader
> election; and as an `ApplicationRunner` (`@Order(20)`) it fires **after Tomcat is already accepting
> traffic**. Two instances during a rolling restart both compute the same write, one commits, the
> other violates a unique constraint — and because it is a single transaction, that one conflict
> rolls back the entire backfill including the alias. `AppUser` also carries `@Version`, so the same
> path yields `OptimisticLockException`.
>
> Minimum bar for the new reconciler, **corrected 2026-08-14**:
>
> - **`pg_advisory_xact_lock`, not `pg_try_advisory_lock`.** The latter is **session**-scoped: taken
>   on a pooled Hikari connection inside a `@Transactional` method it is never released and leaks for
>   the life of the process. The transaction-scoped form auto-releases.
> - **No clean skip that returns normally.** A silent skip flips readiness to accepting traffic with
>   nothing done, and the container reports healthy — and `orbixerp.sh status` is the customer's
>   entire vocabulary for success.
> - **Chunked** transactions (~1k rows) so a crash mid-run is resumable, with a **per-row guard in the
>   update predicate** rather than a per-run flag, so a partial run is always safe to repeat.
> - Run **before** the connector binds (a `SmartInitializingSingleton` / context initializer, not an
>   `ApplicationRunner`). *(Correction: the template's javadoc claim of "Order 20 vs 10" is false —
>   `BootstrapRunner` carries no `@Order` at all, so it runs last.)*
> - **Do not gate it on `ERP_BOOTSTRAP_ENABLED`.** `cmd_update` force-writes that flag false on every
>   update (`orbixerp.sh:373`) and `BootstrapRunner.java:93` short-circuits on it — a reconciler
>   sharing that gate would never run again on any updated box.
> - **Log residual NULL counts per table** after its own pass, not rows visited. The residual is the
>   number anyone actually needs.
> - It **remains ungated and runs every boot even after V101**. A partial `pg_restore` boots
>   (`cmd_restore` downgrades errors to `warn`, `orbixerp.sh:311-313`), and once history says V101 is
>   applied the reconciler is the only healer left.

> #### ✅ Retired 2026-08-14 — the username-rewrite outage
>
> The former step 4 broke every saved login on web and on every POS till, the `dev` `rootadmin`,
> `ERP_BOOTSTRAP_*`, the QA/prod env files, ~140 test files and the user-manual screenshots — all at
> once, on a durable database, in every environment. **It is gone** (§0.2): legacy bare usernames
> remain valid indefinitely and only new tenants get a composed username.
>
> This is *not* the "ambiguity-tolerant login" this plan rejects. Nothing is resolved or
> disambiguated — `findByUsername("jkomba")` looks up the literal string `jkomba`, exactly as today.
> The rejection stands and is unchanged: a bare name submitted against the shared instance, where no
> such user exists, simply fails.

**V100 — unique indexes. Plain transactional `CREATE UNIQUE INDEX` — NOT `CONCURRENTLY`.**

> **Why not `CONCURRENTLY`:** this repo has **no non-transactional migration wiring**, and five
> migrations say so in their own headers — `V78:7-9` (*"Chosen over CREATE INDEX CONCURRENTLY because
> this repo has no non-transactional migration…"*), `V81:7-8` (*"there is no .conf /
> executeInTransaction wiring"*), and the same disclaimer verbatim in `V82:9`, `V83:8`, `V84:13`,
> `V85:8`. `grep CONCURRENTLY` across all 99 migrations returns **only those comments — zero
> executions.** Flyway wraps each migration in a transaction on Postgres, so this would fail at boot
> with SQLSTATE 25001 and leave a failed row in `flyway_schema_history` plus a possible `INVALID`
> index to drop by hand. `roles` is ~15 rows in every environment; the brief lock is nothing.

```sql
-- Split in two: Postgres treats NULLs as distinct, so a plain composite
-- (organisation_id, code) would permit duplicate GLOBAL role codes.
CREATE UNIQUE INDEX uq_role_code_global
    ON roles (code) WHERE organisation_id IS NULL;
CREATE UNIQUE INDEX uq_role_org_code
    ON roles (organisation_id, code) WHERE organisation_id IS NOT NULL;

-- The alias is the uniqueness partition for every NEW tenant's usernames.
CREATE UNIQUE INDEX uq_organisation_alias
    ON organisations (alias) WHERE alias IS NOT NULL;
```

> #### ⚠ As specified, the two role indexes are INERT — resolve this before authoring V100
>
> `uq_role_code UNIQUE (code)` is deliberately **retained** (see the second ⚠ box below, protecting
> `ApprovalEngineImpl:301` and `StepApproverResolver:78-84`). But retaining it means org-scoped role
> codes must *still* be globally unique — so `uq_role_org_code`'s entire stated purpose ("every
> customer wants a `SUPERVISOR`") is defeated and the second tenant still gets a 409.
>
> The two facts are individually right and jointly contradictory. **The resolution is a sequencing
> one, and it is P4-1c:** make role-code resolution org-aware at the three approvals call sites
> first, *then* drop `uq_role_code`, and only then do these indexes carry uniqueness.
>
> Until that lands, V100 buys nothing for roles while carrying all the risk of the `is_system`
> misclassification above. **Two defensible options:** author V100 now for `uq_organisation_alias`
> only and defer the role indexes to sit alongside the `uq_role_code` drop; or author all three and
> accept they are dormant. **Recommend the former** — a dormant unique index invites someone to
> "clean up" the redundant old constraint without knowing what it protects.

**V101 — constrain, and self-sufficient.** Ships in the **same release** as V99 and V100
(see the box below — the two-release split was withdrawn 2026-08-14). It carries its own convergent
backfill for `app_users.organisation_id` so it succeeds whether or not the reconciler has ever run,
plus a column DEFAULT so the constraint cannot be re-broken by ordinary user creation. Note what is
*no longer here*: `roles.organisation_id SET NOT NULL` and the `uq_role_code` drop, both of which
break live single-tenant production — see the boxes below.

```sql
ALTER TABLE organisations VALIDATE CONSTRAINT ck_organisation_alias;
ALTER TABLE app_users     VALIDATE CONSTRAINT fk_app_user_organisation;
ALTER TABLE roles         VALIDATE CONSTRAINT fk_role_organisation;

-- Convergent, idempotent, and total on any install holding exactly one organisation.
-- Derivation order, most authoritative key first; each pass only touches rows still NULL.
--   (a) user_company  -> companies.organisation_id
--   (b) user_branch   -> branches -> companies.organisation_id
--   (c) user_role     -> companies.organisation_id
--   (d) the sole organisation, where the count is exactly 1
-- Pass (d) is not a fallback of last resort but the main path today:
-- UserServiceImpl.java:112-116 returns early when the creator is ROOT, and on every
-- single-tenant install the customer's own admin IS root (BootstrapRunner.java:137),
-- so a root-created user has no company, no branch and no grant.
-- <full SQL to be drafted with the migration; see the obligations list below>

-- A tripwire, not a convenience: refuse rather than guess if any row is still unattributable.
-- Unreachable on a shipped install (see the single-organisation invariant below), which is
-- exactly why it must be loud if it ever fires.

-- D-2 resolved: the platform operator belongs to the sentinel organisation, not to NULL,
-- so this is safe and every org predicate stays total (no null-org wildcard — §0.1).
ALTER TABLE app_users     ALTER COLUMN organisation_id SET NOT NULL;

-- D-10. MUST come after the SET NOT NULL above: a composite unique would permit
-- duplicates for every row whose organisation_id is still NULL. Inert at one
-- organisation, where (organisation_id, email) is equivalent to (email).
DROP INDEX  uq_app_users_email;
CREATE UNIQUE INDEX uq_app_users_org_email
    ON app_users (organisation_id, email) WHERE email IS NOT NULL;
```

**The single-organisation invariant this rests on was verified independently, twice:**
`new Organisation(` occurs **exactly once** in `backend/src/main/java`
(`BootstrapRunner.java:103`), guarded by `if (organisations.count() > 0) return;` (`:93-97`);
`OrganisationController` has **no** `@PostMapping`/`@PutMapping`/`@PatchMapping`/`@DeleteMapping`;
`OrganisationServiceImpl:21-34` exposes only `current()` and `list()`; and **no migration anywhere
contains `INSERT INTO organisations`**. The count guard — not `ERP_BOOTSTRAP_ENABLED` — is what makes
`BootstrapRunner` unable to create a second organisation even if it runs twice. So on every shipped
database there is exactly one legal value, and the backfill cannot choose a wrong one.

> **Standing-rule tension, named deliberately.** *Provisioning over data migrations* says fix missing
> data in app code, not in Flyway. This is a genuine exception and should be recorded in the ADR as
> one. The rule exists to stop brittle backfills that guess; here the derivation is **total and
> unambiguous** (one legal value), and the alternative is a migration that bricks a customer's
> machine. The app-side reconciler is **not** replaced — it still runs every boot, still owns the
> alias and the roles, and is the only healer after a partial restore. Migration approval applies.

> #### ⚠ The DEFAULT is temporary and must be removed on a date, not by omission
>
> The column DEFAULT that keeps the constraint satisfied is **behaviour living in the schema**,
> invisible to anyone reading `AppUser.java`. It counts all `organisations` rows regardless of
> `status`, which is fail-safe for an ARCHIVED tenant but **fail-open if a tenant organisation is ever
> hard-deleted** — the count drops back to 1 and silent defaulting silently re-enables on a
> multi-tenant box.
>
> **Drop it in the same migration that maps `AppUser.organisation` (P2-1)** — not "eventually". Name
> it in the V101 header, in `DATA-MODEL.md`, and in the P2-1 work item.

`organisations.alias` stays **nullable**: under §0.2 the pre-existing organisation on a legacy
install has no functional need of one, and forcing it would resurrect a client-visible change for
installs that will never host a second tenant. It is `NOT NULL` *in effect* for every tenant minted
by `TenantProvisioningService` (P5-1), enforced in the service and by `uq_organisation_alias`.

> #### ⚠ The two-release split — **WITHDRAWN 2026-08-14.** One release, self-sufficient V101.
>
> The original reasoning was sound as far as it went: Flyway runs during context refresh and the
> reconciler runs after, so a `SET NOT NULL` in the same deployable as an app-code backfill fails on
> first boot. The conclusion drawn from it — ship V101 as a second release — does not survive.
> **Four reasons, in descending force. The first applies even on two deployments you fully control.**
>
> **1 · The window between the releases manufactures the very NULLs the gate checks for.**
> `AppUser` has no organisation field until P2-1, so both insert sites — `UserServiceImpl.java:81` and
> `BootstrapRunner.java:133` — omit the column, and the reconciler is a boot-time pass. Verify zero
> NULLs on Monday; the customer adds three staff by Friday; release 2's `SET NOT NULL` fails. This is
> a data problem, not a deployment one, and controlling both boxes does not help. **The column DEFAULT
> in V101 is the only mechanism that closes it.**
>
> **2 · Every pre-R1 backup becomes permanently unrestorable the moment a box moves to R2.**
> `cmd_restore` (`orbixerp.sh:308-313`) runs `pg_restore --clean` over a full dump that **includes
> `flyway_schema_history`**, so a restore reverts the migration history. A box on R2 restoring any
> pre-R1 dump then re-runs V99→V101 in one context refresh with no reconciler between them → 23502 →
> crash-loop, with `restore` as the only recovery command. Nothing in the product or the docs says so.
>
> **3 · On the offline estate the ordering cannot be enforced at all.** `cmd_update` has **no
> version-ordering check, no minimum-version gate and no monotonicity check** — the sole refusal is on
> CPU architecture (`orbixerp.sh:339`). The "Updating from X to Y" line at `:332` is a display string;
> nothing compares the two values. Multi-version hops are already normal (1.4.2 → 1.4.3 applies
> V94–V98 in one boot), so a hop spanning V99→V101 looks like every previous update. And any gate
> added would ship in the **installed** script, which `cmd_update` replaces *last* (`:381-389`) — so it
> could not protect a single box already in the field.
>
> **4 · The confirmation channel does not exist.** There is no telemetry egress anywhere in the
> product, no register of who holds which version, and release numbers do not track schema versions
> (1.1.0, 1.2.0, 1.3.0 and 1.3.1 are all the same commit). "Verify zero NULLs in every environment in
> between" is unexecutable on any box the vendor cannot reach.
>
> **What replaces it:** one bundle, one command, from any prior version, with no ordering instruction
> for anyone to forget. The vendor-side two-step (QA → verify → prod) remains good *practice*; it is
> no longer a *correctness dependency*.
>
> **The one ordering rule that survives is vendor-side and enforceable in CI:** `OrganisationController`
> must gain no write mapping while any box may still be pre-V101 — i.e. "do not ship that feature
> yet", assertable in the build rather than by telephone.

> #### ⚠ Two items REMOVED because they break live single-tenant production
>
> **1. `roles.organisation_id SET NOT NULL` is mutually exclusive with the seed it must ship beside.**
> `R__seed_permissions.sql:287` is `INSERT INTO roles (uid, code, name, description, is_system)` —
> **no `organisation_id`** — and Postgres checks NOT NULL *before* the `ON CONFLICT` arbiter. The
> repeatable seed would then fail on **every boot of every environment, live production included**.
> V99's own comment (`NULL = shipped/global role`) and V100's `uq_role_code_global … WHERE
> organisation_id IS NULL` both *require* those NULLs to persist. The column stays nullable, which
> also settles **D-3 in favour of "the shipped bundles stay global"**.
>
> **2. Dropping `uq_role_code` changes approvals semantics at `organisations.count() == 1`.** Once
> P4-1 makes role creation org-scoped, a customer's own admin can create a role coded `CASHIER` — a
> shipped bundle code (seed line 288). Then `ApprovalEngineImpl.java:301` `roleRepo.findByCode(code)`
> over two rows throws `IncorrectResultSizeDataAccessException` (a 500 on approval-step display),
> `ApprovalPolicyServiceImpl.java:189` `existsByCode` becomes ambiguous, and
> `StepApproverResolver.java:78-84` matches on the code **string**, so a holder of the global
> `CASHIER` satisfies a step routed to the tenant's own — an **approval-authorisation widening**.
> `uq_role_code` therefore **stays** until role-code resolution is made org-aware across those three
> call sites, and that work is a prerequisite for P4-1, not a follow-up.

**`app_users` carries no constraint change in any of the three migrations** — `uq_app_user_username`
and `uq_app_users_email` remain exactly as shipped. That is the whole payoff of the global-username
decision, and it holds under the `@alias` convention because the suffix supplies the partitioning
that a composite key would otherwise have had to.

> #### ⚠ …with one exception that must be decided, not inherited: `uq_app_users_email`
>
> §2.2's claim that this constraint is "correct as it stands" holds for **usernames**, which the
> alias partitions. It does **not** hold for **email**, which is a separate column with its own
> global unique — `V69__unique_identifiers.sql:25-27`, whose own header says *"org-wide … unique
> across the whole deployment"*, written when "org-wide" meant "the whole product".
>
> On the shared instance a shared bookkeeper, outsourced accountant or group IT admin working for
> two customers **cannot be given an account in the second one**, and the 409 is a working
> cross-tenant email-existence oracle — the same enumeration hole the composed-username rule closed,
> on a different field. It becomes an account-takeover primitive the day self-service password reset
> ships (there is none today; the only path is `POST /users/uid/{uid}/password` behind `USER.MANAGE`).
>
> Scoping it to `(organisation_id, email)` means **`app_users` is not purely additive after all** and
> V100 is not roles-only. That is a material change to this plan's stated payoff. **Decide in
> Phase 0.** Note it costs nothing on a legacy install, where every row shares one organisation.

> #### ✅ `G8`'s deploy brick — retired 2026-08-14, by not dropping the constraint
>
> `R__seed_permissions.sql:301` ends `ON CONFLICT (code) DO UPDATE`, whose conflict target requires a
> unique constraint on `(code)` alone. Because `uq_role_code` now **stays** (see above), the
> repeatable seed keeps working unchanged and there is no same-deployable coupling to manage.
>
> A second, subtler brick is retired by backfill step 2 stamping roles **except `is_system`**: had the
> shipped bundles been given an `organisation_id`, they would no longer match `WHERE organisation_id
> IS NULL`, the re-targeted upsert would match nothing, and the statement would fall through to
> `INSERT` — with the hard-coded literal uids at `R__seed_permissions.sql:288-299`, violating
> `uq_role_uid` (`V1__baseline.sql:242`), which V100 never touches. That failure would have landed on
> a *later* deploy than the one that passed QA.
>
> Still to fix while we are in there, independent of tenancy: the same upsert sets `is_system = true`,
> so a tenant's custom role whose code collides with a shipped one is silently adopted as a system
> role and has the shipped grants unioned onto it — widening their users' authority.

- [ ] **P1-0** **Measure first, author second.** On a restored production dump:
      `SELECT count(*), pg_total_relation_size('audit_logs')`; the row counts each derivation pass
      (a)–(d) would carry; the count of `is_system = true` roles whose uid is not a seed uid; and the
      count of audit rows with `company_id` non-null vs actor non-null. Three of this section's
      decisions rest on numbers nobody has looked at.
- [ ] **P1-1** Get §5.1 approved (migration-approval rule). **P1-2** Author V99. **P1-3** Backfill
      reconciler, hardened per the corrected box above. **P1-4** Author V100 — alias index now, role
      indexes with the `uq_role_code` drop (see the inert-index box). **P1-5** Author V101,
      self-sufficient, **same release as P1-2/P1-4**.
- [ ] **P1-6** **PREREQUISITE of P1-3's role stamping** — fix `R__seed_permissions.sql:301` so the
      upsert stops setting `is_system = true` on conflict, and log already-adopted customer roles for
      owner review. No longer a "ship it whenever": without it the reconciler has no reliable
      discriminator and can publish a customer's role to every tenant. Migration-approval applies.
- [ ] **P1-7** ~140 test files call `new Organisation(...)` (174 sites). Only `app_users` gains a
      `NOT NULL`, so the break is smaller than first budgeted — but **convert one representative file
      before estimating the rest**; the figure has never been converted from a grep count to
      engineer-time (§11 G-A).
- [ ] **P1-8** **Three things to prove on the restored dump before authoring**, all currently
      inferred. ⬤ **The stack is ready** — [`docs/ops/rehearsal-stack.md`](docs/ops/rehearsal-stack.md),
      restored from Kilimanjaro production, 205 tables, Flyway v98, `organisations = 1`, all objects
      owned by the `erp` app role so migrations run with the privileges they really have. The third
      item below is **already answered by Stage A**: the append-only grant was never applied on either
      estate, and the rehearsal stack reproduces that, so there is no `permission denied` risk.
      Remaining to prove: that the DEFAULT function sees the IDENTITY-flushed `Organisation` inside
      `BootstrapRunner`'s own transaction (`UidEntity.java:24-25` is `GenerationType.IDENTITY`, so the
      INSERT is issued immediately — verify, don't assume); that Flyway's parser handles the first
      `DO $$ … $$` body in a 98-migration line; and that no deployment has ever applied the
      INSERT/SELECT-only grant on `audit_logs` that ADR-0004 D-5 documents — if any has, an audit
      backfill fails `permission denied` on every boot. *(No `GRANT`/`REVOKE` was found anywhere in
      `infra/`, `dist/` or the migrations — see §10 H-9 — but "not in the repo" is not "not applied".)*
- [ ] **P1-9** **CI assertion:** `OrganisationController` has no write mapping. That is the one
      ordering rule that survives the withdrawal of the two-release split, and it is enforceable in
      the build rather than by procedure.

### Phase 2 — Principal and tenant derivation

> Global usernames make this the *smallest* phase rather than a rewrite. **No login contract
> changes**: `LoginRequest` keeps `{username, password}`, `findByUsername` stays a global
> single-result lookup, and `G6`'s three `IncorrectResultSizeDataAccessException` failure modes
> **never occur** — the constraint that would have produced them is never dropped.

- [x] **P2-1** DONE 2026-08-14. `organisationId` into `RequestContext.Principal` and the JWT (`JwtService.java:34-46`),
      populated from the authenticated `app_users.organisation_id`.
      **Prerequisite for all of Phase 3.**
- [x] **P2-2** DONE. Nothing on the login request. Confirm by test that the tenant is *never* readable from
      caller input — this is the property that makes global usernames safer than a typed code, and
      it is worth an explicit regression test so a future "convenience" parameter cannot re-open it.
      **Includes the string:** assert that no production code path splits a username on `@` to
      resolve scope. The suffix is a naming convention (§1), and treating it as data would hand an
      attacker a tenant selector in the one field they fully control.
- [ ] **P2-2b** STILL OPEN - needs an alias to exist before it has anything to check. Enforce the **suffix ↔ FK invariant** at user creation and at any alias change: the
      text after `@` must equal the organisation's alias. There are now two representations of a
      user's tenant — the string and `organisation_id` — and they can drift. `organisation_id` is
      authoritative; the string is display. Add a boot-time reconcile check that reports drift
      rather than silently correcting it.
- [x] **P2-2c** DONE 2026-08-14. **Server-side username composition.** `CreateUserRequest` carries the **local part
      only**; `UserServiceImpl.java:75` composes `local + "@" + alias` with the alias resolved from
      `principal.organisationId()`. Reject any `@` in the local part (otherwise `smith@evil`
      composes to `smith@evil@jambobora`), apply the D-7a character rules to the local part, and run
      the uniqueness check on the **composed** value. Note this is an API contract change on
      `CreateUserRequest` — the field's meaning narrows even if its name does not; decide whether to
      rename it for clarity.
- [x] **P2-3** DONE 2026-08-14 - all three authoriser sites; the refusal is byte-identical to the unknown-user path. `G6` is largely retired, but **two call sites still need an org check for a different
      reason** — cross-tenant *authorisation*, not a 500:
      - `StepUpAuthServiceImpl.java:134` resolves the authoriser globally, so a supervisor in
        tenant A could approve a till override in tenant B. Assert the authoriser's organisation
        equals the caller's principal's. **Take the org from the principal, never from input.**
      - `UserServiceImpl.java:76` no longer breaks, but its message
        `"Username already exists: " + username` reveals that *another tenant* holds the name.
        Generic message, per D-7.
- [x] **P2-4** DONE 2026-08-14. Login consults organisation status once D-6 lands (`AuthServiceImpl` currently
      checks only the user's own active flag).
- [x] **P2-5** D-7 credential standards — **RESOLVED 2026-08-14, mostly by finding it already done.**
      The strong-password half is **already enforced and has been all along**: `PasswordPolicy`
      requires the configured minimum length, at least one letter, at least one digit, and rejects a
      blocklist of common passwords. `UserServiceImpl.create` and every password-set path call it.
      Nothing to build.
      - `must_change_password` and `password_expires_at` remain **unread on the login path**; they
        surface only in `UserDto`. Making them live means a forced-rotation flow, which is a feature
        in its own right and is **not** part of tenancy. Deferred deliberately, not by oversight.
      - `mfa_enabled` is read nowhere at all. It stays deferred for the general population and
        becomes **required for tier-2 and tier-3 roles** at **P4-2b**, which is where the grant path
        it protects actually changes. Building MFA here would be speculative: nothing in Phase 2
        confers privileged authority.

### Phase 2.5 — Scope enforcer and parity harness *(new 2026-08-14; gates all of Phase 3)*

The premise that Phase 3 is inert on a one-organisation install is false (§0.1). These four items
make it true, and they must land before any predicate in Phase 3 is written.

- [x] **P2.5-1** DONE 2026-08-14 - built, and its rule locked by a test proven to fail on the wrong version. **One `TenancyScopeEnforcer`, one call site.** Every org comparison in Phase 3 goes
      through it, so the exemption cannot be forgotten at one of eighteen sites and there is one
      place to audit.

> #### ⚠ CORRECTED 2026-08-14 — exempt on a NULL **userId**, never on a NULL **organisation**
>
> This item previously said to treat *"a principal whose **organisation** is NULL as SYSTEM/UNSCOPED
> and exempt"*. **That is a privilege-escalation hole, and it must not be built.**
>
> A real user can have a NULL organisation — every user created between V101 and the constraining
> migration does, by design (V101's own notice says so). Exempting on a null organisation would make
> each of those users **unscoped**: exempt from every tenant check in Phase 3, through a *data gap*
> rather than an authorisation decision. The newer the account, the more privileged it would be.
>
> **The correct discriminator is `userId == null`.** Verified against the code: all **18** outbox
> handlers construct `Principal(null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(),
> null)` — a null `userId` in every one, and no handler passes a real user id. It is structural,
> it cannot be produced by missing data, and it cannot be reached from a request, because the filter
> always sets a `userId` from the authenticated subject.
>
> So: **`userId == null` ⇒ system, exempt. `userId != null` with a null organisation ⇒ DENY** — a
> real user whose tenant cannot be established must fail closed, not sail through.
- [x] **P2.5-2** **DONE 2026-08-14 — the test already existed.**
      `SalesPostingHandlerIT.finalise_producesBalancedJournalEntry` dispatches the event and asserts
      `journalEntryRepo` holds the entry, with debits equal to credits. That is exactly the
      assert-the-effect shape this item asked for, so nothing new was needed; what it needed was to
      be *identified* as the safety net, because it is the test that will fail loudly if a Phase 3
      predicate ever denies a system principal.
      **The rule it protects is now locked by `TenancyScopeEnforcerTest`**, which was verified to
      fail — and to fail on precisely the right assertion — when the plan's original (wrong)
      exemption rule is injected. Original wording kept below for the reasoning:
      **Prove it on the posting path with an integration test that asserts the effect, not
      the absence of an exception.** Dispatch a `SaleFinalised` event with enforcement ON and assert
      **the GL journal row lands**. `SalesPostingHandler.java:77-86` catches `Exception` and marks the
      event processed to avoid a poison transaction, so a "no exception thrown" test proves nothing —
      it is exactly how `G14` stayed invisible.
- [ ] **P2.5-3** **Audit read tolerates NULL:** `organisation_id = :org OR organisation_id IS NULL`,
      so the pre-auth and system-written trail stays visible. The alternative — stamping those rows
      with an explicit platform organisation at write time — is also acceptable, but pick one and
      write it in the ADR; do not leave it to the implementer.
- [ ] **P2.5-4** *(deliberately deferred to Phase 3 — see note)* **Row-count parity harness** for the
      filter-shaped sites listed in §0.3.
      > **Sequencing note, 2026-08-14.** A harness that compares a query with and without its
      > organisation predicate has nothing to compare until the predicate exists, and none of
      > P3-4/P3-5/P3-7/P3-8 is written yet. Building it now would mean writing it against imagined
      > call signatures and rewriting it on contact. It is therefore built **with the first
      > filter-shaped Phase 3 item**, and no filter item ships without it. Recorded here rather than
      > silently skipped. Same query,
      with and without the predicate, on a one-organisation database, asserting identical counts.
      Shadow mode cannot cover these — a filter has nothing to allow and nothing to log.
- [ ] **P2.5-5** Decide and record how shadow/parity evidence is actually collected, given there is
      **no telemetry egress from a customer box** (§0.3). Either a minimal opt-in counter export is in
      scope, or the rollout's confidence is re-stated honestly as "verified on the two boxes we own".

### Phase 3 — IAM scope repair *(the actual security work; none of it is schema)*

> **Rollout rule (§0.3):** guard-shaped items ship in shadow mode — log `WOULD_DENY` with actor,
> target and both organisation ids, allow the request, flip to enforce after a clean week on live
> production. **Filter-shaped items (P3-4, P3-5, P3-7, P3-8) cannot use shadow mode** and ship
> behind the P2.5-4 parity harness instead.

- [x] **P3-1** DONE 2026-08-15, root included. The branch override now refuses a branch whose company
      is in another organisation, **before** the assignment check and before the principal is built.
      Refusal reuses the *unavailable branch* message verbatim: a distinct one would confirm that a
      branch uid exists in some other tenant. Without this a cross-tenant switch would build a session
      on a foreign company and P3-11 would then refuse every action inside it — a broken session that
      reads as a bug rather than as a boundary. Same positive-mismatch rule as `canActIn`.
- [x] **P3-2** DONE 2026-08-15. New `BRANCH.SWITCH` audit action, written in the filter where the
      switch actually happens, whenever the override leaves the company minted at login (a move
      between branches of one's own company is ordinary navigation and would drown the signal).
      `recordIndependent` because the filter runs outside any business transaction, wrapped so a
      failure to audit can never take the request down. Original text: move the `ROOT_BYPASS` audit onto the branch switch. Today `ScopeGuard.java:675`
      fires it only when the target company differs from the principal's — after a header switch it
      does not differ, so **the row is never written**. That line is the only writer of
      `ROOT_BYPASS` in the backend and there is no `BRANCH_SWITCH` action at all (`G4`).
- [x] **P3-3** DONE 2026-08-14 - org asserted BEFORE the membership oracle. Org assert in `UserCompanyServiceImpl.assign`, `UserBranchServiceImpl.assign`,
      `UserRoleServiceImpl.assign`, and `UserServiceImpl.requireInScope` — **before** the membership
      oracle is consulted (`G1`, `G12`).
- [x] **P3-4** DONE 2026-08-14, root included. Org predicate on `listOrgWide`; the derived query `findByRootFalseOrderByUsername`
      returns every non-root user in the database (`G1` step 1).
- [x] **P3-5** DONE 2026-08-14, NULL-tolerant per I-2. Org predicate in `RoleServiceImpl.requireByUid` and `list()` (`G2`) — **NULL-tolerant
      per I-2**: `organisation_id = :caller OR organisation_id IS NULL`. `list()` today is a bare
      `roles.findAllByOrderByName()` with no predicate at all, so a plain equality here would hide
      **all twelve shipped bundles from every tenant** — the roles screen would show a customer only
      the roles they authored themselves, and the ones they actually use would vanish.
- [x] **P3-6** DONE 2026-08-14 (create half; `/companies/accessible` gating still open). Org assert in `CompanyServiceImpl.create`; gate `/companies/accessible`, currently
      `isAuthenticated()` only (`G3`).
- [x] **P3-7** DONE 2026-08-14. Rewrite `OrganisationServiceImpl.current()` and `list()` off the principal (`G5`).
- [x] **P3-8** DONE 2026-08-15 (spine half in batch 2, read half same day) — the two that mattered most, both in the authorisation spine
      itself and neither on the original list: **`ScopeGuard.canActOn`** returned `true` for root
      *before* `canActIn` was reached, which would have made P3-11's tenant check dead code for every
      uid-addressed operation; and **`PermissionChecks.scoped`/`scopedOrMember`** short-circuited on
      `principal.root() ||` *before calling* `canActOn`, skipping it again one level up. Root keeps
      its company-level bypass; only cross-tenant reach is gone.
      **COMPLETED 2026-08-15** with the read-shaped remainder:
      - `AuditReadService` — root's search dropped the predicate entirely, so on a shared instance one
        `is_root` row read **every customer's audit trail**. Now org-wide *within its own tenant*.
        This exposed a related hole: `AuditLog` had **no `organisation_id` field at all**, so although
        V99 created the column and V101 backfilled it, nothing had written it since — every audit row
        created after V99 was unattributed. The entity now carries and stamps it, and the read
        predicate is NULL-tolerant so the un-stamped middle period stays visible.
      - `UserServiceImpl.list()` — root's branch was `findAllByOrderByUsername()`, i.e. **every user
        in the database, of every customer, by name**. Now the caller's own organisation
        (NULL-tolerant, so an unattributed account stays visible to the screen an admin would use to
        fix it). `requireByUid` gained the same bound, applied **before** the root branch.
      - `CompanyServiceImpl` — the organisation uid comes **from the caller** and the endpoint is only
        `isAuthenticated()`; the non-root assignment filter made that harmless, but root got the named
        organisation's whole company list. Now gated on the caller's tenant, which also closes the
        `/companies/accessible` half of **P3-6**. NotFound, not Forbidden.
      - `StockLocationServiceImpl` — switched to the revocation-aware finder, so it agrees with P3-14;
        `ProductStockReportQuery`'s javadoc documenting the old asymmetry is now corrected.
      - `PartyBranchGuard` — the P3-12 existence oracle, **fixed narrowly**: a cross-tenant branch
        collapses to not-found, but a sibling company inside the caller's own organisation keeps the
        explicit BR-PARTY-01 message. A blanket collapse would have destroyed a real error message
        for the multi-company case that every current customer actually is, and bought no security.
      - **Left as-is deliberately:** `ProductStockReportQuery:393` and `StockLocationServiceImpl:222`
        still let root skip the *branch-assignment* check. Both are company-scoped through
        `assertCanActIn`, which P3-11 now bounds to the tenant, so what remains is root acting across
        branches inside its own organisation — exactly what D-2 grants it.
- [x] **P3-9** DONE 2026-08-15 — first half was already closed in Phase 2, second half now built to
      the recommendation (count it, keep the message). Detail below.
      - *Root of another tenant approving a till override* — **already shut in Phase 2.** Both
        step-up paths (`StepUpAuthServiceImpl:147` and `:262`) compare the authoriser's organisation
        to the caller's and collapse a mismatch onto the unknown-user path — same message, same
        dummy-hash timing, same throttle accounting. A root authoriser from another tenant is refused
        there, before `isRoot()` is ever read. The item's premise no longer holds.
      - *The `G11` credential oracle* — **real, but the current behaviour is a documented deliberate
        choice, so changing it is the owner's call.** Today a wrong password returns
        `CREDENTIALS_MESSAGE` and counts against the throttle, while a correct password with
        insufficient authority returns `NOT_AUTHORISED_MESSAGE` and counts against nothing. The
        consequence: a cashier can confirm a colleague's password without ever tripping a lockout,
        then use it at the main login. The fix (one message, always counted) is three lines, and the
        cost is real: a legitimate "wrong manager" attempt would then read as bad credentials and
        push the operator toward a cooldown at a till with a customer waiting. **BUILT as
        recommended:** `NO_AUTHORITY` and `UNKNOWN_PERMISSION` now feed `countAgainst(caller)`, and
        the message stays distinct so a manager who genuinely lacks the permission is never told
        their password is wrong. `SELF_APPROVAL` stays uncounted, and for a sharper reason than
        before — the authoriser IS the caller, so there is no oracle, only friction. The throttle
        counts against the caller, never the authoriser, so a cashier still cannot lock a manager
        out by guessing at them. Proven by
        `repeatedRightPasswordNoAuthorityAttempts_alsoThrottle_closingTheG11Oracle`, verified to fail
        on the pre-fix behaviour.
- [x] **P3-10** DONE 2026-08-15, **after P4-1e in the same reviewed edit** (owner-approved). Three
      codes: `ORG.VIEW` (`module = 'iam'`, tenant-level) plus `ORG.CREATE` and `ORG.SUSPEND`
      (`module = 'platform'`). `OrganisationController.list()` moves off its borrowed `COMPANY.VIEW`;
      `/current` stays `isAuthenticated()` because **149 components call it before any
      permission-scoped screen loads** — and the same measurement showed the *list* endpoint has
      **zero web callers**, which is why re-gating it cannot break a screen.
      Export/deletion codes were **deliberately not added**: they belong to **D-6, still open**, and
      seeding codes for an undecided policy is how phantom codes are born.
      Original text follows. ~~Add `ORG.*` permission codes — the seed file contains **not one**, and the
      organisation endpoints currently reuse `COMPANY.VIEW`. Needed to express the D-2 split.
      Touches `R__seed_permissions.sql` → migration-approval rule applies.
      **Do P4-1e first** (R-2), or these codes flow into every tenant's `ORG_ADMIN` on the next deploy.

> The five items below were verified 2026-08-14 and were missing from this phase. **P3-11 is the
> largest single under-scoping in the plan** — without it, P3-1 closes one door and leaves 130 open.

- [x] **P3-11** DONE 2026-08-15. The organisation comparison now runs inside `canActIn`, ahead of
      the `root ||` disjunct, via `TenancyScopeEnforcer.isForeignTenant` + `CompanyTenantIndex`
      (a write-once `companyId → organisationId` cache — `companies.organisation_id` is `NOT NULL`
      and has no setter, so it cannot go stale, and 698 call sites cannot afford a query each).
      **One correction to the item as written:** it says apply the equality *unconditionally*. Doing
      that literally would lock out any account whose `organisation_id` is still NULL — and since
      `companies.organisation_id` is already `NOT NULL`, on today's single-organisation estate that
      is the *only* way the strict rule could fire. It would be a total lockout with no security to
      show for it. The rule shipped is therefore a **positive** mismatch (both sides known and
      different); an unknown organisation on either side is a data gap, not evidence, and a caller
      sitting in it gets exactly the pre-P3-11 behaviour. Nothing reachable is given up — a caller
      cannot null their own organisation — and the branch self-liquidates when P2-1's follow-up
      constrains the column. `TenancyReconciler` now **names** the unattributed accounts on every
      boot, which is where that signal belongs rather than on a 698-site hot path.
      `TenancyScopeEnforcerTest` was verified to fail on the naive `!isSameTenant` form.
- [ ] ~~**P3-11** (original wording, kept for the record)~~ **The org check belongs inside `canActIn`, not only on the branch header.**
      `ScopeGuard.java:646` is `principal.root() || companyId.equals(principal.companyId())`, with
      **763 `assertCanActIn` call sites** — and **130 controller methods take
      `@RequestParam Long companyId` straight from the caller** (`DocumentController.java:94`,
      `ArStatementController.java:42`, `ApStatementController.java:41`, …). §8 frames root
      cross-tenancy as an `X-Branch-Uid` problem; it is not. A single `is_root = true` row turns a
      **query parameter** into a full read/write API over every tenant's ledger — no header, no
      exploit, and the rows written carry the victim's `company_id`, so they look native in every
      report. Apply the organisation equality unconditionally **inside** `canActIn`.
- [x] **P3-12** DONE 2026-08-15 — full triage at
      [docs/ops/multitenancy-freeze-store-triage.md](docs/ops/multitenancy-freeze-store-triage.md).
      All 207 entries classified: 119 already carry a scope assertion in-method (and inherit P3-11's
      tenant boundary for free), ~61 are `toDto`/`enrich*` FK navigation off an already-loaded row,
      13 run on SYSTEM paths, 13 take a bare id parameter and were read individually. **None was
      found to be exploitable across a tenant boundary** — because URLs address entities by *uid*,
      so caller-supplied numeric ids barely exist and the store is mostly internal FK navigation.
      The item's premise ("the real number is 207 + 1") was therefore too pessimistic; what it got
      right is that the *justification* had to be rebuilt, and it now rests on two properties of the
      code rather than on there being one customer per database. One finding logged for batch 3: the
      `PartyBranchGuard` "not found" vs "different company" split is a cross-tenant existence oracle.
- [ ] ~~**P3-12** (original wording, kept for the record)~~ **Re-triage the ArchUnit freeze store as a Phase 3 gate.**
      `archunit_freeze/8e68c60e-…` holds **207 bare `findById`/`getReferenceById` calls** in
      `..service..`, triaged as acceptable in the 2026-06-26 sweep — acceptable *because a leak stayed
      inside one customer's install*. That premise dies here. The freeze store makes each one both
      invisible to CI **and** pre-blessed, so nothing will re-open them. Any entry that resolves a
      company-owned entity from request input moves to a scoped finder **before cutover**. The plan
      budgets P7-4 for one bare `existsById`; the real number is 207 + 1.
- [x] **P3-13** DONE 2026-08-15. `ActiveUserScope` gained `getRoot()`; the filter's per-request read
      already existed for the organisation, so this costs **no extra query** — the projection simply
      returns one more column and the `isRoot` claim is no longer consulted. Demotion now takes effect
      on the next request instead of at token expiry. Original text follows.
      ~~**Read `is_root` from the database, not the JWT claim.**~~
      `JwtRequestContextFilter.java:132` is `Boolean.TRUE.equals(jwt.getClaim("isRoot"))`, while the
      filter already performs a per-request DB read four lines later (`:101` `existsByIdAndStatus`).
      Demoting a compromised root leaves them superuser until the 15-minute token expires, with no
      revocation list — during exactly the incident you are trying to contain. ~3 lines.
- [x] **P3-14** DONE 2026-08-15, **precondition discharged with real data**. New
      `findByUserIdAndBranchIdAndRevokedAtIsNullAndActiveTrue`. The plan required querying production
      first, because the fix locks out anyone currently working through a revoked row: measured
      **zero revoked and zero inactive assignments on both estates** (QA 11 rows, live client 12), so
      nobody is affected. Original text follows.
      ~~**The branch-override check must honour revocation.**~~
      `JwtRequestContextFilter.java:148` calls `userBranches.findByUserIdAndBranchId(...)`, which
      filters on neither `revokedAt` nor `active` although `UserBranch` carries both
      (`UserBranch.java:50-55`). The codebase already documents the consequence in a javadoc at
      `ProductStockReportQuery.java:381-386`: *"a revoked user can still switch session scope by
      header yet is refused the branch-filtered report."* **Query production for live revoked-but-used
      assignments before shipping** — the fix is correct, but it will lock out anyone currently
      working through such a row.
- [x] **P3-15** **CLOSED 2026-08-15 as no-change** — recommendation accepted. Severity collapsed by
      P3-1; the prescribed fix is the riskier option. Reasoning retained below so this is not
      re-opened by someone reading only the original text.
      ~~**Severity collapsed by P3-1; the prescribed fix is now the riskier option.**~~
      Reassessed 2026-08-15. The item's mechanism is real — `AuthorityCeiling` resolves the caller's
      ceiling from `principal.companyId()`, the scope they have just switched into — but its premise
      was "a successful horizontal escape". **P3-1 removes the cross-tenant escape at the door**, so
      what remains is a switch between companies *inside one organisation*, which is exactly the
      model ADR-0001 D-E and ADR-0059 already describe and accept.
      The prescribed fix ("resolve the ceiling from the caller's home organisation") does not map
      onto the existing API: `PermissionResolver.resolve` takes `(userId, companyId, branchId)`, and
      permissions are company/branch-scoped **by design**. Resolving from a "home" scope would judge
      an administrator working legitimately in branch B against branch A's permissions and break
      real cross-branch administration; resolving from a union would be strictly more permissive,
      which is the wrong direction for a ceiling. **Decision: closed as no-change.** If a concrete
      cross-branch administration scenario ever shows the ceiling being reset in a way that matters,
      re-open it with that scenario attached — the item as written cannot be built safely without one.
      Original text follows.
      ~~**The vertical guard is evaluated in the horizontal scope.**~~
      `AuthorityCeiling.java:113-114` resolves the caller's ceiling from `principal.companyId()` — the
      scope they have just switched into. **A successful horizontal escape silently resets the
      vertical ceiling**, so ADR-0059's containment is conditional on tenant isolation holding rather
      than being an independent layer. This matters more now that D-3 builds the new grant rule on
      that ceiling: resolve the ceiling from the caller's **home organisation**, not their current
      request scope.

### Phase 4 — Roles per organisation

> Target model (§1.1): **`is_system` roles are platform-wide and apply to every organisation; every
> other role belongs to exactly one.** Tenants read and grant the globals, author and own their own,
> and can change neither the globals nor another tenant's. The V99/V100 DDL already expresses this;
> the four items below are the code that makes it true.

- [ ] **P4-1** Role CRUD scoped to the caller's organisation (the code half of `G2`; the column alone
      does nothing). Reads are NULL-tolerant (P3-5 / I-2); writes are org-equality **plus** the
      existing `isSystem()` immutability guard (I-1), which must not regress.
- [ ] **P4-1b** **`create()`'s uniqueness check is global today** — `RoleServiceImpl:45`
      `roles.existsByCode(request.code())`. Under scoped roles it must become
      `existsByOrganisationIdAndCode`, or tenant B can never use a code tenant A already took, which
      defeats the whole point. **Blocked by the `uq_role_code` prerequisite below.**
- [ ] **P4-1c** **Prerequisite, and the real gate on per-tenant role codes.** `uq_role_code` is
      retained for now because dropping it breaks approvals at one organisation (§5.1). Two tenants
      cannot both have a `SUPERVISOR` until it goes — so make role-code resolution **org-aware first**
      at `ApprovalEngineImpl.java:301` (`findByCode` → 500 on a duplicate),
      `ApprovalPolicyServiceImpl.java:189` (`existsByCode` → ambiguous) and
      `StepApproverResolver.java:78-84` (matches on the code **string** → authorisation widening),
      *then* drop `uq_role_code` and let V100's two partial indexes carry uniqueness.
- [ ] **P4-2** **Implement the D-3 ceiling change — this is what makes global roles usable.**
      `AuthorityCeiling.assertCanConferRole` (verified 2026-08-14) reads:
      `if (roleIsSystem) throw ForbiddenException.notPermitted();` for any non-root caller. All twelve
      bundles are `is_system`, so **a tenant admin cannot grant a single one of them to their own
      staff.** A platform-wide role nobody in the organisation can confer is decorative.
      The replacement rule must let a tenant admin confer an `is_system` role **to a user in their own
      organisation**, bounded by what the caller holds themselves — ADR-0059's escalation guard has to
      survive intact, since it is the only thing standing between `ROLE.MANAGE` and self-elevation.
      **Do not solve this with `setRoot(true)`** — that is §8's sharpest risk, verbatim.
- [ ] **P4-1d** **Enforce R-1 (§1.2): a tenant role code may not collide with a global role code.**
      V100's two partial indexes sit on different partitions, so `(NULL,'CASHIER')` and
      `(2,'CASHIER')` can coexist once `uq_role_code` drops — the exact ambiguity P4-1c is fixing.
      Needs a service check **and** a trigger (a seeder must not be able to bypass it); no index pair
      can express it.
- [x] **P4-1e** DONE 2026-08-15 (owner-approved). `AND p.module <> 'platform'` added to the
      `ORG_ADMIN` CROSS JOIN. **Zero-risk on live data**: the statement is
      `INSERT ... ON CONFLICT DO NOTHING`, so it grants but never revokes — narrowing stops *future*
      grants and removes nothing. That same property is why the ordering was non-negotiable rather
      than stylistic: a code that flows in once stays in, and taking it back would need a separate
      revoking migration against every deployed database.
      `module` is reused as the discriminator rather than adding a column — no DDL, and `platform`
      was unused across all 25 modules. The failure direction is safe: mis-marking a tenant
      capability as `platform` withholds it (a support ticket); the reverse would hand every tenant a
      platform capability.
      Guarded by `PlatformPermissionBoundaryTest`, which reads the SQL in the **fast** suite (the
      grant only happens at Flyway time, so a DB assertion would sit outside the PR gate) and which
      **asserts the platform module is non-empty** — `<> 'platform'` over an empty module is a no-op,
      and a test of it would pass while proving nothing, the same vacuous-pass that made the tenancy
      parity harness green against two empty result sets. Verified against two mutations.
- [ ] **P4-2b** **MFA on tiers 2 and 3** (§1.2 recommendation). Un-defers the privileged-account half
      of P2-5 only; `mfa_enabled` already exists as a column and is never read.
- [ ] **P4-2c** **Never-zero-admins invariant** — the last `ORG_ADMIN` in an organisation cannot be
      removed, demoted or deactivated.
- [ ] **P4-3** Custom-role provisioning path for a new tenant.
- [ ] **P4-4** Test as a **non-root** tenant admin: they can see the twelve bundles, grant one, and
      are refused on editing one, on touching another tenant's role, and on conferring authority they
      do not hold. Root passes all of these by construction, so a root-only test proves nothing.

### Phase 5 — Tenant provisioning and lifecycle

- [ ] **P5-1** Extract `BootstrapRunner.java:101-142` into a reusable `TenantProvisioningService`.
      **Preserve the order verbatim**: validate password → organisation → company →
      `provisionDefaults` → branch with `setDefault(true)` → stock-location and petty-cash seeders →
      user → default `UserBranch` + `markDefault()`.
      **Omit that last row and the new tenant's admin logs in with a null company and is effectively
      read-only** (`AuthServiceImpl` needs `findByUserIdAndIsDefaultTrue`). Keep the existing
      one-shot guard for install #1. (`G7`)
- [ ] **P5-2** Platform-operator-only create-tenant endpoint (gated on the D-2 outcome).
- [ ] **P5-3** Suspend / resume via `organisations.status`, enforced at login.
- [ ] **P5-4** Per-tenant export and deletion policy (D-6). **Gated on D-9** — without an
      `organisation_id` on aggregate roots there is no traversal to build this on.
      Recommend **logical delete** (status + retention) as the default and physical erasure only
      where a contract demands it: "we hand-deleted from 204 tables in FK order" is not a provable
      answer to a customer or a regulator.
- [ ] **P5-5** **Seed `leave_types` for a new tenant.** `V52__hr_leave_loans.sql:179-195` seeds
      ANNUAL/SICK/MATERNITY/PATERNITY/COMPASSION/UNPAID per company **in SQL**, and there is no
      `LeaveTypeSeeder` — the only `new LeaveType(...)` in `src/main/java` is the interactive create
      at `LeaveTypeServiceImpl.java:43`. Every tenant onboarded after go-live opens HR → Leave to an
      empty list and cannot record a leave request. One class, added to P5-1's chain. *(All 21 other
      per-company SQL seeders are already covered by `CompanyProvisioningServiceImpl.provisionDefaults`
      — this is the only gap.)*
- [ ] **P5-6** **Pre-seed `code_sequence` and `party_code_sequence` rows during provisioning.**
      Every number generator does `findByCompanyIdAndEntityKindForUpdate(...).orElseGet(save(new ...))`
      — e.g. `ApBillNumberGenerator.java:31-32` and three more in the same file. `PESSIMISTIC_WRITE`
      on a row that does not exist locks nothing, so two clerks raising the first document of a kind
      simultaneously both insert and one gets a 500 on `UNIQUE (company_id, entity_kind)`. Under
      DB-per-install this happened once, invisibly, years ago; under tenant provisioning it becomes a
      scheduled event on a new customer's first busy morning. The entity-kind list is static.

### Phase 6 — Clients · **the largest single work item**

- [ ] **P6-1** **146 non-spec Angular components** bootstrap their company picker from
      `organisationService.current()` — and zero specs call it. Since `current()` returns
      organisation #1 to everybody, **every tenant except the lowest-id one gets an empty company
      picker on all 146 screens**: AP, AR, GL, approvals, stock, payroll, everything.
      This is a 146-file refactor **or** a new org context in the shell — decide which. Prefer the
      shell context; do not hand-edit 146 files.
- [ ] **P6-2** ~~Tenant code in the login form~~ — **retired.** Neither client's login UI gains a
      field; `LoginRequest`, `auth.model.ts` and `pos_app/lib/services/auth_service.dart` keep
      `{username, password}`. Two small changes remain: set `autocomplete="username"` (not `email`)
      so password managers do not autofill a real address into a `smith@jambobora` field (D-7c),
      and update the field hint/placeholder to show the `user@alias` form.
- [ ] **P6-2b** **User-creation form**: a single input for the local part with `@<alias>` rendered as
      a fixed, non-editable adornment, and the composed username shown back before save. The alias
      comes from the signed-in admin's own organisation — the field is display-only, never a picker.
      The credential handout (screen, print, or email) must carry the **full** `smith@jambobora`,
      since the person who types it at login is not the admin who created it.
- [ ] **P6-3** Namespace the `sessionStorage` keys — they are fixed and un-namespaced, so a stale
      branch uid survives a tenant switch in the same tab (`G13`). **Still required**: the hazard is
      a stale branch, not a stale tenant code.
- [ ] **P6-4** ~~POS tenant code at install time~~ — **retired.** The till continues to persist only
      a host.
- [ ] **P6-5** POS step-up approval still needs the P2-3 org check on the server side; no client
      change.

### Phase 7 — Verification

- [x] **P7-1 / P7-2** DONE 2026-08-15 — `TwoOrganisationIsolationIT`, the first test in this codebase
      to put **two organisations in one database**. 8 probes, one per control Phases 2-3 added, each
      phrased as the attack it prevents; the caller is always a member of org A, the target always
      belongs to org B, and root is included in the scope probes because `is_root` is
      deployment-global.
      **The finding that justified doing this before Phase 4:** all eight existing `*TenantIsolation*`
      ITs create a SINGLE organisation with two companies inside it (`new Organisation("Isolation Test
      Org")` then `ISO-A` / `ISO-B`). They are cross-**company** tests. Their names implied coverage
      that did not exist, which is worse than no tests — it is why nobody had noticed that the whole
      Phase 2/3 security spine had **never once been observed denying anything**.
      Verified against two mutations: removing the tenant boundary failed exactly the 3 probes that
      depend on it (plus 4 unit tests), while the 5 using independent predicates still passed;
      reverting root's user list to `findAllByOrderByUsername()` failed exactly 1.
      Original text follows. ~~Rewrite the isolation harness for **two organisations**. All eight `*Isolation*` ITs
      are currently *two companies inside one organisation* — they will pass unchanged after the
      migration while proving nothing about the new boundary.
- [ ] **P7-2** Probe as a **non-root ORG_ADMIN of org A against org B**. Root passes everything by
      construction; root-only probing is exactly what would let all of this survive.
      Must include: the `G1` four-call chain; a `POST /companies` carrying B's organisation uid;
      an archive of one of B's roles; an `X-Branch-Uid` pointing at a B branch.
- [ ] **P7-3** Extend `TenantScopingRulesTest`. Note its own comment: `findByUid` is *intentionally*
      out of scope, delegated to the e2e harness — **and every cross-org finding above is
      `findByUid`-shaped**. Also add `existsById`, which the predicate does not currently match
      (see P7-4).
- [ ] **P7-4** Fix the one bare `existsById` in `modules/`: `EmployeeServiceImpl` validates the
      department company-scoped but the branch with a bare `existsById`, and never validates
      `userId` at all before writing it to a column with a real FK to `app_users`. A tenant-A
      employee can reference tenant-B's branch and user, and Postgres accepts it. Scope inheritance
      is a *convention*, not a guarantee — elsewhere the codebase uses a dedicated guard.

### Phase 8 — Operations

- [ ] **P8-1** Per-tenant backup/restore (D-5).
- [ ] **P8-2** RLS backstop, or a recorded rejection (D-4).
> **Owner decision 2026-08-15: LOGIN audit rows stay unattributed.** `LOGIN.SUCCESS` / `LOGIN.FAIL`
> are written through the unauthenticated `record(event, actor, ip)` path, which has no established
> tenant, so they carry a NULL `organisation_id`. It was raised that the user *is* known by the time
> login succeeds and could therefore be stamped; the owner chose to leave it. **Consequence to accept
> knowingly: login history cannot be filtered per customer**, which is a partial limit on P8-3's goal.
> Every other audit row is stamped.

- [ ] **P8-3** Add the organisation to the logging MDC — currently request, user, company, branch.
      Support cannot filter a log stream to one customer.
- [ ] **P8-4** Tag metrics by tenant — they are untagged global gauges, so "whose outbox is stuck"
      and "who is generating the load" are unanswerable.
- [ ] **P8-5** Per-tenant mail identity. A single global sender with no per-tenant from-address
      means every customer's notifications leave under the platform's identity, and a misdirected
      email carries no clue whose it was.
- [ ] **P8-6** `StandingOrderServiceImpl`'s scheduled sweep asserts scope against a **null**
      principal on the scheduler thread, is denied, and has the exception swallowed —
      **standing orders have never generated anything**. No leak today because it creates nothing,
      but do not repair it with a global system principal (`G14`). **Note the multi-node hazard when
      repairing it:** `@Scheduled(cron='0 0 0 * * *')` at `:193` iterates `findDueForGeneration(today)`
      with no lock and **creates sales orders** — two app nodes would double-generate for every tenant.
- [ ] **P8-7** **JWT signing-key custody.** `docs/ops/jwt-keys.md` already states that `private.pem`
      "can forge tokens for any user on this deployment" — that sentence changes meaning from one
      customer to **every** customer. Move it to a real secret store before the shared instance takes
      a second tenant, and update the blast-radius framing in that document.
- [ ] **P8-8** **Deploy blast radius and rollback.** There is no canary or staged path (the CI jobs are
      all pre-merge), one Flyway run touches every tenant, and **per-tenant rollback is not expressible**
      — `organisations.status` exists with zero readers, so "suspend tenant X while we fix their bug"
      is not a lever until P5-3. Write the incident-comms procedure for telling N customers at once;
      nothing in this plan covers it.
- [ ] **P8-9** **Enforce the append-only audit invariant. ⬤ CONFIRMED ON A LIVE CLIENT 2026-08-14.**
      CLAUDE.md invariant 7 and ADR-0004 D-5 say the app DB role is denied UPDATE/DELETE on the audit
      table. Measured on **production and QA**: the `erp` role holds
      `INSERT, SELECT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER` on `audit_logs`. **The control
      does not exist** — it is prose in three documents and a grant in none of them. Two consequences:
      P1-8's "an audit backfill might fail `permission denied`" is **resolved, there is no such risk**;
      and the audit trail of a live paying customer is presently mutable by the application role.
      Becomes load-bearing when D-6 must prove one tenant's rows were deleted and no others.
- [ ] **P8-10** **Capacity floor.** `application-prod.yml` deliberately omits Hikari sizing, so
      production runs on the **default of 10 connections deployment-wide**; and
      `spring.task.scheduling.pool.size` is unset everywhere, so Boot's default of **1 thread** is
      shared by the 1-second outbox poller, the 30-second metrics job, the hourly notification scan
      (`companies.findAll()` then serial per-company) and the midnight sweep. Both are one-line config
      changes and both should land before tenant #2. The outbox's lack of per-tenant fairness
      (`ORDER BY occurredAt ASC`, batch 100, dispatched serially) and its **absence of any purge path
      in 99 migrations** are the follow-ups.

---

## 6. Test strategy — the current estate cannot catch any of this

Facts to plan around, not discover:

1. **The structural gate is blind to this bug class by design.** `TenantScopingRulesTest:37-39`
   states that `findByUid` is intentionally out of scope, delegating that surface to the live e2e
   harness. Every cross-org finding in §2.2 and the register is `findByUid`-shaped.
2. **The harness it delegates to is single-organisation — and is not in CI at all.** All eight
   isolation ITs are two companies inside one org, and `web-ci.yml`'s e2e job is **`if: false`**. The
   delegation target is a set of manual operator scripts in `e2e/` plus hand-written test cases.
3. **Cross-company isolation ITs cover 8 of 25 modules** (cashbank, fixedassets, hr, iam,
   manufacturing, parties, products, purchases). **Sales, GL, AR, AP, stock, POS, tax, approvals and
   documents have none.** And **13 of the 124 `*ServiceImpl` classes that call `findByUid` never
   reference `ScopeGuard` at all** — e.g. `DocumentTemplateServiceImpl`, `GLPostingServiceImpl`,
   `DirectGoodsReceiptServiceImpl`, `BomWhereUsedServiceImpl:51-55` (resolves a product by uid with
   no company check).
4. **~140 test files call `new Organisation(...)`** (174 sites). Now that only `app_users` gains a
   `NOT NULL`, the break is smaller than first budgeted — but it has never been measured. **Convert
   one file before estimating the rest** (P1-7).

> ### The single cheapest decision-changing step, and nobody had proposed it
>
> **Restore a production backup into a scratch stack, apply V99–V101 and the Phase-3 build against
> it, and measure.** That one rehearsal is simultaneously: the empirical falsification test the whole
> superset thesis rests on; the only way to price the fixture break; the only way to learn the
> migrations' actual lock time on a real 98-version database; and the only way to know a live
> customer's ERP still boots.
>
> Nothing in CI does this today. `MigrationKeepDataIT` migrates a **synthetic V9 fixture** — one
> organisation, one company — not anything resembling a real install with 98 versions of accumulated
> data. `infra/prod/backup.sh` already produces the dump; the gap is procedural, not technical.
>
> #### ⬤ CORRECTED 2026-08-14 — "run it twice" does not test what it was meant to test
>
> This previously said: *run the release twice, because `R__seed_permissions.sql` re-runs on every
> deploy and a repeatable-migration failure only appears on the second boot.* **The premise is false.**
> Flyway re-applies a repeatable migration **only when its checksum changes** — i.e. when the file is
> edited, not when you deploy.
>
> Measured on the live customer's own history: the seed has run **three times ever** — 2026-08-02,
> 2026-08-10, 2026-08-12 — and it did not re-run on any of three consecutive application boots during
> the P1-8 proof.
>
> **The correct test is: edit `R__seed_permissions.sql` the way P1-6 will, then boot.** A second boot
> of an unchanged release exercises nothing.
>
> Booting twice is still worth doing — it proves the versioned migrations are not re-applied and that
> the app restarts cleanly — but it is not the seed test, and it must not be recorded as one.

Add, in this order: a two-org fixture builder; the P2.5-4 parity harness; the P7-2 non-root probe as
a failing test *first*; then the P3 fixes; then the ArchUnit extension so CI holds the new line.

---

## 7. Gap register → work item map

| Gap | Severity | Phase / item |
|---|---|---|
| `G1` cross-tenant account takeover via `user_company` assign | P0 | P3-3, P3-4 |
| `G2` role catalogue globally mutable, zero scope enforcement | P0 | P4-1 (+ V99 column) |
| `G3` `POST /companies` writes into a caller-supplied organisation | P0 | P3-6 |
| `G4` `root` has no organisation ceiling; ~~model undecided~~ **model decided** | P0/P1 | **D-2 resolved** — `is_root` re-bounded to "full authority inside my own organisation" (P3-1, P3-2), tier-3 `PLATFORM_OPERATOR` added (§1.2) |
| `G5` `/organisations/current` returns org #1 to everybody | P1 | P3-7 (+ **P6-1**) |
| `G6` global `findByUsername` — **failure mode retired**, authorisation half remains | P2 | P2-3 |
| `G7` no tenant provisioning path exists | P1 | P5-1, P5-2 |
| `G8` `R__seed_permissions.sql` breaks the deploy, twice | ~~P1~~ **retired** | Neither brick fires: `uq_role_code` is not dropped and the backfill skips `is_system` roles (§5.1). The `is_system` adoption bug remains, as P1-6 |
| `G9` `organisations` has no code | P1 | V99 `alias` — **load-bearing**: the username uniqueness partition |
| `G10` audit trail not org-attributable pre-auth | P2 | V99 + backfill |
| `G11` `verify-authority` cross-tenant password oracle | P2 | P3-9 |
| `G12` no org-equality invariant on the user↔tenant bridge | P2 | P3-3 |
| `G13` clients have no tenant slot | ~~P2~~ low | **mostly retired**; only P6-3 (session keys) remains |
| `G14` schedulers have no tenant context (one already dead) | P3 | P8-6 |
| `G15` outbox / caches / idempotency / blobs / sequences | clean | — no work |

---

## 8. The sharpest risk

**The provisioning shortcut that makes every customer's administrator a deployment-wide superuser —
because the product does not work otherwise.**

A pincer between two lines that look unrelated:

- `AuthorityCeiling.java:103-104` — only root may grant an `is_system` role, and all twelve shipped
  bundles are `is_system`. **A tenant admin who is not root cannot give their own cashier the
  CASHIER role.**
- `ScopeGuard.java:646` — `root ||` short-circuits the company comparison, and the request filter
  lets root re-scope into any branch of any organisation via a header.

So whoever writes `TenantProvisioningService` (P5-1) will hit *"the new admin can't grant any
roles"*, and the one-line fix that makes it work is `setRoot(true)` — precisely what
`BootstrapRunner.java:137` already does today. That single line silently disables org isolation for
exactly the users who use the system most.

**Why it is easy to miss.** No error, no failing test. Every one of the 182 company predicates still
evaluates *correctly* — the principal genuinely is in that company, because the filter put them
there from a header. Rows written carry the victim tenant's `company_id`, so they look native in
every report and audit query. And the compensating control is silently defeated (P3-2).

It does not require an attacker: a branch picker listing branches from the wrong org, a support
engineer helping a customer, or a stale `X-Branch-Uid` in a reused tab is enough to write one
tenant's invoice into another's ledger, with nothing in the audit trail to say it happened.

**Mitigation — RESOLVED 2026-08-14, and resolved *structurally* rather than by vigilance.**

D-2 and D-3 are now closed, and between them they take the danger out of the shortcut rather than
guarding against it:

- **D-2 stage 1 re-bounds `is_root`** to *"full authority inside the organisation I belong to"*.
  Once the organisation predicate applies unconditionally, root included (P3-1, P3-2),
  `setRoot(true)` in provisioning makes the new admin powerful **inside their own tenant** — which is
  what the implementer wanted in the first place. The trap stops being a trap.
- **D-3 removes the reason to reach for it at all.** The replacement ceiling rule lets a tenant admin
  confer a tier-1/tier-2 role they hold themselves, within their own organisation. Provisioning no
  longer hits *"the new admin can't grant any roles"*, so there is no pressure toward the shortcut.

**The sequencing this imposes is now load-bearing: P3-1 and P3-2 are prerequisites of P5-1, not
parallel work.** Until root is organisation-bounded, `setRoot(true)` is still exactly as dangerous as
this section describes — the resolution is *conditional on those two items having landed*. Write that
into the ADR (P0-3), because it is the one dependency an implementer would reasonably assume is soft.

Two residual controls, since the guard is now doing real work rather than being a blanket refusal:
MFA on tier-2/tier-3 grants (P4-2b) and the never-zero-admins invariant (P4-2c).

---

## 9. Out of scope

- Schema-per-tenant and DB-per-tenant (§1).
- Per-tenant rate limiting / noisy-neighbour control — revisit after Phase 8.
- Self-service tenant signup. Provisioning is platform-operator-only for now.
- Cross-tenant reporting for the platform operator.
- Any change to the 182 company-scoped tables (§2.2 conclusion) — **but see the caveat below.**

> #### ⚠ The out-of-scope line above is the plan's most load-bearing unverified claim
>
> "The 182 company-scoped tables need no new predicate" is inherited from §2.2 — a section that
> simultaneously marks the *reasoning* behind it **REFUTED**. No lens has re-verified the conclusion,
> and it is not gate-backed: `findByUid` is explicitly outside the ArchUnit gate, isolation ITs cover
> 8 of 25 modules, and 13 of 124 `findByUid` service implementations never touch `ScopeGuard` (§6).
>
> This claim decides whether tenancy is a ~10-site IAM job or an estate-wide one. **It should be
> re-verified before Phase 3 is sized**, not assumed because it appears in a table.
>
> Two things that *were* re-verified and can be trusted: there is **no shared reference table a
> tenant can write to** — `currencies` is the only genuinely global tenant-visible one and has no
> write endpoint (`CurrencyController` is list+get; every write targets `currency_rates`, which is
> `company_id NOT NULL`), and UoM, tax rates, payment terms, chart of accounts, GL configs, document
> templates, notification types, dimensions, price lists and PAYE bands all carry
> `company_id NOT NULL`. And the branch-link tables are guarded by `ProductBranchGuard` /
> `PartyBranchGuard`, whose own comment reads *"SQL FKs cannot assert cross-row company equality, so
> this guard is the application-layer enforcement"* — which makes `EmployeeServiceImpl`'s bare
> `existsById` (P7-4) **the single known deviation**, not merely an example.

### Also out of scope, but now named rather than omitted

- **Horizontal scale-out of the shared instance.** A client box is one JVM by design; a shared
  instance serving N customers is the first deployment where 2+ app nodes make sense — and only the
  outbox is multi-node safe (`DomainEventRepository.java:49`, `FOR UPDATE SKIP LOCKED`).
  `StandingOrderServiceImpl.java:193`'s midnight cron iterates `findDueForGeneration(today)` with no
  lock and **creates sales orders** — two nodes double-generate for every tenant.
  `PermissionResolver.java:51,176-179` invalidates per-JVM, so a revoke takes up to 30 s to reach
  other nodes. `StepUpAuthServiceImpl.java:101`'s throttle is a per-JVM map, so POS step-up
  brute-force throttling is bypassable by hitting a second node. Whether the shared instance is ever
  multi-node decides whether a leader-election knob is needed. **Revisit before tenant #2.**
- **Support access to tenant data.** P3-1 applies the org predicate "unconditional, root included",
  which by design prevents the platform operator from reading a tenant's data — i.e. from
  reproducing the bug the tenant just reported. **D-2 stage 3 is the answer** (a time-boxed, audited
  support grant against one named tenant); it is designed in principle and unbuilt in practice, and
  it is the first hosted buyer's security-questionnaire question.
- **`dist/` becomes permanently dual-track — a consequence of D-1 = (a), not a separate choice.**
  Every existing customer stays on the offline-bundle model (`dist/bundle/orbixerp.sh`: customer
  pulls when ready, safety backup before update, a clean single-DB rollback), while the shared
  instance is hosted and continuously deployed. That is **two deploy pipelines, two backup stories,
  two upgrade cadences and two support runbooks**, for as long as any single-tenant customer remains.
  It is a real ongoing cost and it should be stated in the ADR rather than discovered after Phase 6.
  Note also what the hosted model removes: the customer's ability to choose their moment — which
  matters to anyone with a month-end or payroll change-freeze.

---

## 10. Ship ahead of the programme — independent of tenancy

These are **live-production problems today**. The tenancy analysis surfaced them; none of them is
tenancy work, none waits on a §3 decision, and each reduces the blast radius of everything that
follows. Ship them on their own branches, ahead of Phase 1.

| # | Item | Evidence | Size |
|---|---|---|---|
| **H-1** | `ERP_SWAGGER_ENABLED` is **never set in `infra/prod/docker-compose.yml`**, and `/v3/api-docs/**` + `/swagger-ui/**` are `permitAll` (`SecurityConfig.java:54-55`). Only the *client* bundles set it (`dist/bundle/.env.example:149`). Production serves a complete unauthenticated endpoint and DTO map to anyone with the hostname. | `application.yml` default is `true` | one line |
| **H-2** | Login lockout is keyed on the **target account** with **no IP throttle, no per-source limiter and no CAPTCHA anywhere** (`LoginAttemptService.java:53-72`). Any host can hold a known account locked at 6 requests per 15 minutes. Under `@alias`, usernames become publicly derivable. | — | S |
| **H-3** | **Username-existence oracle.** The unknown-user path is correctly timing-equalised and generic, but the *locked* path returns a distinguishable message (`AuthServiceImpl.java:96-99`). Six requests confirm an account exists — and lock it. Return the generic string; the lockout still works. | — | XS |
| **H-4** | Read `is_root` from the DB, not the JWT claim → see **P3-13**. | `JwtRequestContextFilter.java:132` | XS |
| **H-5** | Honour `revokedAt`/`active` on the branch-assignment check → see **P3-14**. ~~Query prod first.~~ **✅ CLEARED 2026-08-14** — measured on both estates: `user_branch` holds **0 revoked and 0 inactive** rows (QA 11 assignments, production 12), and no user's assignments are all-revoked. Nobody is working through a revoked row, so this ships without locking anyone out. | `:148` | XS |
| **H-6** | No Hikari max-pool setting in `application-prod.yml` → production runs on the **default 10 connections**. | deliberate omission, documented | one line |
| **H-7** | `spring.task.scheduling.pool.size` unset everywhere → **one scheduler thread** shared by the outbox poller, metrics, the hourly notification scan and the midnight sweep. | `OutboxSchedulingConfig.java:19` is a bare `@EnableScheduling` | one line |
| **H-8** | `audit_logs` has **no index leading with `company_id`** (`V1__baseline.sql:317-319`) while `AuditReadService` pages with a `count(*)`. **Already folded into V99** — listed here because it is a live problem, not a tenancy one. | — | in V99 |
| **H-9** | The append-only audit invariant is documented but **not enforced** — no `GRANT`/`REVOKE` anywhere → **P8-9**. | — | S |
| **H-10** | JWT signing key custody → **P8-7**. Worth doing before the estate grows, independent of tenancy. | `docs/ops/jwt-keys.md` | M |
| **H-11** | **`OrbixERP.cmd` is broken in every bundle ever shipped.** Lines 19 and 102 reference **`erp.ps1`**; the bundles ship **`orbixerp.ps1`**. Verified across all 11 bundles in `dist/release/` (1.1.0 → 1.6.1) — every one carries the identical broken launcher. Double-clicking prints *"This folder looks incomplete. Unpack the bundle again."* and exits 1. This is the file the desktop shortcut targets (`setup-wizard.ps1:1200`), that `Install.cmd:51` tells the customer to use "from now on", and that `INSTALL.md`, `OPERATIONS.md` and `TROUBLESHOOTING.md` all name as the day-to-day tool. **A Windows customer currently has no working self-service surface at all.** | `dist/bundle/OrbixERP.cmd:19,102` | XS |
| **H-12** | `cmd_update` writes the new `ERP_VERSION` into `.env` **before** `dc up -d` succeeds (`orbixerp.sh:371`), and nothing writes it back on failure — so the printed rollback line loops through a 900-second `wait_healthy` each time. Write it after health returns, or ship a supported `pin <version>`. | `dist/bundle/orbixerp.sh:371` | S |
| **H-13** | `TROUBLESHOOTING.md:104` handles a `wait_healthy` timeout with only "give Docker more memory" or "look for the first ERROR" — and in a slow-migration timeout **there is no ERROR**. The customer falls through to `restore`, which destroys committed work and replays the same wait. Rewrite it to say: *"If this times out, do NOT restore. Run `status` again in ten minutes. Restore only if the log shows a line starting ERROR."* | `dist/bundle/docs/TROUBLESHOOTING.md:104` | XS |

**H-11 through H-13 are the precondition for everything else in this section**, and for any future
step that asks a customer to confirm anything: a launcher broken in 11 consecutive bundles over 13
days went unnoticed because nobody executes that surface. It is not a surface to entrust with gating
a live production migration.

**Why this section exists:** every one of these was found while looking for tenancy defects, and
every one of them is already costing a live customer something. Shipping them first also means the
tenancy branches are not carrying unrelated risk when they land.

---

## 11. Known gaps in this plan — named, not filled

Recording these honestly is the point; do not read their absence elsewhere as coverage.

- **G-A · No schedule and no effort estimate.** There is no critical path and no answer to *"what is
  the earliest date tenant #2 can be onboarded"*. §4 gives T-shirt sizes only. **Every cost figure in
  this document is a grep count** — 140 files / 174 `new Organisation(` sites, 146 components, 207
  freeze entries, ~35 new tests — and **not one has been converted to engineer-time by attempting a
  representative slice**. The relative cost of the phases is therefore unpriced.
- **G-B · Nothing has been executed.** The entire analysis behind this plan is static reading. No
  `mvn -B clean test`, no `mvn verify`, no `bash scripts/check-migrations.sh`, no `npm test`, no
  rehearsal. Load-bearing claims — the IT suite's wall-clock, the freeze store's behaviour on a moved
  call, the fixture break's real size — rest on greps. §6's production-dump rehearsal is the fix for
  most of this and is the highest-value unscheduled item in the document.
- **G-C · There is no production deploy runbook.** `docs/ops/` holds backup-restore, jwt-keys,
  migrations-and-seeding and security-sweep — and no deployment procedure. `infra/prod` disclaims
  itself as reference-only. A second production-class deployment cannot be operated from tribal
  knowledge; the runbook must cover the two-phase cold start, key custody, mode/config drift and a
  maintenance-window story.
- **G-D · No SLA, incident-comms or on-call model.** On a client box an outage is one customer who
  telephones you. On the shared instance it is N customers, a support queue and an availability
  commitment nobody has written. Uptime targets, on-call rota, backup-restore drills and the fact
  that a migration taking a table lock now stops **every tenant's trading day at once** are all
  unaddressed. → partly **P8-8**.
- **G-E · No data-residency or jurisdiction answer.** Several customers' VAT books will sit in one
  database, in one place, under one legal regime. Nobody has asked where the shared instance is
  hosted or who can be compelled to produce it.
- **G-F · The 182-table premise is unverified** — see the warning in §9. It decides whether tenancy is
  a ~10-site IAM job or an estate-wide one, and it should be re-verified before Phase 3 is sized.
- **G-G · Per-tenant recovery does not exist.** ~~Gated on D-9.~~ **Corrected 2026-08-14:** it is not
  gated on D-9 — 182 of 205 tables are already one join from their organisation (see D-9), so the
  blocker is that **nobody has written the extract/restore tooling**, not that the schema prevents it.
  This is the programme's true
  operational blocker; D-5 cannot be answered while `organisation_id` lives on one table.
- **G-H · The existing paying customers are treated as a risk surface, not as stakeholders.** Every
  phase ships migrations and security tightenings into live customer databases that will **never host
  a second tenant**, down an additive-only line with no down path. Nobody has asked what those
  customers are told, what notice or consent is owed, or whether an on-prem customer would accept
  schema churn driven by a hosted product they are not buying.

---

## 12. Release strategy — environments, stages, and what the client sees

> Added 2026-08-14, answering two questions the phase list does not: *do we deploy per phase or once
> at the end*, and *when does the customer find out*.

### 12.1 Deployment facts that constrain everything below

- **Web and API are ONE artefact.** `infra/prod/Dockerfile` builds the Angular SPA (stage 1), then
  copies `/web/dist/web/browser/` into `src/main/resources/static/` before packaging the Spring Boot
  jar (stage 2). There is no separate nginx or CDN at this tier. **Every deploy ships both**, a web
  fix cannot go out without the backend on the same branch, and users get the new SPA on their next
  page load with no client-side action.
- **The POS is the only separately-deployed client**, and on current scope it needs **no change at
  all**: P6-2 and P6-4 are retired, P6-5 is server-side only, and `LoginRequest` keeps
  `{username, password}`. **Confirm this explicitly before P2-2c lands** — it is the one client that
  cannot be upgraded atomically, so a contract change there is expensive.
- **There is no rollback worth the name.** `infra/prod/restore.sh` and `dist/bundle/orbixerp.sh`'s
  `cmd_restore` both `pg_restore --clean` a full dump **including `flyway_schema_history`**. Undoing a
  release means restoring a pre-deploy dump and **losing every transaction posted since**. The
  smaller the increment, the smaller that loss.

### 12.2 Three environments, not two

| Environment | Role | Tenancy |
|---|---|---|
| **QA** (`infra/qa`, one host, one durable volume) | Validates the **next increment**. Mirrors what the live customer runs. One batch ahead of production, never eight. | **Single-tenant, permanently** |
| **Rehearsal stack** — ✅ **built 2026-08-14**, see [`docs/ops/rehearsal-stack.md`](docs/ops/rehearsal-stack.md) | Migration rehearsal on real data; the two-organisation probe for Phases 4–7. | Disposable; may hold two orgs |
| **Production** | Continuously updated with completed, verified batches. | Single-tenant until tenant #2 |

> **Why the rehearsal stack is not optional, and why the two-org work must not run on QA.**
> QA has one durable volume that is never wiped. The moment it is given a second organisation to
> exercise Phases 4–7, it holds two permanently — and can never again validate a single-tenant
> release, which is exactly what the live customer runs. Being disposable is what lets the rehearsal
> stack hold two organisations without contaminating the environment that signs off releases.

### 12.3 The rule: stream the invisible work, batch the visible work

**Rejected: "run all 8 phases on QA, verify, then update production once."** The instinct — one
change window for the customer — is right, and §12.4 delivers it. As a *deployment* strategy it
fails five ways:

1. **QA's data is not the risk.** Everything dangerous here is a migration or backfill meeting *real
   accumulated data*. A green QA run proves the code is correct and proves nothing about lock time,
   `audit_logs` volume, or whether a customer role was flipped to `is_system` years ago. §6's
   falsification test is a **restored production dump**, not QA.
2. **It makes the riskiest deploy the only deploy** — V99–V101 plus 75 items at once, against the
   rollback story in §12.1.
3. **Shadow mode becomes theatre.** Phase 3's method is observing `WOULD_DENY` against *real* traffic
   — real branch switches, a real month-end, a real payroll run. Synthetic QA traffic produces none
   of them, so 15 predicates would be flipped to enforce on live data having observed nothing.
4. **QA can be single-tenant or multi-tenant, never both** (§12.2).
5. **Production sits unpatched for months.** §10's items are live defects *today*, and QA already
   runs `develop` ahead of `main` — letting that drift reach 75 items breaks the branch workflow for
   every unrelated feature in the meantime.

**The rule that replaces it:**

- **Invisible work — stream it.** Phases 1, 2, 2.5, 3, 5 and all of §10 change nothing a user sees.
  Ship them in small, independently revertible batches as each is verified.
- **Visible work — batch it.** Phase 6 is the first thing a customer notices; Phase 4 changes what
  admins can do. Hold those, test them together with full persona UAT on QA, and release as one
  coordinated change.

Net effect: **one production *disruption the customer perceives*, at Phase 6 — not one production
*deploy*.** The customer gets a single moment of change, fully verified beforehand, without the
technical risk being concentrated into one irreversible migration event.

**The deploy unit is a batch of related items, not a phase.** A phase completes when its last batch
lands. Batch deploys into an off-hours window: with two deployments and no HA, every deploy is a
brief outage, so a daily deploy is fine and a daily 10 a.m. deploy is not.

### 12.4 Stage sequence

| Stage | Contents | Deploys | Client sees |
|---|---|---|---|
| **A · Facts and decisions** | P0-1c org-count query · stand up the rehearsal stack · P1-0 measurements · close Phase 0 · ADR + ADR-0059 amendment | none | nothing |
| **B · Hardening (§10)** | H-1 Swagger · H-2/H-3 throttle + oracle · H-6 Hikari · H-7 scheduler pool · H-4 `is_root` from DB · H-5 revocation *(query prod first)* · H-11/12/13 if bundles are in the field | 2–3 | restarts only |
| **C · Phase 1 schema** | Rehearse on the dump; boot twice, **and separately test an edited `R__` seed** (§6) · QA → verify → prod in a window | **1** | a restart |
| **D · Phases 2 + 2.5** | Principal + JWT · enforcer + parity harness. Derive `organisationId` from the user row, not the claim, or live tokens break. Confirm POS contract first. | 2 | nothing |
| **E · Phases 3 + 6 IN PARALLEL** | Phase 3 in shadow batches, weeks apart, flipped when clean. Phase 6 on its own branch, kept green and mergeable. | many | **Phase 6 is the first visible change** |
| **F · Phases 4 + 5** | P1-6 → P4-1c → drop `uq_role_code` → P4-1b/1d. P4-1e before P3-10. **P3-1 + P3-2 enforcing before P5-1.** | 2–3 | admins gain role-granting |
| **G · Phase 7** | Continuous, not a stage. P7-2's probe written as a **failing test first**. Two-org work on the rehearsal stack only. | — | nothing |
| **H · Phase 8, then tenant #2** | Per-tenant backup, org in MDC, tenant-tagged metrics. **D-5 and D-8 closed before tenant #2 exists.** | 2–3 | nothing |

**The scheduling point that matters most: Phase 6 is gated only on Phase 2, not on Phase 3.** It is
the largest item in the programme and it is independent of the security work, so run the two
concurrently. Sequencing Phase 6 after Phase 3 adds its full duration to the critical path for no
reason — and Phase 3's pace is set by observation windows, which is calendar time the web work can
absorb for free.

### 12.5 Telling the customer

Nothing before Stage E requires a conversation — every change through Phase 5 is additive or
shadowed, and the username rewrite that would have forced one was deleted (§0.2). The single
customer-facing communication is **Phase 6**, and it is about screens changing, not credentials.

That is the direct payoff of two earlier decisions, and it is worth stating so neither gets quietly
reversed: legacy usernames are never rewritten, and the POS stores only a host.

---

## 13. Change log

| Date | Change | By |
|---|---|---|
| 2026-08-13 | Initial draft from the verified gap register. Target model settled; D-1…D-6 open. | audit + owner |
| 2026-08-13 | **Login reversed to global usernames, no tenant code** (§1). Tenant now derived from `app_users.organisation_id`. Consequences: `app_users` DDL becomes purely additive (`uq_app_user_username` and `uq_app_users_email` untouched); V100 is roles-only; `organisations.code` demoted to nullable ops metadata; `G6`'s 500-on-second-tenant failure mode retired, its cross-tenant *authorisation* half kept as P2-3; client tenant-code work (P6-2, P6-4) retired. New **D-7** on username allocation + credential standards. | owner |
| 2026-08-13 | **Creation UX fixed:** the admin types the local part only; `@alias` shows as a fixed suffix and the **server composes** the stored username from `principal.organisationId()`. Adds P2-2c (composition + reject `@` in the local part + uniqueness on the composed value) and P6-2b (form adornment, show composed name, full username on the credential handout). Side effect: D-7's cross-tenant enumeration worry is **eliminated** — a collision can only be inside the caller's own org. New **D-7e**: what username does a platform operator with no organisation get? (coupled to D-2, blocks P5-1). | owner |
| 2026-08-13 | **Username convention set: `<user>@<org-alias>`** (e.g. `smith@jambobora`); strong password now, MFA later. Resolves D-7's collision question by construction. Re-promotes the org handle to a load-bearing `organisations.alias` (NOT NULL + UNIQUE + format CHECK) while `app_users` constraints still stay untouched. Adds the **username-rewrite backfill**, which changes every existing user's login credential — now the one irreversible, client-visible item in Phase 1. Adds the *never parse the suffix for scope* invariant (P2-2) and the suffix↔FK drift check (P2-2b). D-7 narrowed to four sub-questions: alias format, immutability, separator, case-path verification. | owner |
| 2026-08-14 | **Production constraints promoted to §0 and the inertness premise REFUTED.** Adds §0 (live production, strictly one codebase, no tenancy-mode flag, D-1 = (a), no username rewrite, rehearse on a prod dump), §0.1 (seventeen outbox handlers + the pre-auth audit path run with a principal that has no user, so Phase 3 is *not* inert at one organisation), §0.2 (legacy bare usernames never rewritten) and §0.3 (shadow mode covers guards only; four filter-shaped sites need a row-count parity harness, and there is no telemetry egress from a customer box). New **Phase 2.5** gating Phase 3. **D-1 resolved (a)**; **D-3 forced to "bundles stay global"**, which closes the per-tenant-clone escape and makes §8's pincer sharper; new **D-8** on per-tenant fiscalisation (one JVM-wide TRA provider vs per-customer TIN/VRN/device — a legal defect on a shared instance). §5.1 revised: FKs `NOT VALID`, `audit_logs` indexes added, `CONCURRENTLY` removed (no wiring exists; five migrations say so), backfill hardened and step 4 deleted, backfill now skips `is_system` roles, **V101 split into a second release**, and **two items removed for breaking live single-tenant production** — `roles.organisation_id SET NOT NULL` (incompatible with `R__seed_permissions.sql:287`) and the `uq_role_code` drop (breaks `ApprovalEngineImpl:301` and widens `StepApproverResolver:78-84`). `G8` retired. §6 rewritten around a production-dump rehearsal. §9 caveated: the 182-table claim is the plan's most load-bearing unverified premise. | owner + 14-agent workflow |
| 2026-08-14 | **Global vs tenant-scoped stated as a rule (§1.1)**, on the owner's point that some things are platform-wide. Adds the classification register and two invariants: **I-1** a global row is readable by all tenants and writable by none (already shipped for roles via the `isSystem()` guard — must not regress), and **I-2** every scoping predicate is NULL-tolerant or it hides the global rows. Three mechanics were found missing and are now work items: **P3-5** reworded (a plain equality would hide all twelve bundles from every tenant — `list()` has no predicate at all today); **P4-1b** `create()`'s global `existsByCode` must become org-scoped; **P4-1c** per-tenant role codes are blocked until the three approvals call sites are org-aware and `uq_role_code` can be dropped; **P4-2** given teeth — `assertCanConferRole` hard-refuses any non-root caller conferring an `is_system` role, so today a tenant admin cannot grant one of the twelve bundles at all. Plus **P4-4**, a non-root tenant-admin test. | owner |
| 2026-08-14 | **Implementation: Phases 1, 2, 2.5 and Phase 3 batch 1 built, merged to `develop`, verified on QA.** V99/V100/V101 authored and live on QA **and on the live client (1.7.0)**. `TenancyReconciler` (P1-3) fills the alias and stamps customer roles, leaving the thirteen shipped roles global - verified on a restored copy of production (alias `kilimanjaro`, 2 custom roles) and on QA (`erp-qa`, 8 custom roles). Phase 2 complete: the tenant is on the principal, derived from the DB not the JWT claim; the cross-tenant authoriser hole is shut at all three step-up paths with a refusal byte-identical to the unknown-user one; organisation status gates login. **P2.5-1's rule was corrected before it was built** - exempt on a null `userId`, never on a null organisation, which would have handed unscoped sessions to every user created between V101 and the constraining migration; `TenancyScopeEnforcerTest` was verified to fail on the wrong version and only on the right assertion. Phase 3 batch 1 (P3-3, P3-4, P3-5, P3-6, P3-7) scopes the resolution paths, with the **parity harness** that filter-shaped items need because shadow mode cannot observe a query that silently returns fewer rows. Remaining: Phase 3 batch 2 (P3-11 `canActIn` + 130 controllers, P3-12 the 207-entry freeze store, P3-8) and batch 3 (P3-1, P3-2, P3-9, P3-13, P3-14, P3-15). | owner |
| 2026-08-15 | **P3-8 completed — the read-shaped half, and the worst leak in the programme so far.** `AuditReadService` dropped its predicate entirely for root, so on a shared instance a single `is_root` row would read **every customer's audit trail**: who logged in, what they sold, what they were paid. Chasing it surfaced a second defect nobody had noticed - **`AuditLog` had no `organisation_id` field at all**, so although V99 created the column and V101 backfilled it, *nothing had written it since*; every audit row created after V99 was unattributed. The entity now stamps it, and the read predicate is NULL-tolerant precisely so the un-stamped middle period stays readable. Same shape in `UserServiceImpl.list()`, where root's branch was `findAllByOrderByUsername()` - every user in the database, of every customer, by name. `CompanyServiceImpl` took the organisation uid **from the caller** on an `isAuthenticated()`-only endpoint, which also closes the outstanding half of **P3-6**. `StockLocationServiceImpl` moved to the revocation-aware finder so it agrees with P3-14, and `ProductStockReportQuery`'s javadoc describing the old asymmetry was corrected rather than left to mislead. The **`PartyBranchGuard` oracle was fixed narrowly**: cross-tenant collapses to not-found, but a sibling company inside the caller's own organisation keeps the explicit BR-PARTY-01 message - the blanket version would have destroyed a real error message for the multi-company shape every current customer actually has, and bought no security. Two root branch-assignment bypasses (`ProductStockReportQuery:393`, `StockLocationServiceImpl:222`) were **left as-is on purpose**: both are company-scoped through `assertCanActIn`, which P3-11 bounds to the tenant, so what remains is root acting across branches inside its own organisation - exactly what D-2 grants. 1,365 tests green. | owner |
| 2026-08-15 | **P3-9 built and P3-15 closed — Phase 3 is complete except the read-shaped P3-8 leftovers and P3-10.** **P3-9**: the `G11` credential oracle is shut. A post-password refusal used to be classified "not a credential failure" and fed no counter, so an operator could stand at a till confirming a colleague's password an unlimited number of times - every correct guess visibly different from a wrong one - and reuse it at the main login, where the only real throttle lives. `NO_AUTHORITY` and `UNKNOWN_PERMISSION` now advance the caller's throttle; the **message stays distinct**, so a manager who genuinely lacks a permission is never told their password is wrong and sent off to reset it. `SELF_APPROVAL` stays uncounted, for a sharper reason than before: the authoriser IS the caller, so there is no oracle, only friction. The counter runs against the caller, never the authoriser - a cashier still cannot lock a manager out by guessing at them. Two existing assertions had encoded the OLD decision and were deliberately reversed rather than deleted. **P3-15 closed as no-change**: its premise was a successful horizontal escape, which P3-1 removed, and the prescribed fix does not map onto `PermissionResolver`'s company/branch-scoped API without breaking legitimate cross-branch administration - re-open it only with a concrete scenario attached. | owner |
| 2026-08-15 | **Phase 3 batch 3: P3-1, P3-2, P3-13, P3-14 built; P3-9 and P3-15 reassessed and returned as decisions.** All four built items land in `JwtRequestContextFilter`, which turned out to be the right place for three of them at once. **P3-1** refuses a branch override whose company is in another organisation - root included - reusing the *unavailable branch* message verbatim so the refusal cannot confirm that a uid exists elsewhere. **P3-13** reads `is_root` from the row the filter was already fetching, so demotion takes effect on the next request rather than at token expiry, at **no extra query**. **P3-14** honours `revokedAt`/`active`; the plan required checking production first because the fix locks out anyone working through a revoked row - measured **zero revoked and zero inactive on both estates** (QA 11, live client 12), so nobody is affected. **P3-2** adds a `BRANCH.SWITCH` audit written where the switch happens: `ROOT.BYPASS` structurally could not cover it, because after a header switch the principal's company IS the target and its "target differs" test is false - the most powerful scope change in the product left no trace. Two items came back as decisions rather than code. **P3-9's first half was already closed in Phase 2** (both step-up paths compare the authoriser's organisation and collapse a mismatch onto the unknown-user path), and its second half - the `G11` credential oracle - is a *documented deliberate* trade-off, so changing it is the owner's call. **P3-15's severity collapsed once P3-1 landed**: its premise was a successful horizontal escape, which no longer exists, and the prescribed fix does not map onto `PermissionResolver`'s company/branch-scoped API without breaking legitimate cross-branch administration. New `BranchOverrideTenancyTest` (4 tests) was verified against two separate mutations, each failing exactly its own assertions and no others. | owner |
| 2026-08-15 | **Phase 3 batch 2 built: P3-11, P3-12, and the half of P3-8 that was in the authorisation spine.** The organisation comparison now sits **inside** `ScopeGuard.canActIn`, ahead of the `root ||` disjunct - 698 call sites and 89 `@RequestParam companyId` controllers close at one method. Two further root short-circuits were found and shut *because* P3-11 would otherwise have been dead code: `canActOn` returned true for root before ever calling `canActIn`, and `PermissionChecks.scoped`/`scopedOrMember` short-circuited before calling `canActOn`. Root keeps its company-level bypass inside its own organisation; only cross-tenant reach is gone. **P3-11's wording was corrected in the building**: applying the equality *unconditionally*, as written, would lock out any account whose `organisation_id` is still NULL - and since `companies.organisation_id` is already NOT NULL, that is the only way the strict rule could fire on today's estate, so it would be a total lockout with nothing bought. The rule shipped fires on a **positive** mismatch only; the permissive branch is a data gap, not an input, and self-liquidates at P2-1's follow-up. **P3-12 refuted its own premise**: all 207 frozen entries were classified (119 guarded, ~61 loaded-row FK navigation, 13 SYSTEM, 13 read by hand) and **none is exploitable across a tenant boundary** - because URLs address by uid, so caller-supplied numeric ids barely exist. The blessing now rests on two properties of the code rather than on one customer per database. 1,353 tests green; the new rule was verified to fail on the naive `!isSameTenant` form. | owner |
| 2026-08-14 | **§1.2 role classification added, with recommendations.** Corrects the count: **13** shipped `is_system` roles, not 12 — `ORG_ADMIN` is seeded separately at `V1__baseline.sql:289-292`. Separates the two axes that were being conflated: *who owns a role* (global vs scoped) and *who may confer it* (four tiers). Adds worked sample data for two tenants. Two rules fall out: **R-1** a tenant role code may not collide with a global one (V100's partial indexes sit on different partitions, so they permit exactly the ambiguity P4-1c is fixing) → **P4-1d**; **R-2** platform capabilities must never be ordinary permission rows, because `R__seed_permissions.sql:267-274`'s CROSS JOIN gives `ORG_ADMIN` every permission on every deploy → **P4-1e**, which must land *before* P3-10. Recommendations recorded: **ORG_ADMIN stays tier 2** (matching AWS/Azure/Google practice) with MFA on privileged roles (**P4-2b**, un-defers that half of P2-5), a never-zero-admins invariant (**P4-2c**) and a narrowed CROSS JOIN; **`PLATFORM_OPERATOR` becomes a real tier-3 role** and `is_root` is **re-bounded rather than removed** — organisation-bounded root dissolves §8's sharpest risk, since `setRoot(true)` then only makes someone powerful inside their own tenant. D-2 updated with the staged recommendation. | owner |
| 2026-08-14 | **§1.2's recommendations RATIFIED; D-2 and D-3 closed.** Four of eight decisions now resolved. **D-2:** sentinel platform organisation + tier-3 `PLATFORM_OPERATOR` role + `is_root` re-bounded (not removed) to "full authority inside my own organisation", in three stages. Consequence: `app_users.organisation_id` **can** be `NOT NULL` in V101, and every predicate stays total. **D-3:** bundles stay global and `assertCanConferRole`'s blanket "non-root ⇒ refuse" is replaced by a four-part rule — grantee in the caller's own organisation, tier-1/2 conferrable only by a caller who holds it (ADR-0059's subset checks intact), tier-3 never conferrable by a tenant, tier-2/3 requiring MFA and a high-severity audit row. **§8's sharpest risk is now resolved structurally**: organisation-bounded root makes `setRoot(true)` harmless, so the trap dissolves rather than being guarded — *conditional on P3-1 and P3-2 landing before P5-1*, now recorded as a hard prerequisite. P2-5 amended (privileged-account MFA un-deferred; general MFA still deferred). `G4` closed. New **P0-4**: the ceiling change must also amend ADR-0059, or an implementer reading it alone will restore the old refusal. Remaining open: D-4, D-5, D-6, D-8, P0-1b, P0-1c — with **D-5 the day-one operational blocker** and **D-8 a legal gate on tenant #2**. | owner |
| 2026-08-14 | **Verified findings folded in; the plan's own gaps named.** New **D-9** — `organisation_id` on aggregate roots, the last one-way door, previously rejected by omission (204 tables, 608 FKs, zero composite FKs, zero cascades; recommendation (b), the ~20-table hedge). Phase 3 gains **P3-11** (the org check belongs inside `canActIn` — 130 controllers take `@RequestParam companyId`, so P3-1 alone closes one door and leaves 130 open; the largest under-scoping in the plan), **P3-12** (re-triage the 207-entry ArchUnit freeze store as a Phase 3 gate, not P7-4's single `existsById`), **P3-13** (`is_root` from DB not JWT claim), **P3-14** (branch check must honour `revokedAt`/`active`), **P3-15** (the vertical ceiling is resolved in the *current* request scope, so a horizontal escape resets it — ADR-0059 is not an independent layer, which matters now that D-3 builds on it). Phase 5 gains **P5-5** (`leave_types` has no Java provisioning path — every new tenant opens HR→Leave empty) and **P5-6** (`code_sequence` lazy-create races on a new tenant's first busy morning). Phase 8 gains **P8-7** JWT key custody, **P8-8** blast radius + incident comms, **P8-9** the unenforced append-only audit invariant, **P8-10** the capacity floor (Hikari default 10; **one** scheduler thread). §9 records that D-1 = (a) makes `dist/` permanently dual-track. New **§10** — ten live-production items to ship ahead of the programme, independent of tenancy (Swagger open in prod, no IP throttle, the locked-account oracle, …). New **§11** — eight named gaps this plan does *not* fill: no schedule or engineer-time estimate, nothing ever executed, no production deploy runbook, no SLA or incident model, no data-residency answer, the unverified 182-table premise, no per-tenant recovery, and existing customers treated as a risk surface rather than stakeholders. Change log renumbered §10 → §12. | owner |
| 2026-08-14 | **The two-release split WITHDRAWN; Phase 1 is one self-sufficient release.** A 7-agent workflow put both the deployment design and the backfill semantics to adversarial refutation; **both skeptics returned refuted at high confidence.** Four reasons, the first of which applies even on two vendor-controlled deployments: (1) the R1→R2 window **manufactures the NULLs the gate checks for** — `AppUser` has no organisation field until P2-1, so users created between the releases have none and R2's `SET NOT NULL` fails; (2) `cmd_restore` reverts `flyway_schema_history`, so **every pre-R1 backup becomes permanently unrestorable** once a box is on R2; (3) `cmd_update` has **no version-ordering, minimum-version or monotonicity check** (sole guard is CPU arch) and any gate would ship in the *installed* script, which is replaced last — protecting nobody in the field; (4) no telemetry egress and no version register, so "verify in every environment" is unexecutable. V101 now carries its own convergent backfill plus a temporary column DEFAULT (to be dropped in P2-1's migration, named explicitly rather than by omission); the standing-rule tension with *provisioning over data migrations* is recorded as a deliberate exception. **`audit_logs` cut out of Phase 1 entirely** — the two indexes and the whole-table UPDATE were the real unbounded work, inside a hard-coded 900s `wait_healthy` window, on a table inflated by `ROOT_BYPASS` rows with no purge path; the `ADD COLUMN` stays, the rest defers to a post-readiness background pass. Backfill semantics corrected: **do not classify roles by `is_system`** (the seed's `ON CONFLICT … SET is_system = true` has already adopted customer roles, and `Role.createdBy` is never set, so no discriminator survives) — **P1-6 becomes a prerequisite, not a follow-up**; the audit key becomes `COALESCE(company→org, actor→org)`; `pg_advisory_xact_lock` replaces the session-scoped `pg_try_advisory_lock`; no clean-skip; not gated on `ERP_BOOTSTRAP_ENABLED`; log residual NULL counts. V100's role indexes flagged **inert** while `uq_role_code` is retained — resolved by sequencing behind P4-1c. New **P1-0** (measure before authoring), **P1-8** (three inferred claims to prove on a restored dump), **P1-9** (CI: `OrganisationController` has no write mapping — the one surviving ordering rule). §10 gains **H-11** — `OrbixERP.cmd` references `erp.ps1` while every bundle ships `orbixerp.ps1`, **broken in all 11 shipped bundles**, leaving Windows customers with no working self-service surface — plus **H-12** and **H-13**. | owner |
| 2026-08-14 | **P1-8 CLOSED — proven with real Flyway and a real application boot.** Three files placed in `db/migration` on a scratch branch against a clone of the live customer's database: the migration-hygiene gate passes, **Flyway applied V99/V100/V101 in 173 ms** (the `DO $$` block — the first dollar-quoted body in 98 migrations — parsed correctly), a second boot reported *"Schema up to date"* having validated 104 migrations, and a third boot **started the application fully** (`Started ErpApplication in 18.383 seconds`) with **`ddl-auto: validate` raising no schema-validation error against the 35 new columns**. 12/12 users attributed, 0 failed migrations. Scratch branch deleted; `db/migration` is back at V98. **One claim corrected across three documents:** the plan, ADR-0062 and the rehearsal runbook all said `R__seed_permissions.sql` *"re-runs on every deploy, in every environment"*. It does not — Flyway re-applies a repeatable migration only when its **checksum changes**. Measured on the live customer's own `flyway_schema_history`, the seed has run **three times ever** (2026-08-02, 2026-08-10, 2026-08-12) and did not re-run across three consecutive boots. G8's conclusion and R-2 both survive, because the triggering edit is exactly what P1-6 and P3-10 are — but **§6's "run the release twice" test is invalid as written** and has been replaced: to test the seed, edit it the way the release will, then boot. | owner |
| 2026-08-14 | **New §12 — release strategy.** Answers two questions the phase list never did: deploy per phase or once at the end, and when the customer is told. Records the deployment fact that constrains everything: **web and API are ONE artefact** (`infra/prod/Dockerfile` compiles the Angular SPA into the Spring Boot jar's static resources), so every deploy ships both and the SPA updates itself — the **POS is the only separately-deployed client and on current scope needs no change at all**, to be confirmed before P2-2c. **Three environments, not two:** QA stays permanently single-tenant and mirrors production, one batch ahead; a new **disposable rehearsal stack** restored from a production dump carries the migration rehearsals and the two-org probe; production is continuously updated. Rationale: QA has one durable never-wiped volume, so giving it a second organisation would permanently disqualify it from validating the single-tenant releases the live customer actually runs. **"All 8 phases on QA, then one production update" evaluated and rejected**, with five reasons — QA's synthetic data cannot exercise the real risk (migration lock time, `audit_logs` volume, already-adopted `is_system` roles); it concentrates all risk into the one deploy with the worst rollback; **shadow mode becomes theatre** without real traffic; QA cannot be single- and multi-tenant at once; and production would sit unpatched for months while `develop` drifts. **Replaced by: stream the invisible work, batch the visible work** — so the customer perceives *one* change (Phase 6), while the technical risk stays spread across many small revertible deploys. Adds the **A–H stage sequence** with deploy counts and what the client sees at each, and records the key scheduling call: **Phase 6 is gated only on Phase 2, so run it in parallel with Phase 3** rather than after it — Phase 3's pace is set by observation windows, which the web work can absorb for free. §12.5 notes the single customer-facing communication is Phase 6 and is about screens, not credentials — the payoff of never rewriting usernames. Change log renumbered §12 → §13. | owner |
| 2026-08-14 | **Implementation started — Stage A opened on `feat/multitenancy-phase-0`.** Two decisions closed, both of which gated Phase 1's DDL. **D-9 resolved (b)**: the ~20 aggregate-root hedge, with columns added **nullable in V99 and left unconstrained** — populated forward by app code from Phase 2 and backfilled by a bounded background pass, deliberately *not* in V101's `NOT NULL` set, since backfilling twenty transactional tables would give back exactly the migration window that cutting `audit_logs` bought. Root list verified against the shipped schema, with a naming correction: the table is **`stock_movements`**, not `stock_move` as §9 and CLAUDE.md invariant 9 both say. **P0-1b promoted to D-10 and resolved**: email scoped to `(organisation_id, email)`, swapped in V101 *after* the `SET NOT NULL` (a composite unique would otherwise permit duplicates while the column is nullable) — inert at one organisation, and it costs §5.1 its "purely additive `app_users`" claim, which is now stated plainly rather than implied. Adds [`docs/ops/multitenancy-phase0-measurements.sql`](docs/ops/multitenancy-phase0-measurements.sql), a read-only script closing P0-1c and P1-0 across prod and QA. **ADR numbering corrected: next free is ADR-0062**, not 0060 — both 0060 and 0061 are taken. Six of ten decisions now closed; every decision gating Phase 1 is closed. | owner |
| 2026-08-14 | **P0-1c / P1-0 MEASURED on QA and on the live production client.** 19 read-only blocks, zero errors on both; a fresh backup taken on each box first (production via the supported `orbixerp.sh backup`). **The go/no-go gate passes: `organisations = 1` on both** — QA "ERP QA", production "Kilimanjaro" — so §5.1's self-sufficient V101 is valid as written. Full results recorded against P0-1c. **One plan claim is refuted by the data:** the `ROOT_BYPASS` inflation behind the `audit_logs` cut is a **QA artefact** — QA is 28% `ROOT.BYPASS`, production has none in its top 15 and is dominated by real business actions, at 6,265 rows / 3.4 MB. The cut is now hygiene rather than necessity and the §5.1 box says so; the reasoning must not be restated as "the table is huge". **Three findings strengthen the design:** COALESCE beats actor-only by **545 rows (8.7%)** on production versus 86 on QA, validating the step-3 key correction; `pass_d = 0` on production, so the sole-organisation fallback carries **nobody** and the V101 derivation is robust on real data; and **zero users have an email on either estate**, making D-10's index swap a literal no-op. **Two items cleared:** H-5 ships safely (0 revoked, 0 inactive `user_branch` rows on both), and P1-8's `permission denied` risk is resolved. **One item confirmed on a live client:** the append-only audit invariant **was never applied** — the `erp` role holds UPDATE, DELETE and TRUNCATE on `audit_logs` in production (**P8-9**). **Production topology recorded:** the client runs `orbixerp-api:1.6.1` from a `dist/` bundle with native host Postgres at `/opt/orbixerp`, not the `infra/prod` compose topology — so §5.1's and §10's offline-estate analysis applies to a real paying customer. | owner |

---

### Provenance

Findings verified by a read-only 12-agent audit (5 lenses → per-lens skeptic → completeness critic →
synthesis), 43 findings raised, 37 survived refutation, 399 tool calls. Full register with all
file:line evidence: <https://claude.ai/code/artifact/d274eaf8-3bd8-4b51-a1f2-346d438d3dfa>

The 2026-08-14 revisions come from a second, independent 14-agent workflow (5 discovery lenses → 3
competing designs → a 3-lens judge panel → 2 adversarial refuters + a completeness critic; 371 tool
calls, 0 errors). **Both refuters returned `refuted = true` at high confidence** against the panel's
own recommendation. The one-codebase conclusion survived unanimously — the fork option scored 0 of 3
— but the safety argument for it did not, and §0.1 is the result.

Standing rules that constrain this plan: migrations frozen and additive-only; DB durable in every
environment; no Flyway migration authored without explicit DDL approval; provisioning over data
migrations; branch off `develop` and PR into it, never touch `main`.
