# 06 — Sales Advanced: Blanket Orders, Standing Orders & Pricing Rules (SADV)

Test cases for the SALES ADVANCED domain: framework "blanket" agreements with call-off draw-down, recurring "standing" orders with a scheduler/manual trigger, and advanced pricing rules (quantity-break tiers, customer-specific contract prices, and promotions). Cases are written against the verified controllers/DTOs/enums/migrations and the shipped Angular UI; every claim below was read from source. Where a backend capability has no UI it is flagged as backend-only; where the UI deviates from a convention or a permission is mis-seeded, that defect is called out and given an explicit negative case.

## Modules / submodules covered

- **Blanket Orders** — `BlanketOrderController` · base `/api/v1/blanket-orders`
  - `BlanketOrderServiceImpl` (`backend/src/main/java/com/erp/modules/sales/service/`), migration `V45__sales_blanket_standing.sql`
  - Frontend routes (`web/src/app/features/admin/admin.routes.ts`):
    - `/admin/blanket-orders` → `BlanketOrderListComponent` (guard `SALES.BLANKET.VIEW`)
    - `/admin/blanket-orders/create` → `BlanketOrderCreateComponent` (guard `SALES.BLANKET.CREATE`)
    - `/admin/blanket-orders/uid/:uid` → `BlanketOrderDetailComponent` (guard `SALES.BLANKET.VIEW`)
  - Nav: shell "Sales" group → "Blanket Orders" (`permission: SALES.BLANKET.VIEW`)
- **Standing Orders (recurring sales)** — `StandingOrderController` · base `/api/v1/standing-orders`
  - `StandingOrderServiceImpl`, migration `V45__sales_blanket_standing.sql`
  - Frontend routes:
    - `/admin/standing-orders` → `StandingOrderListComponent` (guard `SALES.STANDING.VIEW`)
    - `/admin/standing-orders/create` → `StandingOrderCreateComponent` (guard `SALES.STANDING.CREATE`)
    - `/admin/standing-orders/uid/:uid` → `StandingOrderDetailComponent` (guard `SALES.STANDING.VIEW`)
  - Nav: shell "Sales" group → "Standing Orders" (`permission: SALES.STANDING.VIEW`)
- **Pricing Rules** — `PricingRuleController` · base `/api/v1/pricing-rules`
  - `PricingRuleServiceImpl` (`backend/src/main/java/com/erp/modules/products/service/`), migration `V42__sales_pricing_rules.sql`
  - Sub-resources: `/tiers`, `/customer-prices`, `/promotions`
  - Frontend route: `/admin/pricing-rules` → `PricingRulesComponent` (guard `SALES.PRICING.RULE.VIEW`); single screen, two tabs: **Price Tiers** and **Customer Prices**.
  - Nav: shell "Sales" group → "Pricing Rules" (`permission: SALES.PRICING.RULE.VIEW`)

## Permission codes in scope (exact `@PreAuthorize` codes)

| Code | Used by | Seeded in V42/V45? | Granted to (migration) |
|---|---|---|---|
| `SALES.BLANKET.VIEW` | blanket get/list (route guards too) | yes (V45) | ORG_ADMIN |
| `SALES.BLANKET.CREATE` | blanket create (route guard too) | yes (V45) | ORG_ADMIN |
| `SALES.BLANKET.MANAGE` | blanket **draw** + **cancel** (DELETE), FE `canManage()` | **NO — not seeded anywhere** | none |
| `SALES.BLANKET.CLOSE` | (none — orphan) | yes (V45) | ORG_ADMIN |
| `SALES.STANDING.VIEW` | standing get/list (route guards too) | yes (V45) | ORG_ADMIN |
| `SALES.STANDING.CREATE` | standing create (route guard too) | yes (V45) | ORG_ADMIN |
| `SALES.STANDING.MANAGE` | standing **pause/resume/cancel/trigger**, FE `canManage()` | **NO — not seeded anywhere** | none |
| `SALES.STANDING.GENERATE` | (none — orphan) | yes (V45) | ORG_ADMIN |
| `SALES.PRICING.RULE.VIEW` | tier/customer-price/promotion get + list (route guard too) | yes (V42) | ORG_ADMIN |
| `SALES.PRICING.RULE.MANAGE` | tier/customer-price/promotion create + deactivate | yes (V42) | ORG_ADMIN |

> **DEFECT D-1 (RBAC, P1 — must verify on QA):** the controllers gate every blanket *write after create* (`draw`, `cancel`) on `SALES.BLANKET.MANAGE`, and every standing *write after create* (`pause`, `resume`, `cancel`, `trigger`) on `SALES.STANDING.MANAGE`. **Neither `…MANAGE` code is inserted by `V45__sales_blanket_standing.sql` nor granted to any role.** V45 instead seeds `SALES.BLANKET.CLOSE` and `SALES.STANDING.GENERATE`, which **no controller references** (orphan permissions). Net effect to verify: with the seeded data, **no role except `rootadmin` (superuser bypass) can draw/cancel a blanket or pause/resume/cancel/trigger a standing order** — and the FE `canManage()` (which checks the same `…MANAGE` codes) will hide those buttons for everyone. Cases TC-SADV-016/017/032/033 probe this; if a later migration grants `…MANAGE`, update the expectation and re-baseline.

> **DEFECT D-2 (FE coverage, P2):** `PricingRuleController` fully supports **Promotions** CRUD (`POST/GET/DELETE /promotions`, perms `SALES.PRICING.RULE.MANAGE`/`VIEW`), but the Angular `PricingRulesComponent` exposes only the **Price Tiers** and **Customer Prices** tabs — there is **no Promotions tab** and `PricingRuleService` (client) has **no promotion methods**. Promotions are therefore **backend-only (API-only)**; their cases (TC-SADV-046..052) are Manual/API.

## Type / role variations exercised

| Dimension | Values exercised |
|---|---|
| User roles (allowed) | `rootadmin` (superuser bypass — sanity only), `ORG_ADMIN` (holds VIEW/CREATE/PRICING; see D-1 re: MANAGE) |
| User roles (denied) | `SALES_REP`, `STOREKEEPER`, NO-PERMISSION user, CUSTOM role with VIEW-only subset |
| `BlanketStatus` | `ACTIVE`, `EXHAUSTED`, `CANCELLED` |
| `StandingStatus` | `ACTIVE`, `PAUSED`, `CANCELLED` |
| `StandingFrequency` | `DAILY`, `WEEKLY`, `BIWEEKLY`, `MONTHLY` |
| `PromotionTarget` | `PRODUCT`, `CATEGORY`, `ALL` |
| `PromotionEffect` | `PERCENT_DISCOUNT`, `AMOUNT_DISCOUNT`, `OVERRIDE_PRICE` |
| `PriceSource` (diagnostic on SO/invoice line) | `CUSTOMER_PRICE`, `PROMOTION`, `TIER`, `LIST_PRICE`, `NONE` (stamped by pricing resolution; read-only) |
| Customer kind | BUSINESS + CREDIT_ACCOUNT (typical contract/blanket customer); CASH_WALK_IN (edge) |
| Product type | GOODS (stockable); SERVICE (edge — tier/customer-price still allowed) |
| Branch context | default vs non-default; multi-branch company; acting in an unassigned branch (deny) |
| Company/tenant | single company; cross-company isolation (tenant A vs B) |

## Verified domain facts (used by cases below)

