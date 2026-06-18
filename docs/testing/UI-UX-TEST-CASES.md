# UI/UX Automated Test-Case Catalogue

Generated from the five spec files in `web/e2e/uiux-*.spec.ts`.
Each case maps to a Playwright test; the "Spec file" column gives the file name and the
exact `test(...)` title so you can run an individual case with `--grep`.

---

## How to run

```
# Prerequisites
#   docker compose up -d db        — Postgres on :5434
#   mvn spring-boot:run (dev)      — backend on :8081 (bootstraps rootadmin/RootPass12345)
#   ng serve --port 4400           — frontend on :4400 (proxy.conf.json forwards /api → :8081)

# Full suite (all 5 spec files)
PW_BASE_URL=http://localhost:4400 \
PLAYWRIGHT_REUSE_SERVER=1 \
ROOT_PASS=RootPass12345 \
npx playwright test --project=chromium --project=chromium-unauthenticated

# Single spec
PW_BASE_URL=http://localhost:4400 PLAYWRIGHT_REUSE_SERVER=1 ROOT_PASS=RootPass12345 \
  npx playwright test e2e/uiux-auth-session.spec.ts --project=chromium-unauthenticated

# Single test by title fragment
PW_BASE_URL=http://localhost:4400 PLAYWRIGHT_REUSE_SERVER=1 ROOT_PASS=RootPass12345 \
  npx playwright test --grep "TC-AUTH-01"

# QA environment (no webServer spin-up needed)
PW_BASE_URL=https://qa.your-erp.example.com \
PLAYWRIGHT_REUSE_SERVER=1 \
ROOT_PASS=<qa-root-password> \
  npx playwright test --project=chromium --project=chromium-unauthenticated
```

**Project assignment**
| Playwright project | Specs included |
|---|---|
| `chromium` (authenticated, storageState) | `uiux-forms-validation`, `uiux-empty-error-states`, `uiux-journeys-a11y` |
| `chromium-unauthenticated` (cold session, logs in itself) | `uiux-auth-session`, `uiux-rbac-nav` |

---

## Area 1: Auth & Session

Spec file: `web/e2e/uiux-auth-session.spec.ts`
Playwright project: `chromium-unauthenticated`
Test suite: `UI/UX: Authentication & Session management`

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-AUTH-01 | Unauthenticated visit to a protected route redirects to /login | 1. Open a fresh context (no session). 2. Navigate directly to `/admin/customers`. | URL becomes `/login` within 10 s; login form `form[aria-label="Sign in"]` is visible. No admin content rendered. |
| UX-AUTH-02 | Wrong-password login shows inline error, stays on /login | 1. Navigate to `/login`. 2. Fill username=rootadmin, password=definitely-wrong-password-12345!. 3. Click Submit. | `div.alert.alert-danger[role="alert"]` appears within 15 s with non-empty text. URL stays `/login`. Login form still visible for retry. |
| UX-AUTH-03 | Empty-field submit shows inline validation error, stays on /login | 1. Navigate to `/login`. 2. Click Submit without filling any field. | Error alert appears immediately (no network call). URL stays `/login`. Both inputs remain visible and interactive. |
| UX-AUTH-04 | Valid login navigates to /admin and renders the app shell | 1. Navigate to `/login`. 2. Fill valid rootadmin credentials. 3. Click Submit. | URL becomes `/admin/*` within 20 s. `header.topbar`, `.topbar-brand`, `.brand-word` (text = "ERP") are all visible. No error-state banner visible. |
| UX-AUTH-05 | Reload after login keeps session — user stays on /admin | 1. Log in. 2. Navigate to `/admin/customers`. 3. Reload the page. | URL does NOT match `/login` after reload. URL still matches `/admin`. Shell topbar visible. |
| UX-AUTH-06 | Logout returns to /login; subsequent protected visit also redirects | 1. Log in. 2. Click `button[aria-label="Sign out"]`. 3. Expect `/login`. 4. Navigate to `/admin/customers`. | After logout: URL = `/login`, login form visible. After step 4: URL redirects back to `/login` again; login form visible. |

---

## Area 2: Forms & Validation

