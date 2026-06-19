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
| `chromium` (authenticated, storageState) | `uiux-forms-validation`, `uiux-empty-error-states`, `uiux-journeys-a11y`, `uiux-ar-ap-posting`, `uiux-cash-bank`, `uiux-gl-lifecycle`, `uiux-depreciation-payroll`, `uiux-purchasing-journey`, `uiux-approvals`, `uiux-edit-forms-409`, `uiux-inventory-transactions`, `uiux-tax-sales-downstream`, `uiux-finance-states-a11y`, `uiux-pos-crm-mfg`, `uiux-cross-cutting` |
| `chromium-unauthenticated` (cold session, logs in itself) | `uiux-auth-session`, `uiux-rbac-nav`, `uiux-rbac-actions` |

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
| AR/AP Posting | uiux-ar-ap-posting.spec.ts | chromium | 15 |
| Cash & Bank | uiux-cash-bank.spec.ts | chromium | 11 |
| GL Lifecycle | uiux-gl-lifecycle.spec.ts | chromium | 11 |
| Depreciation & Payroll | uiux-depreciation-payroll.spec.ts | chromium | 18 |
| Purchasing Journey | uiux-purchasing-journey.spec.ts | chromium | 12 |
| Approvals | uiux-approvals.spec.ts | chromium | 16 |
| Edit Forms & 409 Conflict | uiux-edit-forms-409.spec.ts | chromium | 12 |
| Inventory Transactions | uiux-inventory-transactions.spec.ts | chromium | 14 |
| Tax & Sales Downstream | uiux-tax-sales-downstream.spec.ts | chromium | 18 |
| Finance States & A11y | uiux-finance-states-a11y.spec.ts | chromium | 22 |
| POS, CRM & Manufacturing | uiux-pos-crm-mfg.spec.ts | chromium | 22 |
| Cross-Cutting UX | uiux-cross-cutting.spec.ts | chromium | 12 |
| RBAC Actions | uiux-rbac-actions.spec.ts | chromium-unauthenticated | 6 |
| **Total** | | | **333** |

---

## Area 6: AR/AP Money-Movement Posting

Spec file: `web/e2e/uiux-ar-ap-posting.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `UI/UX: AR Record Receipt form`, `UI/UX: AP Enter Supplier Bill form`, `UI/UX: AP Record Payment form`, `UI/UX: AR/AP pages have no unexpected console errors or page-level error banners`

| ID | Title | Expected result |
|---|---|---|
| AR-1a | Record Receipt submit button is disabled when customer and amount are empty | `button[type="submit"]` is disabled on fresh page load. No raw 500 text visible. |
| AR-1b | Entering zero or negative amount keeps submit button disabled with no 500 leak | Submit remains disabled for amount=-500 and amount=0. No raw error patterns in body. |
| AR-1c | Allocating more than receipt amount shows "Over-allocated" tag and disables submit | `.status-tag.status-tag--danger[role="alert"]` with text matching `/Over-allocated/i` is visible. Submit disabled. |
| AR-1d | Allocation exceeding one invoice outstanding shows "exceeds outstanding" tag | `.status-tag.status-tag--danger[role="alert"]` with text matching `/An allocation exceeds the invoice outstanding/i` visible. Submit disabled. |
| AR-1e | Valid on-account receipt records successfully and shows receipt number in success panel | `div.alert.alert-success[role="status"]` appears. Text matches `/Receipt recorded/i`. No API 5xx. |
| AP-2a | Enter Bill submit button is disabled when no supplier or required fields are empty | `button[type="submit"]` disabled; no form error pre-shown on clean load. |
| AP-2b | Bill with valid header but empty line description shows "At least one bill line" error | `p[role="alert"].text-danger` visible; text matches `/line/i`. No 500 text. |
| AP-2c | Bill line with negative unit cost does not leak a 500 or stack trace | After submit: no raw 500/stack-trace patterns in body. Clean 4xx message if rejected. |
| AP-2d | Valid supplier bill enters successfully and shows match result or success | Either "3-Way Match Result" card or match-failed warning visible. "Enter Another Bill" button visible. |
| AP-3a | Record Payment submit button disabled with no supplier; page shows no raw 500 text | `button[type="submit"]` disabled. No raw error text in body. |
| AP-3b | Supplier with no payable bills shows informational message and submit stays disabled | "No payable bills found" or "Select a supplier" text visible. No `[role="alert"].text-danger`. Submit disabled. |
| AP-3c | Valid payment run records successfully and shows payment numbers in success panel | `section.alert.alert-success[aria-label="Payment run result"]` visible. Text matches `/Payment run complete/i`. "Record Another" button visible. |
| AP-3x | "Select at least one bill" status tag and disabled submit when bills exist but none checked | `.status-tag.status-tag--warn[role="alert"]` with text `/Select at least one bill/i` visible. Submit disabled. |
| CC-AR/AP-LOAD | Each AR/AP list and form route loads cleanly without console errors or error banners | 6 routes checked: no page errors, no console errors, no raw 500 text, no unexpected `.alert.alert-danger`. |

---

## Area 7: Cash & Bank

Spec file: `web/e2e/uiux-cash-bank.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `CB-TR: Record Cash Transfer`, `CB-EN: Record Cash/Bank Entry`, `CB-RC: Bank Reconciliation`

