# ERPCLEAN2 Frontend Conventions

> The contract every feature builder follows. Grounded in the existing `web/` app (Angular 21,
> standalone, signals). Read this fully before writing any feature code. Do NOT invent patterns —
> mirror the exemplars cited here.

## 1. Project Shape
- **Angular 21.2**, standalone components only (no NgModules). Providers in `src/app/app.config.ts`.
- **Signals** are the state primitive: `signal<T>()` for mutable fields, `computed()` for derived.
  RxJS only for HTTP streams + debounced search (`toObservable` + `takeUntilDestroyed`).
- **Forms:** `FormsModule` + `[ngModel]`/`(ngModelChange)` two-way to signals. NO ReactiveForms.
  Validation is imperative in the submit handler → sets a `formError = signal<string|null>(null)`
  rendered in `<p role="alert">`.
- **Control flow:** `@if` / `@for (x of rows(); track x.uid)` / `@switch`. NO `*ngIf`/`*ngFor`.
- **Routing:** `withComponentInputBinding()` is on — bind `:uid` path param to
  `readonly uid = input.required<string>()`. Query params via `ActivatedRoute.snapshot.queryParamMap`.
- **Styles:** Bootstrap 5.3 + Bootstrap Icons 1.13 (global). Component `.scss` relies on global
  `src/app/features/admin/admin.styles.scss` (`.admin-page`, `.grid`, `.create-form`, `.state`,
  `.form-error`, `.page-head`).
- **Commands:** `npm run build` (type-check+bundle, must be zero errors), `npm test` (Vitest),
  single spec: `npx vitest run <path-to-spec>`.

## 2. HTTP / API Layer
- **Base URL:** `environment.apiBaseUrl = '/api/v1'`. Dev proxy forwards `/api`→`:8080`.
- **Envelope auto-unwrap:** `apiResponseInterceptor` (`src/app/core/api/http.interceptors.ts`) strips
  `{data,errors}` → services/components NEVER see `ApiResponse<T>`. A non-paginated `http.get<T>()`
  returns `Observable<T>` already unwrapped.
- **Paginated lists opt OUT** to keep `meta`: set `SKIP_UNWRAP` (`core/api/http-context.tokens.ts`),
  type `http.get<ApiResponse<T[]>>(...)`, map to `Observable<{ rows: T[]; meta: PageMeta }>`.
- **Auth:** `authHeaderInterceptor` adds `Authorization: Bearer` + `X-Branch-Uid` automatically.
- **Services:** one per feature folder, `@Injectable({ providedIn: 'root' })`,
  `private readonly base = \`${environment.apiBaseUrl}/<resource>\``. No central wrapper.
- **uid-in-URL / id-in-body:** URL paths always use `uid` → `/<resource>/uid/${uid}`. Request bodies
  use `id` for FK refs (`companyId: string`) and `uid` for lookups (`companyUid`). Mirror the backend
  DTO exactly. ALL Long/BigDecimal fields are typed `string` on the wire.

Exemplars: `parties/customer.service.ts`, `sales/sales-orders.service.ts`, `products/product.service.ts`.

## 3. List Component Pattern
Exemplar: `sales/quotation-list.component.{ts,html,scss}`, `parties/customer-list.component.*`.
- Signals: `rows`, `meta`, `state ('loading'|'idle'|'error'|'forbidden')`, `currentPage`, `searchQ`,
  `isEmpty = computed(...)`, `canManage = computed(() => session.hasPermission('X.MANAGE'))`.
- Load in **constructor** (no `ngOnInit`): merge debounced `toObservable(searchQ)` typing stream +
  an `immediateTrigger$` Subject → single `switchMap` → `takeUntilDestroyed()`. Fire
  `immediateTrigger$.next()` after companies resolve.
- Load companies first via `OrganisationService.current()` → `CompanyService.list(org.uid)`; bind a
  company `<select>` (hide when single company); pass company id as filter.
- **Four-state `@switch (state())`**: loading (spinner + `aria-live`), error (`role="alert"`),
  forbidden ("no permission"), default → `@if (isEmpty())` empty msg `@else` table.
- Table: `<table class="table table-hover align-middle bg-white grid">`, `<caption class="visually-hidden">`,
  `scope="col"` on every `<th>`, actions header is visually-hidden, action links use `x.uid` in path +
  `aria-label`, icons `aria-hidden="true"`.
- Pagination nav only `@if (meta().totalPages > 1)` with prev/next buttons gated on `currentPage`/`hasNext`.
- Simple creates: inline form toggled by `showCreateForm = signal(false)`. Complex: link to a create route.
- Success → `alerts.success('X created', name)` then reload. Error → `formError.set(messageFrom(err))`.

## 4. Detail + Create/Edit Pattern
Exemplars: `parties/customer-detail.*`, `sales/quotation-detail.*`, `purchases/goods-receipt-create.*`.
- `readonly uid = input.required<string>()`. Init via `constructor() { queueMicrotask(() => this.init()); }`.
- Signals: `entity`, `state`, one `f*` signal per editable field. `patchForm(entity)` after load.
- `save()`: validate → `saving.set(true)` → service call → on next `entity.set(updated)` +
  `alerts.success(...)`; on error `saveError.set(messageFrom(err,'Could not save.'))`.
