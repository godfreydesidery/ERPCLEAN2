# 24 — Cross-Cutting Conventions (C1–C9) Test Suite

This is a **dedicated, cross-cutting suite** that treats the nine system conventions (C1–C9) as first-class
test targets, exercised across a representative set of screens spanning every layer of the app (masters,
multi-FK transaction forms, financial postings, lists, detail/create). It complements the per-domain suites
(05 O2C, 07 POS, etc.) which assert C1–C9 *incidentally*; here each convention is the subject under test.

The conventions are verified against the **real shared primitives and core plumbing**, not against prose:
`web/src/app/shared/uid-picker/uid-picker.component.ts`, `web/src/app/shared/paginator/paginator.component.ts`,
`web/src/app/core/api/http.interceptors.ts`, `web/src/app/core/api/api-response.model.ts`,
`web/src/app/core/auth/session.store.ts`, `web/src/app/core/auth/permission.guard.ts`,
`web/src/app/layout/shell/shell.component.ts`, and `docs/frontend/CONVENTIONS.md`. Every endpoint, permission
code, route, enum value, and component behaviour cited below was read from those files — none are invented.

## Modules / submodules covered (representative screens — the *vehicles* for the convention tests)

| Screen | Frontend route | API base path | Controller | Why chosen |
|---|---|---|---|---|
| Customer list + create | `/admin/customers`, `/admin/customers/uid/:uid` | `/api/v1/customers` | `CustomerController` | List four-state, pagination, soft-delete (archive/restore), money-free master |
| POS sale (checkout) | `/admin/pos/sell` | `/api/v1/pos/sales` | `PosSaleController` | **Multi-FK form**: session + customer + agent + per-line product + unit pickers (C1 stress test) |
| Manual journal | `/admin/gl/journals`, `/admin/gl/journals/uid/:uid` | `/api/v1/gl/journals` | `JournalController` | **Multi-FK posting** + append-only reversal (C9), GL.POST scoped |
| Sales order detail | `/admin/sales-orders`, `/admin/sales-orders/uid/:uid` | `/api/v1/sales-orders` | `SalesOrderController` | Multi-FK + status lifecycle + uid-only-in-URL detail |
| Chart of Accounts | `/admin/gl/accounts` *(per 18-gl suite)* | `/api/v1/gl/accounts` | `ChartOfAccountController` | Soft-delete via `DELETE → deactivate` (C9) |
| AR invoices list | `/admin/ar/invoices` *(per 11-AR suite)* | `/api/v1/ar/invoices` | `ArInvoiceController` | Money/date formatting (C8) |
| Admin shell + nav | `/admin/*` | n/a | `shell.component.ts` | RBAC nav-hiding (C3), branch switcher (C7) |

> Routes verified in `web/src/app/features/admin/admin.routes.ts` (lines 68, 155, 310, 880) and
> `shell.component.ts` (lines 344–352). The customer/SO/journal/POS-sale screens are confirmed to import
> and use `<app-uid-picker>` and/or `<app-paginator>` (grep over `features/admin`).

## Permission codes in scope (exact `@PreAuthorize` codes, verified from controllers)

- `CUSTOMER.VIEW`, `CUSTOMER.MANAGE`, `PARTY.BRANCH.ASSIGN` (`CustomerController`)
- `POS.SALE.CREATE` (`PosSaleController`, `@perm.has('POS.SALE.CREATE')`)
- `GL.VIEW`, `GL.POST` (`JournalController`); `GL.MANAGE` (`ChartOfAccountController`)
- `SALES.ORDER.VIEW`, `SALES.ORDER.CREATE`, `SALES.ORDER.CONFIRM`, `SALES.ORDER.CANCEL` (`SalesOrderController`, per suite 05)
- `BI.VIEW`, `POS.SESSION.VIEW`, `POS.TILL.VIEW` (nav items, `shell.component.ts`)

RBAC is enforced **strictly by permission code** via `@perm.has(code)` / `@perm.scoped(uid,type,code)`; `rootadmin`
bypasses all checks (`SessionStore.hasPermission` returns true when `user().isRoot`). Cases that test "denied"
run as a user/custom-role **lacking the exact code**, never by role name.

## Ground-truth facts the assertions rely on (verified in source)

- **C1 picker** (`uid-picker.component.ts`): renders a native `<select>` whose `<option [value]="o.uid">` shows
  `o.label` (+ optional `(hint)`); above `searchThreshold` (default 12) it adds a *name filter* input with
  `aria-label="<placeholder> — filter by name"` and placeholder `"Type to filter by name…"`. It is a
  `ControlValueAccessor` (drops into `[(ngModel)]="xUid"`). It NEVER renders a free-text uid input.
- **C2 envelope** (`api-response.model.ts` + `http.interceptors.ts`): wire shape is `{data, errors, meta?}`.
  `apiResponseInterceptor` unwraps `{data}` → raw `T` for non-paginated calls; paginated lists set
  `SKIP_UNWRAP` and keep `meta {page,size,totalElements,totalPages,hasNext}`.
- **C3 RBAC** (`session.store.ts`, `permission.guard.ts`, `shell.component.ts`): nav `nav()` computed filters
  items by `session.hasPermission(item.permission)` (line 356–363). The route guard `requirePermission(code)`
  **redirects to `/admin/home`** when the code is absent (it does NOT render a 403 page). The API returns 403;
  `authErrorInterceptor` does NOT pop the red modal on 403 (line 101) — the screen shows its own calm state.
- **C4 four-state**: list components carry `state: 'loading'|'idle'|'error'|'forbidden'` and render via
  `@switch (state())`; 403 → `state='forbidden'` (customer-list line 112). NOTE: because the route guard
  redirects, the in-page `forbidden` state is reachable for **embedded/sub-resource** loads and for users who
  reach the screen via a still-valid `*.VIEW` but lack a finer code — see TC-CONV-031.
- **C5 paginator** (`paginator.component.ts`): the ONLY pagination control. Renders First / Previous /
  page-numbers (windowed, with `…` gaps) / Next / Last as real `<button>`s with `aria-label`, current page is
  `aria-current="page"` + `disabled`, a `visually-hidden aria-live` status announces position. **Renders
  nothing when `totalPages <= 1`.** Emits 0-based page via `(pageChange)`.