| ID | Title | Expected result |
|---|---|---|
| CB-TR-1 | Clicking Record Transfer with no selection shows form-level error, not a 500 | `button[name="Record Transfer"]` disabled on clean load. No `formError` div pre-shown. No raw error text. |
| CB-TR-2 | Selecting same account for source and destination shows the "must differ" inline error | `.invalid-feedback[role="alert"]` with text `/Source and destination must differ/i` visible. Button disabled. |
| CB-TR-3 | Record Transfer button becomes enabled only when source, dest (different), amount and date are valid | Button disabled at each partial stage; enabled only when all four fields valid. Clears back to disabled on amount clear. |
| CB-TR-4 | Valid transfer records successfully — success banner shows transfer number, no 500 | `.alert.alert-success[aria-live="polite"]` visible; text matches `/recorded successfully/i`. No API 5xx. "Record another" button visible. |
| CB-EN-1 | Record Entry button is disabled when required fields are empty (client-side guard) | Button disabled on fresh load. No stray formError div. No raw error text. |
| CB-EN-2 | Submitting a direct entry with a control GL account shows a clean 409 message — no raw 500 or stack trace | No raw error patterns in body. If formError visible, text has no stack trace. No API 5xx. |
| CB-EN-3 | Valid direct entry records successfully — success banner shows txnNumber and direction tag, no 500 | `.alert.alert-success[aria-live="polite"]` visible; text matches `/recorded/i`. `.status-tag` inside banner shows "IN" or "OUT". No API 5xx. "Record another" button visible. |
| CB-RC-1 | Reconciliation page renders heading and bank account picker without errors | h1 "Bank Reconciliation" visible. `#reconAccount` picker visible. No API 5xx. No page/console errors. |
| CB-RC-2 | Selecting a bank account loads the transactions panel or clean empty-state, no errors | After account selection: table, `.erp-empty`, or "Open Reconciliation" button visible. No "Failed to load" alert. |
| CB-RC-3 | Opening a reconciliation shows KPI tiles with difference displayed and Out-of-Balance tag initially | "Statement Closing Balance", "Cleared Book Balance", "Difference" KPI labels visible. Status tag shows "Balanced" or "Out of Balance". No API 5xx. |
| CB-RC-4 | "Complete Reconciliation" button is disabled while difference is non-zero | Button disabled when "Out of Balance" tag is shown. `#completeHelp` text length > 5 with no raw error patterns. No API 5xx. |

---

## Area 8: GL Lifecycle

Spec file: `web/e2e/uiux-gl-lifecycle.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `GL-D: Journal detail page`, `GL-TB: Trial Balance page`, `GL-FP: Fiscal Periods page`, `GL-YE: Year-End Close page`

| ID | Title | Expected result |
|---|---|---|
| GL-D1 | Journal list renders h1 heading without 5xx, no raw error text | h1 visible on `/admin/gl/journals`. No API 5xx. No raw error patterns in body. |
| GL-D2 | Opening first journal renders header card, lines table, and balanced indicator | h1 visible on detail. "Lines" section visible. `.status-tag` with "Balanced" visible. No API 5xx. |
| GL-TB1 | Trial balance page renders h1 heading, no 5xx, no raw error text | h1 visible. No API 5xx. No raw error text. |
| GL-TB2 | Period picker (#periodPicker) is rendered after company context loads | `#periodPicker` visible after company loads. |
| GL-TB3 | When trial balance data loads, total debits equal total credits (balanced) and no error shown | Debit total equals credit total in the table footer. No error alert. |
| GL-FP1 | Fiscal periods page renders h1 heading without 5xx or raw error text | h1 visible. No API 5xx. No raw error patterns. |
| GL-FP2 | "Accounting Periods" h2 section heading is rendered after company load | `h2` with text "Accounting Periods" visible. |
| GL-FP3 | When periods are loaded, status pills (.status-tag) are visible in the periods table | At least one `.status-tag` visible in periods table. |
| GL-FP4 | "Open Fiscal Year" button (if present) toggles the create form and form fields render | Clicking the button shows the create form with fiscal year inputs. |
| GL-YE1 | Year-end close page renders h1 heading without 5xx or raw error text | h1 visible. No API 5xx. No raw error patterns. |
| GL-YE2 | Year-end close page resolves to a valid final state — no 500, no raw error text | Page settles to either a data table, empty state, or clean permission-denied. No raw error text. |
| GL-YE3 | When OPEN fiscal years exist, each row has a "Close Year" button with aria-label | Each OPEN row has a button with `aria-label` containing "Close Year". |