Spec file: `web/e2e/uiux-forms-validation.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `UI/UX: Customer create form validation`, `UI/UX: Product create form validation`, `UI/UX: GL manual journal post form validation`, `UI/UX: Customer detail edit form validation`

### Customer create form

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-FV-C1 | Empty display name blocks submission with visible error | 1. Open `/admin/customers`. 2. Click "New Customer". 3. Leave `#newDisplayName` empty. 4. Click Submit. | `#createCustomerForm output.text-danger` visible with message length > 5 chars. No 500/stack-trace text in body. Form stays open. |
| UX-FV-C2 | BUSINESS party type without TIN shows BR-PARTY-04 message, no 500 | 1. Open create form. 2. Fill a display name. 3. Select `BUSINESS` party type. 4. Leave TIN empty. 5. Submit. | Error output contains "TIN" (case-insensitive). No raw server error in body. Form stays open. (Skip if `#newTin` not rendered.) |
| UX-FV-C3 | Valid submission shows success alert and new row appears in list | 1. Open create form. 2. Fill unique display name (tag=`AutoCust-<timestamp>`). 3. Set party=INDIVIDUAL, kind=CASH_WALK_IN. 4. Submit. | `[role="alertdialog"].alert-success` appears. No API 5xx. After alert, searching for the unique name in `#customerSearch` returns a matching table row. |

### Product create form

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-FV-P1 | Empty product name blocks submission with visible error | 1. Open `/admin/products`. 2. Click "New Product". 3. Leave `#newName` empty (pick a unit if present). 4. Submit. | `#createProductForm p[role="alert"].text-danger` visible, text length > 5. No raw error leak. Form stays open. |
| UX-FV-P2 | Missing base unit shows "Base unit is required" error | 1. Open create form. 2. Fill a name. 3. Reset `#newBaseUnit` to placeholder. 4. Submit. | Error text matches `/unit/i`. No raw 500. Form stays open. (Skip if no units seeded.) |
| UX-FV-P3 | Valid submission shows success alert and new product appears in list | 1. Open create form. 2. Fill unique name, pick first unit, set type=GOODS. 3. Submit. | Success alertdialog appears. Searching `#productSearch` finds the new row. (Skip if no units available.) |
| UX-FV-P4 | SERVICE type disables Stockable checkbox (BR-PROD-01 UI guard) | 1. Open create form. 2. Set `#newType` = SERVICE. | `#newStockable` is disabled (HTML `disabled` or `aria-disabled="true"`). Switching back to GOODS re-enables it. |

### GL manual journal post form

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-FV-G1 | Unbalanced entry shows "Not balanced" tag and disables Post button | 1. Open `/admin/gl/journals/post`. 2. Fill description. 3. Enter a debit-only amount on line 1. | `.status-tag` containing "Not balanced" visible. `button[name~="Post Journal"]` is disabled. Balance hint text visible. (Skip if no accounts loaded.) |
| UX-FV-G2 | Empty description with balanced entry keeps Post button disabled | 1. Build a balanced entry (equal debit/credit). 2. Clear `#description`. | "Balanced" tag visible but Post button remains disabled. No 500 leaked. (Skip if accounts unavailable.) |
| UX-FV-G3 | Valid balanced journal posts and navigates to journal detail | 1. Fill date + description + balanced lines. 2. Click Post Journal. | URL changes to `/admin/gl/journals/uid/<ULID>`. No API 5xx. "Lines" section heading visible on detail page. (Skip if fewer than 2 accounts.) |
| UX-FV-G4 | All in-form error messages are clean strings — no 500 or stack trace | 1. Attempt partial/missing-account submission. | Any `p[role="alert"].text-danger` text contains no `/500/`, `/stack trace/i`, `/NullPointerException/i`, `/unexpected error/i`. |

### Customer detail edit form

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-FV-D1 | Saving with empty display name shows clean error, not a 500 | 1. Open `/admin/customers`. 2. Click first Edit link. 3. Clear `#fDisplayName`. 4. Click "Save changes". | `output.text-danger[aria-live="polite"]` appears with message length > 5. No raw error. URL stays on `/customers/uid/*`, not bounced to `/login`. (Skip if no customers seeded.) |

