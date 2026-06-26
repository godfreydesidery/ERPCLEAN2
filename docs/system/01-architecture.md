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
5. **Repository** issues the query. The base interface injects the `company_id`/`branch_id` predicate from `RequestContext`, so a finder cannot accidentally read another tenant's rows. On the IAM-admin path (where the blanket predicate is off) the service must scope explicitly — see §4: the scope-company is derived from the *loaded* entity, never a caller-supplied parameter, on both reads and writes.
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
- **Derive the scope-company from the loaded entity, never from a caller-supplied parameter — and apply the guard to reads AND writes.** A 2026-06-26 e2e probe found 28 cross-company leaks across 11 modules from two patterns: a *confused-deputy via a secondary identifier* (the endpoint scope-checks a caller-supplied `companyId` — which the attacker sets to their own — then loads data by a separate, unverified `accountId`/`productId`/`glAccountId`/…), and a *missing write-scope* (a `uid`-addressed mutator loaded by `uid` without re-checking the loaded entity's company, so you could write a record you could not read — worst case, cross-tenant account takeover via the IAM user update/disable/enable/unlock/set-password path). All 28 are fixed in the service layer — company-scoped repository finders plus an ownership re-check derived from the loaded row; the isolation read oracle is unchanged and there is no schema change. A regression guard, the **`TenantScopingRulesTest`** ArchUnit `FreezingArchRule`, fails the build if a `..service..` class makes a bare `findById`/`getReferenceById` on a Spring Data repository, forcing company-scoped finders; ~197 pre-existing audited calls are frozen as a baseline (`allowStoreUpdate = false`).
- **Company master data is not enumerable cross-tenant.** `GET /api/v1/companies` (the company-admin list) once returned every organisation company to any `COMPANY.VIEW` holder; it now returns only the companies the caller belongs to — the same assigned-or-root filter as `/companies/accessible` (root still sees all).
- **`user_company` membership is an authoritative write-path prerequisite (ADR-0046, superseding the non-authoritative phase of ADR-0045).** `UserRoleService.grant` and `UserBranchService.assign` require an active `user_company` membership for the target company (else 409); the prior auto-create on grant/assign is removed, and removing a membership is blocked while the user still holds roles or branches there (no cascade). Root bypasses tenant scope but **not** the membership gate; bootstrap/seeders write entities directly and are unaffected. The read/isolation oracle stays the additive superset (role OR branch OR `user_company`) as a defensive belt. `USER.COMPANY.MANAGE` gates explicit assign/remove; coverage is guaranteed by an idempotent every-boot reconcile (`UserCompanyBackfill`), so there is no Flyway migration. BR-6 is re-decided: branch and role assignment stay independent of each other, but each now requires prior company membership.

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