---

## Area 9: Depreciation & Payroll

Spec file: `web/e2e/uiux-depreciation-payroll.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `UI/UX: Depreciation run list`, `UI/UX: Depreciation post form`, `UI/UX: Payroll run list`, `UI/UX: Payroll run detail`

| ID | Title | Expected result |
|---|---|---|
| DR-1 | List page renders heading and no raw server error regardless of data state | `h1.h4` "Depreciation Runs" visible. No raw error patterns in body. |
| DR-2 | "Run Depreciation" toolbar button links to /admin/depreciation-runs/post (or absent when permission denied) | `a.btn[routerLink*="post"]` links to post route, OR is absent with no error when permission denied. |
| DR-3 | Opening the first depreciation run detail renders Run Summary and Per-Asset Lines, no 500 | "Run Summary" and "Per-Asset Lines" headings visible. `.status-tag` visible. No API 5xx. |
| DR-4 | Depreciation-run-list has zero axe serious/critical violations | axe scan: zero serious/critical violations. |
| DP-1 | Post form renders heading, back link, Fiscal Period UID + Posting Date fields | `h1.h4` "Run Depreciation" visible. `#fFiscalPeriodUid` and `#fPostingDate` visible. |
| DP-2 | Clicking Preview with empty Fiscal Period UID shows a clean inline error, no 500 | `p.text-danger[role="alert"]` visible; no raw error patterns. |
| DP-3 | Clicking Post Run with empty Fiscal Period UID shows a clean inline error, no 500 | `p.text-danger[role="alert"]` visible; no raw error patterns. |
| DP-4 | Clicking Post Run with period UID filled but empty Posting Date shows "Posting date required" error | `p.text-danger[role="alert"]` matches `/Posting date required/i`. |
| DP-5 | Preview with an invalid Fiscal Period UID shows a clean API error, no raw 500 visible | `p.text-danger[role="alert"]` shows a human-readable message; no stack trace in body. |
| DP-6 | Depreciation-post form has zero axe serious/critical violations | axe scan: zero serious/critical violations. |
| PR-1 | List page renders "Payroll Runs" heading and either rows or clean empty state, no 500 | `h1` "Payroll Runs" visible. Table or `.erp-empty` visible. No API 5xx. |
| PR-2 | "New Payroll Run" button opens the inline create form with required fields visible | `#createPayRunForm` visible with `#prYear`, `#prMonth`, `#prPayDate`. |
| PR-3 | Submitting create form with empty Year shows "Period year is required" error, no 500 | `output[role="alert"]` visible with text matching `/year/i`. No raw 500. |
| PR-4 | Submitting create form with Year filled but no Month shows "Period month" error | `output[role="alert"]` visible with text matching `/month/i`. No raw 500. |
| PR-5 | Submitting create form with Year+Month filled but no Pay Date shows "Pay date required" error | `output[role="alert"]` visible with text matching `/pay date/i`. No raw 500. |
| PR-6 | Valid create form submission shows success alert and new run appears in the list | Success alert visible. New run row with `.status-tag` appears in table. No API 5xx. |
| PR-7 | Payroll-run-list has zero axe serious/critical violations | axe scan: zero serious/critical violations. |
| PD-1 | Payroll run detail renders heading, status tag, Run Summary card and no 500 | `h1` with run number visible. `.status-tag` and "Run Summary" card visible. No API 5xx. |
| PD-2 | Payroll run detail "Payroll Lines" section renders table or clean empty state, no error | `h2.erp-section-title` with "Payroll Lines" visible. Table or `.erp-empty` present. No error. |
| PD-3 | Calculate button on a DRAFT payroll run is visible and does not crash on click | "Calculate" button visible on DRAFT run; click triggers no 500, no uncaught error. |
| PD-4 | Payroll run detail breadcrumb "Payroll Runs" link navigates back to list page | Breadcrumb link "Payroll Runs" navigates to `/admin/hr/payroll-runs`. |
| PD-5 | Payroll run detail has zero axe serious/critical violations | axe scan: zero serious/critical violations. |

---

## Area 10: Purchasing Journey