---

## Area 3: Empty / Error / Loading States

Spec file: `web/e2e/uiux-empty-error-states.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Routes under test: `customers`, `products`, `suppliers`, `sales-orders`

### EE-1: Populated list renders table + paginator

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-EE-1-C | Customers list: rows + paginator when multi-page | Navigate to `/admin/customers`. | `table.erp-table tbody tr` first row visible. No error banner alongside rows. If paginator present: "Last page" + "Next page" buttons visible; status span matches `Page \d+ of \d+`. |
| UX-EE-1-P | Products list: rows + paginator when multi-page | Navigate to `/admin/products`. | Same assertions as EE-1-C. |
| UX-EE-1-S | Suppliers list: rows + paginator when multi-page | Navigate to `/admin/suppliers`. | Same assertions as EE-1-C. |
| UX-EE-1-SO | Sales Orders list: rows + paginator when multi-page | Navigate to `/admin/sales-orders`. | Same assertions as EE-1-C. |

### EE-2: Absent-term search returns clean empty state

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-EE-2-C | Customers: absent-term search shows empty state, no error | Fill `#customerSearch` with `ZZNOEXIST<random>`, press Enter. | Zero `tbody tr` rows OR `.erp-empty` visible. No `[role="alert"].text-danger` banner. No console errors. Page body has content. |
| UX-EE-2-P | Products: absent-term search shows empty state, no error | Fill `#productSearch` with absent term. | Same as EE-2-C. |
| UX-EE-2-S | Suppliers: absent-term search shows empty state, no error | Fill `#supplierSearch` with absent term. | Same as EE-2-C. |

### EE-3: Clearing absent-term search restores rows

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-EE-3-C | Customers: clear no-results search restores rows | 1. Search absent term. 2. Click "Clear" button (or clear input + Enter). | `tbody tr` first row visible again. No error banner. (Skip if list empty at rest.) |
| UX-EE-3-P | Products: clear no-results search restores rows | Same for products. | Same as EE-3-C. |
| UX-EE-3-S | Suppliers: clear no-results search restores rows | Same for suppliers. | Same as EE-3-C. |

### EE-4: Page-2 navigation

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-EE-4-C | Customers: Next page changes rows and shows no error | Click "Next page" in paginator. | Paginator status span contains "Page 2 of". `tbody tr` present. No error banner. (Skip if single page or empty.) |
| UX-EE-4-P | Products: Next page changes rows and shows no error | Same for products. | Same as EE-4-C. |
| UX-EE-4-S | Suppliers: Next page changes rows and shows no error | Same for suppliers. | Same as EE-4-C. |
| UX-EE-4-SO | Sales Orders: Next page changes rows and shows no error | Same for sales-orders. | Same as EE-4-C. |

### EE-5: No spinner stuck after page load

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-EE-5-C | Customers: no loading spinner stuck after networkidle + 1.2 s | Navigate + wait. | `p[aria-live="polite"] .spinner-border` is NOT visible. |
| UX-EE-5-P | Products: no spinner stuck | Same for products. | Same as EE-5-C. |
| UX-EE-5-S | Suppliers: no spinner stuck | Same for suppliers. | Same as EE-5-C. |
| UX-EE-5-SO | Sales Orders: no spinner stuck | Same for sales-orders. | Same as EE-5-C. |

### EE-6: First-row detail drill-down renders heading without errors

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-EE-6-C | Customer detail: h1 heading visible, no console or page errors | Click first `/uid/<ULID>` anchor from customer list. | No session bounce. No API 5xx. No page errors. No console errors. `h1` visible. No error-state banner. (Skip if list empty.) |
| UX-EE-6-P | Product detail: same assertions | From products list. | Same as EE-6-C. |
| UX-EE-6-S | Supplier detail: same assertions | From suppliers list. | Same as EE-6-C. |
| UX-EE-6-SO | Sales Order detail: same assertions | From sales-orders list. | Same as EE-6-C. |

