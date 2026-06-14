# ERPCLEAN2 — System Documentation

_ERPCLEAN2 — modular-monolith ERP (Spring Boot + Angular + PostgreSQL). Generated from the live codebase + the verified test-case suite._

## Contents

1. ERPCLEAN2 — System Overview
2. Architecture
3. Module Catalog
4. Data Model
5. Security
6. Deployment and Operations
7. Engineering Conventions
8. Testing

---

# ERPCLEAN2 — System Overview

ERPCLEAN2 is a full, multi-company Enterprise Resource Planning system built as a **modular monolith**: one Spring Boot application, one Angular web client, one PostgreSQL database. It runs the back-office of a trading and light-manufacturing business end to end — from a sales quotation to the posted journal entry behind it, from a purchase requisition to the supplier payment, from a stock receipt to the inventory valuation on the balance sheet.

This document orients a new engineer or implementer. The companion documents go deeper:

- `01-architecture.md` — layering, the request lifecycle, the transactional outbox, the GL posting engine.
- `02-module-catalog.md` — every module, its endpoints, entities, permissions, and governing ADR.
- `03-data-model.md` — the identity model, money model, multi-tenancy, and per-domain table summary.

## What ERPCLEAN2 is

A single deployable system covering the standard ERP surface:

| Area | What it does |
|---|---|
| Finance — General Ledger | Chart of accounts, double-entry journals, fiscal calendar, trial balance, year-end close. The posting engine every financial module feeds. |
| Accounts Receivable | Customer open items, receipts with allocation, credit notes, write-offs, opening balances, statements and ageing. |
| Accounts Payable | Supplier bills with 3-way match, single + run payments, debit notes, opening balances, statements. |
| Cash & Bank | Cash/bank accounts, transfers, direct entries, bank reconciliation, cheque register. |
| Tax | VAT return lifecycle (open → recompute → file) with adjustments; WHT types and the WHT register/certificates. |
| FX / Multi-currency | Currency master, effective-dated rates, foreign-currency posting against a base ledger, FX revaluation runs. |
| Sales (Order-to-Cash) | Quotation → Sales Order → Delivery → Sales Invoice → Sales Return, plus advanced pricing rules and price lists. |
| Point of Sale | Till setup, session lifecycle (open float → sell → X-read → close → reconcile/Z-read), quick checkout. |
| Procurement (Procure-to-Pay) | Requisition → RFQ → supplier quote → Purchase Order → Goods Receipt → landed cost → supplier bill → 3-way match → purchase return. |
| Inventory / Stock | On-hand by location/product, movements, adjustments, transfers, counts, batches/lots, serials, valuation. |
| Manufacturing | Multi-level Bills of Materials, Work Orders (release → issue → cost → complete), WIP reconciliation. |
| Fixed Assets | Asset categories, register, depreciation runs, revaluation, disposal, FA→GL reconciliation. |
| HR & Payroll | Departments, employees, contracts, leave, loans, pay components, payroll runs (calculate → approve → post → disburse), statutory setup. |
| CRM | Leads, opportunities, pipeline analytics, stages, activities; conversion to Quotation/Sales Order. |
| Projects (Job Costing) | Projects, tasks, timesheets, issue-materials-to-project, per-project P&L and cross-project WIP. |
| Budgeting | Budget headers + version lifecycle, line entry modes, budget-vs-actual variance reporting. |
| Reporting & BI | P&L, Balance Sheet, Cash Flow, trial balance, account-ledger drill-down, BI analytics dashboards, analytical reports. |
| Platform services | Documents (PDF render/templates/branding), notifications, approvals engine, cost-centre dimensions, audit trail. |

All business modules sit on a shared **platform spine**: identity & access (IAM), security/RBAC, multi-tenancy, the document-numbering service, the transactional outbox, money/currency, and audit.

## The modular-monolith philosophy

ERPCLEAN2 is deliberately **not** microservices. It is one process with hard internal boundaries:

- **One deployable, one database.** Simple to run, simple to reason about, simple to deploy. A single Spring Boot container against a single PostgreSQL instance. Splitting a module out later is straightforward; recombining microservices is not — so the system starts combined and keeps the option open.
- **Boundaries enforced, not merely documented.** Modules live under `com.erp.modules.<name>`; cross-cutting infrastructure under `com.erp.platform.*`; REST controllers flat under `com.erp.api`. An ArchUnit test (`ModuleBoundaryTest`) fails the build if a controller touches a repository directly, or if one module imports another module's entity or service.
- **Modules talk through contracts, never internals.** A module exposes only its `domain.dto` / `domain.enums`. Cross-module *side effects* go through the **transactional outbox** — a `domain_events` row written in the same database transaction as the business change, dispatched by an in-process poller to in-process consumers. A sale finalising never calls into the stock module directly; it publishes `SALE.FINALISED`, and stock consumes it. (See `01-architecture.md`.)
- **The boring option, applied consistently.** Spring's first-class primitives, JPA's normal patterns, Flyway for every schema change, standard PostgreSQL features used deliberately (`JSONB`, partial/expression indexes, `NUMERIC`, sequences). No clever pattern survives without a written ADR.

## System invariants (the non-negotiables)

Every module obeys the same handful of rules. They are described fully in the companion documents; in brief:

1. **API envelope.** Every REST response is wrapped in `ApiResponse<T>` (`data`, `errors[]`, meta). Controllers return raw `T`; an interceptor wraps it. The web client unwraps it before services see it.
2. **Identity duality.** Every externally exposed entity carries a numeric `id` (internal, for joins) and a stable external `uid` (a ULID). **URLs address by `uid`**; the numeric `id` never appears in a URL and is never shown to users. Pickers resolve a human name to a `uid`.
3. **Multi-tenancy.** Organisation → Company → Branch. Every transactional table carries `company_id` (and `branch_id` where a branch boundary applies). A `RequestContext` sets the active company/branch from the JWT plus an optional branch-override header; repository base interfaces inject the tenant predicate automatically.
4. **RBAC by permission, not role.** The atomic unit is a **permission code** (dot-separated, e.g. `SALES.INVOICE.POST`). Roles are bundles of permissions. `@PreAuthorize` checks reference permission codes, never role names. Permissions are seeded via Flyway.
5. **Money is currency-aware.** Every amount is an `(amount, currency)` pair — `BigDecimal` in Java, `NUMERIC` in PostgreSQL, a string on the wire (`{ "amount": "1500.0000", "currency": "TZS" }`). Base currency is TZS, configurable per company; foreign-currency documents carry the base-currency equivalent and the rate used.
6. **Append-only posting.** Posting tables (the GL, stock movements, the audit log) are never updated or deleted — corrections are new, reversing entries.
7. **Cross-module side effects = transactional outbox.** Never an in-memory event, never a direct call into another module.

## Technology stack

| Layer | Technology |
|---|---|
| Backend runtime | Spring Boot 3.3.5 · Java 21 |
| Persistence | Hibernate 6 (JPA) · Spring Data · PostgreSQL 15+ |
| Schema management | Flyway (`ddl-auto=validate`; migrations `V1` … `V83`) |
| Authentication | In-house JWT, **RS256** signing, refresh-token rotation; Spring Security OAuth2 resource server |
| Authorization | RBAC by permission code, enforced with `@PreAuthorize` and a `@perm` expression bean |
| Web client | Angular 21 · standalone components (no NgModules) · signals · TypeScript strict |
| Web build | `ng` / npm |
| Backend build | Maven (`mvn`) |
| Testing — backend | JUnit 5 · Spring Boot Test · **Testcontainers** (integration tests against real PostgreSQL) · ArchUnit |
| Testing — web | Unit tests · Playwright e2e · axe-core (WCAG 2.1 AA gate) |

Money is stored as `NUMERIC(19,4)` (rates `NUMERIC(19,8)`), serialised as strings on the wire. 64-bit `id` fields serialise as JSON strings globally so they survive JavaScript's number precision.

## High-level architecture

```
                       ┌───────────────────────────────────────────┐
                       │            Angular 21 web client            │
                       │   standalone components · signals · strict  │
                       │   HTTP interceptor unwraps ApiResponse<T>   │
                       └─────────────────────┬───────────────────────┘
                                             │  HTTPS
                                             │  Authorization: Bearer <JWT, RS256>
                                             │  X-Branch-Uid: <branch override>
                                             ▼
   ┌──────────────────────────────────────────────────────────────────────────┐
   │                    Spring Boot 3.3.5 API  (single container)               │
   │                                                                            │
   │  com.erp.api.*Controller        REST, flat, one per resource               │
   │        │  (returns raw T; ResponseBodyAdvice wraps → ApiResponse<T>)       │
   │        ▼                                                                    │
   │  com.erp.modules.<name>.service      @Transactional · owns invariants      │
   │        │                                                                    │
   │        ▼                                                                    │
   │  com.erp.modules.<name>.repository   Spring Data · tenant predicate        │
   │        │                                                                    │
   │        ▼                                                                    │
   │  com.erp.modules.<name>.domain       entity · dto · enums · event          │
   │                                                                            │
   │  ┌──────────────────────── platform spine ────────────────────────────┐   │
   │  │ security (JWT, RequestContext, @perm/RBAC, ScopeGuard)              │   │
   │  │ iam · company  ·  sequence (document numbering)                     │   │
   │  │ events (transactional outbox: domain_events + @Scheduled poller)   │   │
   │  │ audit (aspect → audit_log, append-only)  ·  common (ApiResponse,    │   │
   │  │ Money, UidEntity, error model)                                      │   │
   │  └────────────────────────────────────────────────────────────────────┘   │
   │                                                                            │
   │  Cross-module link:  module A  ──writes domain_events (same TX)──►          │
   │                      poller  ──invokes──►  module B's DomainEventHandler    │
   └─────────────────────────────────────┬──────────────────────────────────────┘
                                          │  JDBC
                                          ▼
                       ┌───────────────────────────────────────────┐
                       │              PostgreSQL 15+                  │
                       │  Flyway migrations validated on start       │
                       │  business tables: company_id (+ branch_id)  │
                       │  outbox: domain_events / processed_events   │
                       │  ledger: journal_*, stock_movements (append)│
                       └───────────────────────────────────────────┘
```

The request path is strictly **controller → service → repository → entity**. Controllers never touch repositories. Modules never import each other's entities or services — they exchange DTOs/enums and communicate side effects only through the outbox. The platform spine is the only thing every module is allowed to depend on.

---

# Architecture

This document is the technical architecture of ERPCLEAN2: how the code is structured, how a request flows, how modules integrate without coupling, and how the two posting engines — the transactional outbox and the General Ledger — work. It is grounded in the shipped code, `ARCHITECTURE.md`, `PROJECT-CONVENTIONS.md`, and the ADRs in `docs/decisions/`. Where a rule originates in an ADR it is cited.

## 1. Layered / modular structure

ERPCLEAN2 is a **modular monolith** (`ARCHITECTURE.md` §1, `PROJECT-CONVENTIONS.md` §2). One Spring Boot process, one Angular client, one PostgreSQL database, with hard internal boundaries enforced by package layout and an ArchUnit test (`ModuleBoundaryTest`).

### Package layout

```
com.erp
├── api/                      REST controllers — flat, one per resource
│                             (e.g. SalesInvoiceController, JournalController)
├── platform/                 cross-cutting infrastructure (every module may depend on it)
│   ├── common/               ApiResponse<T>, ResponseBodyAdvice wrapper, UidEntity,
│   │                         ULID generator, Money embeddable, error model, validation
│   ├── security/             JWT issue/verify, RequestContext filter, @perm (PermissionResolver),
│   │                         ScopeGuard (uid → scope resolution), branch-override validation
│   ├── iam/ · company/       AppUser/Role/Permission · Organisation/Company/Branch
│   ├── sequence/             document numbering (code_sequence, row-locked allocation)
│   ├── events/               transactional outbox: domain_events + @Scheduled dispatcher
│   ├── audit/                audit aspect → audit_log (append-only)
│   └── ...
└── modules/
    └── <name>/               one package per business area
        ├── domain/
        │   ├── entity/       JPA entities (extend the UidEntity shape)
        │   ├── dto/          *Dto records — the only cross-module surface
        │   ├── enums/        domain enums
        │   └── event/        outbox event payloads
        ├── service/          interface Xxx + class XxxImpl; @Transactional at public methods
        └── repository/       Spring Data; one aggregate per repository; findByUid + scoped finders
```

### Layering (ArchUnit-enforced)

The layering inside any module is strict, top to bottom (`PROJECT-CONVENTIONS.md` §2):

1. **Controller** (`com.erp.api`) — HTTP only: validate request DTOs (`@Valid`), gate with `@PreAuthorize`, call a service, return the raw `T`. No business logic, **no repository access**.
2. **Service** (`<module>.service`) — `interface Xxx` + `class XxxImpl`, `@Transactional` at public methods. Owns the invariants and the state transitions.
3. **Repository** (`<module>.repository`) — Spring Data, one aggregate per repository. `Optional<X> findByUid(String)` plus scoped finders. The repository base interface injects the tenant predicate.
4. **Domain** (`<module>.domain`) — entities, DTOs, enums, event payloads.

`ModuleBoundaryTest` fails the build on two violations: a controller touching a repository, and a module importing another module's entity or service. The only cross-module surfaces are `..domain.dto..` / `..domain.enums..` and the outbox. The platform packages (`security`, `events`, `audit`, `common`, …) are on the allow-list — modules depend on them, never the reverse (ADR-0009 D-1).

## 2. The request lifecycle

A typical authenticated write request flows like this:

1. **Web client** sends the request over HTTPS with `Authorization: Bearer <JWT>` and, when the user has switched branch, an `X-Branch-Uid` header. An Angular HTTP interceptor will later unwrap the `ApiResponse<T>` envelope before the calling service sees it.
2. **Security filter chain** validates the RS256 JWT (Spring Security OAuth2 resource server). A request-scoped **`RequestContext`** is built from the JWT (user, active company, default branch, `isRoot`) plus the optional `X-Branch-Uid`. If the header is present, the filter verifies the branch is in the caller's active `user_branch` assignments (else 403); the active company becomes that branch's company. Branch switching is context-only — no DB write, no re-login (ADR-0003).
3. **Controller** (`com.erp.api`) receives the request. `@Valid` validates the request DTO. `@PreAuthorize` evaluates a permission expression — `@perm.has('SALES.INVOICE.VIEW')` for a flat permission, or `@perm.scoped(#uid, 'invoice', 'SALES.INVOICE.CREATE')` when the permission must be checked against the scope (company/branch) that the target `uid` resolves to. `ScopeGuard` maps the `uid` and its kind (`invoice`, `account`, `company`, …) to a company/branch and asserts the caller may act there. `isRoot` short-circuits to allowed, always audited.
4. **Service** runs inside a `@Transactional` boundary. It loads aggregates through repositories, enforces invariants, mutates state, and — for cross-module side effects — calls `OutboxPublisher.publish(...)` **inside the same transaction**.
5. **Repository** issues the query. The base interface injects the `company_id`/`branch_id` predicate from `RequestContext`, so a finder cannot accidentally read another tenant's rows.
6. **Response** — the controller returns the raw `T`; a `ResponseBodyAdvice` wraps it in `ApiResponse<T>` (`data`, `errors[]`, meta). Errors are user-safe strings; internal exception text is never leaked.