- **Blanket create** (`POST /api/v1/blanket-orders`): body `CreateBlanketOrderRequest{companyUid, branchId(Long), customerId(Long), currency, validFrom(LocalDate), validTo(LocalDate), lines[], notes(≤500)}`; each line `{productId, unitId, committedQtyBase(≥0.0001), unitPriceAmount(≥0.00)}`. Service also throws `IllegalArgumentException("validTo must not be before validFrom.")`; DB also enforces `chk_blanket_order_window (valid_to >= valid_from)`. `totalCommittedAmount = Σ committedQtyBase·unitPriceAmount`; status defaults `ACTIVE`; `orderNumber` auto-generated.
- **Blanket draw** (`POST /uid/{uid}/draw`): body `DrawBlanketRequest{blanketUid, branchId(Long), agentId(Long), lines[{blanketLineUid, qtyBase(≥0.0001)}]}`. Guards (in order): blanket must be `ACTIVE` → else `ConflictException "Blanket … is not ACTIVE."`; `now() > validTo` → `ConflictException "Blanket … has expired."`; per line `qtyBase > remaining` → `ConflictException "Draw quantity … exceeds remaining … on blanket line …"`. On success: creates a **SalesOrder** (stamped `source_blanket_uid`), adds SO lines, increments each blanket line `drawnQtyBase`, increments header `totalDrawnAmount`; DB `chk_blanket_order_line_drawn (drawn <= committed)` is the backstop. **Returns the generated `SalesOrderDto`.** Note the controller method takes the body's `request` (its `blanketUid`), not the path `uid`.
- **Blanket cancel** (`DELETE /uid/{uid}`): sets status `CANCELLED`; already-`CANCELLED` → `ConflictException "Blanket … is already CANCELLED."`. (A `CANCELLED` or `EXHAUSTED` blanket cannot be drawn — caught by the "not ACTIVE" guard.) No status field is auto-set to `EXHAUSTED` by the service code read — `EXHAUSTED` exists in the enum/DB and on lines via `remainingQtyBase<=0`, but the header is not flipped to `EXHAUSTED` automatically in the read implementation; treat header `EXHAUSTED` as a data/admin state, fully-drawn lines as the functional "exhausted" signal.
- **Standing create** (`POST /api/v1/standing-orders`): body `CreateStandingOrderRequest{companyUid, branchId, customerId, currency, frequency(StandingFrequency), startDate(req), endDate(opt), lines[{productId, unitId, qty, qtyBase, unitPriceAmount}], notes}`. Status defaults `ACTIVE`; `nextRunDate` initialised on the entity.
- **Standing lifecycle**: `pause` requires `ACTIVE` (else `ConflictException "… is not ACTIVE."`) → `PAUSED`; `resume` requires `PAUSED` (else `"… is not PAUSED."`) → `ACTIVE`; `cancel` blocks only if already `CANCELLED` (`"… is already CANCELLED."`) → `CANCELLED`; `trigger` requires `ACTIVE` (else `"… is not ACTIVE."`), generates a SalesOrder (stamped `source_standing_uid`), advances `nextRunDate` by frequency (DAILY +1d, WEEKLY +1w, BIWEEKLY +2w, MONTHLY +1mo), publishes outbox `STANDING_ORDER_GENERATED`, returns updated `StandingOrderDto`. A `@Scheduled(cron="0 0 0 * * *")` `generateDue()` batch generates for ACTIVE orders with `next_run_date <= today` (backend-only, not user-triggerable via UI).
- **Price tier create** (`POST /tiers`): `CreatePriceTierRequest{companyUid, productUid, priceListUid, minQty(≥0.000001), unitPriceAmount(≥0), currency}`; duplicate `(product, priceList, minQty)` → `ConflictException "A tier with min_qty … already exists for this product+price_list."` (also DB `uq_price_tier_break`). Status `ACTIVE` default; deactivate sets `INACTIVE`.
- **Customer price create** (`POST /customer-prices`): `CreateCustomerPriceRequest{companyUid, customerUid, productUid, unitPriceAmount(≥0), currency, effectiveFrom(opt), effectiveTo(opt)}`; DB `uq_customer_price_scope (customer_id, product_id)` is unique **regardless of status**, and `chk_customer_price_window (to >= from)`. Deactivate sets `INACTIVE`.
- **Promotion create** (`POST /promotions`, backend-only): `CreatePromotionRequest{companyUid, code, name, target(PromotionTarget), targetProductUid(req if PRODUCT), targetCategory(req if CATEGORY), effect(PromotionEffect), effectValue(≥0), effectiveFrom, effectiveTo, priority(short)}`. Service: PRODUCT target missing `targetProductUid` → `IllegalArgumentException("targetProductUid required when target=PRODUCT")`; PERCENT_DISCOUNT value out of 0–100 → `IllegalArgumentException("effect_value must be 0–100 for PERCENT_DISCOUNT")`. DB: `uq_promotion_company_code`, `chk_promotion_pct`, `chk_promotion_window`, `chk_promotion_target_ref`.
- **Lists / envelope**: blanket `list` and standing `list` and promotion `listPromotions` return `ApiResponse<List<…>>` with `PageMeta` (`?companyId=&page=&size=`); tier `listTiers` (`?companyId=&productId=&priceListId=`) and customer-price `listCustomerPrices` (`?companyId=&customerId=`) return **plain `List` (no pagination)**. All single-get/create return the bare DTO (auto-unwrapped).
- **UI specifics**: list/detail screens show **raw numeric `customerId`** (DTOs carry no customer name/uid) — a C1 deviation, see D-3. Blanket **create** uses company `<select>` + branch `<select>` + **customer picker** (search by code/name) + **product picker** + unit `<select>`. Blanket **draw form** uses a **hand-typed numeric "Branch ID" text input** (the detail screen lacks the company uid to load branches — see component comment) + an **agent picker** — branch-by-typed-id is a C1 deviation, see D-3. Standing **create** uses `<app-uid-picker>` for branch/customer/product/unit. Pricing screen uses `<app-uid-picker>` for product/price-list/customer and a company `<select>`.

> **DEFECT D-3 (C1 convention, P2):** (a) blanket & standing list/detail render the customer as a **raw numeric id** ("Customer ID") instead of a human name (DTOs omit customer name/uid). (b) The blanket **draw** form requires the user to **hand-type a numeric Branch ID** rather than choosing a branch by name via a picker. Both violate C1 (uid/id never shown/typed; choose by name via picker). Cases TC-SADV-018 and TC-SADV-009 assert these as known deviations.

---

## TEST CASES