### EE-SEARCH-DERIVE: Derived-term search returns at least one result

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-EE-SD-C | Customers: search term from first row returns results | Extract a 3+ char word from first row's name cell, fill `#customerSearch`, press Enter. | At least one `tbody tr` visible after search. No error banner. |
| UX-EE-SD-P | Products: derived-term search returns results | Same for products. | Same as SD-C. |
| UX-EE-SD-S | Suppliers: derived-term search returns results | Same for suppliers. | Same as SD-C. |

---

## Area 4: RBAC & Navigation

Spec file: `web/e2e/uiux-rbac-nav.spec.ts`
Playwright project: `chromium-unauthenticated`
Test suite: `UI/UX RBAC nav — permission-driven sidebar`

Setup (beforeAll): logs in as rootadmin via API, discovers first company + branch, creates
a narrow role (`NARROW_<tag>`) with only `STOCK.VIEW` + `PRODUCT.VIEW`, creates user
`narrow.<tag>` and grants the role. If setup fails all tests in this suite are skipped.

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-RBAC-1 | rootadmin: no nav group renders with zero navigable links | Log in as rootadmin; wait for `.nav-group`. | Every `.nav-group` has `count(a.nav-item) > 0`. No empty group headers rendered. |
| UX-RBAC-2 | rootadmin: many nav groups visible (root sees all) | Log in as rootadmin; wait for `.nav-group`. | `count(.nav-group) >= 10`. "Accounting" group visible. "HR & Payroll" group visible. |
| UX-RBAC-3 | narrow user: no nav group renders with zero navigable links | Log in as narrow user; wait for `.nav-group`. | Every rendered `.nav-group` has `count(a.nav-item) > 0`. (Skip if no groups at all.) |
| UX-RBAC-4 | narrow user: STOCK.VIEW + PRODUCT.VIEW groups are visible | Log in as narrow user. | At least one of ["Products", "Inventory"] nav groups is visible. |
| UX-RBAC-5 | narrow user: groups requiring ungranted permissions are absent | Log in as narrow user; wait for `nav.sidebar-nav`. | None of ["Accounting", "HR & Payroll", "Administration", "Purchasing", "Sales"] groups rendered. |
| UX-RBAC-6 | narrow user: forbidden route /admin/gl/journals redirects to /admin/home — no data shown | Log in as narrow user; navigate to `/admin/gl/journals`. | URL does not match `/login`. URL does not match `/admin/gl/journals`. URL matches `/admin/home`. Body text does not contain "Journal Entries". No error-state banner visible. |
| UX-RBAC-7 | narrow user at /admin/home: sidebar renders no empty group headers | Log in as narrow user; navigate to `/admin/home`. | Every `.nav-group` visible on home has `count(a.nav-item) > 0`. |

---

## Area 5: Journeys & Accessibility

Spec file: `web/e2e/uiux-journeys-a11y.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `journey: customer create`, `shell: navigation and chrome UX`, `a11y: axe WCAG 2.1 AA on key authenticated screens`, `keyboard: forms reachable and submittable via keyboard`

### Customer create journey

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-J1 | Navigate to Customers via sidebar, create a customer, confirm in list | 1. Land on `/admin/home`. 2. Click "Customers" in `aside.sidebar a.nav-item`. 3. Confirm URL = `/admin/customers` + h1 visible + nav-item has `.active` class. 4. Click "New Customer" — confirm `aria-expanded="true"`. 5. Fill `#newDisplayName` with unique tag. 6. Submit. 7. Confirm form closes. 8. Search for unique tag in `#customerSearch`. | No API 5xx. No page/console errors. Sidebar Customers link has `active` class. Form closes after success. New row with customer name visible in `table.erp-table tbody td`. |
| UX-J2 | Open first customer detail — renders header, no raw UID visible | 1. Go to `/admin/customers`. 2. Click first `a` with text "Edit". | `h1` visible. `.status-tag` visible. `form[aria-label="Edit customer"]` visible. (Skip if list empty.) |
| UX-J3 | Sales Orders list loads; first SO detail renders Lines section | 1. Go to `/admin/sales-orders`. 2. Click first `/uid/<ULID>` anchor. | No session bounce. No API 5xx. `h1` visible on list. Status-tag visible on detail. "Lines" section heading visible on detail. (Skip if no SOs seeded.) |