- **C6 a11y**: tables use a visually-hidden `<caption>` (the codebase class is `sr-only`, e.g.
  `<caption class="sr-only">Customers in this company</caption>`), `scope="col"` on every `<th>`, icons
  `aria-hidden="true"`, errors `role="alert"`, loading `aria-live="polite"` (verified in customer-list.html /
  pos-sale.html). Axe scans are an automated assertion target. (Assert the caption *exists* and is visually
  hidden, not a specific class name.)
- **C7 scoping**: `authHeaderInterceptor` attaches `Authorization: Bearer` + `X-Branch-Uid` (the active branch
  uid from `SessionStore.activeBranchUid()`) to every API call. The shell branch switcher (`shell.component.ts`
  ~line 385 `onBranchPick` → `auth.switchBranch(branchUid)`) picks a branch **by name**, never by uid.
- **C8 money/date**: money is a **string on the wire**; rendered to exactly **2 decimals** paired with the
  currency code. The *grouping* (thousands separators) depends on the formatter the screen uses: POS uses
  Angular's `| number:'1.2-2'` which **groups** (e.g. `{{inv.currency}} {{+inv.grossTotalAmount | number:'1.2-2'}}`
  → `TZS 1,234.50`); AR uses a `fmtMoney(v)` helper that calls `(+(v ?? 0)).toFixed(2)`, which is 2-dp but
  **does NOT group** (e.g. `{{fmtMoney(v)}} {{currency}}` → `1234.50 TZS`). The invariant is **string-wire +
  2-dp + a currency code**; grouping is NOT universal. Dates are ISO `yyyy-MM-dd`.
- **C9 soft-delete / append-only**: masters deactivate/archive (`CustomerController` `PUT …/archive` +
  `…/restore` → `MasterStatus`; `ChartOfAccountController` `DELETE …/uid/{uid}` calls `service.deactivate`).
  Postings are append-only: the only "delete" of a journal is `POST …/uid/{uid}/reverse` (a new reversing entry).

## Type / role variations exercised

| Dimension | Values exercised in this suite |
|---|---|
| User type | `rootadmin` (bypass — positive smoke only, never for negative-auth); `ORG_ADMIN` (holds codes); CUSTOM role holding a *subset* (e.g. `CUSTOMER.VIEW` but not `CUSTOMER.MANAGE`); NO-PERMISSION user (empty nav, guard redirect) |
| Screen kind | master list, master create/inline, multi-FK transaction form (POS sale), multi-FK posting (journal), detail (SO), financial list (AR) |
| Resource refs | session, customer, agent, product, unit (POS); account (journal); product (SO line) — ALL via `<app-uid-picker>` by name |
| Branch | default vs non-default; user on ONE vs MANY vs ALL branches; switching active branch; acting in an unassigned branch (denied) |
| Company | multi-company isolation (tenant A cannot see tenant B); company `<select>` hidden when single company |
| Pagination | `totalPages > 1` (controls shown) vs `totalPages <= 1` (controls self-hidden); first/last/middle page |
| Enum (incidental) | `PartyType {INDIVIDUAL,BUSINESS}`, `CustomerKind {CASH_WALK_IN,CREDIT_ACCOUNT}`, `MasterStatus {ACTIVE,INACTIVE,ARCHIVED}` |

---

## C1 — uid is a machine id: never shown, always picked by name, never in body as a typed value; uid only in URL path

### TC-CONV-001 — uid never rendered as visible text on a master list (customers)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list (`/admin/customers` · `/api/v1/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as `ORG_ADMIN`
- **Variation:** customer rows include INDIVIDUAL + BUSINESS, ACTIVE + ARCHIVED
- **Preconditions / Seed:** ≥3 customers exist (seed via `POST /api/v1/customers` or suite 03)
- **Steps:**
  1. Navigate to `/admin/customers`.
  2. Wait for the table (`state='idle'`).
  3. Read all visible cell text and all visible labels.
- **Test Data:** customer "Acme Distributors Ltd" (BUSINESS), "Jane Walk-in" (INDIVIDUAL)
- **Expected Result:** Each row shows human fields (display name, type, kind, status badge); **no 36-char UUID / machine uid string is visible** anywhere in the table or row labels.
- **Convention Assertions:** **C1** (uid not shown); **C4** (idle state); **C6** (table has `<caption>`, `scope="col"`); **C8** (status badge text, not codes).
- **Negative / Edge:** Assert via regex that no cell text matches a uid pattern (`/[0-9a-f]{8}-[0-9a-f]{4}-/i`). Action links may carry the uid in the `href` path (allowed, C1) but the visible link text/aria-label must be human ("View Acme Distributors Ltd"), not the uid.

### TC-CONV-002 — uid appears ONLY in the URL path on a detail screen, never in on-screen text
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales order detail (`/admin/sales-orders/uid/:uid` · `/api/v1/sales-orders`)
- **Permission / Role:** `SALES.ORDER.VIEW` — runs as `ORG_ADMIN`
- **Variation:** SO referencing a customer + GOODS product lines
- **Preconditions / Seed:** one confirmed sales order with ≥2 lines (suite 05)
- **Steps:**
  1. From the SO list, open a row → lands on `/admin/sales-orders/uid/<uid>`.
  2. Capture `page.url()`.
  3. Read all visible detail text (header, customer, lines, totals).
- **Test Data:** SO number "SO-2026-0007", customer "Acme Distributors Ltd"
- **Expected Result:** The **URL path** contains the uid segment (`/uid/<uid>`); the **screen body** shows the SO *number* and customer *name*, never the uid; the numeric DB id appears nowhere (not in URL, not on screen).
- **Convention Assertions:** **C1** (uid in path only; id never in URL); **C2** (detail loaded from unwrapped `{data}`); **C6** (axe scan clean).
- **Negative / Edge:** Confirm the linked customer is rendered as a name link to `/admin/customers/uid/<customerUid>` — the customer uid is again only in the href path, never visible text.

