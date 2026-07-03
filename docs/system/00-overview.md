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
| Schema management | Flyway (`ddl-auto=validate`; migrations `V1` … `V78`) |
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