Spec file: `web/e2e/uiux-purchasing-journey.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `UI/UX: Purchase Orders`, `UI/UX: Goods Receipts`, `UI/UX: Purchase Requisitions`, `UI/UX: Landed Costs`

| ID | Title | Expected result |
|---|---|---|
| PO-1 | /admin/purchase-orders loads without API 5xx or console errors | Page loads. No API 5xx. No console errors. |
| PO-2 | Submitting New Order without a supplier shows a clean inline error, no 500 | Inline error visible; no raw 500 text. |
| PO-3 | Happy-path — search supplier, select, create PO, new row appears in list | Success alert visible. New PO row appears in `table.erp-table`. No API 5xx. |
| PO-4 | PO detail "Add Line" form shows an inline error when product is not selected | Inline error visible when product field is empty on Add Line submit. |
| GR-1 | /admin/goods-receipts loads without error and shows "New Receipt" link | Page loads. "New Receipt" link visible. No API 5xx. |
| GR-2 | Entering over-receipt qty surfaces a clean error message, no raw 500 | Clean error message visible; no stack trace in body. |
| GR-3 | Entering zero qty on an included line shows invalid-feedback before server is called | Client-side invalid-feedback visible for zero qty. No API call made. |
| REQ-1 | /admin/purchase-requisitions loads without API 5xx or console errors | Page loads. No API 5xx. No console errors. |
| REQ-2 | Submitting requisition with empty line fields shows a clean formError, no 500 | `p[role="alert"].text-danger` or equivalent error visible. No raw 500. |
| REQ-3 | Clicking "Add line" appends a new row to the requisition line table | Line table row count increases by 1 after clicking "Add line". |
| LC-1 | /admin/landed-costs loads without API 5xx or console errors | Page loads. No API 5xx. No console errors. |
| LC-2 | Submitting landed cost without selecting any GR shows a clean formError, no 500 | Clean formError visible. No raw 500 text. |
| LC-3 | "Add Charge" appends a charge row; remove button removes it | Charge row count increases; remove reduces it back. |
| LC-4 | Charge line with zero amount blocks submission with a clean formError, no 500 | Clean formError visible for zero-amount charge. No API 5xx. |

---

## Area 11: Approvals

Spec file: `web/e2e/uiux-approvals.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `Approvals: My Inbox`, `Approvals: All Requests`, `Approvals: Policies`

| ID | Title | Expected result |
|---|---|---|
| AP-I1 | Inbox page renders — heading visible, no 5xx, no raw error leak | h1/h2 heading visible. No API 5xx. No raw error text. |
| AP-I2 | Empty inbox shows clean empty state — no error banner, no 500 | `.erp-empty` or similar empty-state visible. No `[role="alert"].text-danger`. |
| AP-I3 | Opening a pending inbox item shows Approve and Reject action buttons | "Approve" and "Reject" buttons visible on pending item detail. |
| AP-I4 | Approving a pending request updates status — no 500 | After Approve click: status updates (status-tag changes). No API 5xx. |
| AP-I5 | Inbox page passes axe WCAG 2.1 AA (zero serious/critical violations) | axe scan: zero serious/critical violations. |
| AP-R1 | Requests list renders — heading visible, no 5xx, no raw error leak | Heading visible. No API 5xx. No raw error patterns. |
| AP-R2 | Requests list body resolves to rows or empty state without error banner | Table or `.erp-empty` visible. No `[role="alert"].alert-danger`. |
| AP-R3 | Status filter applies without 5xx or error banner | Status filter selection triggers reload. No API 5xx. No error banner. |
| AP-R4 | Clicking Open on a request row navigates to the detail page | URL changes to `/admin/approvals/requests/uid/...`. h1 visible. |
| AP-R5 | Requests list passes axe WCAG 2.1 AA (zero serious/critical violations) | axe scan: zero serious/critical violations. |
| AP-P1 | Policies list renders — heading visible, no 5xx, no raw error leak | Heading visible. No API 5xx. No raw error patterns. |
| AP-P2 | Policies list body resolves to rows or empty state without error banner | Table or `.erp-empty` visible. No error banner. |
| AP-P3 | New Policy button opens create form; empty submit shows clean validation error | Form opens. Empty submit shows clean error; no raw 500. |
| AP-P4 | Valid policy create shows success alert and new policy appears in list | Success alert visible. New row in table. No API 5xx. |
| AP-P5 | Document type filter applies without 5xx or error banner | Filter selection triggers reload without error. |
| AP-P6 | Clicking Open on a policy row navigates to the policy detail page | URL changes to policy detail. h1 visible. |
| AP-P7 | Edit form with empty policy name shows clean saveError, no 500 | `saveError` paragraph visible with text length > 5. No raw 500. |
| AP-P8 | Policies list passes axe WCAG 2.1 AA (zero serious/critical violations) | axe scan: zero serious/critical violations. |

---

## Area 12: Edit Forms & 409 Conflict Handling