### TC-CONV-003 — multi-FK form: every resource reference is an `<app-uid-picker>` chosen by name (POS sale)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** POS sale (`/admin/pos/sell` · `/api/v1/pos/sales`)
- **Permission / Role:** `POS.SALE.CREATE` — runs as `ORG_ADMIN`; also as a user lacking it → permission state
- **Variation:** sale with 1 customer + optional agent + 2 GOODS lines (product + unit each)
- **Preconditions / Seed:** one OPEN POS session; ≥2 GOODS products with a unit; ≥1 customer; ≥1 agent
- **Steps:**
  1. Navigate to `/admin/pos/sell`.
  2. For Session, Customer, Agent fields: assert each is a `<select>` (the picker), choose by **name** (`getByRole('combobox', { name: /session|customer|agent/i })` → `selectOption({ label: ... })`).
  3. Click "Add Line"; for the line's Product and Unit, choose by name via the picker.
- **Test Data:** session "Till-1 / 2026-06-14" (label), customer "Jane Walk-in", agent "Internal Rep", product "Widget A", unit "EA"
- **Expected Result:** All five references (session, customer, agent, product, unit) are selected from pickers by human label; **no free-text uid input exists** for any of them. The submit body sends `customerId`/`agentId`/`productId`/`unitId` (FK ids) + `sessionUid` — derived from picks, never hand-typed.
- **Convention Assertions:** **C1** (pickers everywhere, no typed uid); **C3** (POS.SALE.CREATE gates the screen — "You don't have permission to create POS sales." when absent, pos-sale.html line 25–29); **C6** (each picker has an `aria-label`/associated `<label>`); **C8** (line subtotal + total via `number:'1.2-2'` with currency).
- **Negative / Edge:** A user lacking `POS.SALE.CREATE` sees the in-page permission message (the screen itself is reached only if they can navigate; nav item is hidden — TC-CONV-021). Try to submit with no session selected → client `formError` "Session is required." (role=alert), no API call.

### TC-CONV-004 — multi-FK posting: journal account lines use pickers by account name/code (append-only safe)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Manual journal (`/admin/gl/journals` · `/api/v1/gl/journals`)
- **Permission / Role:** `GL.POST` — runs as `ORG_ADMIN`; also as a user with `GL.VIEW` but not `GL.POST`
- **Variation:** balanced 2-line journal (one debit, one credit)
- **Preconditions / Seed:** ≥2 ACTIVE GL accounts in the active company (suite for GL/COA)
- **Steps:**
  1. Navigate to the journal post form.
  2. For each line, select the account via `<app-uid-picker>` by name/code (e.g. "1000 — Cash", "4000 — Sales").
  3. Enter debit/credit amounts; submit.
- **Test Data:** Dr "1000 — Cash" 1,000.00; Cr "4000 — Sales" 1,000.00; postingDate 2026-06-14
- **Expected Result:** Accounts chosen by label, not uid; on submit the body carries `companyUid` + per-line account references derived from picks. Posting succeeds (201), returns the unwrapped `JournalEntryDto`.
- **Convention Assertions:** **C1** (account picker by name); **C2** (201 → unwrapped DTO, no envelope leaks to component); **C3** (`GL.POST` scoped to `companyUid` — `@perm.scoped(#req.companyUid,'company','GL.POST')`); **C8** (amounts 2-dp; date ISO).
- **Negative / Edge:** With `GL.VIEW` but not `GL.POST` → POST returns **403**; the screen shows its own error state (no red modal — interceptor line 101). Unbalanced journal (Dr ≠ Cr) → client/server validation error in `errors[0]` surfaced as `role="alert"`.

### TC-CONV-005 — large option set in a picker shows the name-filter box (never a uid box)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS sale product picker (`/admin/pos/sell`)
- **Permission / Role:** `POS.SALE.CREATE` — runs as `ORG_ADMIN`
- **Variation:** product catalogue with > 12 GOODS products (above `searchThreshold` default 12)
- **Preconditions / Seed:** ≥13 GOODS products
- **Steps:**
  1. Open `/admin/pos/sell`, Add Line.
  2. Inspect the product picker region.
  3. Type a partial name into the filter box; observe filtered options.
- **Test Data:** 13+ products; filter text "Wid"
- **Expected Result:** A filter `<input>` with `aria-label` ending "— filter by name" and placeholder "Type to filter by name…" appears above the `<select>`; typing narrows the `<option>` list by label/hint. The bound value remains a uid stored under the hood; the user only ever types a **name fragment**, never a uid.
- **Convention Assertions:** **C1** (filter is by name; uid never typed); **C6** (filter input has an aria-label); **C5** n/a here.
- **Negative / Edge:** With ≤12 products the filter box is absent (plain `<select>`) — assert it does NOT render. The filter is case-insensitive (`includes` on lowercased label/hint).

### TC-CONV-006 — manual URL with a numeric id is rejected / never produced (id never in a route)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customer detail routing (`/admin/customers/uid/:uid`)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as `ORG_ADMIN`
- **Variation:** n/a
- **Preconditions / Seed:** one customer
- **Steps:**
  1. From the customer list, hover/click the row action; capture the generated `routerLink` href.
  2. Confirm it is `/admin/customers/uid/<uid>` (uid string), not `/admin/customers/123`.