### Shell / navigation UX

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-N1 | Topbar brand is visible and links to /admin/companies | Navigate to `/admin/home`. Inspect `a.topbar-brand`. | Element visible. `textContent.trim()` is non-empty. |
| UX-N2 | User menu button renders and opens dropdown with sign-out option | 1. Navigate to `/admin/home`. 2. Click `button[aria-label="Account menu"]`. 3. Press Escape. | Avatar initials visible. `.user-menu` dropdown shows "Sign out" button and `.user-menu__header`. Menu closes on Escape. |
| UX-N3 | Sidebar nav groups render; nav item navigates and becomes active | 1. Navigate to `/admin/home`. 2. Click "Customers" nav-item. | `count(.nav-group) > 0`. After click: URL = `/admin/customers`, `a.nav-item.active` with text "Customers" visible. |
| UX-N4 | Branch chip renders in topbar; opens dropdown when switchable | Navigate to `/admin/home`. Inspect `.branch-chip-wrap` or `.branch-chip--none`. | One of the two chip variants is rendered. If switchable (multi-branch): `.branch-menu[role="menu"]` opens on click with at least one `menuitemradio`. Menu closes on Escape. |

### Accessibility — axe WCAG 2.1 AA

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-A11Y-1 | /admin/home — zero serious/critical axe violations | Navigate to `/admin/home`, run axe (color-contrast excluded). | `violations.filter(v => v.impact === 'serious' \|\| v.impact === 'critical')` is empty. |
| UX-A11Y-2 | /admin/customers — zero serious/critical axe violations | Navigate to `/admin/customers`, run axe. | Same as A11Y-1. |
| UX-A11Y-3 | Customer create form (inline) — zero serious/critical axe violations | Open `/admin/customers`, click "New Customer", wait for form, run axe. | Same as A11Y-1. (Skip if "New Customer" button absent.) |
| UX-A11Y-4 | /admin/sales-orders — zero serious/critical axe violations | Navigate to `/admin/sales-orders`, run axe. | Same as A11Y-1. |

### Keyboard reachability

| ID | Title | Steps | Expected result |
|---|---|---|---|
| UX-KB-1 | Login form — username/password inputs and submit button are keyboard-reachable | 1. Navigate to `/login`. 2. `focus()` username input. 3. Tab → password. 4. Tab → submit. | Username input focused after `.focus()`. Password input focused after first Tab. Submit button focused after second Tab. Submit button enabled before any interaction. (Skip with annotation if authenticated session redirects immediately.) |
| UX-KB-2 | Customer create form — display-name and submit reachable via Tab | 1. Open `/admin/customers`. 2. Press Enter on "New Customer" button. 3. Focus `#newDisplayName`, fill value. 4. Tab through form until submit button focused. | Form opens via keyboard. Submit button reached within 10 Tabs. Submit button enabled when display name is filled. |

---

## Coverage summary

| Area | Spec file | Project | Cases |
|---|---|---|---|
| Auth & Session | uiux-auth-session.spec.ts | chromium-unauthenticated | 6 |
| Forms & Validation | uiux-forms-validation.spec.ts | chromium | 14 |
| Empty / Error / Loading States | uiux-empty-error-states.spec.ts | chromium | 24 |
| RBAC & Navigation | uiux-rbac-nav.spec.ts | chromium-unauthenticated | 7 |
| Journeys & Accessibility | uiux-journeys-a11y.spec.ts | chromium | 13 |
| **Total** | | | **64** |

---

## Exit criteria for a clean UI/UX gate

- All 64 cases either PASS or are annotated SKIP with a recorded reason (not silent failures).
- Zero serious/critical axe violations on A11Y-1 through A11Y-4.
- Zero API 5xx responses captured by `watchProblems` across all forms/journey tests.
- No raw `500` / `stack trace` / `NullPointerException` / `unexpected error` text visible in
  any page body after a form submission error.
- RBAC-5 is always a hard assertion: no unpermitted group must ever be rendered for the narrow user.