### TC-SADV-001 — Blanket Orders list: loading → populated, pagination, four-state
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Blanket Orders (`/admin/blanket-orders` · `GET /api/v1/blanket-orders?companyId=&page=&size=`)
- **Permission / Role:** `SALES.BLANKET.VIEW` — runs as `ORG_ADMIN`; also as NO-PERMISSION user → expect nav item hidden + route guard blocks (forbidden)
- **Variation:** single-company tenant; ≥21 blanket orders so 2 pages at size 20
- **Preconditions / Seed:** seed ≥21 ACTIVE blanket orders for the company (via TC-SADV-006 API loop)
- **Steps:**
  1. Login `ORG_ADMIN`; navigate to `/admin/blanket-orders`.
  2. Observe the loading state ("Loading blanket orders…", `aria-live="polite"`).
  3. After load, read the table (columns: Order #, Customer ID, Status, Valid From, Valid To, Currency, Committed, Drawn, Actions).
  4. Use `<app-paginator>` controls: NEXT, page number 2, LAST, FIRST, PREVIOUS.
  5. Run axe scan.
- **Test Data:** company = "Acme (TZS)"; size=20.
- **Expected Result:** rows render; status as a colored badge; Committed/Drawn right-aligned monospace numbers; paginator shows FIRST/PREVIOUS/numbers/NEXT/LAST and navigates; envelope `ApiResponse<List>` with `meta{page,size,totalElements,totalPages,hasNext}` drives the paginator.
- **Convention Assertions:** C2 (envelope+meta), C4 (loading/empty/error/forbidden all present in template), C5 (full paginator), C6 (axe clean), C7 (only this company's rows), C8 (currency-prefixed money, ISO dates).
- **Negative / Edge:** with NO-PERMISSION user the "Blanket Orders" nav item is absent and direct nav to `/admin/blanket-orders` is blocked by `requirePermission('SALES.BLANKET.VIEW')`.

### TC-SADV-002 — Blanket list: empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Blanket Orders (`/admin/blanket-orders`)
- **Permission / Role:** `SALES.BLANKET.VIEW` — runs as `ORG_ADMIN`
- **Preconditions / Seed:** a company with **zero** blanket orders
- **Steps:** navigate to `/admin/blanket-orders` for the empty company.
- **Expected Result:** "No blanket orders yet." with a "Create one" link (link only when `canCreate()`); no table, no paginator.
- **Convention Assertions:** C4 (empty distinct from loading/error), C5 (paginator self-hidden — list not rendered), C6 axe.
- **Negative / Edge:** confirm "Create one" link absent for a VIEW-only CUSTOM role.

### TC-SADV-003 — Blanket list: error state (API down)
- **Type:** Automated (Playwright) | Manual
- **Priority:** P2
- **Module / Submodule:** Blanket Orders (`/admin/blanket-orders`)
- **Permission / Role:** `SALES.BLANKET.VIEW` — `ORG_ADMIN`
- **Preconditions / Seed:** stub the list endpoint to 500 (Playwright route intercept).
- **Steps:** navigate; intercept `GET …/blanket-orders` → 500.
- **Expected Result:** "Could not load blanket orders. Please try again." with `role="alert"`.
- **Convention Assertions:** C4 (error), C6 axe.
- **Negative / Edge:** company-list call failing shows the separate "Could not load companies" error above the list.

### TC-SADV-004 — Blanket list: multi-company switch isolates data
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Blanket Orders (`/admin/blanket-orders`)
- **Permission / Role:** `SALES.BLANKET.VIEW` — `ORG_ADMIN` of an org with ≥2 companies
- **Variation:** company A has blankets; company B has none/different ones
- **Preconditions / Seed:** two companies under one org, distinct blanket sets
- **Steps:** 1. open list; 2. the company `<select>` appears (only when >1 company); 3. switch from A to B.
- **Expected Result:** switching reloads the list scoped to the selected company; A's blankets never appear under B.
- **Convention Assertions:** C7 (company scoping), C2 envelope, C6 axe.
- **Negative / Edge:** a user assigned to only one company sees no selector (single-company branch of the template).

### TC-SADV-005 — Blanket list: forbidden (no VIEW) at API
- **Type:** Automated (Playwright) | Both
- **Priority:** P1
- **Module / Submodule:** Blanket Orders (`GET /api/v1/blanket-orders`)
- **Permission / Role:** `SALES.BLANKET.VIEW` — runs as `STOREKEEPER` (lacks it) → expect 403
- **Steps:** as `STOREKEEPER`, call `GET /api/v1/blanket-orders?companyId=…` directly.
- **Expected Result:** HTTP 403; envelope carries an error; UI route guard would redirect/forbid before the call.
- **Convention Assertions:** C3 (RBAC 403), C4 (forbidden UI state exists).
- **Negative / Edge:** `rootadmin` calling the same returns 200 (superuser bypass) — sanity only.

### TC-SADV-006 — Blanket create: full flow (pickers, lines) → ACTIVE + detail
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Blanket Orders create (`/admin/blanket-orders/create` · `POST /api/v1/blanket-orders`)
- **Permission / Role:** `SALES.BLANKET.CREATE` — runs as `ORG_ADMIN`; also as `SALES_REP` lacking CREATE → nav/route forbidden
- **Variation:** customer = BUSINESS + CREDIT_ACCOUNT; products = GOODS; branch = default; currency TZS
- **Preconditions / Seed:** ≥1 active customer, ≥1 active product, ≥1 active unit, ≥1 non-archived branch for the company
- **Steps:**
  1. Navigate `/admin/blanket-orders/create`.
  2. Company `<select>` defaults to first; branch `<select>` defaults to first non-archived.
  3. In the **customer picker**, type the customer's name; select the matching active customer by `code — name`.
  4. Set currency TZS; Valid From = today; Valid To = today+90d; notes "Q3 framework".
  5. In the **product picker** add a line: choose product by name, choose unit, committed qty `100`, unit price `1500.00`; click Add Line. Add a second line.
  6. Submit.
- **Test Data:** customer "Beta Traders Ltd (BUSINESS/CREDIT_ACCOUNT)"; product "WIDGET-A"; qty 100 @ 1500.00; line 2 qty 50 @ 2000.00.
- **Expected Result:** POST succeeds; success toast with the generated order number; navigates to `/admin/blanket-orders/uid/:uid`; status `ACTIVE`; `totalCommittedAmount = 100·1500 + 50·2000 = 250,000.00`; both lines show committed, drawn `0`, remaining = committed.
- **Convention Assertions:** C1 (customer + product chosen via picker **by name**, uid stored under the hood; the resulting uid appears only in the URL, never typed), C2 (bare DTO unwrapped), C7 (scoped to company/branch), C8 (money "TZS 250,000.00", ISO dates), C6 axe on the form.
- **Negative / Edge:** see TC-SADV-007/008.

### TC-SADV-007 — Blanket create: required-field & date-window validation
- **Type:** Automated (Playwright) | Both
- **Priority:** P1
- **Module / Submodule:** Blanket create (`/admin/blanket-orders/create` · `POST /api/v1/blanket-orders`)
- **Permission / Role:** `SALES.BLANKET.CREATE` — `ORG_ADMIN`
- **Steps:**
  1. Submit with no customer → "Customer is required."
  2. Add customer; submit with no branch → "Branch is required."
  3. Provide branch+customer; blank currency → "Currency is required."
  4. Blank Valid From / Valid To → "Valid From date is required." / "Valid To date is required."
  5. Set Valid From > Valid To → "Valid From must be before Valid To." (client) and, if bypassed, API throws `IllegalArgumentException "validTo must not be before validFrom."` / DB `chk_blanket_order_window`.
  6. Submit with zero lines → "Add at least one agreement line."
  7. Add line with qty `0` → "Enter a committed quantity greater than zero." (API: `committedQtyBase` `@DecimalMin("0.0001")`).
- **Expected Result:** each message blocks submit; no POST until valid; server-side bean-validation 400 on a crafted bad body.
- **Convention Assertions:** C2 (400 envelope `errors[]`), C6 axe, C8 dates.
- **Negative / Edge:** committed qty `0.0001` accepted (boundary); unit price `0.00` accepted (`@DecimalMin("0.00")`); negative price rejected.

### TC-SADV-008 — Blanket create: cross-tenant company / unassigned branch denied
- **Type:** Manual | API
- **Priority:** P1
- **Module / Submodule:** Blanket create (`POST /api/v1/blanket-orders`)
- **Permission / Role:** `SALES.BLANKET.CREATE` (scoped: `@perm.scoped(#request.companyUid(),'company',…)`) — `ORG_ADMIN` of tenant A
- **Variation:** companyUid = tenant B's company
- **Steps:** as A's `ORG_ADMIN`, POST with `companyUid` of tenant B (and/or a branchId not in scope).
- **Expected Result:** denied — `scopeGuard.assertCanActIn` rejects acting in B's company (403/forbidden); no blanket created.
- **Convention Assertions:** C3, C7 (multi-tenant isolation).
- **Negative / Edge:** a valid company but `branchId` of an unrelated branch — confirm behaviour (branch is referenced by id; assert no cross-branch leakage in created record).

### TC-SADV-009 — Blanket draw: release creates a SalesOrder, decrements remaining
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Blanket detail draw (`/admin/blanket-orders/uid/:uid` · `POST /api/v1/blanket-orders/uid/{uid}/draw`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` (controller) — runs as a role that **has MANAGE** (see D-1: must be granted first; with seed-only data only `rootadmin` qualifies). Also as `ORG_ADMIN` (VIEW/CREATE but no MANAGE under current seed) → Draw button hidden.
- **Variation:** ACTIVE blanket within its validity window; agent = INTERNAL
- **Preconditions / Seed:** an ACTIVE blanket from TC-SADV-006 with remaining > 0; ≥1 active agent; know the numeric branch id
- **Steps:**
  1. Open detail; confirm status `ACTIVE`.
  2. Click "Draw Release" (visible only when `canManage()` and status ACTIVE).
  3. In the draw form type the **numeric Branch ID** into the "Branch ID" text input (C1 deviation — see D-3).
  4. In the **agent picker** search and select an active agent by `code — name`.
  5. In the lines table, keep line 1 included, set Draw Qty `40` (≤ remaining); uncheck line 2.
  6. Click "Create Release".
- **Test Data:** branchId "1"; agent "AG-001 — Inside Sales"; draw 40 of remaining 100.
- **Expected Result:** POST returns the generated `SalesOrderDto`; success toast "Release created" with the SO number; a "Open sales order" link to `/admin/sales-orders/uid/:soUid` appears; the blanket is re-fetched — line 1 drawn becomes `40`, remaining `60`, header `totalDrawnAmount` increases by `40·1500=60,000.00`; the SO is stamped `source_blanket_uid`.
- **Convention Assertions:** C1 (**agent chosen via picker by name**; SO uid shown only as a link, not typed) **and documented C1 deviation: Branch entered by hand-typed numeric id, not a picker (D-3)**; C2 (DTO unwrapped), C8 (money/qty formatting), C6 axe.
- **Negative / Edge:** include zero lines → "Include at least one line to draw."; missing branch → "Branch ID is required."; missing agent → "Agent is required." (all client-side before POST).

### TC-SADV-010 — Blanket draw: over-draw rejected (qty > remaining)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Blanket draw (`POST …/uid/{uid}/draw`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — role with MANAGE
- **Variation:** draw qty exceeds the line's remaining
- **Preconditions / Seed:** ACTIVE blanket, line remaining = 60
- **Steps:** UI — set Draw Qty `61` on a line with remaining 60. API — POST `qtyBase=61`.
- **Expected Result:** UI blocks with "…draw quantity exceeds remaining (60)."; if sent, API throws `ConflictException "Draw quantity 61 exceeds remaining 60 on blanket line …"` (HTTP 409); DB `chk_blanket_order_line_drawn` is the final guard. No SO created.
- **Convention Assertions:** C2 (409 envelope), C9 (no partial postings — transaction rolls back).
- **Negative / Edge:** draw exactly `60` (full remaining) succeeds; remaining becomes 0 (line functionally exhausted).

### TC-SADV-011 — Blanket draw: drawing the full remaining exhausts the line
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Blanket draw (`POST …/uid/{uid}/draw`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — role with MANAGE
- **Preconditions / Seed:** ACTIVE blanket, single line remaining = 60
- **Steps:** draw `60`; re-open detail.
- **Expected Result:** line remaining = `0` (rendered with warning style `text-warning`/`fw-semibold`); a second open of the draw form shows "All lines are fully drawn. No remaining quantity." and the Create Release button is disabled. Header stays `ACTIVE` (service does not auto-flip to `EXHAUSTED`).
- **Convention Assertions:** C2, C4 (empty draw-lines sub-state), C6 axe.
- **Negative / Edge:** attempting another draw on the fully-drawn line → over-draw conflict (remaining 0).

### TC-SADV-012 — Blanket draw: denied on non-ACTIVE blanket (CANCELLED)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Blanket draw (`POST …/uid/{uid}/draw`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — role with MANAGE
- **Variation:** blanket status `CANCELLED`
- **Preconditions / Seed:** a CANCELLED blanket (via TC-SADV-015)
- **Steps:** UI — open the CANCELLED blanket: Draw button is **not rendered** (only shown when `isActive()`). API — POST a draw anyway.
- **Expected Result:** API throws `ConflictException "Blanket … is not ACTIVE."` (409); no SO.
- **Convention Assertions:** C3/C9 (illegal transition rejected), C2 envelope.
- **Negative / Edge:** repeat against an `EXHAUSTED`-status header → same "not ACTIVE" rejection.

### TC-SADV-013 — Blanket draw: denied on expired blanket (now > validTo)
- **Type:** Manual | API
- **Priority:** P2
- **Module / Submodule:** Blanket draw (`POST …/uid/{uid}/draw`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — role with MANAGE
- **Variation:** ACTIVE status but `validTo` in the past
- **Preconditions / Seed:** an ACTIVE blanket whose `validTo` < today (seed with a back-dated window via API)
- **Steps:** POST a draw.
- **Expected Result:** `ConflictException "Blanket … has expired."` (409); no SO.
- **Convention Assertions:** C2, C8 (date boundary).
- **Negative / Edge:** `validTo == today` (boundary) — `now().isAfter(validTo)` is false, so draw is allowed on the last valid day.

### TC-SADV-014 — Blanket draw: multi-line draw across two lines in one release
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Blanket draw (`POST …/uid/{uid}/draw`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — role with MANAGE
- **Preconditions / Seed:** ACTIVE blanket with two lines, both remaining > 0
- **Steps:** include both lines, set valid draw quantities on each, create release.
- **Expected Result:** one SalesOrder with two lines; both blanket lines' drawn incremented; `totalDrawnAmount` = sum of both line contributions.
- **Convention Assertions:** C2, C8.
- **Negative / Edge:** if any single line over-draws, the whole release is rejected (transaction atomic) and neither line is decremented (C9).

### TC-SADV-015 — Blanket cancel: ACTIVE → CANCELLED (confirm dialog)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Blanket cancel (`/admin/blanket-orders/uid/:uid` · `DELETE /api/v1/blanket-orders/uid/{uid}`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — role with MANAGE; also as a role without MANAGE → Cancel button hidden
- **Preconditions / Seed:** an ACTIVE blanket
- **Steps:** open detail → "Cancel" → confirm card "Cancel this blanket order?" → "Confirm Cancel".
- **Expected Result:** DELETE succeeds; toast "Blanket order cancelled"; re-fetch shows status `CANCELLED` (red badge); Draw/Cancel buttons disappear (no longer ACTIVE). Already-drawn SOs are unaffected (per UI copy).
- **Convention Assertions:** C9 (soft lifecycle, not hard delete — `DELETE` verb sets status), C3, C6 axe.
- **Negative / Edge:** "Keep Agreement" dismisses without calling DELETE.

### TC-SADV-016 — Blanket cancel: already CANCELLED rejected
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Blanket cancel (`DELETE …/uid/{uid}`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — role with MANAGE
- **Steps:** DELETE a blanket that is already CANCELLED.
- **Expected Result:** `ConflictException "Blanket … is already CANCELLED."` (409).
- **Convention Assertions:** C9 (idempotency guard), C2.
- **Negative / Edge:** cancel an `EXHAUSTED`/fully-drawn-but-ACTIVE blanket — allowed (only already-CANCELLED is blocked).

### TC-SADV-017 — Blanket MANAGE permission gap (DEFECT D-1)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Blanket draw + cancel (`POST …/draw`, `DELETE …/uid/{uid}`)
- **Permission / Role:** `SALES.BLANKET.MANAGE` — runs as `ORG_ADMIN` (has VIEW/CREATE but, under V45 seed, **not MANAGE**)
- **Preconditions / Seed:** stock-seeded DB only (no extra grants)
- **Steps:**
  1. As `ORG_ADMIN`, open an ACTIVE blanket detail.
  2. Observe action buttons.
  3. Call `POST …/draw` and `DELETE …/uid/{uid}` directly.
- **Expected Result (current/expected-fail):** FE `canManage()` is false → **Draw Release and Cancel buttons are not rendered** for ORG_ADMIN; direct API calls return **403** because `SALES.BLANKET.MANAGE` is granted to no role. **This documents the defect** — after a fix grants MANAGE (or repoints the guard to `BLANKET.CLOSE`), buttons should appear and calls succeed; re-baseline this case.
- **Convention Assertions:** C3 (RBAC), C4 (button visibility tied to permission).
- **Negative / Edge:** `rootadmin` succeeds (superuser bypass), proving the endpoints work and the gap is purely a missing grant.

### TC-SADV-018 — Blanket detail: customer shown as raw id (DEFECT D-3 / C1 deviation)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Blanket detail (`/admin/blanket-orders/uid/:uid`)
- **Permission / Role:** `SALES.BLANKET.VIEW` — `ORG_ADMIN`
- **Steps:** open any blanket detail; inspect the metadata `dl`.
- **Expected Result (documents deviation):** "Customer ID" shows a **numeric id** (e.g. `42`) not the customer name — a C1 violation because the BlanketOrderDto carries no customer name/uid. The blanket's own `uid` correctly appears only in the URL, never in a label.
- **Convention Assertions:** C1 — partial: uid-in-URL-only holds for the blanket itself; **fails** for the related customer (id is surfaced). Log as D-3.
- **Negative / Edge:** same deviation on the list "Customer ID" column and the standing detail/list.

### TC-SADV-019 — Blanket detail: four states (loading/error) + read-only when not ACTIVE
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Blanket detail (`/admin/blanket-orders/uid/:uid`)
- **Permission / Role:** `SALES.BLANKET.VIEW` — `ORG_ADMIN`
- **Steps:** 1. open detail (loading spinner → idle); 2. intercept get → 500 (error "Could not load blanket order."); 3. open a CANCELLED blanket.
- **Expected Result:** loading and error states render distinctly; CANCELLED blanket shows lines read-only with **no action buttons**.
- **Convention Assertions:** C4, C6 axe.
- **Negative / Edge:** unknown uid → NotFound surfaces as the error state.

### TC-SADV-020 — Standing Orders list: populated + pagination + four-state
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Standing Orders (`/admin/standing-orders` · `GET /api/v1/standing-orders?companyId=&page=&size=`)
- **Permission / Role:** `SALES.STANDING.VIEW` — `ORG_ADMIN`; also NO-PERMISSION → nav hidden + guard forbids
- **Preconditions / Seed:** ≥21 standing orders for 2 pages
- **Steps:** open list; verify columns (Order #, Customer ID, Frequency, Status, Start Date, Next Run, Currency, Actions); exercise paginator; axe.
- **Expected Result:** rows render with frequency + status badge + next-run; paginator full set; envelope+meta.
- **Convention Assertions:** C2, C4, C5, C6, C7, C8 (ISO dates).
- **Negative / Edge:** empty company → "No standing orders yet." (+ "Create the first one." when `canCreate()`).

### TC-SADV-021 — Standing list: error + forbidden + multi-company switch
- **Type:** Automated (Playwright) | Both
- **Priority:** P2
- **Module / Submodule:** Standing Orders (`/admin/standing-orders`)
- **Permission / Role:** `SALES.STANDING.VIEW` — `ORG_ADMIN`; denied as `STOREKEEPER` (API 403)
- **Steps:** 1. intercept list → 500 (error state); 2. as STOREKEEPER call API → 403; 3. as ORG_ADMIN with ≥2 companies, switch company and confirm scoping.
- **Expected Result:** error "Could not load standing orders…" `role=alert`; 403 for STOREKEEPER; data re-scopes on company switch.
- **Convention Assertions:** C3, C4, C7.
- **Negative / Edge:** `forbidden` template branch present though guard usually blocks pre-render.

### TC-SADV-022 — Standing create: MONTHLY recurring order (uid-pickers)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Standing create (`/admin/standing-orders/create` · `POST /api/v1/standing-orders`)
- **Permission / Role:** `SALES.STANDING.CREATE` — `ORG_ADMIN`; also `SALES_REP` lacking CREATE → route/nav forbidden
- **Variation:** frequency MONTHLY; customer BUSINESS+CREDIT_ACCOUNT; product GOODS; branch = default
- **Preconditions / Seed:** active customer/product/unit/branch for the company
- **Steps:**
  1. Navigate create; company `<select>` defaults.
  2. Pick **branch** via `<app-uid-picker>` (by name); pick **customer** via picker (by name).
  3. Currency TZS; Frequency = Monthly; Start Date = today; End Date blank.
  4. Line 1: pick **product** + **unit** via pickers; qty `10`, qtyBase `10`, unit price `1200.00`.
  5. Submit.
- **Test Data:** customer "Beta Traders Ltd"; product "MILK-1L"; qty 10 @ 1200.00; MONTHLY.
- **Expected Result:** POST creates standing order; toast with order number; navigates to `/admin/standing-orders/uid/:uid`; status `ACTIVE`; frequency `MONTHLY`; `nextRunDate` populated; line listed.
- **Convention Assertions:** C1 (branch/customer/product/unit all via `<app-uid-picker>` **by name**; uids stored under the hood, only the new order uid appears in the URL), C2, C7, C8.
- **Negative / Edge:** see TC-SADV-024.

### TC-SADV-023 — Standing create: each StandingFrequency value
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Standing create (`POST /api/v1/standing-orders`)
- **Permission / Role:** `SALES.STANDING.CREATE` — `ORG_ADMIN`
- **Variation:** frequency ∈ {DAILY, WEEKLY, BIWEEKLY, MONTHLY}
- **Steps:** create four standing orders, one per frequency option (UI select offers exactly Daily/Weekly/Bi-Weekly/Monthly).
- **Expected Result:** all created `ACTIVE`; on later trigger, `nextRunDate` advances by the correct cadence (DAILY +1d, WEEKLY +1w, BIWEEKLY +2w, MONTHLY +1mo) — verified in TC-SADV-029.
- **Convention Assertions:** C8 (date arithmetic), C2.
- **Negative / Edge:** no other frequency value is accepted (DB `chk_standing_order_frequency`).

### TC-SADV-024 — Standing create: validation (required fields, per-line)
- **Type:** Automated (Playwright) | Both
- **Priority:** P1
- **Module / Submodule:** Standing create (`/admin/standing-orders/create`)
- **Permission / Role:** `SALES.STANDING.CREATE` — `ORG_ADMIN`
- **Steps:**
  1. Submit with no branch → "Branch is required."; then no customer → "Customer is required."
  2. Blank currency → "Currency is required."; blank start date → "Start date is required."
  3. Line missing product → "Line 1: product is required."; missing unit → "Line 1: unit is required."
  4. Line qty `0` or non-numeric → "Line 1: enter a valid quantity."; price negative → "Line 1: enter a valid unit price."
- **Expected Result:** each blocks submit before POST; server bean-validation (`@NotBlank/@NotNull/@DecimalMin`) returns 400 on a crafted bad body (`qty`/`qtyBase` ≥0.0001, `unitPriceAmount` ≥0.00).
- **Convention Assertions:** C2 (400 envelope), C6 axe.
- **Negative / Edge:** `endDate` optional — omit it and creation still succeeds.

### TC-SADV-025 — Standing detail: action visibility by status
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Standing detail (`/admin/standing-orders/uid/:uid`)
- **Permission / Role:** `SALES.STANDING.MANAGE` (controller); FE `canManage()` checks it — runs as role with MANAGE; also as a role without MANAGE → no action buttons
- **Variation:** status ACTIVE vs PAUSED vs CANCELLED
- **Preconditions / Seed:** three standing orders in ACTIVE, PAUSED, CANCELLED
- **Steps:** open each; record visible buttons.
- **Expected Result:** ACTIVE → **Pause**, **Trigger Now**, **Cancel**; PAUSED → **Resume**, **Cancel**; CANCELLED → **no action buttons** (read-only). All gated by `canManage()`.
- **Convention Assertions:** C3/C4 (buttons match status × permission), C6 axe.
- **Negative / Edge:** with MANAGE absent (current seed), NO action buttons appear for any status (ties to D-1 / TC-SADV-033).

### TC-SADV-026 — Standing pause: ACTIVE → PAUSED
- **Type:** Automated (Playwright) | Both
- **Priority:** P1
- **Module / Submodule:** Standing pause (`POST /api/v1/standing-orders/uid/{uid}/pause`)
- **Permission / Role:** `SALES.STANDING.MANAGE` — role with MANAGE
- **Preconditions / Seed:** an ACTIVE standing order
- **Steps:** open detail → "Pause".
- **Expected Result:** 200; toast "Standing order paused"; re-load shows `PAUSED` (warning badge); now only Resume + Cancel show; the scheduler will skip it (only ACTIVE due orders generate).
- **Convention Assertions:** C9 (lifecycle), C3, C2.
- **Negative / Edge:** see TC-SADV-027 (illegal pause).

### TC-SADV-027 — Standing pause: illegal from non-ACTIVE
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Standing pause (`POST …/pause`)
- **Permission / Role:** `SALES.STANDING.MANAGE` — role with MANAGE
- **Variation:** status PAUSED, then CANCELLED
- **Steps:** POST pause on a PAUSED order; then on a CANCELLED order.
- **Expected Result:** both → `ConflictException "Standing order … is not ACTIVE."` (409). UI never offers Pause for these statuses, so this is the API guard backstop.
- **Convention Assertions:** C9 (illegal transition rejected), C2.
- **Negative / Edge:** n/a.

### TC-SADV-028 — Standing resume: PAUSED → ACTIVE (and illegal resume)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Standing resume (`POST …/resume`)
- **Permission / Role:** `SALES.STANDING.MANAGE` — role with MANAGE
- **Steps:** 1. resume a PAUSED order (UI "Resume"); 2. POST resume on an ACTIVE order; 3. POST resume on a CANCELLED order.
- **Expected Result:** (1) 200, toast "Standing order resumed", status `ACTIVE`; (2)&(3) `ConflictException "Standing order … is not PAUSED."` (409).
- **Convention Assertions:** C9, C3, C2.
- **Negative / Edge:** resume restores scheduler eligibility on the next due run.

### TC-SADV-029 — Standing trigger now: generates a SalesOrder, advances nextRunDate
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Standing trigger (`POST /api/v1/standing-orders/uid/{uid}/trigger`)
- **Permission / Role:** `SALES.STANDING.MANAGE` — role with MANAGE
- **Variation:** frequency MONTHLY (assert +1 month); repeat conceptually for DAILY/WEEKLY/BIWEEKLY
- **Preconditions / Seed:** an ACTIVE standing order with `nextRunDate = N`, ≥1 line
- **Steps:** open detail → "Trigger Now".
- **Expected Result:** 200 returns the updated `StandingOrderDto`; toast "Order generated — check Sales Orders for the new order"; a new SalesOrder is created (stamped `source_standing_uid`) with the template's lines; `nextRunDate` advances to `N + frequency`; status stays `ACTIVE`; an outbox `STANDING_ORDER_GENERATED` event is emitted. The "View Generated Sales Orders" link navigates to `/admin/sales-orders`.
- **Convention Assertions:** C2 (DTO unwrapped), C8 (date math), C6 axe.
- **Negative / Edge:** trigger does not consume any quota; can be triggered repeatedly while ACTIVE (each advances nextRunDate again).

### TC-SADV-030 — Standing trigger: illegal on non-ACTIVE
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Standing trigger (`POST …/trigger`)
- **Permission / Role:** `SALES.STANDING.MANAGE` — role with MANAGE
- **Steps:** POST trigger on a PAUSED order; then on a CANCELLED order.
- **Expected Result:** both → `ConflictException "Standing order … is not ACTIVE."` (409); no SO created. UI hides Trigger Now unless ACTIVE.
- **Convention Assertions:** C9, C2.
- **Negative / Edge:** n/a.

### TC-SADV-031 — Standing cancel: ACTIVE/PAUSED → CANCELLED; already-CANCELLED rejected
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Standing cancel (`DELETE /api/v1/standing-orders/uid/{uid}`)
- **Permission / Role:** `SALES.STANDING.MANAGE` — role with MANAGE
- **Steps:** 1. cancel an ACTIVE order (UI "Cancel"); 2. cancel a PAUSED order; 3. DELETE an already-CANCELLED order.
- **Expected Result:** (1)&(2) 200, toast "Standing order cancelled", status `CANCELLED`, all action buttons gone; (3) `ConflictException "Standing order … is already CANCELLED."` (409).
- **Convention Assertions:** C9 (soft lifecycle via DELETE verb; idempotency guard), C3, C2.
- **Negative / Edge:** the scheduler permanently skips CANCELLED orders.

### TC-SADV-032 — Standing scheduler `generateDue()` is backend-only (no UI trigger)
- **Type:** Manual | API
- **Priority:** P3
- **Module / Submodule:** Standing scheduler (`StandingOrderServiceImpl.generateDue`, `@Scheduled(cron="0 0 0 * * *")`)
- **Permission / Role:** n/a (internal scheduled job; not exposed via controller)
- **Preconditions / Seed:** ≥1 ACTIVE standing order with `next_run_date <= today` and ≥1 with a future date and ≥1 PAUSED/CANCELLED
- **Steps:** advance/seed dates so an order is due; run the job (wait for cron or invoke in a test harness).
- **Expected Result:** only ACTIVE orders with `next_run_date <= today` generate a SO and advance `nextRunDate`; PAUSED/CANCELLED and future-dated orders are skipped; failures on one order are logged and do not abort the batch; outbox `STANDING_ORDER_GENERATED` emitted per generated order.
- **Convention Assertions:** C7 (per-company scoping preserved in generated SOs), C9 (append-only generation).
- **Negative / Edge:** confirm there is **no UI button** for the batch — only per-order "Trigger Now" exists.

### TC-SADV-033 — Standing MANAGE permission gap (DEFECT D-1)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Standing pause/resume/cancel/trigger
- **Permission / Role:** `SALES.STANDING.MANAGE` — runs as `ORG_ADMIN` (has VIEW/CREATE but, under V45 seed, **not MANAGE**)
- **Steps:** as `ORG_ADMIN`, open a standing-order detail; observe buttons; call each manage endpoint directly.
- **Expected Result (current/expected-fail):** `canManage()` false → **no Pause/Resume/Cancel/Trigger buttons** render; direct API calls return **403** (no role holds `SALES.STANDING.MANAGE`; V45 seeds the unused `SALES.STANDING.GENERATE` instead). Documents the defect; `rootadmin` succeeds. Re-baseline once a fix grants MANAGE (or repoints guards to `STANDING.GENERATE`).
- **Convention Assertions:** C3, C4.
- **Negative / Edge:** verify the orphan `SALES.STANDING.GENERATE` is referenced by no controller.

### TC-SADV-034 — Pricing Rules screen: VIEW gate, tabs, company context
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Pricing Rules (`/admin/pricing-rules` · `/api/v1/pricing-rules/*`)
- **Permission / Role:** `SALES.PRICING.RULE.VIEW` — `ORG_ADMIN`; also NO-PERMISSION → nav hidden + route guard forbids
- **Steps:** open `/admin/pricing-rules`; confirm two tabs "Price Tiers" and "Customer Prices" (no Promotions tab — D-2); company `<select>` loads first company; reference data (products, price-lists, customers) loaded.
- **Expected Result:** screen renders with both tabs; default tab Price Tiers; company selector present when >1 company; axe clean.
- **Convention Assertions:** C3 (guard), C4 (company-load loading/error states), C6 axe, **D-2 noted (no Promotions tab)**.
- **Negative / Edge:** company-load failure shows error; switching company clears tier/customer-price filters and rows.

### TC-SADV-035 — Price Tier: create quantity-break tier (pickers, by name)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Price Tiers (`/admin/pricing-rules` Tiers tab · `POST /api/v1/pricing-rules/tiers`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`; also VIEW-only CUSTOM role → create form/buttons hidden (`canManage()` false)
- **Variation:** product = GOODS; price list = the default list
- **Preconditions / Seed:** a product and a price list for the company
- **Steps:**
  1. Tiers tab → open create form ("New tier" toggle, only when `canManage()`).
  2. Pick **product** via `<app-uid-picker>` (by name); pick **price list** via picker (by name).
  3. Min Qty `10`; Unit Price `950.00`; Currency TZS; save.
- **Test Data:** product "WIDGET-A"; price list "Standard"; minQty 10 @ 950.00.
- **Expected Result:** POST `{companyUid, productUid, priceListUid, minQty, unitPriceAmount, currency}` succeeds; toast "Price tier created — Min qty: 10"; the tier list (filtered to the same product+price-list) reloads and includes the row with status `ACTIVE`.
- **Convention Assertions:** C1 (product + price-list chosen via picker **by name**; uids under the hood; no raw uid typed/shown except tier uid in any internal links), C2, C7, C8 (currency formatting).
- **Negative / Edge:** see TC-SADV-036/037.

### TC-SADV-036 — Price Tier: duplicate min-qty break rejected
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Price Tiers (`POST /api/v1/pricing-rules/tiers`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Variation:** same `(product, priceList, minQty)` as an existing tier
- **Preconditions / Seed:** a tier exists with minQty 10 for product+list
- **Steps:** create another tier with the same product, price list, and minQty 10.
- **Expected Result:** `ConflictException "A tier with min_qty 10 already exists for this product+price_list."` (409); DB `uq_price_tier_break` is the backstop; surfaced as the form error.
- **Convention Assertions:** C2 (409 envelope `errors[]`).
- **Negative / Edge:** a different minQty (e.g. 25) for the same product+list is accepted.

### TC-SADV-037 — Price Tier: client + server validation
- **Type:** Automated (Playwright) | Both
- **Priority:** P2
- **Module / Submodule:** Price Tiers (`/admin/pricing-rules` Tiers tab)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Steps:** submit with: no product → "Product is required."; no price list → "Price list is required."; minQty `0`/blank/non-numeric → "Minimum quantity must be a positive number."; price negative → "Unit price must be a non-negative number."; blank currency → "Currency is required."
- **Expected Result:** each blocks submit; server `@DecimalMin("0.000001")` on minQty and `@DecimalMin("0")` on price + `@NotBlank` on currency/uids return 400 on a crafted bad body.
- **Convention Assertions:** C2 (400), C6 axe.
- **Negative / Edge:** minQty `0.000001` (boundary) accepted; price `0` accepted.

### TC-SADV-038 — Price Tier: list requires product + price-list filter
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Price Tiers list (`GET /api/v1/pricing-rules/tiers?companyId=&productId=&priceListId=`)
- **Permission / Role:** `SALES.PRICING.RULE.VIEW` — `ORG_ADMIN`
- **Steps:** Tiers tab; pick product + price list to load; observe states.
- **Expected Result:** with both filters chosen, `loadTiers()` resolves uid→id and calls the API; loading → idle list or empty ("idle + 0 rows"); the response is a **plain array (no paginator)**. No load until both filters chosen.
- **Convention Assertions:** C4 (loading/empty/error/forbidden signals on `tierState`), C2 (plain list, **no** PageMeta — so no `<app-paginator>` here), C6 axe.
- **Negative / Edge:** API 403 sets `tierState='forbidden'`; a non-403 error sets `'error'`.

### TC-SADV-039 — Price Tier: deactivate (soft) removes from list
- **Type:** Automated (Playwright) | Both
- **Priority:** P1
- **Module / Submodule:** Price Tiers deactivate (`DELETE /api/v1/pricing-rules/tiers/uid/{uid}`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`; denied as VIEW-only → 403
- **Preconditions / Seed:** an ACTIVE tier in the loaded list
- **Steps:** click deactivate on the tier row.
- **Expected Result:** DELETE sets status `INACTIVE` (soft, not hard delete); toast "Price tier deactivated"; the row is removed from the in-memory list; a re-fetch by `getTierByUid` would show `INACTIVE`.
- **Convention Assertions:** C9 (soft-delete via MasterStatus, append-only), C3, C2.
- **Negative / Edge:** deactivating again is a no-op/edge — status already INACTIVE.

### TC-SADV-040 — Price Tier: cross-tenant scope denied
- **Type:** API | Manual
- **Priority:** P1
- **Module / Submodule:** Price Tiers (`POST /tiers`, `GET /tiers`, `DELETE /tiers/uid/{uid}`)
- **Permission / Role:** `SALES.PRICING.RULE.*` (create/manage `@perm.has`, get scoped `@perm.scoped(#uid,'pricetier',…)`) — tenant A `ORG_ADMIN`
- **Steps:** as A, create a tier with B's `productUid` (resolves to B's company) and/or GET a tier uid belonging to B.
- **Expected Result:** `scopeGuard.assertCanActIn` rejects acting in B's company → 403/forbidden; B's tier is invisible to A.
- **Convention Assertions:** C7 (tenant isolation), C3.
- **Negative / Edge:** `rootadmin` sees both (superuser) — sanity.

### TC-SADV-041 — Customer Price: create contract price (pickers)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customer Prices (`/admin/pricing-rules` Customer Prices tab · `POST /api/v1/pricing-rules/customer-prices`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`; VIEW-only → form hidden
- **Variation:** customer = BUSINESS+CREDIT_ACCOUNT; product = GOODS; with effective window
- **Preconditions / Seed:** active customer + product for the company
- **Steps:**
  1. Customer Prices tab → open create form.
  2. Pick **customer** via picker (by name); pick **product** via picker (by name).
  3. Unit Price `880.00`; Currency TZS; Effective From today; Effective To today+180d; save.
- **Test Data:** customer "Beta Traders Ltd"; product "WIDGET-A"; 880.00; window today..+180d.
- **Expected Result:** POST `{companyUid, customerUid, productUid, unitPriceAmount, currency, effectiveFrom, effectiveTo}` succeeds; toast "Customer price created — TZS 880.00"; the customer-price list (filtered by that customer) reloads with the row, status `ACTIVE`.
- **Convention Assertions:** C1 (customer + product via picker **by name**), C2, C7, C8 (money + ISO dates).
- **Negative / Edge:** omit both effective dates → open-ended price (allowed; columns null).

### TC-SADV-042 — Customer Price: unique (customer, product) scope rejected
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Customer Prices (`POST /customer-prices`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Variation:** second price for the same `(customer, product)`
- **Preconditions / Seed:** a customer price already exists for customer C + product P (any status — `uq_customer_price_scope` ignores status)
- **Steps:** create another customer price for the same C + P.
- **Expected Result:** rejected by DB `uq_customer_price_scope (customer_id, product_id)` → conflict surfaced as the form error; no new row. (Important: because the unique is status-agnostic, you **cannot create a fresh price after deactivating** the old one for the same C+P — note as a usability edge.)
- **Convention Assertions:** C2 (409/constraint envelope), C9 (soft-deactivate does not free the unique slot).
- **Negative / Edge:** different product for the same customer is accepted.

### TC-SADV-043 — Customer Price: effective window + amount validation
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Customer Prices (`/admin/pricing-rules` CP tab · `POST /customer-prices`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Steps:** submit with: no customer → "Customer is required."; no product → "Product is required."; price negative → "Unit price must be a non-negative number."; blank currency → "Currency is required."; (API) effectiveTo < effectiveFrom → DB `chk_customer_price_window`.
- **Expected Result:** client messages block submit; crafted bad window/amount returns constraint/400 at the API.
- **Convention Assertions:** C2, C8 (date window), C6 axe.
- **Negative / Edge:** price `0` accepted (`@DecimalMin("0")`); equal from==to accepted.

### TC-SADV-044 — Customer Price: list (plain, no paginator) + four-state + deactivate
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customer Prices list/deactivate (`GET /customer-prices?companyId=&customerId=`, `DELETE /customer-prices/uid/{uid}`)
- **Permission / Role:** `SALES.PRICING.RULE.VIEW` (list) + `MANAGE` (deactivate) — `ORG_ADMIN`
- **Steps:** 1. pick a customer to load prices (loading→idle/empty); 2. intercept → 500 (error); 3. as VIEW-only call DELETE → 403; 4. as MANAGE deactivate a row.
- **Expected Result:** list is a **plain array (no `<app-paginator>`)**; states map to `cpState` loading/idle(empty)/error/forbidden; deactivate sets `INACTIVE`, toast "Customer price deactivated", row removed in memory.
- **Convention Assertions:** C4, C2 (no PageMeta), C9 (soft), C3, C6 axe.
- **Negative / Edge:** no customer chosen → no load.

### TC-SADV-045 — Pricing MANAGE vs VIEW: action visibility & 403
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Pricing Rules (tiers + customer-prices)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` vs `VIEW` — runs as ORG_ADMIN (has both), and as a CUSTOM role with **VIEW only**
- **Steps:** as VIEW-only: open Pricing Rules; observe no "New tier"/"New customer price" forms or deactivate controls (`canManage()` false); then call `POST /tiers` and `DELETE /customer-prices/uid/{uid}` directly.
- **Expected Result:** management UI hidden for VIEW-only; direct create/deactivate calls return **403** (`@perm.has('SALES.PRICING.RULE.MANAGE')`). VIEW-only can still read/list.
- **Convention Assertions:** C3 (RBAC by code), C4 (control visibility).
- **Negative / Edge:** NO-PERMISSION user gets the route guard forbidden before the screen renders.

### TC-SADV-046 — Promotion: create PRODUCT-target PERCENT_DISCOUNT (API-only, D-2)
- **Type:** Manual | API
- **Priority:** P1
- **Module / Submodule:** Promotions (`POST /api/v1/pricing-rules/promotions`) — **backend-only, no UI (D-2)**
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`; denied as VIEW-only (403)
- **Variation:** target PRODUCT; effect PERCENT_DISCOUNT
- **Preconditions / Seed:** a product P
- **Steps:** POST `{companyUid, code:"SUMMER10", name:"Summer 10%", target:"PRODUCT", targetProductUid:P, effect:"PERCENT_DISCOUNT", effectValue:10, effectiveFrom:today, effectiveTo:today+30d, priority:5}`.
- **Expected Result:** 201 with `PromotionDto` (status `ACTIVE`, `targetProductId` resolved). No UI exists to view it; verify via `GET /promotions/uid/{uid}` and `GET /promotions?companyId=`.
- **Convention Assertions:** C2 (DTO/envelope; `listPromotions` is paginated `ApiResponse<List>`+meta), C7 (company scope), C8 (ISO dates).
- **Negative / Edge:** PERCENT_DISCOUNT value `101` → `IllegalArgumentException "effect_value must be 0–100 for PERCENT_DISCOUNT"` (and DB `chk_promotion_pct`).

### TC-SADV-047 — Promotion: PRODUCT target requires targetProductUid
- **Type:** API | Manual
- **Priority:** P1
- **Module / Submodule:** Promotions (`POST /promotions`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Steps:** POST `target=PRODUCT` with no/blank `targetProductUid`.
- **Expected Result:** `IllegalArgumentException "targetProductUid required when target=PRODUCT"` (400); DB `chk_promotion_target_ref` is the backstop.
- **Convention Assertions:** C2 (400 envelope).
- **Negative / Edge:** target CATEGORY with no `targetCategory` → rejected by DB `chk_promotion_target_ref`.

### TC-SADV-048 — Promotion: each PromotionTarget value
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Promotions (`POST /promotions`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Variation:** target ∈ {PRODUCT, CATEGORY, ALL}
- **Steps:** create one promotion per target — PRODUCT (with `targetProductUid`), CATEGORY (with `targetCategory`, e.g. product `category` "Beverages"), ALL (neither product nor category).
- **Expected Result:** all 201; the CATEGORY one matches products whose `products.category` (added by V42) equals the value; ALL applies to every product.
- **Convention Assertions:** C2.
- **Negative / Edge:** PRODUCT without product / CATEGORY without category → rejected (see TC-SADV-047).

### TC-SADV-049 — Promotion: each PromotionEffect value
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Promotions (`POST /promotions`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Variation:** effect ∈ {PERCENT_DISCOUNT, AMOUNT_DISCOUNT, OVERRIDE_PRICE}
- **Steps:** create three promotions, one per effect, with valid effectValue (e.g. 10 / 250.00 / 999.00).
- **Expected Result:** all 201; PERCENT_DISCOUNT clamped to 0–100; AMOUNT_DISCOUNT and OVERRIDE_PRICE accept any `effectValue ≥ 0` (DB `chk_promotion_effect_value`).
- **Convention Assertions:** C2, C8.
- **Negative / Edge:** negative `effectValue` rejected by `@DecimalMin("0")` + DB check.

### TC-SADV-050 — Promotion: unique code per company + window check
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Promotions (`POST /promotions`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Steps:** 1. create promo code "X"; create another "X" same company → DB `uq_promotion_company_code` conflict. 2. POST with `effectiveTo` < `effectiveFrom` → DB `chk_promotion_window`.
- **Expected Result:** duplicate code → 409/constraint; bad window → 400/constraint; neither row persists.
- **Convention Assertions:** C2.
- **Negative / Edge:** same code in a **different** company is allowed (unique is per company).

### TC-SADV-051 — Promotion: get + paginated list + cross-tenant scope
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Promotions (`GET /promotions/uid/{uid}`, `GET /promotions?companyId=&page=&size=`)
- **Permission / Role:** `SALES.PRICING.RULE.VIEW` (list `@perm.has`, get `@perm.scoped(#uid,'promotion',…)`) — `ORG_ADMIN`; denied as STOREKEEPER (403)
- **Steps:** seed ≥21 promotions; GET list with size 20 (assert `meta`); GET one by uid; as tenant A GET tenant B's promo uid.
- **Expected Result:** list returns `ApiResponse<List>` + PageMeta (paginated); get returns `PromotionDto`; cross-tenant get → 403 (scope guard).
- **Convention Assertions:** C2 (envelope+meta), C5 (this list **is** paginated — though no UI consumes it), C7.
- **Negative / Edge:** unknown uid → NotFound.

### TC-SADV-052 — Promotion: deactivate (soft)
- **Type:** API | Manual
- **Priority:** P2
- **Module / Submodule:** Promotions (`DELETE /promotions/uid/{uid}`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`; denied as VIEW-only (403)
- **Steps:** DELETE a promotion uid.
- **Expected Result:** status set `INACTIVE` (soft); `GET /promotions/uid/{uid}` shows `INACTIVE`; record not hard-deleted.
- **Convention Assertions:** C9 (soft-delete via MasterStatus), C3.
- **Negative / Edge:** deactivating an already-INACTIVE promo is a no-op edge.

### TC-SADV-053 — PriceSource diagnostic stamped on generated/priced lines
- **Type:** Manual | API
- **Priority:** P3
- **Module / Submodule:** Pricing resolution (sales_order_lines / sales_invoice_lines `price_source` column added by V42; enum `PriceSource`)
- **Permission / Role:** sales line creation perms (out of this controller set) — observed effect of pricing rules
- **Preconditions / Seed:** for one product+customer set up, in priority order: an ACTIVE customer price, an ACTIVE promotion, an ACTIVE tier, and a plain list price; plus a product with none of these
- **Steps:** create sales-order/invoice lines for each scenario and inspect the stored `price_source`.
- **Expected Result:** the line's `price_source` reflects which rule won — `CUSTOMER_PRICE` > `PROMOTION` > `TIER` > `LIST_PRICE` > `NONE` (diagnostic only; "never used in financial math" per enum doc). Confirms the rules feed real pricing.
- **Convention Assertions:** C8 (money correctness), C9 (diagnostic is descriptive, not a posting).
- **Negative / Edge:** product with no rule and no list price → `NONE`. (This is observational; exact resolver precedence should be re-confirmed against the line-pricing service when those cases are authored.)

### TC-SADV-054 — SERVICE product variation: tier/customer-price on a SERVICE product
- **Type:** API | Manual
- **Priority:** P3
- **Module / Submodule:** Price Tiers / Customer Prices (`POST /tiers`, `POST /customer-prices`)
- **Permission / Role:** `SALES.PRICING.RULE.MANAGE` — `ORG_ADMIN`
- **Variation:** product = SERVICE (non-stockable)
- **Preconditions / Seed:** a SERVICE product
- **Steps:** create a price tier and a customer price referencing the SERVICE product.
- **Expected Result:** accepted — pricing rules are product-type-agnostic (no stockable constraint on tiers/customer-prices); rows created `ACTIVE`.
- **Convention Assertions:** C2, C7.
- **Negative / Edge:** blanket/standing lines reference any product by id; confirm a SERVICE product can be committed/recurred (no stockable check in these services).

### TC-SADV-055 — Nav visibility across roles (Sales advanced group)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Shell nav (Sales group: Blanket/Standing/Pricing)
- **Permission / Role:** `SALES.BLANKET.VIEW`, `SALES.STANDING.VIEW`, `SALES.PRICING.RULE.VIEW`
- **Steps:** log in as: ORG_ADMIN (all three VIEW perms); a CUSTOM role with only `SALES.PRICING.RULE.VIEW`; a NO-PERMISSION user.
- **Expected Result:** ORG_ADMIN sees all three nav items; CUSTOM sees only "Pricing Rules"; NO-PERMISSION sees none of the three (and the wider Sales group collapses if empty).
- **Convention Assertions:** C3 (nav driven by permission code), C4 (empty-nav handling), C6 axe.
- **Negative / Edge:** direct deep-link to a hidden route is blocked by the matching `requirePermission(...)` guard.

### TC-SADV-056 — Branch context: act in an unassigned branch denied (X-Branch-Uid)
- **Type:** Manual | API
- **Priority:** P2
- **Module / Submodule:** Blanket/Standing create + draw/trigger (company/branch scoped)
- **Permission / Role:** `SALES.BLANKET.CREATE` / `SALES.STANDING.CREATE` (+ MANAGE) — a user assigned to branch B1 only, acting with `X-Branch-Uid` of B2
- **Variation:** multi-branch company; user assigned to ONE branch; non-default target branch
- **Steps:** as the B1-only user, set active branch to B2 (header) and attempt to create/draw/trigger referencing B2.
- **Expected Result:** denied — scoping rejects acting in an unassigned branch; no record created. A user assigned to B2 (or ALL) succeeds.
- **Convention Assertions:** C7 (branch scoping), C3.
- **Negative / Edge:** default vs non-default branch behaves identically for scoping (no "branch type" concept exists); switching to an assigned branch succeeds.