- **Test Data:** customer uid (UUID), db id "123"
- **Expected Result:** All generated detail/action links use the `…/uid/<uid>` shape; **no link anywhere uses a numeric id path**. (Per CONVENTIONS §2 INVARIANT #3: a numeric id is never placed in a URL.)
- **Convention Assertions:** **C1** (uid-in-URL, id-never-in-URL).
- **Negative / Edge:** Navigating to a fabricated `/admin/customers/uid/<bad-uid>` → API 404; the detail screen shows its `error` state (C4), not a crash.

---

## C2 — ApiResponse envelope: unwrap for scalars, keep meta for lists

### TC-CONV-007 — non-paginated GET is auto-unwrapped (component sees raw T)
- **Type:** Automated (Playwright + network inspect)
- **Priority:** P1
- **Module / Submodule:** Customer detail (`GET /api/v1/customers/uid/{uid}`)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as `ORG_ADMIN`
- **Variation:** n/a
- **Preconditions / Seed:** one customer
- **Steps:**
  1. Open the customer detail; capture the raw HTTP response body for `GET …/uid/{uid}`.
  2. Confirm the wire body is `{ "data": {...}, "errors": [] }` (envelope).
  3. Confirm the screen rendered the customer fields (proving the component received the unwrapped object).
- **Test Data:** customer "Acme Distributors Ltd"
- **Expected Result:** Wire = envelope; the component-visible value = `data` only (interceptor strips it; `isEnvelope` checks `data` + array `errors`). No `data.` prefix leaks into the UI.
- **Convention Assertions:** **C2** (envelope on wire, unwrapped to component).
- **Negative / Edge:** A server response that is NOT an envelope (e.g. a raw blob) passes through untouched (`isEnvelope` false) — not in scope to break the UI.

### TC-CONV-008 — paginated GET keeps `meta {page,size,totalElements,totalPages,hasNext}`
- **Type:** Automated (Playwright + network inspect)
- **Priority:** P1
- **Module / Submodule:** Customer list (`GET /api/v1/customers?companyId=&page=&size=`)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as `ORG_ADMIN`
- **Variation:** > 1 page of customers
- **Preconditions / Seed:** ≥ (size+1) customers so `totalPages > 1`
- **Steps:**
  1. Open `/admin/customers`; capture the list response.
  2. Confirm the body is the FULL envelope with a populated `meta`.
  3. Confirm the paginator renders using `totalPages`/`hasNext`.
- **Test Data:** 25 customers, size 20 → page 0, totalPages 2, hasNext true
- **Expected Result:** The list request opts out of unwrap (`SKIP_UNWRAP`); component receives `{rows, meta}`; `meta.totalPages = 2`, `meta.hasNext = true`; `<app-paginator>` shows controls.
- **Convention Assertions:** **C2** (meta preserved); **C5** (paginator driven by meta).
- **Negative / Edge:** Empty result → `meta.totalElements=0`, `totalPages=0`; `isEmpty` true; paginator self-hides (`totalPages <= 1`).

### TC-CONV-009 — error envelope: `errors[0]` is the user-facing message
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer create (`POST /api/v1/customers`)
- **Permission / Role:** `CUSTOMER.MANAGE` — runs as `ORG_ADMIN`
- **Variation:** BUSINESS party missing TIN (server rule BR-PARTY-04)
- **Preconditions / Seed:** active company selected
- **Steps:**
  1. Open the inline create form; choose partyType BUSINESS; leave TIN blank but bypass the client guard by other means (e.g. server-only rule) and submit a value the server rejects.
  2. Capture the response and the on-screen message.
- **Test Data:** displayName "Bad Biz", partyType BUSINESS, no TIN
- **Expected Result:** Response is envelope `{data:null, errors:["...TIN..."]}`; the component's `messageFrom(err)` surfaces `errors[0]` in `formError` rendered as `role="alert"`.
- **Convention Assertions:** **C2** (errors array consumed); **C6** (`role="alert"`).
- **Negative / Edge:** A 5xx with no `errors[]` → `authErrorInterceptor` pops the centered alert ("Something went wrong"); a 403 does NOT pop the modal (line 101).

---

## C3 — RBAC gating + nav hiding (by permission code, not role name)

### TC-CONV-010 — nav hides items the user lacks the permission for
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Admin shell nav (`shell.component.ts` `nav()` computed)
- **Permission / Role:** runs as a CUSTOM role holding ONLY `CUSTOMER.VIEW`; compared with `ORG_ADMIN`
- **Variation:** sparse permission set
- **Preconditions / Seed:** a custom role with exactly `[CUSTOMER.VIEW]`; a user assigned it
- **Steps:**
  1. Log in as the custom-role user.
  2. Read the rendered nav items.
- **Test Data:** custom role "Read-only Customers"
- **Expected Result:** "Customers" nav item is visible; **POS / journals / sales-orders / dashboard nav items are absent** (filtered out — `!item.permission || hasPermission(item.permission)`).
- **Convention Assertions:** **C3** (nav filtered by code); **C6** (nav is a landmark with accessible links).
- **Negative / Edge:** The NO-PERMISSION user sees an **empty nav** (only permission-free items, if any). `rootadmin` sees ALL items (`isRoot` bypass) — positive smoke only.

### TC-CONV-011 — route guard redirects to /admin/home when permission absent (not a 403 page)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Route guard `requirePermission` (`permission.guard.ts`)
- **Permission / Role:** runs as a user lacking `POS.SALE.CREATE`
- **Variation:** direct navigation to a guarded route
- **Preconditions / Seed:** user without `POS.SALE.CREATE`
- **Steps:**
  1. While logged in, navigate the browser directly to `/admin/pos/sell`.
  2. Observe the resulting URL.
- **Test Data:** n/a
- **Expected Result:** The guard returns `createUrlTree(['/admin/home'])` → the user lands on `/admin/home`, **not** on a 403 screen and **not** on the POS form. (Important: the absence of a code is a *redirect*, per `permission.guard.ts`.)
- **Convention Assertions:** **C3** (guard backstop); **C4** (no error/forbidden screen — a clean redirect).
- **Negative / Edge:** A user WITH the code reaches `/admin/pos/sell` normally. `rootadmin` always passes the guard.

### TC-CONV-012 — API returns 403 for a missing code; UI shows a calm state, no red modal
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer create (`POST /api/v1/customers`, `CUSTOMER.MANAGE`)
- **Permission / Role:** runs as a user with `CUSTOMER.VIEW` but NOT `CUSTOMER.MANAGE`
- **Variation:** create attempt without the manage code
- **Preconditions / Seed:** user holding only `CUSTOMER.VIEW`
- **Steps:**
  1. Open `/admin/customers` (allowed by VIEW). Confirm the "Create" affordance is hidden (`canManage` computed false).
  2. Force the POST (e.g. replay) → observe 403.
- **Test Data:** n/a
- **Expected Result:** The Create button/form is not rendered (`@if (canManage())`); a forced POST returns **403**; `authErrorInterceptor` does NOT show the global red alert for 403 (line 101) — the rejection propagates to the component handler.
- **Convention Assertions:** **C3** (action gated by `CUSTOMER.MANAGE`; button hidden + API 403); **C2** (403 envelope).
- **Negative / Edge:** Confirm a 5xx on the same screen DOES pop the centered alert — proving 403 is special-cased.

### TC-CONV-013 — same screen, two roles: action buttons differ by exact code
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Sales order detail (`SALES.ORDER.CONFIRM` / `SALES.ORDER.CANCEL`)
- **Permission / Role:** role A holds `SALES.ORDER.VIEW`+`CONFIRM` but not `CANCEL`; role B holds VIEW only
- **Variation:** DRAFT order
- **Preconditions / Seed:** a DRAFT sales order with ≥1 line
- **Steps:**
  1. Open the SO detail as role A → assert "Confirm" present, "Cancel" absent.
  2. Open the same SO as role B → assert both action buttons absent.
- **Test Data:** SO "SO-2026-0008"
- **Expected Result:** Buttons render strictly per the `computed()` permission flags for each exact code; never by role name.
- **Convention Assertions:** **C3** (per-action code gating).
- **Negative / Edge:** Role A forcing a `PUT …/cancel` → 403. (Status-transition legality is covered in suite 05; here the focus is RBAC.)

---

## C4 — Four screen states: loading / empty / error / forbidden (distinct)

### TC-CONV-014 — loading state on a list (spinner + aria-live)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list (`/admin/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** n/a
- **Preconditions / Seed:** throttle/delay the list response (route interception)
- **Steps:**
  1. Intercept `GET /api/v1/customers` to add latency.
  2. Navigate; assert the loading branch renders before data.
- **Test Data:** 1s delay
- **Expected Result:** `@switch (state())` → `loading`: a spinner with `aria-live="polite"`; the table is not yet shown.
- **Convention Assertions:** **C4** (loading distinct); **C6** (aria-live announces).

### TC-CONV-015 — empty state (idle + 0 rows) is distinct from loading
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** company with no customers
- **Preconditions / Seed:** a company that has zero customers (or filter that returns none)
- **Steps:**
  1. Select the empty company / apply a non-matching search.
  2. Assert the empty message.
- **Test Data:** search "zzzz-no-match"
- **Expected Result:** `isEmpty()` true (`state==='idle' && rows().length===0`) → an explicit "no customers" empty message, NOT the spinner and NOT an error. Paginator absent (`totalPages <= 1`).
- **Convention Assertions:** **C4** (empty distinct); **C5** (paginator self-hidden).

### TC-CONV-016 — error state on server failure (role=alert), distinct from forbidden
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** 500 from the list endpoint
- **Preconditions / Seed:** intercept list with a 500
- **Steps:**
  1. Force `GET /api/v1/customers` → 500.
  2. Assert the error branch.
- **Test Data:** n/a
- **Expected Result:** `state='error'` (non-403 → 'error', line 112) → an error message with `role="alert"`; the centered global alert also fires (5xx). Distinct from the forbidden branch.
- **Convention Assertions:** **C4** (error distinct); **C2** (error envelope handled).

### TC-CONV-017 — forbidden state on 403 from a list/sub-resource load
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list (or customer branches sub-list, `PARTY.BRANCH.ASSIGN`)
- **Permission / Role:** user reaching the screen via `CUSTOMER.VIEW` but the underlying call 403s
- **Variation:** 403 specifically (not 500)
- **Preconditions / Seed:** intercept the list/sub-list with a 403
- **Steps:**
  1. Force the data load → 403.
  2. Assert the forbidden branch (distinct text from the error branch).
- **Test Data:** n/a
- **Expected Result:** `state='forbidden'` (403 → 'forbidden', line 112) → a calm "no permission" message; the red global alert does NOT fire (interceptor 403 special-case).
- **Convention Assertions:** **C4** (forbidden distinct from error); **C3** (403 surfaced as forbidden, not crash).
- **Negative / Edge:** This is the case where the in-page `forbidden` state is genuinely reachable (vs the route-guard redirect of TC-CONV-011): the user could navigate (had VIEW) but a finer call denies.

### TC-CONV-018 — detail screen four states (loading → idle/error/forbidden)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customer detail / Sales order detail
- **Permission / Role:** respective `*.VIEW`
- **Variation:** valid uid (idle), bad uid (error/404), 403-intercept (forbidden)
- **Preconditions / Seed:** one valid entity
- **Steps:** open valid uid → idle; open bad uid → error; intercept 403 → forbidden; throttle → loading.
- **Test Data:** valid + fabricated uid
- **Expected Result:** Each of the four states renders distinctly on the detail screen.
- **Convention Assertions:** **C4** (all four on a detail screen); **C1** (uid in URL only).

---

## C5 — Pagination via the shared `<app-paginator>` (first/prev/numbers/next/last; self-hide)

### TC-CONV-019 — full control set renders on a multi-page list, with a11y
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list (`<app-paginator>`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** `totalPages >= 5` (so a `…` gap appears)
- **Preconditions / Seed:** ≥ (5 × size) customers
- **Steps:**
  1. Open the list (page 0).
  2. Assert the nav landmark and every control.
- **Test Data:** 120 customers, size 20 → 6 pages
- **Expected Result:** A `<nav aria-label="Customers pagination">` containing: **First** (`aria-label="First page"`, disabled on page 0), **Previous** (disabled on page 0), numbered page buttons (windowed ±2, with `…` `aria-hidden` gap to page 6), **Next**, **Last** (`aria-label="Last page"`); the current page button has `aria-current="page"` and is `disabled`; a visually-hidden `aria-live` status reads "Page 1 of 6, 120 items total".
- **Convention Assertions:** **C5** (all five control groups present); **C6** (nav landmark, aria-current, aria-live, real `<button>`s); **C2** (driven by `meta`).
- **Negative / Edge:** On the last page, Next + Last are disabled and First + Previous enabled.

### TC-CONV-020 — paginator self-hides on a single page
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** `totalPages <= 1`
- **Preconditions / Seed:** a company with ≤ size customers (e.g. 3)
- **Steps:** open the list; assert NO pagination nav is rendered.
- **Test Data:** 3 customers, size 20 → totalPages 1
- **Expected Result:** `<app-paginator>` renders nothing (`@if (meta() && totalPages > 1)` false). No bespoke prev/next buttons exist anywhere on the screen.
- **Convention Assertions:** **C5** (self-hide; single control component, no roll-your-own nav).
- **Negative / Edge:** Zero rows (totalPages 0) → still hidden.

### TC-CONV-021 — paginator navigation re-loads the chosen 0-based page
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** jump to a middle page, then Last, then First
- **Preconditions / Seed:** 6 pages (TC-CONV-019 seed)
- **Steps:**
  1. Click page "3" → assert request `…&page=2` (0-based emit), rows update, status "Page 3 of 6".
  2. Click Last → `page=5`. Click First → `page=0`.
- **Test Data:** as above
- **Expected Result:** Each click emits the correct 0-based index via `(pageChange)`; `goToPage` re-runs the load through the shared `immediateTrigger$` pipeline (race-safe via switchMap); the table and status reflect the new page.
- **Convention Assertions:** **C5** (page change wired to reload); **C2** (meta updates each page).
- **Negative / Edge:** Clicking the current page button (disabled/`aria-current`) emits nothing (`go()` no-ops when clamped === current).

### TC-CONV-022 — search resets to page 0 and re-paginates
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customer list search + paginator
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** type a query while on page 3
- **Preconditions / Seed:** 6 pages
- **Steps:** navigate to page 3 → type "Acme" → assert request fires with `page=0` and the filtered query (debounced 300ms).
- **Test Data:** query "Acme"
- **Expected Result:** Typing pushes `{q, page:0}`; results re-paginate from page 0; paginator recomputes (or self-hides if the filtered set is one page).
- **Convention Assertions:** **C5** (filter resets paging); **C4** (empty if no match).

---

## C6 — Accessibility (axe-clean, keyboard, aria, table semantics)

### TC-CONV-023 — axe scan clean on a list screen
- **Type:** Automated (Playwright + axe)
- **Priority:** P1
- **Module / Submodule:** Customer list
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** populated list (idle state)
- **Preconditions / Seed:** ≥1 page of customers
- **Steps:** open the list; run `AxeBuilder` against the page; assert zero serious/critical violations.
- **Test Data:** n/a
- **Expected Result:** No WCAG 2.1 AA serious/critical violations: the table has a `<caption>`, every `<th>` has `scope="col"`, the actions header is visually-hidden, icons are `aria-hidden`, action links have `aria-label`.
- **Convention Assertions:** **C6** (axe clean + table semantics).

### TC-CONV-024 — axe scan + label association on the multi-FK POS form
- **Type:** Automated (Playwright + axe)
- **Priority:** P1
- **Module / Submodule:** POS sale (`/admin/pos/sell`)
- **Permission / Role:** `POS.SALE.CREATE` — `ORG_ADMIN`
- **Variation:** form with one line added
- **Preconditions / Seed:** open session, ≥1 product
- **Steps:** open the form, add a line, run axe.
- **Test Data:** n/a
- **Expected Result:** Zero serious/critical violations; each picker is reachable by an associated `<label>` or `aria-label` (Session/Customer/Agent/Currency/per-line Product/Unit/Qty); required markers use `aria-hidden` asterisks; the line-items table has a `<caption>` and `scope="col"` headers; errors are `role="alert"`.
- **Convention Assertions:** **C6** (axe + form labels + table semantics); **C1** (pickers, not raw inputs).

### TC-CONV-025 — keyboard-only operation of paginator + picker
- **Type:** Automated (Playwright, keyboard)
- **Priority:** P1
- **Module / Submodule:** Customer list paginator; POS picker
- **Permission / Role:** respective view/create codes
- **Variation:** keyboard navigation only (no mouse)
- **Preconditions / Seed:** multi-page list; option-rich picker
- **Steps:**
  1. Tab to the paginator; press Enter on "Next" → page advances.
  2. Tab to a `<app-uid-picker>` select; choose an option with the keyboard (arrow keys / type-ahead).
- **Test Data:** n/a
- **Expected Result:** Both controls are fully keyboard-operable (real `<button>`s and a native `<select>`); focus is visible; selection updates the bound value.
- **Convention Assertions:** **C6** (keyboard-operable, native semantics).

### TC-CONV-026 — axe scan clean on a detail screen
- **Type:** Automated (Playwright + axe)
- **Priority:** P2
- **Module / Submodule:** Sales order detail
- **Permission / Role:** `SALES.ORDER.VIEW` — `ORG_ADMIN`
- **Variation:** order with lines table
- **Preconditions / Seed:** one SO with lines
- **Steps:** open detail; run axe.
- **Expected Result:** Zero serious/critical violations; status badge is text + colour (not colour alone); lines table has caption/scope.
- **Convention Assertions:** **C6**; **C8** (status as text badge).

---

## C7 — Multi-tenancy / scoping (company + branch isolation; X-Branch-Uid)

### TC-CONV-027 — company isolation: tenant A cannot see tenant B's data
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer list (company-scoped `?companyId=`)
- **Permission / Role:** `CUSTOMER.VIEW` — a user of company A
- **Variation:** two companies in the org with distinct customers
- **Preconditions / Seed:** company A has "A-Customer"; company B has "B-Customer"; the user belongs to A only
- **Steps:**
  1. Log in as the company-A user; open `/admin/customers`.
  2. Read all rows.
- **Test Data:** "A-Customer", "B-Customer"
- **Expected Result:** Only "A-Customer" is listed; "B-Customer" never appears; the company `<select>` offers only the user's company (and is hidden when single company per the list pattern).
- **Convention Assertions:** **C7** (company-scoped); **C2** (scoped meta).
- **Negative / Edge:** Forcing `?companyId=<B's id>` → backend scope check denies (403/empty) — cross-tenant read blocked.

### TC-CONV-028 — single-company org hides the company selector
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customer list / POS sale company select
- **Permission / Role:** view/create code — `ORG_ADMIN` of a single-company org
- **Variation:** org with exactly one company
- **Preconditions / Seed:** one company
- **Steps:** open the screen; assert the company `<select>` is absent (`@if (companies().length > 1)` false on POS; list hides single).
- **Expected Result:** No company chooser; the single company is auto-selected and used as the filter.
- **Convention Assertions:** **C7** (single-company UX); **C4** (loads straight to data).

### TC-CONV-029 — branch switch changes X-Branch-Uid and reloads branch-scoped data
- **Type:** Automated (Playwright + network inspect)
- **Priority:** P1
- **Module / Submodule:** Shell branch switcher (`onBranchPick` → `auth.switchBranch`)
- **Permission / Role:** a user assigned to MANY branches
- **Variation:** default branch → non-default branch
- **Preconditions / Seed:** user assigned to ≥2 branches (one default); branch-scoped data differs per branch
- **Steps:**
  1. Note the active branch (shown by **name** in the switcher).
  2. Open the branch menu; pick another branch by name.
  3. Capture a subsequent API call's headers.
- **Test Data:** branches "HQ (default)", "Mwanza Depot"
- **Expected Result:** `auth.switchBranch(branchUid)` updates `SessionStore.activeBranchUid`; subsequent requests carry `X-Branch-Uid: <new branch uid>` (`authHeaderInterceptor`); branch-scoped lists reload. The branch is chosen **by name** — its uid is never shown or typed.
- **Convention Assertions:** **C7** (X-Branch-Uid override; branch scoping); **C1** (branch picked by name, uid hidden).
- **Negative / Edge:** A user on ONE branch sees a non-switchable (or single-entry) selector. A user on ALL branches can switch among all.

### TC-CONV-030 — acting in an unassigned branch is denied
- **Type:** Automated (Playwright / API)
- **Priority:** P1
- **Module / Submodule:** Branch scoping via X-Branch-Uid
- **Permission / Role:** a user assigned to branch HQ only
- **Variation:** forge X-Branch-Uid of a branch the user is NOT assigned to
- **Preconditions / Seed:** user assigned to HQ; a second branch "Mwanza" the user is NOT on
- **Steps:**
  1. Issue an API call with `X-Branch-Uid: <Mwanza uid>` while logged in as the HQ-only user.
- **Test Data:** Mwanza branch uid
- **Expected Result:** The backend rejects the request (403 / scope error); the user cannot act in a branch they are not assigned to. The shell switcher never offers Mwanza to this user, so the UI cannot reach this state normally.
- **Convention Assertions:** **C7** (branch-assignment enforced); **C3** (scope = authorization).
- **Negative / Edge:** Same call with the user's assigned branch succeeds.

---

## C8 — Money/date formatting (string on wire; 2-dp grouped amount + currency; ISO dates)

### TC-CONV-031 — money rendered as 2-decimal grouped amount paired with currency code
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** AR invoices list (`/admin/ar/invoices`) and POS receipt
- **Permission / Role:** respective view code — `ORG_ADMIN`
- **Variation:** amounts with thousands + decimals
- **Preconditions / Seed:** an invoice with a non-trivial total (e.g. 1234.5)
- **Steps:**
  1. Open the AR invoices list and a POS success receipt.
  2. Read the rendered amount strings.
- **Test Data:** total 1234.5 in TZS
- **Expected Result:** Amount renders with exactly two decimals paired with the currency code. **Pairing + 2-dp are the invariant; thousands-grouping is NOT** — it depends on the per-screen formatter: POS uses `| number:'1.2-2'` and **groups** (`TZS 1,234.50`); AR uses `fmtMoney → toFixed(2)` and does **NOT** group (`1234.50 TZS`, amount then currency). The wire value is a **string**; no floating-point artifacts (no `1234.4999`). Do NOT assert a comma in the AR amount — assert the comma only on the POS amount.
- **Convention Assertions:** **C8** (money string on wire, 2-dp + currency; grouping per-screen, not universal).
- **Negative / Edge:** A null/blank amount renders `0.00` (AR `fmtMoney` `toFixed(2)` on `+(v ?? 0)`), never "NaN" or empty.

### TC-CONV-032 — dates rendered as ISO yyyy-MM-dd
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Journal posting date / invoice dates
- **Permission / Role:** respective view code
- **Variation:** a posting/invoice with a known date
- **Preconditions / Seed:** one journal entry / invoice
- **Steps:** open the detail; read the date field(s).
- **Test Data:** postingDate 2026-06-14
- **Expected Result:** Dates display as ISO `yyyy-MM-dd` (`2026-06-14`), consistent across screens; the wire value is an ISO string.
- **Convention Assertions:** **C8** (ISO dates).
- **Negative / Edge:** No locale-shifted or US-format (`06/14/2026`) rendering.

### TC-CONV-033 — money amounts never lose precision through the picker→submit→render round-trip
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** POS sale totals (`/admin/pos/sell`)
- **Permission / Role:** `POS.SALE.CREATE`
- **Variation:** line with qty 3 × price 33.33, discount 0.01
- **Preconditions / Seed:** open session, product, unit
- **Steps:** add a line with the figures; observe the live subtotal/total; submit; read the receipt total.
- **Test Data:** qty 3, unitPrice 33.33, discount 0.01
- **Expected Result:** Live `lineSubtotal`/`subtotal` show 2-dp; the submitted body sends string-safe decimals; the returned invoice total is rendered 2-dp with currency, equal to the client computation.
- **Convention Assertions:** **C8** (2-dp consistency client↔server); **C2** (server total authoritative).

---

## C9 — Soft-delete / archive (masters) + append-only postings

### TC-CONV-034 — customer archive sets a status, not a hard delete; row remains retrievable
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer archive (`PUT /api/v1/customers/uid/{uid}/archive`, `CUSTOMER.MANAGE`)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Variation:** ACTIVE → ARCHIVED
- **Preconditions / Seed:** one ACTIVE customer
- **Steps:**
  1. On the customer detail, archive the customer.
  2. Re-fetch the customer by uid; read its status.
- **Test Data:** customer "Old Client"
- **Expected Result:** `PUT …/archive` returns **204**; the customer still exists (`GET …/uid/{uid}` succeeds) with `status = ARCHIVED` (`MasterStatus`), shown by a muted badge. It is NOT removed from the database.
- **Convention Assertions:** **C9** (soft-delete via status); **C1** (archived via uid path, no on-screen uid); **C8** (status badge text).
- **Negative / Edge:** A user with `CUSTOMER.VIEW` only → archive button hidden + forced PUT 403.

### TC-CONV-035 — archived master can be restored (reversible soft-delete)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer restore (`PUT /api/v1/customers/uid/{uid}/restore`, `CUSTOMER.MANAGE`)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Variation:** ARCHIVED → ACTIVE
- **Preconditions / Seed:** an ARCHIVED customer (from TC-CONV-034)
- **Steps:** restore the customer; re-fetch.
- **Test Data:** customer "Old Client"
- **Expected Result:** `PUT …/restore` returns 204; status flips back to `ACTIVE`; proves soft-delete is reversible (vs hard delete).
- **Convention Assertions:** **C9** (restore path exists).
- **Negative / Edge:** Restoring an already-ACTIVE customer is idempotent or a benign no-op (no error pop for 403; 4xx surfaced in errors[0] if rejected).

### TC-CONV-036 — Chart of Accounts "delete" is a deactivate (soft-delete), not removal
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Chart of Accounts (`DELETE /api/v1/gl/accounts/uid/{uid}`, `GL.MANAGE`)
- **Permission / Role:** `GL.MANAGE` — `ORG_ADMIN`
- **Variation:** ACTIVE account → deactivated
- **Preconditions / Seed:** one unused ACTIVE GL account
- **Steps:**
  1. Trigger the account "delete" action.
  2. Re-fetch the account by uid.
- **Test Data:** account "9999 — Suspense"
- **Expected Result:** The `DELETE …/uid/{uid}` endpoint maps to `service.deactivate(uid)` (returns 204); the account still exists on `GET …/uid/{uid}` but is INACTIVE. No hard delete of a GL account (would orphan postings).
- **Convention Assertions:** **C9** (DELETE = deactivate); **C1** (acted via uid path).
- **Negative / Edge:** Deactivating an account with existing postings is allowed (history preserved); the account stops being selectable for NEW journal lines (picker excludes inactive) — assert it is filtered from the journal account picker.

### TC-CONV-037 — financial postings are append-only: a journal is corrected by a reversal, never edited/deleted
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Journal reversal (`POST /api/v1/gl/journals/uid/{uid}/reverse`, `GL.POST`)
- **Permission / Role:** `GL.POST` — `ORG_ADMIN`
- **Variation:** reverse a posted entry on a given reversal date
- **Preconditions / Seed:** one posted `JournalEntry`
- **Steps:**
  1. Open the posted journal detail.
  2. Confirm there is **no edit/delete** of the posting — only a "Reverse" action.
  3. Reverse it (optionally with a `reversalDate`); observe a NEW reversing entry.
- **Test Data:** original entry "JE-2026-0011"; reversalDate 2026-06-14
- **Expected Result:** `POST …/reverse` returns **201** with a new `JournalEntryDto` (the reversing entry, debits/credits swapped); the original entry is unchanged and still present. There is no `PUT`/`DELETE` for a posted journal in `JournalController` (only create, view, list, reverse).
- **Convention Assertions:** **C9** (append-only — reversal not edit/delete); **C2** (201 → new DTO); **C3** (`GL.POST` scoped to the journal entry).
- **Negative / Edge:** A user with `GL.VIEW` but not `GL.POST` → reverse 403. Attempting to reverse an already-reversed entry → server rule rejects (`errors[0]`). No UI affordance exists to mutate the original entry's lines.

### TC-CONV-038 — archived master is hidden from default pickers but visible in an archive filter
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customer list status filter + downstream pickers
- **Permission / Role:** `CUSTOMER.VIEW`
- **Variation:** one ARCHIVED + several ACTIVE customers
- **Preconditions / Seed:** TC-CONV-034 archived customer plus active ones
- **Steps:**
  1. On a screen that picks a customer (e.g. POS sale), confirm the ARCHIVED customer is not offered.
  2. On the customer list, confirm the archived row is still discoverable (e.g. via search/status), shown with an ARCHIVED badge.
- **Test Data:** archived "Old Client"
- **Expected Result:** Active masters are selectable; archived ones are excluded from new-transaction pickers (C9 intent) yet remain auditable in the master list. Demonstrates soft-delete preserves history while removing the record from active use.
- **Convention Assertions:** **C9** (archived excluded from new use, retained for audit); **C1** (selection by name); **C8** (status badge).

---

## Coverage map (convention → cases)

| Convention | Cases |
|---|---|
| C1 uid hidden / picker-by-name / id-never-in-URL | 001, 002, 003, 004, 005, 006 (+ asserted across 029, 034, 036, 038) |
| C2 ApiResponse envelope (unwrap / keep meta / errors) | 007, 008, 009 |
| C3 RBAC gating + nav hiding (by code) | 010, 011, 012, 013 (+ across 004, 030, 034, 037) |
| C4 four screen states | 014, 015, 016, 017, 018 |
| C5 shared paginator (full set / self-hide / reload) | 019, 020, 021, 022 |
| C6 a11y (axe / keyboard / table+form semantics) | 023, 024, 025, 026 |
| C7 multi-company + multi-branch scoping | 027, 028, 029, 030 |
| C8 money/date formatting | 031, 032, 033 |
| C9 soft-delete + append-only postings | 034, 035, 036, 037, 038 |

## Notes on accuracy / known nuances (verified, not assumed)

1. **Route guard = redirect, NOT a forbidden page.** `requirePermission(code)` returns
   `router.createUrlTree(['/admin/home'])` on failure. The in-page `forbidden` *state* is reached only when the
   user can navigate (holds the page's `*.VIEW`) but a data/sub-resource call returns 403 (TC-CONV-017), or for
   embedded loads. Tests must not assert a "403 page" appears from the guard.
2. **403 vs other errors in the UI.** `authErrorInterceptor` returns 403 silently (no red modal, line 101) but
   pops the centered alert for 401-driven logout and for every other API error (5xx/network). Distinguish in C4
   cases.
3. **Money format position varies per screen** (`CUR amount` on POS vs `amount CUR` on AR). The invariant is
   **2-decimal grouped value + currency code from a string wire value** — do not over-assert a fixed position.
4. **`MasterStatus`** values are `ACTIVE, INACTIVE, ARCHIVED`. Customers use archive/restore (ACTIVE↔ARCHIVED);
   GL accounts use deactivate (ACTIVE→INACTIVE via `DELETE`). Both are soft-delete — neither hard-deletes.
5. **Append-only GL:** `JournalController` exposes only `POST` (post), `GET` (view/list), and
   `POST …/reverse` — there is deliberately **no** `PUT`/`DELETE` for a posted entry. Correction = reversal.