Spec file: `web/e2e/uiux-edit-forms-409.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `EF-1: Customer detail edit-form validation`, `EF-2: Supplier detail edit-form validation`, `EF-3: Product detail edit-form validation`, `EF-LOAD: detail page loads cleanly without raw error text`

| ID | Title | Expected result |
|---|---|---|
| EF-1a | Clearing display name and saving shows inline error, stays on the page | `output.text-danger[aria-live="polite"]` visible. URL stays on `/customers/uid/*`. No raw 500. |
| EF-1b | Valid save succeeds and shows success alertdialog without API 5xx | `[role="alertdialog"].alert-success` visible. No API 5xx. |
| EF-1c | Synthetic 409 on customer PUT shows clean conflict message, not a raw 500 | Error text matches `/conflict/i` or a clean human-readable message. No stack trace. |
| EF-2a | Clearing supplier display name and saving shows inline error, stays on the page | Inline error visible. URL stays on supplier detail. No raw 500. |
| EF-2b | Valid supplier save shows success alertdialog without API 5xx | Success alertdialog visible. No API 5xx. |
| EF-2c | Synthetic 409 on supplier PUT shows clean conflict message, not a raw 500 | Clean conflict error message. No stack trace or raw 500. |
| EF-3a | Clearing product name and saving shows inline error, stays on the page | Inline error visible. URL stays on product detail. No raw 500. |
| EF-3b | Clearing base unit selection and saving shows inline "Base unit is required" error | Error text matches `/base unit/i`. No raw 500. |
| EF-3c | Valid product save shows success alertdialog without API 5xx | Success alertdialog visible. No API 5xx. |
| EF-3d | Synthetic 409 on product PUT shows clean conflict message, not a raw 500 | Clean conflict error message. No stack trace or raw 500. |
| EF-LOAD-C | Customer detail page renders h1 heading without raw error leak | h1 visible. No raw error patterns in body. |
| EF-LOAD-S | Supplier/Product detail pages render h1 heading without raw error leak | h1 visible on each detail page. No raw error patterns. |

---

## Area 13: Inventory Transactions

Spec file: `web/e2e/uiux-inventory-transactions.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `UI/UX: Stock Transfer list`, `UI/UX: Stock Transfer create form guards`, `UI/UX: Stock Count list and create`, `UI/UX: Opening Valuation form`

| ID | Title | Expected result |
|---|---|---|
| ST-1 | /admin/stock-transfers loads without 5xx and shows table rows or a clean empty state | Page loads. Table or `.erp-empty` visible. No API 5xx. |
| ST-2 | Submitting without source branch shows clean client-side error, no 500 | Clean inline error visible. No raw 500. |
| ST-3 | Selecting same source and destination location shows the same-location guard message | Guard message visible (e.g. "Source and destination must differ"). No raw 500. |
| ST-4 | Entering zero quantity on a line shows a clean positive-number error | Client-side invalid-feedback visible for zero qty. |
| ST-5 | Valid transfer flows to detail page showing DRAFT status and Line Items section | URL changes to transfer detail. "DRAFT" `.status-tag` and "Line Items" section visible. |
| ST-6 | Over-transfer attempt surfaces a clean error message, no raw 500 | Clean error message visible. No stack trace. No raw 500 text. |
| SC-1 | /admin/stock-counts loads without 5xx and shows table rows or a clean empty state | Page loads. Table or `.erp-empty` visible. No API 5xx. |
| SC-2 | Submitting stock count without selecting a location shows "Select a stock location." error | Error text matches `/Select a stock location/i`. No raw 500. |
| SC-3 | Valid stock count create navigates to detail with COUNTING status and Count Lines section | URL changes to stock count detail. "COUNTING" status tag and "Count Lines" section visible. |
| SC-4 | A stock count in COUNTING status shows editable counted-qty inputs on its detail page | Counted-qty input fields are visible and editable on COUNTING detail page. |
| OV-1 | /admin/stock/valuation/opening loads without 5xx and renders a recognisable state | Page loads. Heading or form visible. No API 5xx. |
| OV-2 | Unvalued rows cause the info-alert and #ovProduct select to be visible | `.alert.alert-info` visible. `#ovProduct` select visible. |
| OV-3 | Submit button is disabled when no product is selected (submitDisabled guard) | `button[type="submit"]` disabled when `#ovProduct` has no selection. |
| OV-4 | Non-numeric opening cost keeps the submit button disabled | Submit remains disabled with non-numeric cost input. |
| OV-5 | Entering 0.00 as opening cost enables the submit button (boundary allowed) | Submit button becomes enabled when cost is 0.00. |
| OV-6 | Posting opening valuation shows success banner and removes valued row from picker | Success banner visible after post. The posted product no longer in `#ovProduct` options. |
| OV-7 | When all products are valued the page shows a green success alert, not an error banner | `.alert.alert-success` visible (all valued). No `.alert.alert-danger`. |

---

## Area 14: Tax & Sales Downstream

Spec file: `web/e2e/uiux-tax-sales-downstream.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `TAX-1` through `TAX-8`, `SALES-1` through `SALES-10`

| ID | Title | Expected result |
|---|---|---|
| TAX-1 | /admin/tax/vat-returns loads with h1, no 5xx, no console errors | h1 visible. No API 5xx. No console errors. |
| TAX-2a | "New VAT Return" button opens inline form with year and month inputs | Form visible with year and month inputs after button click. |
| TAX-2b | Cancel closes the form without page errors | Form closes. No page errors. |
| TAX-2c | Submitting a duplicate period shows alert-danger — no raw 500 | `.alert.alert-danger` with clean message; no stack trace. |
| TAX-3 | Clicking a row in VAT returns table opens the detail with h1 and status pill | Detail URL loaded. h1 and `.status-tag` visible. No API 5xx. |
| TAX-4 | /admin/tax/wht-types loads h1 "Withholding Tax Types", no 5xx | h1 "Withholding Tax Types" visible. No API 5xx. |
| TAX-5 | Submitting "New WHT Type" with empty code shows alert-danger, form stays open | `.alert.alert-danger` visible. Form stays open. No raw 500. |
| TAX-6 | Valid WHT type creation shows success alert and new row appears in the table | Success alert visible. New row in `table.erp-table`. No API 5xx. |
| TAX-7 | /admin/tax/wht-register loads with period-select form and Load button | Period-select form and "Load" button visible. No API 5xx. |
| TAX-8 | WHT Register Load with current month returns data sections or clean empty state | Data sections or `.erp-empty` visible after Load click. No error banner. |
| SALES-1 | /admin/quotations loads h1 "Quotations", table or empty state, no 5xx | h1 "Quotations" visible. Table or `.erp-empty` visible. No API 5xx. |
| SALES-2 | Submitting "New Quotation" without selecting a customer shows a clean p[role=alert] error | `p[role="alert"]` visible with error text. No raw 500. |
| SALES-3 | First DRAFT quotation detail renders h1 with status pill, no 5xx | h1 and `.status-tag` "DRAFT" visible. No API 5xx. |
| SALES-4 | "Send to Customer" button exists on DRAFT quotation detail; disabled when no lines | "Send to Customer" button visible and disabled when no lines on DRAFT quotation. |
| SALES-5 | /admin/deliveries loads h1 "Deliveries", table or empty state, no 5xx | h1 "Deliveries" visible. Table or `.erp-empty`. No API 5xx. |
| SALES-6 | /admin/deliveries/create without soUid shows error message, no raw 500 | Clean error message visible. No raw 500 text. |
| SALES-7 | /admin/sales-invoices loads h1, #statusFilter present, no 5xx | h1 and `#statusFilter` visible. No API 5xx. |
| SALES-8 | Submitting "New Invoice" without a customer shows p[role=alert] error, no 500 | `p[role="alert"]` visible. No raw 500. |
| SALES-9 | Filtering by each status produces no error alert or 5xx | For each status option: no `.alert.alert-danger` after filtering. No API 5xx. |
| SALES-10 | First invoice detail has h1 + status pill; Finalise disabled without lines | h1 and `.status-tag` visible on detail. "Finalise" button disabled when invoice has no lines. |

---

## Area 15: Finance States & Accessibility

Spec file: `web/e2e/uiux-finance-states-a11y.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `FIN-EE-1` through `FIN-EE-6`, `FIN-EE-DERIVE`, `FIN-A11Y`

Routes under test: AR receipts, AP supplier bills, AP payments, GL journals, depreciation runs, payroll runs, VAT returns, sales orders, quotations, purchase orders.

| ID | Title | Expected result |
|---|---|---|
| FIN-EE-1 (per route) | Populated financial list renders erp-table rows and paginator controls are valid when list has data | `table.erp-table tbody tr` first row visible. Paginator "Page N of M" status span visible if multi-page. No error banner. |
| FIN-EE-2 (per route) | Filtering with a guaranteed-absent term shows empty state without error | Zero `tbody tr` rows OR `.erp-empty` visible. No `[role="alert"].text-danger`. No console errors. |
| FIN-EE-3 (per route) | Clearing no-results filter restores original rows | `tbody tr` first row visible again after clear. No error banner. |
| FIN-EE-4 (per route) | Navigating to page 2 changes rows and shows no error | Paginator status matches "Page 2 of". Rows present. No error banner. |
| FIN-EE-5 (per route) | Spinner resolves within 1.2 s of networkidle | `p[aria-live="polite"] .spinner-border` NOT visible. |
| FIN-EE-6 (per route) | Detail page for first row renders h1 heading without console/page errors | h1 visible. No page/console errors. No API 5xx. No raw error text. |
| FIN-EE-DERIVE (per route) | Filtering with a term derived from the first row returns results | At least one `tbody tr` visible after derived-term filter. No error banner. |
| FIN-A11Y-1 | AR record-receipt form /admin/ar/receipts/record — zero serious/critical axe violations | axe scan: zero serious/critical violations. |
| FIN-A11Y-2 | AP enter-bill form /admin/ap/supplier-bills/enter — zero serious/critical axe violations | axe scan: zero serious/critical violations. |
| FIN-A11Y-3 | AP record-payment form /admin/ap/payments/record — zero serious/critical axe violations | axe scan: zero serious/critical violations. |
| FIN-A11Y-4 | GL post-journal form /admin/gl/journals/post — zero serious/critical axe violations | axe scan: zero serious/critical violations. |
| FIN-A11Y-5 | Depreciation post form /admin/depreciation-runs/post — zero serious/critical axe violations | axe scan: zero serious/critical violations. |

---

## Area 16: POS, CRM & Manufacturing

Spec file: `web/e2e/uiux-pos-crm-mfg.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `UI/UX POS: sell screen`, `UI/UX POS: tills list`, `UI/UX POS: sessions list`, `UI/UX CRM: opportunity create form validation`, `UI/UX CRM: pipeline dashboard`, `UI/UX Manufacturing: work orders`, `UI/UX Manufacturing: bills of materials`, `UI/UX Projects`, `UI/UX Budgeting`, `UI/UX FX rates`

| ID | Title | Expected result |
|---|---|---|
| POS-S1 | /admin/pos/sell renders without 5xx or console errors | Page loads. No API 5xx. No console errors. |
| POS-S2 | Sell form renders Sale Details and Line Items cards | "Sale Details" and "Line Items" card headings visible. |
| POS-S3 | Add Line / Remove cycle updates the line items panel | Add Line increases row count; Remove decreases it back. |
| POS-S4 | Submitting empty POS form shows a clean validation error, no 500 | Clean validation error visible. No raw 500 text. |
| POS-T1 | /admin/pos/tills renders heading without 5xx or console errors | Heading visible. No API 5xx. No console errors. |
| POS-T2 | Till create form empty-name guard shows a clean error, no 500 | Clean error visible on empty name submit. No raw 500. |
| POS-SE1 | /admin/pos/sessions renders heading without 5xx or console errors | Heading visible. No API 5xx. No console errors. |
| POS-SE2 | Sessions table rows render status pills and View links when sessions exist | `.status-tag` and View links visible in table rows. |
| CRM-OC1 | /admin/crm/opportunities/create renders the create form | Create form visible. No API 5xx. |
| CRM-OC2 | Submitting with empty title shows a clean formError, no 500 | Clean formError visible. No raw 500. |
| CRM-OC3 | /admin/crm/opportunities list renders heading and toolbar link | Heading and toolbar link visible. No API 5xx. |
| CRM-PD1 | /admin/crm/pipeline renders heading and selector controls | Heading and selector controls (stage/period pickers) visible. |
| CRM-PD2 | Pipeline board, forecast and KPI section headings render when context is set | "Board", "Forecast" and KPI headings visible after context set. No API 5xx. |
| MFG-WO1 | /admin/work-orders renders heading without 5xx or console errors | Heading visible. No API 5xx. No console errors. |
| MFG-WO2 | Work-order create form empty-submit shows clean validation error, no 500 | Clean error visible on empty submit. No raw 500. |
| MFG-WO3 | Status filter dropdown renders and can be changed without error | Status filter visible and changeable without error or 5xx. |
| MFG-BOM1 | /admin/boms renders heading without 5xx or console errors | Heading visible. No API 5xx. No console errors. |
| MFG-BOM2 | BOM create form empty-submit shows clean validation error, no 500 | Clean validation error visible. No raw 500. |
| MFG-BOM3 | BOM list rows have status tags and detail links when BOMs exist | `.status-tag` and detail links visible in rows. |
| PROJ-1 | /admin/projects renders heading without 5xx or console errors | Heading visible. No API 5xx. No console errors. |
| PROJ-2 | Project create form empty-name guard shows clean error, no 500 | Clean error on empty-name submit. No raw 500. |
| PROJ-3 | Project list rows have status tags and Open links when projects exist | `.status-tag` and Open links visible in rows. |
| BUD-1 | /admin/budgets renders heading without 5xx or console errors | Heading visible. No API 5xx. No console errors. |
| BUD-2 | Budget create form empty-name guard shows clean error, no 500 | Clean error on empty-name submit. No raw 500. |
| BUD-3 | Budget list rows have status tags and Open links when budgets exist | `.status-tag` and Open links visible in rows. |
| BUD-4 | Budget status filter renders and can be changed without error | Status filter visible and changeable without error. |
| FX-1 | /admin/fx/rates renders heading without 5xx or console errors | Heading visible. No API 5xx. No console errors. |
| FX-2 | FX rate create form shows clean error on empty-submit, no 500 | Clean error on empty-submit. No raw 500. |
| FX-3 | FX rate list rows display currency pair, rate, and status tag when rates exist | Currency pair, rate, and `.status-tag` visible in rows. |
| FX-4 | "New Rate" button is disabled while the create form is open | "New Rate" button disabled when create form is open. |

---

## Area 17: Cross-Cutting UX

Spec file: `web/e2e/uiux-cross-cutting.spec.ts`
Playwright project: `chromium` (authenticated as rootadmin)
Test suites: `CC-1: responsive sidebar (mobile viewport 390×844)`, `CC-2: toast / alert feedback mechanisms`, `CC-3: destructive action — archive/restore on customer detail`, `CC-4: loading indicators resolve after page settles`

| ID | Title | Expected result |
|---|---|---|
| CC-1a | Hamburger toggle visible; sidebar opens/closes; nav items reachable | Hamburger button visible on mobile viewport. Sidebar opens on click. Nav items reachable. Sidebar closes. |
| CC-1b | Clicking a nav item in the open sidebar navigates and collapses sidebar | After nav-item click: URL changes, sidebar collapses on mobile. |
| CC-1c | Escape key closes the open sidebar on mobile | Sidebar closes on Escape key press when open. |
| CC-2a | Valid save on customer detail shows success alert-dialog that auto-dismisses | `[role="alertdialog"].alert-success` visible after valid save, then dismisses. |
| CC-2b | Toast-stack container is present; toast dismiss button removes the toast | `.toast-stack` container exists. Toast dismiss button removes the toast. |
| CC-2c | Client-side validation error surfaces as clean inline message (no 500/stack trace) | Inline error visible; no raw 500/stack-trace patterns in body. |
| CC-3a | Archive fires immediately; status changes to ARCHIVED; Restore button appears; success feedback shown | After archive: `.status-tag` shows "ARCHIVED". "Restore" button visible. Success feedback visible. |
| CC-3b | No unwanted browser dialog fires on customer detail page load | No `dialog` event fired on page load. Page settles normally. |
| CC-4a | Global-progress bar is gone after customers list page settles (networkidle + 1.5 s) | Progress bar / loading indicator NOT visible after settle. |
| CC-4b | List-body spinner gone after customers list settles (networkidle + 1.5 s) | Spinner NOT visible after settle. |
| CC-4c | Global-progress bar resolves — not stuck — on products list navigation | Progress bar NOT visible after products list settles. |
| CC-4d | Company-state spinner gone after customers list settles | Company-state spinner NOT visible after page settles. |

---

## Area 18: RBAC Actions

Spec file: `web/e2e/uiux-rbac-actions.spec.ts`
Playwright project: `chromium-unauthenticated`
Test suite: `UI/UX RBAC actions — PRODUCT.VIEW-only user on /admin/products`

Setup (beforeAll): creates a PRODUCT.VIEW-only user via API, grants the role, saves credentials for use in tests. If setup fails all tests in this suite are skipped.

| ID | Title | Expected result |
|---|---|---|
| A1 | View-only user logs in via the UI and lands on /admin (not /login) | After valid login with view-only credentials: URL matches `/admin`, not `/login`. Shell topbar visible. |
| A2 | View-only user reaches /admin/products — route guard allows PRODUCT.VIEW | URL `/admin/products` accessible. h1 or list heading visible. No redirect to `/admin/home`. |
| A3 | "New Product" button is absent for view-only user (canManage() = false) | "New Product" button NOT visible in the DOM. No `aria-disabled` workaround — button absent. |
| A4 | Product list renders for view-only user — no "permission" alert, no raw error | `table.erp-table tbody tr` or `.erp-empty` visible. No `[role="alert"].alert-danger`. No raw error text. |
| A5 | Direct POST /api/v1/products with view-only token returns 403 — write is denied | API response status is 403. No product created. |
| A6 | No raw error text leaks to the screen at any point during the view-only session | No raw 500/stack-trace/NullPointerException text visible on any navigated page. |

---

## Exit criteria for a clean UI/UX gate

- All 333 cases either PASS or are annotated SKIP with a recorded reason (not silent failures).
- Zero serious/critical axe violations on all A11Y cases (A11Y-1 through A11Y-4, DR-4, DP-6, PR-7, PD-5, AP-I5, AP-R5, AP-P8, FIN-A11Y-1 through FIN-A11Y-5).
- Zero API 5xx responses captured by `watchProblems` across all forms/journey tests.
- No raw `500` / `stack trace` / `NullPointerException` / `unexpected error` text visible in
  any page body after a form submission error.
- RBAC-5 (existing) is a hard assertion: no unpermitted group rendered for the narrow user.
- A3 (new) is a hard assertion: "New Product" button must be absent for the PRODUCT.VIEW-only user.
- FIN-A11Y, DR-4, DP-6, PR-7, PD-5, AP-I5, AP-R5, AP-P8 axe gates are hard assertions — zero serious/critical violations.
