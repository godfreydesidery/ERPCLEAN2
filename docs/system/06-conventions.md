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