Identity discipline runs through the whole path: URLs address by `uid`, request/response DTOs carry both `id` and `uid`, and all `Long` ids serialise as JSON strings so 64-bit values survive JavaScript (`PROJECT-CONVENTIONS.md` §3.3).

## 3. Authentication & authorization

- **Login** (`POST /api/v1/auth/login`) verifies username + bcrypt password (cost ≥ 12) and issues an **access JWT (RS256, short-lived)** carrying the user id, username, active company/branch (the user's default), and `isRoot`, plus a **refresh token** stored as a SHA-256 hash, single-use and rotated (ADR-0001, ARCHITECTURE.md §4). Failed logins increment a counter; at the threshold the account is locked for a window.
- **Default context** at login is the user's default branch (`user_branch.is_default`) and its company.
- **Refresh** (`POST /api/v1/auth/refresh`) looks the token up by hash; a presented token already rotated/revoked is treated as reuse and the whole chain is revoked.
- **Authorization** is by **permission code**, never role name. The `@perm` bean computes the effective permissions for the active (user, company, branch) from `user_role` + `role_permission`, cached briefly per scope. `isRoot` bypasses scoping but is always audited (ADR-0002).

## 4. Multi-company / multi-branch

The tenant tree is **Organisation → Company → Branch** (`DATA-MODEL.md`, ADR-0001). Books are kept at **company** level; branch is the operating unit for stock and a business day, and an analysis tag on financial postings.

- Every transactional table carries `company_id`; tables that cross a branch boundary also carry `branch_id`.
- `RequestContext` (request-scoped) holds the active company/branch. The branch-override header (`X-Branch-Uid`) switches branch within the caller's assignments without a re-login (ADR-0003).
- Repository base interfaces inject the tenant predicate. A finder that bypasses the base interface is a tenant-isolation bug. IAM admin tables are exempt from the blanket predicate (administration is cross-branch); isolation there is by permission + explicit scope checks (ADR-0001 D-A).

## 5. The transactional outbox + domain-events pattern (ADR-0009)

The outbox is the **only** sanctioned channel for a cross-module side effect. It exists so that a business change in module A reliably causes an effect in module B **without A calling into B** (which would break `ModuleBoundaryTest` and couple their transactions), and **without losing the event on a crash** (which an in-memory `ApplicationEventPublisher` would).

### Mechanics

- **Producer.** Inside its own `@Transactional` method, a producer calls `OutboxPublisher.publish(eventType, aggregateType, aggregateId, aggregateUid, companyId, branchId, payload)`. The implementation does a plain `save` of a `domain_events` row — **no new transaction** — so the event row commits if and only if the business change commits. This same-TX guarantee is the heart of the pattern: `publish` must never run in `REQUIRES_NEW` (ADR-0009 D-3).
- **Table.** `domain_events` is append-only: `id`, ULID `uid`, `event_type` (`MODULE.EVENT`, e.g. `SALE.FINALISED`), `aggregate_type`/`aggregate_id`/`aggregate_uid`, `company_id` + nullable `branch_id` (the originating tenant scope), a `JSONB payload`, `status` (`PENDING` | `DISPATCHED` | `FAILED`), `occurred_at`/`dispatched_at`, `attempt_count`/`last_error`/`version`. A **partial index on `status = 'PENDING'`** keeps the poller's claim query fast no matter how many `DISPATCHED` rows accumulate (ADR-0009 D-2).
- **Dispatcher.** A single `@Scheduled` in-process poller reads a bounded batch of `PENDING` rows oldest-first by `occurred_at`. For each event, in its own per-event transaction, it invokes every registered `DomainEventHandler` whose `eventType()` matches. All handlers succeed → `DISPATCHED`. A handler throws → `attempt_count++`, record `last_error`, leave `PENDING` to retry; at the cap, park `FAILED` and skip. **A poison event never blocks the queue** — `FAILED` and attempt-exhausted rows are filtered out of the claim (ADR-0009 D-4/D-7).
- **Consumer.** A consumer is a module bean implementing `platform.events.DomainEventHandler`. It runs as the *system* (no JWT), so it sets the working company/branch **from the event** before applying its effect.

### At-least-once delivery and idempotency

A polled outbox cannot give cheap exactly-once delivery: a crash between "consumer succeeded" and "row marked DISPATCHED" redelivers the event. The system is therefore **at-least-once + consumer-side idempotency** (ADR-0009 D-6):

- A generic **`processed_events`** marker table, keyed `UNIQUE (consumer, event_uid)`, is written by the consumer **in the same transaction as its effect**. On a redelivery the marker insert collides → the handler no-ops. Because the marker and the effect commit together, the effect is applied exactly once.
- Strong consumers also stamp the source event uid on their own ledger (e.g. stock movements carry `source_event_uid`) as a DB-level backstop and for traceability.

### Ordering and compensation

Ordering is **best-effort by `occurred_at`** — enough to give per-aggregate causal order (a `SALE.FINALISED` precedes its `SALE.VOIDED`), which is all that matters because consumers are idempotent. It is not a strict global FIFO (one stuck row would wedge the queue). Compensation is the **consumer's** job: a `SALE.VOIDED` event triggers the consumer's reversal logic (the stock module posts a reversing movement; GL posts a reversing journal). The outbox is a delivery channel, not a saga orchestrator.

### The events in flight

The system's principal cross-module events are sales finalisation/void (`SALE.FINALISED` / `SALE.VOIDED`) and goods receipt (`STOCK.RECEIVED`), plus the approvals engine's `APPROVAL.RESOLVED`. The stock module consumes the sales/receipt events to move inventory; the GL module consumes the sales events to post revenue/VAT/AR (or cash) journals — both off the same single fired event, each idempotently.

### Scaling

Built lean for a single container — one poller cannot double-dispatch. The multi-instance upgrade is a one-method swap of the claim query to `SELECT … FOR UPDATE SKIP LOCKED` (the same row-lock discipline `code_sequence` already uses), additive, no schema or consumer change. It must be applied **before** running more than one API container (ADR-0009 D-4, flagged).

## 6. The GL posting engine + double-entry invariants (ADR-0013)

The General Ledger is the system's first and central **posting engine** and the critical-path gate for the entire financial roadmap — AR/AP, Cash/Bank, COGS, the VAT return, and all of Reporting post into or read from it.

### Structure

The posting structure is three levels (ADR-0013 D-2/D-3):

- **`journal_batches`** — the numbered container (`JB-####` from `code_sequence`) a posting run groups its entries under. Source type: `MANUAL` | `SALES` | `SALES_REVERSAL` | `OPENING_BALANCE` (the IN-list widens additively as new posters land).
- **`journal_entries`** — one balanced transaction: `posting_date` (drives the fiscal period), `description`, `source_type`/`source_ref`, the resolved `fiscal_period_id`, and `reversal_of_id` (self-FK, set on a reversing entry).
- **`journal_lines`** — one leg: exactly one `account_id`, and **two amount columns `debit_amount` / `credit_amount` with exactly one non-zero** (not a single signed amount — it matches accounting vocabulary and makes the trial balance the natural `SUM(debit) − SUM(credit)`).

Accounts live in **`chart_of_accounts`** (flat, numeric-range, per company), each with an `account_type` (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE) and a stored `normal_balance`. Posting is gated by an OPEN **`fiscal_periods`** row (12 monthly periods per `fiscal_years`, configurable start month).

### The double-entry invariants

Some invariants the database can enforce per row; the cross-row ones the service must (ADR-0013 D-3):

| Invariant | Where enforced | Mechanism |
|---|---|---|
| A line is one account, one side, both non-negative | **DB CHECK** | `chk_journal_line_one_side` |
| An entry has ≥ 2 lines | **service** | `GLPostingService` rejects < 2 lines |
| Σ debits == Σ credits (balanced or rejected) | **service** | `BigDecimal` value compare; nothing partial is written |
| `posting_date` falls in an OPEN period | **service** | `FiscalPeriodResolver`; reject if none/CLOSED |
| Every posted-to account is active | **service** | checked at post; a since-deactivated account on a *historical* line stands |
| One company's books are isolated | **DB + service** | `company_id` NOT NULL + FK; tenant predicate |
| Append-only / immutable | **structural** | no `updated_*` columns on batch/entry/line; no update/delete path |
| Idempotent auto-posting | **service + DB** | `processed_events` marker + partial-unique `(company_id, source_type, source_ref)` for SALES entries |

`GLPostingService.post(...)` is the **single engine** both manual and automatic posting go through: resolve the open period → validate lines (≥ 2, one-sided, active accounts, base currency) → assert balanced → allocate the batch number → insert batch + entry + lines in one transaction → emit the audit row. There is **no draft state**: a journal either posts (balanced, open period, active accounts) or is rejected. The only correction is a **reversing entry** — a new entry that swaps the original's debits and credits, balanced by construction, linked by `reversal_of_id`. The original stays on the books beside its reversal (ADR-0013 D-3).

### Sales auto-posting (the integration centrepiece)

GL is a pure outbox **consumer**. `SalesPostingHandler` consumes `SALE.FINALISED`; `SaleVoidingHandler` consumes `SALE.VOIDED`. The event payload carries no amounts, so the handler **re-reads the invoice totals** (via a Sales service-layer DTO, never importing a Sales entity) and posts a balanced entry resolving accounts from **`gl_configs`** (the per-company role→account map: `SALES_REVENUE`, `VAT_PAYABLE`, `ACCOUNTS_RECEIVABLE`, `CASH`, …). A missing or inactive required mapping makes the handler throw, so the outbox retries/parks the event rather than mis-posting (ADR-0013 D-5/D-6) — finance fixes the mapping and the parked event replays.

## 7. Multi-currency posting (base ledger) (ADR-0005, ADR-0036)

Money is currency-aware from the first column (ADR-0005). The principle and the storage:

- A monetary value is an inseparable `(amount, currency)` pair — a `Money` `@Embeddable` in `platform.common` materialising a `<field>_amount` `NUMERIC(19,4)` + `<field>_currency` `CHAR(3)` column pair. There is no bare-number money column anywhere.
- **Amounts** are `BigDecimal`/`NUMERIC(19,4)`; **rates** `NUMERIC(19,8)`; never `float`/`double`. Rounding is HALF_UP to the currency's minor unit, at the boundary only.
- Each **company** has a configurable **base (functional) currency** (default TZS), read from config — never a hard-coded literal.
- **The GL posts in base currency only.** When a document is in a foreign currency, it stores the document-currency amount, the **base-currency equivalent**, and the **exchange rate used**, captured at transaction time and immutable thereafter (a posted historical amount is never recomputed when rates move). The GL receives the base-currency figures; the trial balance and statements are base-currency (ADR-0013 D-9, ADR-0005 D-5).
- Mixed-currency arithmetic is illegal at the type level: `Money.plus`/`minus`/`compareTo` throw on a currency mismatch. A "total balance" across currencies is not a scalar `SUM` — it is per-currency balances plus an explicit, rate-stamped conversion.
- The full FX engine (effective-dated rate master, conversion, realized FX on settlement, period-end **unrealized** revaluation runs) is built (ADR-0036, V77–V80); the recording shape ADR-0005 reserved meant adding it was additive, not a migration of live posting tables.

On the wire, money is an object — `{ "amount": "1500.0000", "currency": "TZS", "display": "TZS 1,500.00" }` — with `amount` as a **string** (exact end-to-end; JSON numbers would lose precision in JavaScript). The Angular type is `interface Money { amount: string; currency: string; display?: string; }`.

## 8. How modules integrate (events, not direct calls)

The integration rule is absolute: **modules never call each other's services for a side effect, and never import each other's entities.** The two legitimate cross-module surfaces are:

1. **DTOs / enums** — a module may read another module's data through a published `domain.dto` service method or a scalar-id projection (e.g. GL re-reading invoice totals through a Sales DTO). It never imports the other module's entity.
2. **The transactional outbox** — for an *effect*. The canonical chains:
   - **Sales → Stock / GL.** A finalised invoice publishes `SALE.FINALISED`; the stock module deducts inventory (exploding recipes for composed products) and the GL module posts the revenue/VAT/AR-or-cash journal — each off the one event, each idempotent. A void publishes `SALE.VOIDED`; both modules post their reversals.
   - **Procurement → Stock.** A goods receipt publishes `STOCK.RECEIVED`; the stock module records the receipt movement.
   - **Approvals → consumers.** The approvals engine publishes `APPROVAL.RESOLVED` for asynchronous consumers, and exposes a synchronous `getApprovalState` gate for modules that must block on approval (ADR-0022).

This keeps `ModuleBoundaryTest` green (no module→module edge), keeps each module's transaction its own, and makes every cross-module effect crash-safe and idempotent.

## 9. Persistence & migrations

- **Flyway** owns every schema change; `ddl-auto=validate` (never `update`). Migrations are sequential and additive once shipped — `V1` (IAM baseline) through `V83`. A new module lands as a new `V<n>` file and never edits a shipped one.
- **Optimistic locking** (`@Version`) on mutable aggregates.
- **Append-only** posting tables (the GL, `stock_movements`, `audit_log`) — corrections are new rows.
- **PostgreSQL-native where it pays**: `JSONB` for audit detail / tax summary / event payload; partial and expression indexes (the outbox `PENDING` index, the default-branch uniques `WHERE is_default`); `NUMERIC` for money; row-locked `code_sequence` for concurrency-safe document numbering.

## 10. Audit

An **audit aspect** writes `audit_log` rows for significant actions (user/role/branch changes, password reset, lockout, login success/failure, posting events) — written by the aspect, not the calling code, so it cannot be forgotten (ADR-0004). The log is **append-only**: no `UPDATE`/`DELETE` through the application, and the deploy grants the app DB role no update/delete on the table. `detail` is `JSONB` (before/after or context). The outbox itself does not write audit — the business action audits, the `domain_events` row records delivery, and the ledger records the effect; three append-only trails, no redundancy (ADR-0009 D-9).

## 11. Deployment topology

A single Spring Boot container against a single PostgreSQL. Flyway validates the schema on start. The dispatcher is a single `@Scheduled` bean — correct under one container. Local development runs PostgreSQL in Docker (port 5434), the backend on 8081 (a dev profile bootstraps the root admin), and `ng serve` on 4200; production loads a stable RS256 key from a secret store (a gating item before any prod deploy). Running more than one API container requires the outbox `SKIP LOCKED` upgrade first (§5).

---

# Module Catalog

A reference to every module in ERPCLEAN2: its purpose, key entities, the controllers and base paths it exposes, its permission family, and the governing ADR. The system has ~116 REST controllers grouped into the modules below. All paths are under `/api/v1`. All controllers are flat in `com.erp.api`; domain logic lives in `com.erp.modules.<name>` (business modules) or `com.erp.platform.*` (the spine). Permission codes are dot-separated and gate endpoints via `@PreAuthorize`.

The catalog is organised by area. Within each area, the controller list is the verified set from the shipped code.

## Platform spine

These are not business modules — they are the shared infrastructure every module rests on.

| Concern | Package | Role |
|---|---|---|
| Security / RBAC | `platform.security` | JWT (RS256) issue/verify, `RequestContext`, `@perm` permission resolver, `ScopeGuard` (uid → scope) |
| IAM | `platform.iam` | App users, roles, permissions, assignments |
| Company / tenancy | `platform.company` | Organisation → Company → Branch |
| Document numbering | `platform.sequence` | `code_sequence` row-locked allocation (`JB-####`, invoice numbers, …) |
| Outbox / events | `platform.events` | `domain_events` + `processed_events` + `@Scheduled` dispatcher (ADR-0009) |
| Audit | `platform.audit` | Audit aspect → append-only `audit_log` (ADR-0004) |
| Common | `platform.common` | `ApiResponse<T>`, `Money`, `UidEntity`, error model, validation |

The outbox has no controller, no REST surface, and no permission — it is internal plumbing.

## IAM — Identity & Access (ADR-0001, ADR-0002, ADR-0003, ADR-0004)

- **Purpose.** The security spine: authentication, the org/company/branch tenant tree, users, roles, permissions, branch assignment, and the audit trail. Built first; everything hangs off it.
- **Key entities.** `organisation`, `company`, `branch`, `app_user`, `permission`, `role`, `role_permission`, `user_role`, `user_branch`, `refresh_token`, `audit_log`.
- **Permission family.** `USER.*`, `ROLE.*`, `PERMISSION.*`, `COMPANY.*`, `BRANCH.*`, `AUDIT.*`.

| Controller | Base path | Notes |
|---|---|---|
| `AuthController` | `/api/v1/auth` | login / refresh / logout (no permission — public/auth) |
| `OrganisationController` | `/api/v1/organisations` | org tree root |
| `CompanyController` | `/api/v1/companies` | legal entities; base currency |
| `BranchController` | `/api/v1/branches` | locations; set-default |
| `UserController` | `/api/v1/users` | user CRUD, set-password, unlock |
| `UserBranchController` | `/api/v1/user-branches` | assign branches, set default |
| `UserRoleController` | `/api/v1/user-roles` | grant/revoke roles, scoped to company/branch |
| `RoleController` | `/api/v1/roles` | role + permission-bundle management |
| `AuditController` | `/api/v1/audit` | read-only audit trail |

## Masters — Parties (ADR-0006)

- **Purpose.** The trading-partner master data: who the company sells to, buys from, and deals with.
- **Key entities.** `customers`, `suppliers`, `agents` (sales agents), `other_parties`, party code sequences, branch associations.
- **Permission family.** `CUSTOMER.*`, `SUPPLIER.*`, `AGENT.*`, `OTHERPARTY.*`, `PARTY.*`.

| Controller | Base path |
|---|---|
| `CustomerController` | `/api/v1/customers` |
| `SupplierController` | `/api/v1/suppliers` |
| `AgentController` | `/api/v1/agents` |
| `OtherPartyController` | `/api/v1/other-parties` |

## Masters — Catalog / Products (ADR-0007, ADR-0026)

- **Purpose.** The product/service catalogue and its supporting reference data: products (GOODS=stockable / SERVICE=non-stockable), barcodes, bulk packs, prices, recipe components; units of measure with conversions; price lists; tax rates; distribution routes.
- **Key entities.** `products` (+ branches/barcodes/bulk-packs/prices/components sub-resources), `units_of_measure`, `price_lists`, `tax_rates`, `routes`. Bills of Materials extend products (ADR-0026; see Manufacturing).
- **Permission family.** `PRODUCT.*`, `UOM.*`, `PRICELIST.*`, `TAXRATE.*`, `ROUTE.*`, `BOM.*`.

| Controller | Base path |
|---|---|
| `ProductController` | `/api/v1/products` (+ `/products/uid/{uid}/...` sub-resources, `/products/barcode-lookup`) |
| `UnitOfMeasureController` | `/api/v1/units` |
| `PriceListController` | `/api/v1/price-lists` |
| `TaxRateController` | `/api/v1/tax-rates` |
| `RouteController` | `/api/v1/routes` |

## Sales — Order-to-Cash (ADR-0008, ADR-0021, ADR-0029)

- **Purpose.** The full O2C chain: Quotation → Sales Order (reserves stock) → Delivery (full/partial/backorder) → Sales Invoice (per-delivery or DIRECT walk-in) → Sales Return (RMA). Finalising an invoice publishes `SALE.FINALISED` over the outbox, driving stock deduction and GL posting.
- **Key entities.** `quotations`, `sales_orders`, `deliveries`, `sales_invoices` (with `tax_summary` JSONB), `sales_returns`, and their lines.
- **Permission family.** `SALES.*` (e.g. `SALES.INVOICE.CREATE`, `SALES.INVOICE.VOID`, `SALES.ORDER.*`, `SALES.QUOTE.*`, `SALES.DELIVERY.*`, `SALES.RETURN.*`).

| Controller | Base path |
|---|---|
| `QuotationController` | `/api/v1/quotations` |
| `SalesOrderController` | `/api/v1/sales-orders` |
| `DeliveryController` | `/api/v1/deliveries` |
| `SalesInvoiceController` | `/api/v1/sales-invoices` |
| `SalesReturnController` | `/api/v1/sales-returns` |

## Sales — Advanced Pricing (ADR-0029)

- **Purpose.** Price lists and rule-driven pricing applied across the sales chain.
- **Key entities.** `pricing_rules` (+ price lists, shared with catalog).
- **Permission family.** `PRICELIST.*`, `SALES.*` pricing codes.

| Controller | Base path |
|---|---|
| `PricingRuleController` | `/api/v1/pricing-rules` |

## Point of Sale (ADR — POS, V43/V82/V83)

- **Purpose.** Retail POS: till setup; session lifecycle (open float → sell → payout → X-read → close[count cash] → reconcile[Z-read, variance, GL]); quick checkout (session + customer + agent + line items + tender + change).
- **Key entities.** `pos_tills`, `pos_sessions`, `pos_sales` (+ lines, tenders).
- **Permission family.** `POS.*`.

| Controller | Base path |
|---|---|
| `PosTillController` | `/api/v1/pos/tills` |
| `PosSessionController` | `/api/v1/pos/sessions` |
| `PosSaleController` | `/api/v1/pos/sales` |

## Procurement — Procure-to-Pay (ADR-0011, ADR-0027)

- **Purpose.** The full P2P chain: requisition → submit → approve → convert → RFQ → send → supplier quote → award (creates PO) → PO place/approve → goods receipt → landed cost → supplier bill → 3-way bill match → purchase return. A goods receipt publishes `STOCK.RECEIVED` over the outbox.
- **Key entities.** `purchase_requisitions`, `rfqs`, `supplier_quotes`, `purchase_orders`, `goods_receipts`, `landed_costs`, `purchase_returns`, `purchase_settings`. (Supplier bill + 3-way match live in the AP module — the procurement→bill bridge.)
- **Permission family.** `PURCHASE.*`, `SUPPLIER.*` (supplier-quote codes).

| Controller | Base path |
|---|---|
| `PurchaseRequisitionController` | `/api/v1/purchase-requisitions` |
| `RfqController` | `/api/v1/rfqs` |
| `SupplierQuoteController` | `/api/v1/supplier-quotes` |
| `PurchaseOrderController` | `/api/v1/purchase-orders` |
| `GoodsReceiptController` | `/api/v1/goods-receipts` |
| `LandedCostController` | `/api/v1/landed-costs` |
| `PurchaseReturnController` | `/api/v1/purchase-returns` |
| `PurchaseSettingsController` | `/api/v1/purchase-settings` |

## Inventory / Stock (ADR-0010, ADR-0020, ADR-0028)

- **Purpose.** Inventory of record: on-hand by location/product, movements, adjustments, opening balances, reorder levels, inter-location transfers, physical/cycle counts, batches/lots, serials, and valuation (moving-average, perpetual). The first outbox **consumer** — it applies `SALE.FINALISED`/`SALE.VOIDED`/`STOCK.RECEIVED` idempotently, stamping `source_event_uid` on each movement. Movements are append-only.
- **Key entities.** `stock_movements` (append-only ledger), `stock_on_hand`, `stock_locations`, `stock_transfers`, `stock_counts`, `stock_batches`, `stock_serials`, valuation tables.
- **Permission family.** `STOCK.*`, `INVENTORY.*`.

| Controller | Base path |
|---|---|
| `StockController` | `/api/v1/stock` (on-hand, movements, adjustments, opening, by-location, by-product) |
| `StockLocationController` | `/api/v1/stock-locations` |
| `StockTransferController` | `/api/v1/stock-transfers` |
| `StockCountController` | `/api/v1/stock-counts` |
| `StockBatchController` | `/api/v1/stock-batches` |
| `StockSerialController` | `/api/v1/stock-serials` |
| `StockValuationController` | `/api/v1/stock/valuation` |

## Finance — General Ledger (ADR-0013, ADR-0019)

- **Purpose.** The double-entry posting engine and books of record: chart of accounts, manual journals (balanced-or-rejected, no draft), fiscal calendar (years + periods, open/close), trial balance, GL posting-account config (`gl_configs`), and year-end close. Consumes sales events to auto-post; every other financial module posts into it.
- **Key entities.** `chart_of_accounts`, `fiscal_years`, `fiscal_periods`, `journal_batches`, `journal_entries`, `journal_lines`, `gl_configs`.
- **Permission family.** `GL.*` (e.g. `GL.JOURNAL.POST`, `GL.ACCOUNT.*`, `GL.PERIOD.CLOSE`, `GL.CONFIG.*`).

| Controller | Base path |
|---|---|
| `ChartOfAccountController` | `/api/v1/gl/accounts` |
| `JournalController` | `/api/v1/gl/journals` |
| `FiscalPeriodController` | `/api/v1/gl/periods` (+ `/gl/periods/fiscal-years`) |
| `GlConfigController` | `/api/v1/gl/configs` |
| `TrialBalanceController` | `/api/v1/gl/trial-balance` |
| `YearEndCloseController` | (year-end close, ADR-0019) |

## Cost Centre / Dimensions (ADR-0025)

- **Purpose.** Analytical tagging over GL postings: dimension types, dimension values, mandatory-dimension enforcement, and dimension-sliced trial balance.
- **Key entities.** `dimensions` (types), `dimension_values`, document-default mappings.
- **Permission family.** `COSTING.*` (and dimension codes).

| Controller | Base path |
|---|---|
| `DimensionController` | `/api/v1/dimensions` |
| `DimensionValueController` | `/api/v1/dimension-values` |
| `DimensionReportController` | `/api/v1/costing/reports` |

## Accounts Receivable (ADR-0014)

- **Purpose.** Customer open items: AR invoice view, record receipt (tender + allocation + optional WHT leg), credit notes, write-offs, opening balances, statement / ageing / balance. Posts to the GL AR control account.
- **Key entities.** AR open items (over `sales_invoices`), `ar_receipts` (+ allocations), `ar_credit_notes`, `ar_write_offs`, `ar_opening_balances`.
- **Permission family.** `AR.*`.

| Controller | Base path |
|---|---|
| `ArInvoiceController` | `/api/v1/ar/invoices` |
| `ArReceiptController` | `/api/v1/ar/receipts` |
| `ArCreditNoteController` | `/api/v1/ar/credit-notes` |
| `ArWriteOffController` | `/api/v1/ar/write-offs` |
| `ArOpeningBalanceController` | `/api/v1/ar/opening-balances` |
| `ArStatementController` | `/api/v1/ar` (balance / ageing / statement) |

## Accounts Payable (ADR-0015)

- **Purpose.** Supplier obligations: supplier-bill entry + 3-way match (the procurement→bill bridge), single-bill payment + payment run (with WHT-on-payment), debit notes, opening balances, and supplier statement (balance / ageing / reconciliation). Posts to the GL AP control account.
- **Key entities.** `supplier_bills` (+ match), `ap_payments` (single + run), `ap_debit_notes`, `ap_opening_balances`.
- **Permission family.** `AP.*` (+ `PURCHASE.*` where the bill bridges procurement).

| Controller | Base path |
|---|---|
| `SupplierBillController` | `/api/v1/ap/supplier-bills` |
| `BillMatchController` | `/api/v1/ap/supplier-bills/uid/{billUid}/match` |
| `ApPaymentController` | `/api/v1/ap/payments` |
| `ApDebitNoteController` | `/api/v1/ap/debit-notes` |
| `ApOpeningBalanceController` | `/api/v1/ap/opening-balance` |
| `ApStatementController` | `/api/v1/ap/statement` |

## Cash & Bank (ADR-0016)

- **Purpose.** Treasury: cash/bank account CRUD, inter-account transfers, direct entries, account balance/statement + GL reconciliation, bank reconciliation lifecycle, and the cheque register.
- **Key entities.** `cash_bank_accounts`, `cash_transfers`, `cash_direct_entries`, `bank_reconciliations`, `cheques`.
- **Permission family.** `CASH.*`, `CHEQUE.*`.

| Controller | Base path |
|---|---|
| `CashBankAccountController` | `/api/v1/cash/accounts` |
| `CashTransferController` | `/api/v1/cash/transfers` |
| `CashDirectEntryController` | `/api/v1/cash/entries` |
| `CashAccountStatementController` | `/api/v1/cash/statements` |
| `BankReconciliationController` | `/api/v1/cash/reconciliations` |
| `ChequeController` | `/api/v1/cash/cheques` |

## Tax — VAT & WHT (ADR-0017)

- **Purpose.** VAT return lifecycle (open → recompute → file) with add/remove adjustments; withholding-tax types (rate master) and the WHT register / certificate view.
- **Key entities.** `vat_returns` (+ adjustments), `wht_types`, WHT register.
- **Permission family.** `VAT.*`, `WHT.*`.

| Controller | Base path |
|---|---|
| `VatReturnController` | `/api/v1/vat/returns` |
| `VatAdjustmentController` | `/api/v1/vat/returns/uid/{returnUid}/adjustments` |
| `WhtTypeController` | `/api/v1/wht/types` |
| `WhtRegisterController` | `/api/v1/wht/register` |

## FX / Multi-currency (ADR-0005, ADR-0036)

- **Purpose.** Currency master, effective-dated exchange-rate maintenance, foreign-currency document posting against the base (TZS) ledger, realized FX on settlement, and the period-end **unrealized** FX revaluation run (preview → post → reverse).
- **Key entities.** `currencies` (global reference), `currency_rates` (effective-dated), document base-triple columns, `fx_revaluation_runs`.
- **Permission family.** `FX.*`, `CURRENCY.*`.

| Controller | Base path |
|---|---|
| `CurrencyController` | `/api/v1/fx` (`/currencies`, `/rates`) |
| `FxRevaluationRunController` | `/api/v1/fx/revaluation-runs` |

## Reporting & BI (ADR-0018, ADR-0037)

- **Purpose.** Read-only over the GL: P&L, Balance Sheet, Cash Flow (indirect), trial balance, account-ledger drill-through, server-side PDF/XLSX export; a composite BI analytics dashboard (per-panel RBAC, drill, export); analytical reports (budget variance, departmental actuals, dimension-sliced TB, project WIP/P&L, manufacturing WIP). Posts nothing, owns no business table.
- **Key entities.** None of its own — pure queries over `journal_lines` and the source ledgers.
- **Permission family.** `REPORT.*`, `BI.*`.

| Controller | Base path |
|---|---|
| `ReportingController` | `/api/v1/reports` (income-statement, balance-sheet, cash-flow, account-ledger; `/export`) |
| `BiDashboardController` | `/api/v1/bi` |

## Fixed Assets (ADR-0030)

- **Purpose.** Asset categories, the fixed-asset register (register / acquire-from-bill / place-in-service / transfer), depreciation runs (preview / post), revaluation, disposal & write-off, and the FA→GL reconciliation report.
- **Key entities.** `asset_categories`, `fixed_assets`, `depreciation_runs`, disposals/revaluations.
- **Permission family.** `FA.*`.

| Controller | Base path |
|---|---|
| `AssetCategoryController` | `/api/v1/fixed-assets/categories` |
| `FixedAssetController` | `/api/v1/fixed-assets` |
| `DepreciationRunController` | `/api/v1/fixed-assets/depreciation-runs` |

## HR & Payroll (ADR-0032)

- **Purpose.** Departments, employees (employment-status lifecycle), employment contracts (types + terminate), leave requests (lifecycle + accrual), employee loans, pay components, the payroll run lifecycle (calculate → approve → post → disburse → reverse, with GL + Cash & Bank effects), and statutory setup (PAYE band sets + statutory rate sets).
- **Key entities.** `hr_departments`, `hr_employees`, `hr_contracts`, `hr_leave_requests`, `hr_loans`, `hr_pay_components`, `hr_payroll_runs`, statutory tables.
- **Permission family.** `HR.*`.

| Controller | Base path |
|---|---|
| `HrDepartmentController` | `/api/v1/hr/departments` |
| `HrEmployeeController` | `/api/v1/hr/employees` |
| `HrContractController` | `/api/v1/hr/contracts` |
| `HrLeaveController` | `/api/v1/hr/leave-requests` |
| `HrLoanController` | `/api/v1/hr/loans` |
| `HrPayComponentController` | `/api/v1/hr/pay-components` |
| `HrPayrollController` | `/api/v1/hr/payroll-runs` |
| `HrStatutoryController` | `/api/v1/hr/statutory` |

## CRM (ADR-0031)

- **Purpose.** Lead capture and lifecycle (NEW → CONTACTED → QUALIFIED → CONVERTED/DISQUALIFIED); opportunities (OPEN → WON/LOST, advance-stage, lines, convert to Quotation/Sales Order); pipeline analytics (board, forecast, KPIs); pipeline-stage CRUD; activities (CALL/EMAIL/MEETING/NOTE/TASK on a lead or opportunity).
- **Key entities.** `crm_leads`, `crm_opportunities` (+ lines), `crm_pipeline_stages`, `crm_activities`.
- **Permission family.** `CRM.*`.

| Controller | Base path |
|---|---|
| `LeadController` | `/api/v1/crm/leads` |
| `OpportunityController` | `/api/v1/crm/opportunities` |
| `PipelineController` | `/api/v1/crm/pipeline` |
| `PipelineStageController` | `/api/v1/crm/pipeline-stages` |
| `ActivityController` | `/api/v1/crm/activities` |

## Projects — Job Costing (ADR-0033)

- **Purpose.** Project CRUD + lifecycle, tasks, timesheets, issue-materials-to-project (COGS at moving-average, tagged to the project), and the costing read models (per-project P&L, cross-project WIP).
- **Key entities.** `projects`, `project_tasks`, `project_timesheets`, project material issues, project cost tags on AP/sales/stock.
- **Permission family.** `PROJECTS.*`.

| Controller | Base path |
|---|---|
| `ProjectController` | `/api/v1/projects` |
| `ProjectTaskController` | `/api/v1/project-tasks` |
| `ProjectTimesheetController` | `/api/v1/project-timesheets` |
| `IssueToProjectController` | `/api/v1/project-issues` |
| `ProjectCostingController` | `/api/v1/project-costing` |

## Manufacturing (ADR-0026, ADR-0035)

- **Purpose.** Multi-level Bill of Materials authoring + lifecycle (explode, where-used, cost roll-up); Work Order execution (release → issue → apply-cost → complete → close, plus cancel reversal); per-order cost report; company-level WIP reconciliation.
- **Key entities.** `boms` (+ components), `work_orders` (+ operations), WIP accounts.
- **Permission family.** `BOM.*`, `WORKORDER.*`, `MANUFACTURING.*`.

| Controller | Base path |
|---|---|
| `BomController` | `/api/v1/boms` |
| `WorkOrderController` | `/api/v1/work-orders` |
| `ManufacturingReportController` | `/api/v1/manufacturing` |

## Budgeting (ADR-0034)

- **Purpose.** Budget headers + version lifecycle (DRAFT → SUBMITTED → APPROVED/REJECTED/SUPERSEDED, recall to DRAFT), line entry in three modes (DIRECT, ANNUAL_SPREAD, SEED-from-prior), new-version re-plan, and the two budget reports (budget-vs-actual variance, departmental actuals). Posts **nothing** to GL — read against GL actuals at report time only.
- **Key entities.** `budgets`, `budget_versions`, budget lines.
- **Permission family.** `BUDGETING.*`.

| Controller | Base path |
|---|---|
| `BudgetController` | `/api/v1/budgets` |
| `BudgetVersionController` | `/api/v1/budget-versions` |
| `BudgetReportController` | `/api/v1/budgeting` |

## Approvals engine (ADR-0022)

- **Purpose.** A generic, document-agnostic governance spine: amount-threshold + branch-scoped multi-step approval **policies** (per-company master with an ordered chain of IAM approver roles), runtime **approval requests** that freeze a policy-step snapshot at submit, append-only decisions, deterministic policy-match (branch beats company-wide; no match → auto-approve). Exposes `submitForApproval` (idempotent per type+uid) + `getApprovalState` (synchronous gate) and publishes `APPROVAL.RESOLVED`. Posts nothing to the books — it gates.
- **Key entities.** `approval_policies` (+ steps), `approval_requests` (+ frozen steps, decisions).
- **Permission family.** `APPROVALS.*`.

| Controller | Base path |
|---|---|
| `ApprovalPolicyController` | `/api/v1/approvals/policies` |
| `ApprovalRequestController` | `/api/v1/approvals/requests` |

## Documents (ADR-0023)

- **Purpose.** Server-side document generation: PDF render with a template registry and per-company branding; a generated-documents log.
- **Key entities.** generated documents, `document_templates`, document branding.
- **Permission family.** `DOCUMENT.*`.

| Controller | Base path |
|---|---|
| `DocumentController` | `/api/v1/documents` |
| `DocumentTemplateController` | `/api/v1/documents/templates` |
| `DocumentBrandingController` | `/api/v1/documents/branding` |

## Notifications (ADR-0024)

- **Purpose.** In-app notification inbox, per-user preferences, and an admin type-catalogue toggle + delivery log.
- **Key entities.** notifications, notification preferences, notification type catalogue + delivery log.
- **Permission family.** `NOTIFICATION.*`.

| Controller | Base path |
|---|---|
| `NotificationController` | `/api/v1/notifications` |
| `NotificationPreferenceController` | `/api/v1/notification-preferences` |
| `NotificationAdminController` | `/api/v1/admin/notifications` |

## Sales — Blanket / Standing Orders (ADR-0029)

- **Purpose.** Recurring and committed-volume sales arrangements that drive scheduled releases.
- **Key entities.** `blanket_orders`, `standing_orders`.
- **Permission family.** `SALES.*`.

| Controller | Base path |
|---|---|
| `BlanketOrderController` | `/api/v1/blanket-orders` |
| `StandingOrderController` | `/api/v1/standing-orders` |

---

# Data Model

A readable reference to the ERPCLEAN2 PostgreSQL schema: the cross-cutting models (identity, money, multi-tenancy, append-only posting, soft-delete) and a per-domain summary of the main tables. This is a map, not a DDL dump — the authoritative DDL is the Flyway migrations under `backend/src/main/resources/db/migration/` (`V1` … `V83`). Where the prose in the repo's `DATA-MODEL.md` and the shipped SQL disagree, **the SQL is ground truth**; the table names below follow the shipped migrations.

## Naming conventions (the real, shipped convention)

| Element | Convention | Example |
|---|---|---|
| Master / owned-child tables | `snake_case`, **plural** | `companies`, `customers`, `sales_invoices`, `journal_lines` |
| Junction / utility tables | `snake_case`, **singular**, named for both sides | `user_branch`, `role_permission`, `user_role` |
| Primary key | `id BIGINT GENERATED BY DEFAULT AS IDENTITY` | every table |
| External identifier | `uid VARCHAR(26)` (ULID), unique | every externally exposed entity |
| Foreign key constraint | `fk_<singular-entity>_<ref>` | `fk_journal_line_account` |
| Unique constraint | `uq_<singular-entity>_<cols>` | `uq_chart_of_account_company_code` |
| Standalone index | `ix_<plural-table>_<cols>` | `ix_journal_lines_company_account` |
| Columns | always singular | `company_id`, `account_id` |

## The identity model — `id` + `uid` duality

Every externally exposed entity carries **two** identifiers (`PROJECT-CONVENTIONS.md` §3.3, ADR-0001 D-G):

- **`id` — `BIGINT IDENTITY`.** The internal primary key and the only thing foreign keys point at. Used for body-level joins. Serialised on the wire as a **JSON string** (global Jackson config) so a 64-bit value survives JavaScript's IEEE-754 number precision. The web side types every id field as `string`.
- **`uid` — `VARCHAR(26)` ULID, unique.** The stable external identifier. **URLs address by `uid`** (`/api/v1/<resource>/uid/{uid}`). The `uid` is never shown to a user — pickers resolve a human name to a `uid` behind the scenes. Cross-system references and outbox payloads carry `uid`, not `id`.

Internal-only tables (junctions, `audit_logs`) may omit `uid` and are addressed by their natural key or `code`. Reference catalogues addressed by a business code (`permissions` by `code`, `currencies` by ISO code) likewise need no `uid`.

## Money & currency model (ADR-0005)

A monetary value is an inseparable `(amount, currency)` pair — never a bare number, in the database, in Java, or on the wire:

- **Storage.** A `Money` `@Embeddable` materialises a column pair: `<field>_amount NUMERIC(19,4)` + `<field>_currency CHAR(3)` (ISO 4217 code), both NULL or both NOT NULL together. **Exchange rates** are `NUMERIC(19,8)`. Never `float`/`double`.
- **Base currency.** Each `companies` row has a configurable base (functional) currency (default TZS), read from config — never a hard-coded literal. The GL and all statements are in base currency.
- **Foreign currency.** A document in a non-base currency also stores the **base-currency equivalent** amount and the **exchange rate used** (the base triple), captured at transaction time and **immutable** thereafter — a posted historical amount is never recomputed when rates move (append-only discipline). The FX engine (rate master, conversion, revaluation) lives in `currencies` / `currency_rates` and the FX revaluation runs (ADR-0036, V77–V80).
- **Wire format.** Money is an object — `{ "amount": "1500.0000", "currency": "TZS", "display": "TZS 1,500.00" }` — with `amount` as a **string** (exact end-to-end). Mixed-currency arithmetic throws; a cross-currency "total" is per-currency balances plus an explicit converted roll-up, never a raw `SUM`.

## Multi-tenancy — Organisation / Company / Branch

The tenant tree is **`organisations` → `companies` → `branches`** (ADR-0001):

- **Organisation** — top of the tree, one per deployment. Parent of all companies.
- **Company** — a legal entity; the scoping parent of company-bound master data and the level at which books are kept. Carries the base currency.
- **Branch** — a physical location (shop / depot / warehouse); the smallest unit at which stock and a business day exist. At most one default branch per company (`uq_branch_company_default` — a partial unique `WHERE is_default`).

Every transactional table carries `company_id`; tables that cross a branch boundary also carry `branch_id`. The `RequestContext` sets the active company/branch from the JWT plus the `X-Branch-Uid` override header; repository base interfaces inject the tenant predicate automatically. In the GL, `branch_id` is a nullable **analysis tag** on journals — books are company-level, not per-branch.

## Append-only posting & soft-delete

Two distinct disciplines (`PROJECT-CONVENTIONS.md` §3.6):

- **Append-only posting tables** are never updated or deleted. Corrections are **new, reversing rows**. This applies to `journal_batches`/`journal_entries`/`journal_lines` (no `updated_*` columns; correction = a reversing entry linked by `reversal_of_id`), `stock_movements` (reversal movements), and `audit_logs` (no UPDATE/DELETE through the app; the DB role is granted none).
- **Soft-deletable masters** carry a `MasterStatus` column — `status VARCHAR(32)` with `ACTIVE` | `INACTIVE` | `ARCHIVED`. Archiving hides a master from new use while preserving it on historical documents (e.g. an archived product stays on past invoices; a deactivated GL account stays on historical journal lines but rejects new postings).

Optimistic locking (`@Version` → a `version BIGINT` column) guards mutable aggregates against lost updates.

## Per-domain table summary

The list below covers the main tables per domain. Owned child tables (lines, allocations, components) are noted with their parent. Junctions are singular.

### IAM (V1 baseline)

| Table | Purpose |
|---|---|
| `organisations` | tenant root |
| `companies` | legal entity; base currency; `uq_company_org_code` |
| `branches` | location; `uq_branch_company_default` (≤1 default/company) |
| `app_users` | login identity; bcrypt `password_hash`; lockout fields; `is_root` |
| `permissions` | atomic access unit, dot-separated `code`; seeded; addressed by code |
| `roles` | named permission bundle; `is_system` |
| `role_permission` | junction role ⇄ permission |
| `user_role` | role grant scoped to company, optionally one branch (NULL = all) |
| `user_branch` | branch assignment; `uq_user_branch_default` (≤1 default/user) |
| `refresh_tokens` | hashed, single-use, rotated; reuse → revoke chain |
| `audit_logs` | append-only action trail; `detail` JSONB; no UPDATE/DELETE |

### Outbox / events (V6)

| Table | Purpose |
|---|---|
| `domain_events` | append-only outbox; `event_type`, `aggregate_*`, `company_id`/`branch_id`, `payload` JSONB, `status` (PENDING/DISPATCHED/FAILED), `occurred_at`/`dispatched_at`, `attempt_count`/`last_error`. Partial index on `status='PENDING'` |
| `processed_events` | consumer-side dedup marker; `uq_processed_event (consumer, event_uid)` |

### Parties (V2) & Catalog (V3, V4, V9)

| Table | Purpose |
|---|---|
| `customers`, `suppliers`, `agents`, `other_parties` | trading-partner masters (+ branch associations, party code sequences) |
| `products` | catalogue; GOODS=stockable / SERVICE=non-stockable; sub-tables for branches, barcodes, bulk packs, prices, recipe components |
| `units_of_measure` | UoM + conversions (bulk packs) |
| `price_lists` | named price sets |
| `tax_rates` | VAT/tax rate master; `status` (MasterStatus) |
| `routes` | distribution routes; customer/agent/branch assignment |

### Sales — O2C (V5, V18, V19, V42, V44, V45)

| Table | Purpose |
|---|---|
| `quotations` (+ lines) | quotes; accept → sales order |
| `sales_orders` (+ lines) | orders; confirm reserves stock |
| `deliveries` (+ lines) | full/partial/backorder fulfilment |
| `sales_invoices` (+ `sales_invoice_lines`, `sales_invoice_payments`) | invoice; `net/vat/gross` totals; `tax_summary` JSONB; finalise → `SALE.FINALISED` |
| `sales_returns` (+ lines) | RMA |
| `pricing_rules` | advanced pricing |
| blanket / standing orders | recurring + committed-volume arrangements |

### POS (V43, V82, V83)

| Table | Purpose |
|---|---|
| `pos_tills` | till master |
| `pos_sessions` | session lifecycle (open float → X-read → close → reconcile/Z-read) |
| `pos_sales` (+ lines, tenders) | quick checkout |

### Procurement — P2P (V8, V32–V36)

| Table | Purpose |
|---|---|
| `purchase_requisitions` (+ lines) | requisition → approve → convert |
| `rfqs`, `supplier_quotes` | sourcing; award creates a PO |
| `purchase_orders` (+ lines) | PO place/approve |
| `goods_receipts` (+ lines) | receipt → `STOCK.RECEIVED` |
| `landed_costs` | cost apportionment onto receipts |
| `purchase_returns` (+ lines) | supplier returns |
| `purchase_settings` | per-company procurement config |

### Inventory / Stock (V7, V17, V37–V41)

| Table | Purpose |
|---|---|
| `stock_movements` | **append-only** ledger; carries `source_event_uid` (outbox traceability + idempotency backstop) |
| `stock_on_hand` | per-product/location/batch quantity |
| `stock_locations` | within-branch locations; default |
| `stock_transfers` (+ lines) | inter-location transfers |
| `stock_counts` (+ lines) | physical / cycle counts |
| `stock_batches`, `stock_serials` | lot/expiry and serial tracking |
| valuation tables | moving-average / perpetual valuation (ADR-0020) |

### Finance — General Ledger (V10, V16, V27, V28)

| Table | Purpose |
|---|---|
| `chart_of_accounts` | flat numeric-range accounts; `account_type`, stored `normal_balance`; `uq_chart_of_account_company_code` |
| `fiscal_years` | configurable start month; OPEN/CLOSED |
| `fiscal_periods` | 12 monthly periods/year; **the posting gate** (must be OPEN) |
| `journal_batches` | numbered container (`JB-####`); `source_type` |
| `journal_entries` | one balanced transaction; `posting_date`, `fiscal_period_id`, `reversal_of_id` |
| `journal_lines` | one leg; `debit_amount`/`credit_amount` (exactly one non-zero); `chk_journal_line_one_side`; `ix_journal_lines_company_account` is the trial-balance index |
| `gl_configs` | role→account map (`SALES_REVENUE`, `VAT_PAYABLE`, `ACCOUNTS_RECEIVABLE`, `CASH`, …); `uq_gl_config_company_key` |
| `dimensions`, `dimension_values` | cost-centre / analytical dimensions (ADR-0025) |

### Accounts Receivable (V11)

| Table | Purpose |
|---|---|
| `ar_invoices` | AR open items |
| `ar_receipts` (+ `ar_receipt_allocations`) | receipts; tender + allocation + optional WHT leg |
| `ar_credit_notes`, `ar_write_offs` | credit notes, write-offs |
| AR opening balances | migration of legacy open items |

### Accounts Payable (V12)

| Table | Purpose |
|---|---|
| `supplier_bills` (+ lines, match) | bill entry + 3-way match |
| `ap_payments` | single + payment run; WHT-on-payment |
| `ap_debit_notes` | supplier debit notes |
| AP opening balances | legacy open items |

### Cash & Bank (V13)

| Table | Purpose |
|---|---|
| `cash_bank_accounts` | cash/bank accounts; default |
| `cash_transactions` | direct entries |
| `cash_transfers` | inter-account transfers |
| `bank_reconciliations` | reconciliation lifecycle |
| `cheques` | cheque register |

### Tax — VAT & WHT (V14)

| Table | Purpose |
|---|---|
| `vat_returns` (+ adjustments) | return lifecycle (open → recompute → file) |
| `wht_types` | WHT rate master |
| WHT register | withheld-amount register / certificates |

### FX / Multi-currency (V77–V80)

| Table | Purpose |
|---|---|
| `currencies` | global currency master (no `company_id`); ISO code |
| `currency_rates` | effective-dated rates (add-only, no edit-in-place) |
| `fx_revaluation_runs` | period-end unrealized revaluation (preview → post → reverse) |
| (document base-triple columns) | base-currency equivalent + rate on foreign-currency documents |

### Fixed Assets (V46–V50)

| Table | Purpose |
|---|---|
| `asset_categories` | category master + GL config |
| `fixed_assets` | register; acquire-from-bill, place-in-service, transfer |
| `depreciation_runs` | preview / post |
| disposals / revaluations | disposal, write-off, revaluation |

### HR & Payroll (V56–V63)

| Table | Purpose |
|---|---|
| `hr_departments`, `hr_employees` | org + employee master (employment-status lifecycle) |
| `hr_contracts` | employment contracts; terminate |
| `hr_leave_requests`, `hr_loans` | leave + loan lifecycle |
| `hr_pay_components` | earnings/deductions |
| `hr_payroll_runs` | calculate → approve → post → disburse → reverse (GL + Cash effects) |
| statutory tables | PAYE band sets, statutory rate sets |

### CRM (V51, V52)

| Table | Purpose |
|---|---|
| `crm_leads` | lead capture + lifecycle |
| `crm_opportunities` (+ lines) | OPEN → WON/LOST; convert to Quotation/Sales Order |
| `crm_pipeline_stages` | stage master |
| `crm_activities` | CALL/EMAIL/MEETING/NOTE/TASK on a lead or opportunity |

### Projects — Job Costing (V64–V68)

| Table | Purpose |
|---|---|
| `projects` | project master + lifecycle |
| `project_tasks`, `project_timesheets` | tasks + time |
| project material issues | issue-to-project (COGS at moving-average) |
| project cost tags | tags on AP / sales / stock for project costing |

### Manufacturing (V30, V74–V76)

| Table | Purpose |
|---|---|
| `boms` (+ components) | multi-level BOM authoring + lifecycle |
| `work_orders` (+ operations) | release → issue → apply-cost → complete → close |
| WIP accounts | manufacturing CoA seed; WIP reconciliation |

### Budgeting (V69, V70)

| Table | Purpose |
|---|---|
| `budgets` | budget header |
| `budget_versions` (+ lines) | version lifecycle; line entry modes; posts nothing to GL |

### Platform services — Approvals / Documents / Notifications (V20–V26)

| Table | Purpose |
|---|---|
| `approval_policies` (+ steps) | per-company multi-step policy chain |
| `approval_requests` (+ frozen steps, decisions) | runtime request; snapshot frozen at submit; `APPROVAL.RESOLVED` event |
| documents, `document_templates`, branding | PDF render log, template registry, company branding |
| notifications, preferences, type catalogue + delivery log | in-app inbox, per-user preferences, admin toggle |

## Migration ordering

Migrations are strictly sequential and additive once shipped — IAM (`V1`) is the baseline, and each module lands as a new `V<n>` file (`V2` Parties … `V83` POS permissions). A shipped migration is never edited; a correction is a new migration on top. Every module depends only on already-frozen tables (e.g. all FKs to `companies`/`branches`/`app_users` resolve against the frozen `V1` baseline). The current head is `V83`.

---

# Security

ERPCLEAN2 secures every request through an in-house JWT resource server, enforces
authorization by permission code, and isolates tenants down to the branch. This document
describes how authentication, RBAC, multi-tenant isolation, the audit trail, and secret
handling actually work in the shipped system.

The decisions behind this design are recorded in
[ADR-0001](../decisions/0001-iam-architecture.md) (IAM architecture),
[ADR-0002](../decisions/0002-rbac-enforcement.md) (permission AND scope enforcement),
[ADR-0003](../decisions/0003-branch-switch-override.md) (runtime branch switch), and
[ADR-0004](../decisions/0004-iam-audit-trail.md) (audit trail).

## 1. Authentication

### 1.1 In-house JWT (RS256)

Authentication is a stateless OAuth2 resource-server model with tokens minted by the
application itself — there is no external identity provider. Access tokens are JWTs signed
with **RS256** (RSA, 2048-bit). The private key signs at login; the public key verifies on
every request. Signature and expiry are checked; the decoder is built without a
`JwtIssuerValidator`, which is acceptable for a single in-house signer (see
[ADR-0038](../decisions/0038-production-hardening.md) Risk 8 — flag to security before
accepting tokens from any external issuer).

A JWT carries the claims needed to build the request context without re-querying the
database on every hop:

- `userId` — numeric id of the authenticated user
- `username`
- `companyId` — the active company (the default branch's company at login)
- `branchId` — the active branch (the user's default branch at login)
- `isRoot` — the super-admin flag

The token does **not** embed the permission set. Permissions change with the active branch
and would bloat and stale the token; they are resolved per request instead
(ADR-0001 D-E, see §2.1).

### 1.2 Login flow

1. `POST /api/v1/auth/login` with username + password. The endpoint is public (see §5).
2. The password is verified against a **BCrypt** hash (cost 12). The unknown-user path runs
   a constant-time dummy comparison so a missing username is indistinguishable from a wrong
   password (no user enumeration).
3. On success the server mints an **access token** (short-lived, default 15 minutes) and a
   **refresh token**. The session lands the user in their **default branch** (the
   `user_branch` assignment with `is_default = true`); the active company is that branch's
   company.
4. A branch is only usable for a session if both the branch **and** its company are
   `ACTIVE` (the `Branch.isUsableForSession()` predicate, ADR-0004 D-8 F8). A user with no
   branch assignments lands in a read-only, no-scope state (FR-IAM-19).
5. Account lockout: 5 failed attempts lock the account for 15 minutes. Lockout bookkeeping
   runs in its own `REQUIRES_NEW` transaction so it survives the authentication-failure
   rollback (ADR-0004 D-3).

### 1.3 Refresh-token rotation

- `POST /api/v1/auth/refresh` exchanges a valid refresh token for a new access token and a
  new refresh token. Refresh tokens are **single-use and rotated** on every refresh.
- Tokens are stored **hashed** (SHA-256), never in plaintext — the `refresh_token` table
  keys on `token_hash`.
- **Reuse detection:** presenting an already-rotated (consumed) refresh token is treated as
  a compromise signal and rejected.
- `POST /api/v1/auth/logout` invalidates the refresh token.

### 1.4 Tokens and headers on each request

The client sends two headers (the Angular `authHeaderInterceptor` adds both automatically):

| Header | Purpose |
|---|---|
| `Authorization: Bearer <access-token>` | Authenticates the request; the resource server validates the RS256 signature and expiry. |
| `X-Branch-Uid: <branch-uid>` | Optional. Overrides the **active branch** for this request only (see §3.2). The value is a branch **uid** (ULID), never a numeric id. |
| `X-Request-Id` | Optional correlation id; echoed back in the response (ADR-0038 D-2). If absent, the server generates one. |

`JwtRequestContextFilter` runs **after** `BearerTokenAuthenticationFilter` so the JWT is
already validated and the principal is in the security context. The filter builds a
request-scoped `RequestContext.Principal` (`userId`, `username`, `root`, `companyId`,
`branchId`, `ip`) and clears it in a `finally` block to prevent cross-request leakage. It
also re-checks per request that the user is still `ACTIVE` — a disabled user is rejected on
their next request (401) rather than after the access-token TTL expires (ADR-0004 D-8 F9).

## 2. Authorization (RBAC)

Authorization is **by permission code, never by role name** (PROJECT-CONVENTIONS §3.4).
Roles exist only as bundles of permissions; the gates check the permissions.

### 2.1 Permission resolution

A `PermissionResolver` computes the **effective permission set** for the principal's
*active* company + branch on each request, reading `user_role` joined to `role_permission`,
cached briefly per `(user, company, branch)`. Because resolution is per active scope,
switching branches changes the effective permissions without re-issuing a token
(ADR-0001 D-E). The cache is busted on `user_role` / `role_permission` writes.

### 2.2 The gate: `@perm.has` and `@perm.scoped`

Method security is enabled (`@EnableMethodSecurity`). Every controller handler under
`com.erp.api` is gated by a `@PreAuthorize` referencing the `@perm` bean
(`PermissionChecks`, `com.erp.platform.security`). Spring Security has no 1-arg
`hasPermission` SpEL form, so the system uses a bean reference instead of a custom
expression handler (ADR-0002, Bug-1 fix). Two shapes:

- **`@perm.has('CODE')`** — create / list operations, where the implicit target is the
  active scope. True if the principal holds `CODE` in their active company + branch.

  ```java
  @PreAuthorize("@perm.has('AGENT.VIEW')")
  ```

- **`@perm.scoped(#uid, 'targetType', 'CODE')`** — operations addressing an existing target
  by path uid. True if the principal holds `CODE` **and** may act on the target (root, or
  the target lives in the principal's active company). This closes the cross-company hole: a
  user with a permission in company A cannot operate on a same-typed entity in company B
  (ADR-0002).

  ```java
  @PreAuthorize("@perm.scoped(#uid,'activity','CRM.ACTIVITY.MANAGE')")
  ```

A handler with **no** `@PreAuthorize` fails the build — `EndpointAuthorizationTest`
(ArchUnit) scans every `@RestController` under `com.erp.api` and fails `mvn verify` if any
handler lacks a gate. The allowlist is exactly 4 public endpoints (§5). Authorization is
**fail-closed**: missing a gate is a build failure, not a silent open door.

### 2.3 Seeded roles

The following roles ship seeded via Flyway. They are permission bundles; what each can do is
defined by the permissions granted to it, not by its name.

| Role | Intended scope |
|---|---|
| `ORG_ADMIN` | Organisation-wide administration (users, roles, companies, branches). |
| `SALES_MANAGER` | Full sales / order-to-cash management. |
| `SALES_REP` | Day-to-day sales operations (quotations, orders). |
| `ACCOUNTANT` | GL, AR, AP, cash & bank, tax. |
| `STOREKEEPER` | Inventory / stock operations. |
| `PURCHASE_OFFICER` | Procurement (requisitions, RFQ, PO, GRN, bills). |

Permission codes are dot-separated and module-prefixed (e.g. `SALES_INVOICE.POST`,
`CRM.ACTIVITY.MANAGE`). Every permission-gated endpoint must have its code present in a seed
migration (PROJECT-CONVENTIONS §3.4). A new permission-gated endpoint without its seeded
permission is a defect — see the POS prefix-mismatch defect in
[the test-case suite](../testing/test-cases/07-pos.md) (controllers checked `POS.*` but the
migration seeded `SALES.POS.*`).

### 2.4 Custom roles

Custom roles are created through the IAM admin UI / API. A custom role is a named bundle:
pick permissions, save, then grant the role to users scoped to a company (and optionally one
branch). Custom roles behave identically to seeded roles at the gate — the resolver does not
distinguish them. Seeded/system roles carry an `is_system` flag and are protected from
deletion; custom roles are freely editable.

### 2.5 Root (super-admin) bypass

The `rootadmin` super-user (`isRoot = true`) **bypasses RBAC**: every `@perm.has` /
`@perm.scoped` check short-circuits to allowed, and every scope check short-circuits in
`ScopeGuard`. Root is the seed / recovery actor and usually has no branch assignments, so it
is exempt from the branch-assignment check on switching (§3.2, ADR-0003 D-4). Root is never
unaudited — every root **action** produces its normal audit row (actor = root), and a
distinct `ROOT.BYPASS` row is written when root acts **out of** its active company
(ADR-0004 D-9). In the dev profile the backend bootstraps `rootadmin` / `RootPass12345`
(dev only — never a prod credential).

## 3. Multi-tenant and branch isolation

The tenancy tree is **organisation → company → branch** (PROJECT-CONVENTIONS §4). Every
transactional table carries `company_id` + `branch_id`. Isolation is enforced on two fronts:
the repository tenant predicate for business data, and `ScopeGuard` for cross-tenant
admin operations.

### 3.1 Tenant predicate on business tables

Transactional business tables are scoped by a company/branch predicate injected by the
repository base interface (PROJECT-CONVENTIONS §3.2). A finder that bypasses the base
interface is a tenant-isolation bug. The active company/branch come from `RequestContext`,
so the predicate tracks the current (possibly overridden) scope automatically.

IAM administration tables are the deliberate exception (ADR-0001 D-A): `organisation`,
`permission`, `role`, and `app_user` are **global** (no tenant columns), because
administering IAM is inherently cross-branch — an admin manages many branches' users, and
root must reach every company. IAM isolation is enforced by **permission + scope checks in
the service layer**, not a blanket row predicate.

### 3.2 Branch switch via `X-Branch-Uid`

A user assigned to many branches can switch the **active branch per request** without
re-login (ADR-0001 D-F, ADR-0003):

- The JWT scope minted at login is the **default**. The `X-Branch-Uid` header changes the
  **request** scope only — no token re-mint, no DB write.
- Validation runs in `JwtRequestContextFilter`: resolve the branch uid → `Branch`, read its
  company, and (for non-root) verify an **ACTIVE** `user_branch` assignment for that user +
  branch. The branch and its company must both be ACTIVE.
- **Fail closed** on any defect: unknown branch uid → 403; branch archived mid-session →
  403; no matching active assignment → 403. No header → keep the JWT default scope.
- These are uncached point-reads, so a revoked assignment or archived branch takes effect on
  the very next request (ADR-0003 D-5).
- A rejected override is rendered as a 403 `ApiResponse` envelope by `SecurityErrorResponder`
  directly inside the filter — the filter runs downstream of `ExceptionTranslationFilter`, so
  it cannot rely on the chain's access-denied handler (ADR-0003 D-2 erratum).
- Root may override to any existing ACTIVE branch, unchecked against assignments, but a
  bad/archived uid is still 403 — root must not operate in a phantom scope (ADR-0003 D-4).

The shell's branch selector reads the caller's own assignments from the self-scoped
`GET /api/v1/auth/my-branches` (gated `isAuthenticated()`), so switching one's own branch
does not require an admin permission (ADR-0003 D-6).

### 3.3 `ScopeGuard.assertCanActIn`

`ScopeGuard` (`com.erp.platform.security`) is the single home for the root-bypass +
same-company rule (ADR-0002 D-4):

- `assertCanActIn(RequestContext.get(), companyId)` — root short-circuits to allow; else the
  active company must equal `companyId`, otherwise a 403 (`ForbiddenException`). A null active
  company (no-branch state) is a 403.
- `canActOn(principal, targetType, uid)` — resolves a target uid to its owning company and
  compares it to the active company; this is what `@perm.scoped` calls.

Target-op scope checks live in the gate (`@perm.scoped`). The two **body-scoped** cases —
`UserRole.grant/revoke` and `Branch.create`, where the scoping company is in the request
body, not a path uid — call `ScopeGuard.assertCanActIn` directly in the service. Both paths
converge on `ScopeGuard`, so root-bypass and the same-company predicate exist exactly once.

## 4. Audit trail

Every access-significant action leaves an **append-only** record in `audit_log`
(`com.erp.platform.audit`, ADR-0004). It is the platform's cross-cutting audit table, used by
IAM and every later module.

- **Same-transaction guarantee.** `AuditService.record(...)` is
  `@Transactional(propagation = MANDATORY)` — it joins the caller's transaction, so the audit
  row commits or rolls back atomically with the business change. A rolled-back business
  transaction writes no audit row, structurally (ADR-0004 D-2).
- **Explicit emit, not an aspect.** Each mutating service calls `record(...)` explicitly so
  the trail captures the resolved target id, scope, and status transition that generic AOP
  advice could not see.
- **What is recorded:** actor user, action, target (type + id), company/branch scope (read
  from `RequestContext` post branch-override — the scope the action actually ran in),
  timestamp, and IP for login events. Login / lockout events (`LOGIN.SUCCESS`, `LOGIN.FAIL`,
  `ACCOUNT.LOCKED`) are emitted inside the `REQUIRES_NEW` lockout transaction;
  `LOGIN.FAIL` for an unknown username carries a NULL actor with `usernameAttempted` in detail
  (ADR-0004 D-3).
- **`detail` (JSONB):** identifying / context fields and before/after **status** for
  lifecycle transitions (enable/disable). Profile-field edits record only the **fact** of the
  change, not old→new values, to minimise PII. **Never** stored: password hashes, raw
  passwords, token values/hashes, or JWT contents (ADR-0004 D-6).
- **Append-only enforcement** is in the application + CI, not a DB trigger (owner ruling,
  ADR-0004 D-5): `AuditService` exposes only `record(...)` and read queries (no update/delete);
  an ArchUnit rule fails the build if any class outside `com.erp.platform.audit` depends on
  `AuditRepository` or if `AuditService` grows a mutation method; and the app's DB role is
  granted `INSERT, SELECT` on `audit_log` only — not `UPDATE`/`DELETE`.
- **Read API:** `GET /api/v1/audit`, gated `@perm.has('AUDIT.VIEW')`, org-wide read, with
  AND-combined filters (`actorUid`, `action`, `targetType`, `targetUid`, `from`/`to`) and
  Spring `Pageable` (default sort `at,desc`; size 50; cap 200), returning `PageMeta` in the
  `ApiResponse.meta` slot (ADR-0004 D-7).

## 5. Public endpoints

Only four endpoints are public; every other `/api/**` route requires a valid bearer token
**and** passes its method-security gate. This allowlist is asserted by
`EndpointAuthorizationTest`.

| Endpoint | Why public |
|---|---|
| `POST /api/v1/auth/login` | Mint the first token. |
| `POST /api/v1/auth/refresh` | Rotate tokens without a session. |
| `POST /api/v1/auth/logout` | Invalidate a refresh token. |
| `GET /api/v1/health` | Liveness probe. |

`GET` requests **outside** `/api` are served publicly so the Angular SPA shell, built assets,
and client-side deep-links load (the API above is matched first and stays gated). `/actuator/**`
and the springdoc paths (`/v3/api-docs/**`, `/swagger-ui/**`) are permitted at the filter
level; in production the springdoc surface is disabled by config and the Prometheus endpoint is
moved to a separate internal management port (see [05-deployment-ops.md](05-deployment-ops.md)).
CSRF is disabled — correct for a stateless, token-authenticated API with no session cookie.

## 6. Secret handling

- **12-factor config.** No secret is hardcoded anywhere in `backend/src/main`; every secret
  is supplied as an environment variable (`${ENV_VAR:default}` in `application.yml`).
- **JWT signing key** (see [docs/ops/jwt-keys.md](../ops/jwt-keys.md)). Two modes:
  - `dev-in-memory` (the dev default) generates a fresh RSA keypair at JVM start — every
    restart invalidates all tokens, which is fine for local dev.
  - `file` (the **production default**, hard-wired in the prod compose) reads stable PEM key
    files from disk so tokens survive restarts and multiple API replicas can share one key.
    Keys are generated by `infra/prod/generate-jwt-keys.sh` (PKCS8 private / X.509 public),
    are gitignored (`*.pem`), bind-mounted read-only into the container, and `chmod 600` on
    the host. `private.pem` can forge any user's token — treat it as a top-tier secret kept in
    a secret store, separate from DB backups.
- **Bootstrap admin password** has **no default**: `BootstrapRunner` is fail-closed — it
  refuses to start if `ERP_BOOTSTRAP_ADMIN_PASSWORD` is blank or a known placeholder, and
  enforces a minimum length. `ERP_BOOTSTRAP_ENABLED` defaults to `false`; it is set `true`
  only for the first deploy on a fresh DB, then immediately unset (it is idempotent, so a
  later restart with it `false` is safe). Leaving it `true` would re-attempt bootstrap on
  every restart and expose the password to `docker inspect`.
- **Passwords** are BCrypt-hashed at cost 12; **refresh tokens** are SHA-256-hashed at rest.
- **401/403** responses are enveloped without leaking which check failed or whether a username
  exists (no enumeration). Unexpected 500s log the full stack trace server-side but return only
  `"An unexpected error occurred."` to the client (ADR-0038 D-1).
- **Dependency hygiene:** Dependabot opens weekly PRs; `npm audit --audit-level=high` gates
  the web CI; the OWASP dependency-check runs on demand (see
  [docs/ops/security-sweep.md](../ops/security-sweep.md)).

The overall security posture — what is hardened and what is deferred — is catalogued in
[ADR-0038](../decisions/0038-production-hardening.md) (HTTP security headers / CSP and a JWT
issuer validator are explicitly deferred there, with their conditions for revisiting).

---

# Deployment and Operations

ERPCLEAN2 ships as a Spring Boot 3 / Java 21 backend (with the Angular bundle baked into the
jar's `static/` path), a PostgreSQL 15 database, and — for local development — a separate
`ng serve`. This document covers the three environments, how to run locally, migration
discipline, the QA and production deploys, backup/restore, observability, and CI.

The operational design is recorded in [ADR-0038](../decisions/0038-production-hardening.md);
the runbooks live under [docs/ops/](../ops/) and the deploy artifacts under `infra/`.

## 1. Environments

| Environment | Topology | Database | Use |
|---|---|---|---|
| **Local dev** | `docker compose up -d db` (Postgres) + backend on host (`mvn spring-boot:run`, dev profile) + `ng serve` | Containerised Postgres, host port 5434 | Day-to-day development; the canonical e2e environment. |
| **QA** | Single container (`infra/qa`): API + in-container Postgres + Angular bundle, run by supervisord | In-container Postgres, persistent volume | Release smoke / UAT. |
| **Production** | Split topology (`infra/prod`): separate API and Postgres containers (reference single-node compose; topology-agnostic Dockerfile) | Separate `db` container with a persistent volume, or managed Postgres | Live (fenced ops decisions still open). |

The QA single-container shape (coupled API + DB lifecycle, no scaling) is deliberately
**wrong for production** but right for QA: one image, one `docker run`, one volume. Production
splits the lifecycles.

## 2. Running locally

Three processes: Postgres in Docker, the backend on the host (for hot reload), and `ng serve`.

### 2.1 Database

```bash
docker compose up -d db
```

This starts `postgres:15-alpine` as container `erp-db`, database `erp`, user/password
`erp`/`erp` (dev defaults only — not production secrets), mapped to **host port 5434**
(5432/5433 are taken by other local projects; the container stays on 5432 internally).

### 2.2 Backend

```bash
cd backend
SPRING_PROFILES_ACTIVE=dev ERP_API_PORT=8081 mvn spring-boot:run
```

The dev profile uses `dev-in-memory` JWT signing (fresh key each restart) and bootstraps the
super-user **`rootadmin` / `RootPass12345`** (dev only — never a production credential). The
API binds **port 8081** (`server.port: ${ERP_API_PORT:8081}`). Flyway runs on startup and
applies all migrations.

A containerised API is available opt-in via `docker compose --profile api up --build`, but
day-to-day dev runs the API on the host for hot reload.

### 2.3 Frontend

```bash
cd web
npm install      # first time
npm run start    # ng serve on :4200
```

`ng serve` runs on **port 4200**; its dev proxy forwards `/api` to the backend on
**`http://localhost:8081`** (`web/proxy.conf.json`). Open `http://localhost:4200`.

### 2.4 Ports and bootstrap credentials (summary)

| Component | Port | Notes |
|---|---|---|
| Postgres (dev) | host 5434 → container 5432 | container `erp-db`, db `erp` |
| Backend API | 8081 | `ERP_API_PORT`, dev bootstraps `rootadmin`/`RootPass12345` |
| `ng serve` | 4200 | proxy `/api` → `:8081` |
| Management (prod) | 9090 | Prometheus, internal network only |

## 3. Flyway migration discipline

- **Flyway owns all schema.** Hibernate runs `ddl-auto=validate` (never `update`) — the
  schema is defined by V-prefixed migrations only (PROJECT-CONVENTIONS §3.6).
- **Additive after freeze.** The IAM baseline (V1) was edited in place while the schema was
  pre-stable; after that freeze, all changes are **additive** new migrations. The migration
  head is currently around **V83**.
- **Never edit an applied migration.** A V-prefixed file that has run in any environment must
  not be changed — Flyway validates checksums on startup and a restored DB confirms the schema
  matches. Editing applied migrations is a backend-engineer call, never an ops call
  ([backup-restore.md](../ops/backup-restore.md)).
- **No schema change without a migration.** A new permission-gated endpoint needs its
  permission in a seed migration; a new transactional table needs `company_id` + `branch_id`
  and the tenant predicate.
- **Failed migration rollback:** stop the API, restore the backup taken **before** the deploy,
  fix the migration SQL, redeploy. Flyway `validate` on the next startup confirms the restored
  schema (see [backup-restore.md](../ops/backup-restore.md) "Rollback story").

## 4. QA deployment

QA is a single container on the target box (see `infra/qa/README.md`). After a one-time
bootstrap (install Docker, clone the repo with a fine-grained read-only PAT, place
`infra/qa/qa.env` with the DB and bootstrap secrets), each release is one command from a
developer machine:

```bash
# macOS / Linux / git-bash
export ERP_SSH_KEY=~/keys/qa.pem
infra/qa/deploy.sh                 # deploys the branch named in infra/qa/deploy.env
```

```powershell
# Windows / PowerShell
$env:ERP_SSH_KEY = "C:\path\to\qa.pem"
infra\qa\deploy.ps1
infra\qa\deploy.ps1 -Branch main   # or another branch
```

The deploy script SSHes in, `git pull`s the branch, rebuilds the image, and restarts the
container. The QA image runs the API, an in-container Postgres, and the Angular bundle under
supervisord; it activates `SPRING_PROFILES_ACTIVE=qa` (`infra/qa/application-qa.yml` pins a
Hikari pool of 10 and INFO logging). The container reads the JWT signing mode from
`ERP_JWT_SIGNING_MODE` (defaults to `dev-in-memory`).

- **Data-preserving deploy** is the default: the persistent volume survives a redeploy, so QA
  data carries across releases.
- **Recreate / wipe:** stop and remove the container **and** drop the `erpclean2-data` volume,
  then redeploy — the next start re-bootstraps from `qa.env` on a fresh DB.

The non-secret target host/user/branch live in committed `infra/qa/deploy.env`; the SSH key
path, the GitHub PAT, and the bootstrap secrets stay out of git (`deploy.env.local`,
`qa.env`, both gitignored).

## 5. Production deployment

Production uses the split topology in `infra/prod` (ADR-0038 D-6). The `infra/prod/Dockerfile`
is a three-stage build (Angular build → Maven package with the bundle copied into
`static/` → `eclipse-temurin:21-jre-alpine` runtime) and is **topology-agnostic** — it is the
canonical artifact for any orchestrator. The `infra/prod/docker-compose.yml` is a clearly
labelled **reference single-node topology**: a `db` (Postgres 15) service and an `api` service
on port 8081, both `restart: unless-stopped`, with healthchecks and `depends_on:
service_healthy`. If the owner chooses K8s / ECS / a PaaS, the compose file is documentation
and the Dockerfile is the deploy unit.

Key production settings (from the prod compose / `.env.example`):

- `SPRING_PROFILES_ACTIVE=prod` → JSON logging, pinned INFO/WARN log levels.
- `ERP_JWT_SIGNING_MODE=file` (hard-wired) → stable RS256 keys, bind-mounted read-only; tokens
  survive restarts and can be shared across replicas (see [jwt-keys.md](../ops/jwt-keys.md)).
- `ERP_API_PORT=8081` everywhere (the one consistent port); the management port is 9090.
- DB connection via `ERP_DB_URL` / `ERP_DB_USER` / `ERP_DB_PASSWORD`.
- `ERP_BOOTSTRAP_ENABLED=false` by default; set `true` **only** for the first deploy on a
  fresh DB with a strong `ERP_BOOTSTRAP_ADMIN_PASSWORD`, then immediately unset and restart.
- `ERP_API_IMAGE` lets a CI-built, SHA-tagged image be specified for rollback without editing
  the compose file.

All secrets come from a gitignored `.env` (see `infra/prod/.env.example`) plus the bind-mounted
JWT key files — the key files never enter the image. Several production decisions are explicitly
**fenced** pending an owner choice (host/orchestrator, managed vs self-hosted Postgres,
container registry, secrets backend, TLS edge / reverse proxy, log aggregation, metrics
scraping) — see ADR-0038 D-Fenced.

## 6. Backup and restore

PostgreSQL is the only datastore; backups use `pg_dump` custom format (`-Fc`) and `pg_restore`
(scripts in `infra/prod/`, runbook in [backup-restore.md](../ops/backup-restore.md)).

- **Backup:** `infra/prod/backup.sh` writes a timestamped compressed `.dump` to `BACKUP_DIR`
  and prunes archives older than `BACKUP_RETAIN_DAYS` (default 14–30). It reads connection
  details from `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`/`PGPASSWORD`. Exits non-zero on failure
  so a cron monitor can detect it.

  ```cron
  0 2 * * * BACKUP_DIR=/backups BACKUP_RETAIN_DAYS=14 sh infra/prod/backup.sh >> /var/log/erpclean2-backup.log 2>&1
  ```

- **Restore:** stop the API (so Flyway/Hibernate cannot write mid-restore), take a fresh
  backup of the current state for forensics, then `infra/prod/restore.sh` (`pg_restore --clean
  --if-exists`, with a confirmation guard). Restart the API and watch the log for the Flyway
  validate result.
- **Off-host copy:** the `/backups` volume is on the same host as the database — keep at least
  one backup off-host (S3, rsync to a second server). If the owner adopts managed Postgres,
  rely on the managed snapshot facility and treat these scripts as the self-hosted fallback.

## 7. Observability

- **Health probes** (ADR-0038 D-4). The actuator exposes `health,info` on the main port with a
  readiness/liveness split: readiness includes `db` + `diskSpace`; liveness is `ping`. The
  container `HEALTHCHECK` targets `/actuator/health/liveness` on 8081 (a DB outage should not
  trigger a container restart — that is the orchestrator's concern via `depends_on`); the
  compose `api` healthcheck polls `/actuator/health/readiness`. `start-period` allows for
  Flyway migration time at startup.
- **Metrics** (ADR-0038 D-5). Micrometer + Prometheus exposes `/actuator/prometheus` on a
  **separate management port 9090**, reachable only on the internal container network — never
  bound to the host edge — so metrics (memory, bean names, tenant counts) are not exposed
  unauthenticated over the public port. Custom metrics include outbox dispatch + FAILED-event
  counters (tagged by bounded `event_type`, so a poison-event loop is immediately visible) and
  Hikari pool saturation; JVM, GC, and HTTP timing metrics come free.
- **Structured logging** (ADR-0038 D-3). The `prod` profile emits **JSON to stdout**
  (`logstash-logback-encoder`), 12-factor-compliant and ingestible by any aggregator; dev/test
  use a plain-text pattern. Both include the MDC fields.
- **Correlation id** (ADR-0038 D-2). `JwtRequestContextFilter` puts `requestId`, `userId`,
  `username`, `companyId`, and `branchId` into the SLF4J MDC, so every log line is traceable to
  a request and tenant. The `requestId` is taken from an inbound `X-Request-Id` (or generated)
  and echoed in the response header. MDC is cleared per request. Known limitation: async outbox
  / scheduled handlers run on different threads and do not inherit the MDC.
- **Exception logging** (ADR-0038 D-1). Unexpected 500s are logged with the full stack trace
  server-side; the client still receives only `"An unexpected error occurred."`. Filter-level
  401/403 denials are normal flow and are not logged as errors.

## 8. Continuous integration

Two GitHub Actions workflows under `.github/workflows`, both triggered on push to
`main`/`develop`/`feat/**` and on PRs to `main`/`develop`.

### 8.1 Backend CI — `backend-ci.yml` (ADR-0038 D-8)

- **`fast-check` (REQUIRED):** `mvn -B -ntp clean test` — compile + surefire unit tests +
  ArchUnit gates (`ModuleBoundaryTest`, `EndpointAuthorizationTest`). No Docker; fast (~2–3
  min). This is the PR gate, so RBAC-gate and module-boundary regressions are caught
  immediately.
- **`integration-test` (observe-only):** `mvn -B -ntp clean verify` — the full ~98 Testcontainers
  Postgres integration tests on a Linux runner (`TESTCONTAINERS_RYUK_DISABLED=true`, singleton
  container). Marked `continue-on-error: true` until proven stable across 5+ runs, then promoted
  to required. `clean` is deliberate — an incremental compile gave a false green in a prior wave.
  Failsafe reports are uploaded on failure.

### 8.2 Web CI — `web-ci.yml`

- **`build-and-test`:** `npm ci` → `npm run build` (production, must be zero errors) →
  `npm test` (Vitest unit specs including the axe a11y gate) → `npm audit --omit=dev
  --audit-level=high` (CVSS-7+ gate, mirroring the Maven threshold).
- **`e2e` (manual):** Playwright is wired but not auto-triggered (`if: false`) — it needs a
  running backend; run it via `workflow_dispatch` or locally.

The identity-discipline static gate (`npm run c1`) and the full testing pyramid are described
in [07-testing.md](07-testing.md). The dependency-CVE runbook (OWASP dependency-check,
Dependabot, the fenced weekly scan) is in [docs/ops/security-sweep.md](../ops/security-sweep.md).

---

# Engineering Conventions

These are the invariants every contributor follows. They are the substrate the whole system
rests on; breaking one is a defect, not a style choice. The authoritative sources are
[PROJECT-CONVENTIONS.md](../../PROJECT-CONVENTIONS.md) (stack + backend discipline) and
[docs/frontend/CONVENTIONS.md](../frontend/CONVENTIONS.md) (Angular). This document summarises
both and the cross-cutting C-series conventions (C1–C9) used as automated gates.

## 1. The fixed stack

| Layer | Choice |
|---|---|
| Backend | Spring Boot 3.3 · Java 21 · Maven · Hibernate 6 · Flyway |
| Database | **PostgreSQL 15+** (single engine — not DB-agnostic) |
| Web | Angular 21 · standalone components (no NgModules) · TypeScript strict · signals |
| Auth | In-house JWT (RS256), refresh-token rotation, RBAC by permission |

PostgreSQL is deliberate, not incidental: use `JSONB` for audit payloads and flexible
attributes, `BIGINT GENERATED ... AS IDENTITY` or sequences for keys, partial/expression
indexes, `gen_random_uuid()`. Prefer the boring, well-understood Postgres feature. JPQL /
`CriteriaBuilder` for normal queries; native SQL only for reports and bulk paths, kept behind
clearly-named repository methods.

## 2. Backend layout — modular monolith

Base package `com.erp`. Boundaries are enforced by `ModuleBoundaryTest` (ArchUnit), not by
convention alone.

- `com.erp.api..` — REST controllers, flat, one per resource.
- `com.erp.modules.<name>..` — `domain` (`entity` / `dto` / `enums` / `event`), `service`
  (interface `Xxx` + class `XxxImpl`, `@Transactional` at public methods), `repository`.
- `com.erp.platform..` — cross-cutting spine: `security`, `iam`, `company`, `audit`,
  `sequence`, `events`, `common`.

**Layering (enforced):** controller → service → repository → domain. Controllers may **not**
touch repositories. Modules communicate only via `..domain.dto..` / `..domain.enums..` and the
outbox — never by importing another module's entity or service. If a needed dependency breaks
the rule, the design is wrong; fix it. A rule change needs an ADR. The platform security/audit
spine is the one allowed cross-module importer of IAM repositories (e.g. `PermissionResolver`,
`ScopeGuard`) — an explicit, documented exception.

## 3. The `ApiResponse<T>` envelope (C2)

Every REST response is wrapped in `ApiResponse<T>` (`data`, `errors[]`, `meta`):

- **Backend:** controllers return the raw `T`; a response interceptor wraps it automatically.
  Never leak internal exception text into `errors[]` — user-safe strings only.
- **Web:** an HTTP interceptor (`apiResponseInterceptor`) **auto-unwraps** the envelope, so
  services and components never see `ApiResponse<T>` — a plain `http.get<T>()` returns the
  unwrapped value.
- **Paginated lists opt OUT** of unwrapping (set `SKIP_UNWRAP`) to keep `meta`, typing the call
  as `http.get<ApiResponse<T[]>>(...)` and mapping to `{ rows, meta }`. `PageMeta` is
  `{ page, size, totalElements, totalPages, hasNext }`.

## 4. Identity discipline (C1 — the headline)

Every externally exposed entity carries two identifiers:

- numeric `id` (`BIGINT`) — for body-level FK joins, and
- a stable external `uid` (string, ULID) — the canonical external reference.

The rules:

- **URLs address by uid:** `/api/v1/<resource>/uid/{uid}`. A numeric `id` never appears in a
  URL path — not in a service call, not in a `routerLink`.
- **Bodies use `id` for FK refs** (`companyId: string`) and `uid` for lookups (`companyUid`).
  Mirror the backend DTO exactly.
- **Long / BigDecimal fields serialise as JSON strings** globally (configured once on the
  backend) so 64-bit ids and money survive JavaScript. The web side types every id field as
  `string`.
- **A uid is a machine identifier — never shown to or hand-typed by a user.** Resources are
  chosen by human name/code via the shared `<app-uid-picker>`; the bound value is the uid. A
  free-text uid input (or a raw numeric-id text box) is a violation.

**C1 static gate:** `npm run c1` (`web/scripts/c1-check.mjs`) statically checks the frontend
for C1 violations — uids leaking into the UI, hand-typed uid/id inputs, numeric ids in URLs. It
is part of the verification routine alongside `npm run build` and `npm test`.

## 5. RBAC by permission code (C3)

The atomic unit is a **permission** (`Permission`, table `permission`, dot-separated codes like
`SALES_INVOICE.POST`). Roles are bundles of permissions. `@PreAuthorize` gates reference
**permission codes, never role names**, via the `@perm` bean:

```java
@PreAuthorize("@perm.has('AGENT.VIEW')")                          // create / list
@PreAuthorize("@perm.scoped(#uid,'activity','CRM.ACTIVITY.MANAGE')") // target by uid + scope
```

Permissions are seeded via Flyway; a new permission-gated endpoint must have its code in a seed
migration. The frontend gates with the **exact same codes** —
`SessionStore.hasPermission(code)` (root → always true), `requirePermission(code)` route
guards, and `@if (canX())` on action buttons. Never invent a code on the web side; mirror the
controller. `EndpointAuthorizationTest` fails the build if any handler under `com.erp.api` lacks
a gate (allowlist = exactly 4 public endpoints). See [04-security.md](04-security.md).

## 6. Multi-company / multi-branch (C7)

Every transactional table carries `company_id` + `branch_id`. `RequestContext` sets the current
user / company / branch from the JWT plus the `X-Branch-Uid` override header (branch can be
switched without re-login). Repository base interfaces inject the company/branch predicate
automatically — a finder that bypasses the base interface is a tenant-isolation bug. Global IAM
admin tables are the deliberate exception, isolated by service-layer scope checks instead. See
[04-security.md](04-security.md) §3.

## 7. Persistence discipline (C9)

- **Flyway for all schema**; `ddl-auto=validate` (never `update`). Additive migrations after the
  baseline freeze; the head is around V83. Never edit an applied migration.
- **Optimistic locking** (`@Version`) on transactional aggregates.
- **Append-only posting tables** (e.g. `stock_move`, GL postings, `audit_log`) — corrections are
  **new postings**, never updates. The GL is append-only: a posted journal is corrected by a
  reversing or adjusting entry, not by editing the original.
- **Soft-delete masters** via a `status` enum (`ACTIVE` / `INACTIVE` / `ARCHIVED`), not hard
  deletes.

## 8. Cross-module side effects = transactional outbox (C-outbox)

Write a `domain_event` row in the **same DB transaction** as the business write; a scheduled
poller dispatches it asynchronously. Never call directly into another module's service for a
side effect, and never use Spring's in-memory `ApplicationEventPublisher` for cross-module
events (they are lost on crash). The outbox gives at-least-once delivery with crash durability.

## 9. Coding standards

- **Java:** Google Java Style; `final` where reasonable; records for DTOs (`*Dto` suffix).
  **Lombok** `@Getter @Setter` on JPA entities (never `@Data` / `@EqualsAndHashCode` / `@ToString`
  — they break JPA identity and lazy loading). Hand-write constructors and behaviour methods
  (invariants, state transitions); Lombok generates only plain accessors.
- **TypeScript:** strict; no `any` without a justification comment; ESLint + Prettier.
- **Commits:** Conventional Commits; one logical change per PR; mandatory review.

## 10. Frontend conventions

Grounded in the shipped Angular 21 app — mirror the exemplars, do not invent patterns.

- **Standalone + signals.** No NgModules; providers in `app.config.ts`. `signal<T>()` for
  mutable state, `computed()` for derived; RxJS only for HTTP streams + debounced search
  (`toObservable` + `takeUntilDestroyed`).
- **Forms:** `FormsModule` + `[(ngModel)]` two-way to signals (no ReactiveForms). Validation is
  imperative in the submit handler, setting a `formError` signal rendered in `<p role="alert">`.
- **Control flow:** `@if` / `@for (...; track x.uid)` / `@switch` — never `*ngIf`/`*ngFor`.
- **Routing:** `withComponentInputBinding()` — bind `:uid` to `input.required<string>()`. URL
  shapes: list `'<feature>'`, detail `'<feature>/uid/:uid'`, create `'<feature>/create'`. Route
  guard `requirePermission('CODE')`.
- **Four-state screens (C4):** every list/detail renders four states via `@switch (state())` —
  **loading** (spinner + `aria-live`), **empty** (message), **error** (`role="alert"`), and
  **forbidden** ("no permission"). This is a hard requirement, asserted in e2e.
- **Pagination (C5):** every paginated list uses the shared `<app-paginator>` —
  first / previous / page-numbers / next / last (it self-hides when `totalPages <= 1`). Never
  roll your own prev/next.
- **Resource references (C1):** captured only via `<app-uid-picker>` (a `ControlValueAccessor`
  bound to the uid) — the user picks by name, never types a uid.
- **Shared, append-only files:** `features/admin/admin.routes.ts` (route blocks),
  `layout/shell/shell.component.ts` (nav items into the right group) — append only, never
  reorder; they are high-conflict files.
- **Money / dates (C8):** money is a string on the wire, rendered `CUR 1,234.56`
  (`{{ row.currency }} {{ +row.amount | number:'1.2-2' }}`); dates are ISO `yyyy-MM-dd`.
- **Accessibility (C6):** WCAG 2.1 AA. `scope="col"` on `<th>`, visually-hidden captions,
  `aria-label` on icon links, icons `aria-hidden`. axe-core runs in CI (within `npm test`) and
  in e2e; new serious/critical violations fail the build.

## 11. The C-series conventions at a glance

These are the conventions enforced as first-class automated gates (unit, e2e, and the C1 static
check). Full detail in [docs/testing/test-cases/24-conventions-cross-cutting.md](../testing/test-cases/24-conventions-cross-cutting.md).

| Code | Convention |
|---|---|
| **C1** | Identity: uid only in the URL path; never shown / hand-typed; resources chosen via picker; no numeric id in a URL. |
| **C2** | `ApiResponse<T>` envelope (auto-wrap server-side, auto-unwrap client-side). |
| **C3** | RBAC by permission code, never role name; gates mirror the backend codes. |
| **C4** | Four-state screens (loading / empty / error / forbidden). |
| **C5** | Pagination controls (first / prev / numbers / next / last). |
| **C6** | WCAG 2.1 AA / axe — no new serious/critical violations. |
| **C7** | Multi-company + multi-branch isolation. |
| **C8** | Money (`CUR 1,234.56`) and date (ISO) formatting. |
| **C9** | Soft-delete / archive for masters; append-only postings for ledgers. |

---

# Testing

ERPCLEAN2 is verified by a layered test pyramid — JUnit unit tests, Testcontainers
integration tests, Vitest web unit tests (including an axe a11y gate), Playwright e2e
(with axe), and a static identity-discipline gate — backed by a 1,150-case manual/automated
test-case suite. This document describes each level, how to run it, and how findings flow into
the issues register.

The execution strategy is in
[docs/testing/TESTING-STRATEGY.md](../testing/TESTING-STRATEGY.md); the per-module cases are in
[docs/testing/test-cases/](../testing/test-cases/).

## 1. The test pyramid

| Level | Tool | Where | Count | Run |
|---|---|---|---|---|
| Unit | JUnit (surefire) | `backend/` | — | `mvn test` |
| Architecture gates | ArchUnit (surefire) | `backend/` | — | `mvn test` |
| Integration | Spring + Testcontainers Postgres (failsafe) | `backend/` | ~98 ITs | `mvn verify` |
| Web unit | Vitest | `web/` | 678 specs | `npm test` |
| Web a11y | jest-axe (inside Vitest) | `web/` | (within the 678) | `npm test` |
| C1 static gate | Node script | `web/` | — | `npm run c1` |
| System e2e | Playwright + axe | `web/e2e/` | data-driven | `npm run e2e` |

The pyramid widens upward in coverage and downward in speed: the surefire layer is the fast PR
gate; the Testcontainers ITs and Playwright e2e are the slower, higher-fidelity layers.

## 2. Backend — unit and architecture gates

```bash
cd backend
mvn test          # compile + surefire: JUnit unit tests + ArchUnit gates
```

Two ArchUnit gates run in this fast phase and **fail the build** on violation:

- **`ModuleBoundaryTest`** — enforces the modular-monolith layering: controllers may not touch
  repositories; a module does not import another module's entity/service; cross-module
  communication is via DTOs/enums and the outbox only.
- **`EndpointAuthorizationTest`** — scans every `@RestController` under `com.erp.api` and fails
  if any handler lacks a `@PreAuthorize` gate. The public-endpoint allowlist is exactly 4
  endpoints (see [04-security.md](04-security.md) §5). This makes deny-by-default a build
  guarantee.

This is the **required** gate in `backend-ci.yml`'s `fast-check` job — fast, no Docker.

## 3. Backend — integration tests (Testcontainers)

```bash
cd backend
mvn verify        # surefire + failsafe: ~98 Spring + Testcontainers Postgres ITs
```

The ITs boot a full Spring context against a **real PostgreSQL 15** container, so they exercise
Flyway migrations, the tenant predicate, RBAC gates, the outbox, and HTTP-level filter behaviour
(e.g. the branch-override 403 path that service-level tests cannot see — ADR-0003 D-2).

- A **singleton container per JVM** with **Ryuk disabled** (`PostgresIntegrationTest` /
  `testcontainers.properties`) — required on Windows/Docker-Desktop, harmless on Linux CI.
- The `@Scheduled` outbox poller is disabled in ITs (`erp.outbox.scheduling-enabled=false`) so
  it does not race assertions; `@DynamicPropertySource` overrides the datasource; `MailStubConfig`
  stubs the mail sender; bootstrap is disabled by the test properties.
- `clean verify` is the source of truth — an incremental compile gave a false green in a prior
  wave; always run the clean build before trusting a green.

In CI this is `backend-ci.yml`'s `integration-test` job (observe-only until proven stable, then
promoted to required).

## 4. Web — unit and a11y

```bash
cd web
npm test                          # Vitest, 678 specs, includes the jest-axe a11y gate
npx vitest run <path-to-spec>     # a single spec
npm run build                     # type-check + bundle, must be zero errors
```

Each feature ships a `*-list.component.spec.ts` covering the standard cases: load-once,
`isEmpty`, the validation guard, the success payload, and `403 → 'forbidden'`. The axe a11y
checks run inside Vitest, so accessibility regressions fail the same command.

## 5. The C1 static gate

```bash
cd web
npm run c1        # web/scripts/c1-check.mjs
```

This statically enforces identity discipline (convention C1, [06-conventions.md](06-conventions.md)
§4): uids must not leak into the UI, resources must be chosen via `<app-uid-picker>` (no
free-text uid/id inputs), and no numeric id may appear in a URL path. It is a fast, dependency-free
check run alongside `npm run build` and `npm test`.

## 6. System e2e — Playwright + axe

```bash
# Bring-up (local is the canonical e2e environment)
docker compose up -d db
cd backend && SPRING_PROFILES_ACTIVE=dev ERP_API_PORT=8081 mvn spring-boot:run
cd web && npm run e2e:install                # Chromium, first time only
node e2e/full-coverage-drive.js             # seed volume data via the API

# Run (Playwright auto-starts ng serve on :4200)
cd web && ROOT_PASS=RootPass12345 npm run e2e
```

The suite (`web/e2e/`) is layered so coverage is broad and failures are unambiguous:

| Layer | Proves |
|---|---|
| **L1 Auth & RBAC** | Login per role; nav visibility per permission; forbidden-route handling; cross-tenant denial. |
| **L2 Route smoke** | Every admin route loads (no error state, heading visible, no console error / API 5xx) + axe scan. Data-driven from the route list. |
| **L3 Conventions** | C1 (uid never shown, picker used), C4 (four-state), C5 (pagination), C6 (axe), C8 (money/date). |
| **L4 Lifecycle flows** | The create → action → state journeys per module, grounded in the per-module test-case docs. |

Assertions navigate by **route** and interact by **accessible role/label/placeholder** — never
by uid. The C1 gate asserts no ULID/numeric-id text appears in a visible cell and that resource
selectors are pickers, not free-text uid inputs. axe runs on representative screens; serious /
critical violations fail. Each Playwright test maps back to a `TC-<DOMAIN>-NNN` id so coverage is
traceable. Playwright e2e is wired into `web-ci.yml` but not auto-triggered (it needs a running
backend) — run it locally or via `workflow_dispatch`.

## 7. The test-case suite (1,150 cases)

[docs/testing/test-cases/](../testing/test-cases/) holds **1,150 cases across 25 documents** —
one per module plus a strategy doc, an RBAC matrix, and a cross-cutting conventions doc. They
were authored from the real code (controllers, routes, DTOs, enums, migrations) and
**adversarially verified**: a second pass grep-confirmed every cited endpoint, permission code,
enum value, and route. Each is the best per-module reference for screens, routes, permissions,
fields, status lifecycles, and flows.

The cases are **UI-first**: written to drive automated Playwright e2e (navigate by route, pick by
name, assert four-state + pagination + axe + RBAC) and to double as manual UAT scripts. Start with
[00 — Test Strategy & Environment](../testing/test-cases/00-test-strategy-and-environment.md) for
the environment matrix (all user/branch/entity types), the ID scheme, and the C1–C9 convention
charter; the [README](../testing/test-cases/README.md) has the full catalogue and case counts.

## 8. Issues register

Findings flow into [docs/testing/ISSUES-REGISTER.md](../testing/ISSUES-REGISTER.md) (and
[ISSUES.md](../testing/ISSUES.md)). Every e2e failure is triaged into **real app defect** vs
**spec defect** (a flaw in the test): real defects are logged with id, severity, module, the
`TC-`/spec that found it, route, role, steps, expected vs actual, and evidence
(screenshot/trace); spec defects are fixed in the spec, not logged as product issues. Severity is
P1 (blocking — cannot complete a core flow / 500 / auth broken), P2 (major), or P3 (minor).

**Release gate:** e2e L1+L2 fully green, L4 P1 flows green, and no open P1 in the issues register.

Authoring the test-case suite already surfaced concrete defects to confirm when running — for
example the POS permission prefix mismatch (controllers check `POS.*` while the migration seeds
`SALES.POS.*`, so the exact-match resolver denies every non-root user), hardcoded POS tender, and
several create-path 500s. These are catalogued in the
[test-cases README](../testing/test-cases/README.md).