- Create screens read parent context from query params; on success
  `router.navigate(['/admin/<feature>/uid', created.uid])`.
- Error helper (copy into each component):
```ts
private messageFrom(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse) {
    const errors = (err.error as { errors?: string[] })?.errors;
    if (errors?.length) return errors[0];
  }
  return fallback;
}
```

## 5. Routing Registration — `src/app/features/admin/admin.routes.ts`
Append a banner-commented block inside `ADMIN_ROUTES`, BEFORE the final `{ path:'', redirectTo:'home' }`:
```ts
// ── FeatureName ──────────────────────────────────────────────
{ path: '<feature>',           canActivate: [requirePermission('FEAT.VIEW')],
  loadComponent: () => import('./<feature>/<feature>-list.component').then(m => m.XListComponent) },
{ path: '<feature>/uid/:uid',  canActivate: [requirePermission('FEAT.VIEW')],
  loadComponent: () => import('./<feature>/<feature>-detail.component').then(m => m.XDetailComponent) },
{ path: '<feature>/create',    canActivate: [requirePermission('FEAT.CREATE')],
  loadComponent: () => import('./<feature>/<feature>-create.component').then(m => m.XCreateComponent) },
```
URL shapes: list `'<feature>'`; detail `'<feature>/uid/:uid'`; create `'<feature>/create'`;
nested `'<parent>/uid/:uid/<child>'`. Guard: `requirePermission(code)` from `core/auth/permission.guard.ts`.

## 6. Navigation Shell — `src/app/layout/shell/shell.component.ts`
`private readonly allNav: readonly NavGroup[]` static array. Append a `NavItem` to the right group:
```ts
{ label: 'Feature Name', route: '/admin/<feature>', icon: 'bi-<icon>', available: true, permission: 'FEAT.VIEW' },
```
`NavItem = { label; route(absolute); icon(bi-*); available; permission? }`. A `computed() nav()` filters
by `session.hasPermission(item.permission)`. **High-conflict file — APPEND ONLY, never reorder.**

## 7. Shared UI Primitives (no ui-kit folder — use core/ services + Bootstrap)
- `AlertService` (`core/feedback/alert.service.ts`): `alerts.success('Title','detail')` (auto-dismiss),
  `alerts.error('Title','msg')` (blocking).
- `ToastService` (`core/feedback/toast.service.ts`): `toasts.success/error`.
- `LoadingService`: read-only `loading.active()`.
- **Money:** strings on wire; `{{ row.currency }} {{ +row.amount | number:'1.2-2' }}` (import `DecimalPipe`).
- **Dates:** ISO strings; `DatePipe` or raw YYYY-MM-DD.
- **Status badge:** inline `statusBadgeClass(s): string` returning `text-bg-*`;
  `<span class="badge" [class]="statusBadgeClass(row.status)">{{ row.status }}</span>`.
- Toast/alert hosts are mounted ONCE in the shell — feature components do not mount them.
- No confirm dialog: destructive ops run immediately with a busy state.

## 8. Permissions
`SessionStore.hasPermission(code)` (`core/auth/session.store.ts`; root user → always true).
Inject `protected readonly session = inject(SessionStore)`; gate via
`readonly canX = computed(() => this.session.hasPermission('CODE'))` and `@if (canX())` in template.
NO permission directive. Route gate = `requirePermission('CODE')`. **Use the EXACT codes from the
backend controller `@PreAuthorize`/`@perm` — never invent codes** (per-module contract supplied separately).

## 9. Shared-File Append Points (coordinate / append-only)
| File | Append |
|---|---|
| `features/admin/admin.routes.ts` | route block(s), before final `''` redirect |
| `layout/shell/shell.component.ts` | NavItem(s) into the right NavGroup |
| `features/admin/<feature>/models/<feature>.model.ts` | new model file (no barrel — import directly) |

## NEW FEATURE CHECKLIST
1. `mkdir features/admin/<feature>/` (+ `models/`).
2. `models/<feature>.model.ts` — mirror backend DTOs (both `id` + `uid` as `string`; numbers as `string`).
3. `<feature>.service.ts` — `providedIn:'root'`; `list()` (SKIP_UNWRAP→`{rows,meta}`), `getByUid`,
   `create`, `update`, action methods.
4. `<feature>-list.component.{ts,html,scss}` — section 3 pattern.
5. `<feature>-detail.component.*` — section 4 pattern.
6. `<feature>-create.component.*` if create is non-trivial (else inline on list).
7. `<feature>-list.component.spec.ts` — Vitest; `vi.useFakeTimers()`; cover load-once, isEmpty,
   validation guard, success payload, 403→'forbidden'.
8. Register routes (section 5).
9. Add nav items (section 6).
10. `npm run build` (zero errors) + `npm test` (green). Verify all four states + perm-gated buttons.
