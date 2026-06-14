# Test Cases — Masters: Parties (Customer / Supplier / Agent / Other Party)

Exhaustive functional + convention test cases for the **Party Masters** domain: Customer, Supplier,
Agent, and Other Party administration. Covers CRUD, list/search, archive/restore lifecycle, branch
associations, every entity-type/sub-kind enum variation that changes behaviour, RBAC (allowed +
denied), multi-tenancy/branch scoping, the four screen states, pagination, and the C1–C9 conventions.

## Modules / submodules covered

| Submodule | Controller (base path) | Frontend routes (component) |
|---|---|---|
| Customers | `CustomerController` (`/api/v1/customers`) | `/admin/customers` (`CustomerListComponent`), `/admin/customers/uid/:uid` (`CustomerDetailComponent`) |
| Suppliers | `SupplierController` (`/api/v1/suppliers`) | `/admin/suppliers` (`SupplierListComponent`), `/admin/suppliers/uid/:uid` (`SupplierDetailComponent`) |
| Sales Agents | `AgentController` (`/api/v1/agents`) | `/admin/agents` (`AgentListComponent`), `/admin/agents/uid/:uid` (`AgentDetailComponent`) |
| Other Parties | `OtherPartyController` (`/api/v1/other-parties`) | `/admin/other-parties` (`OtherPartyListComponent`), `/admin/other-parties/uid/:uid` (`OtherPartyDetailComponent`) |

Shell nav items (label → route → gating permission), from `shell.component.ts`:
- Customers → `/admin/customers` → `CUSTOMER.VIEW`
- Suppliers → `/admin/suppliers` → `SUPPLIER.VIEW`
- Sales Agents → `/admin/agents` → `AGENT.VIEW`
- Other Parties → `/admin/other-parties` → `OTHERPARTY.VIEW`

### Endpoints in scope (verified from the four controllers)
Each resource `<r>` ∈ {customers, suppliers, agents, other-parties} exposes the SAME shape:
- `GET /api/v1/<r>?companyId=&q=&page=&size=` — list/search (paged, `ApiResponse<List<…>>` + `meta`)
- `GET /api/v1/<r>/uid/{uid}` — get one
- `POST /api/v1/<r>` — create → **201 CREATED**
- `PUT /api/v1/<r>/uid/{uid}` — update
- `PUT /api/v1/<r>/uid/{uid}/archive` — archive → **204 NO_CONTENT**
- `PUT /api/v1/<r>/uid/{uid}/restore` — restore → **204 NO_CONTENT**
- `GET /api/v1/<r>/uid/{uid}/branches` — list branch associations
- `POST /api/v1/<r>/uid/{uid}/branches` — assign branch → **201 CREATED**
- `DELETE /api/v1/<r>/uid/{uid}/branches/{branchUid}` — remove branch → **204 NO_CONTENT**

## Permission codes in scope (exact `@PreAuthorize` / seeded in `V2__parties.sql`)

| Code | Used on |
|---|---|
| `CUSTOMER.VIEW` | customer list, get, listBranches |
| `CUSTOMER.MANAGE` | customer create (`@perm.has`), update/archive/restore (`@perm.scoped(#uid,'customer',…)`) |
| `SUPPLIER.VIEW` | supplier list, get, listBranches |
| `SUPPLIER.MANAGE` | supplier create / update / archive / restore |
| `AGENT.VIEW` | agent list, get, listBranches |
| `AGENT.MANAGE` | agent create / update / archive / restore |
| `OTHERPARTY.VIEW` | other-party list, get, listBranches |
| `OTHERPARTY.MANAGE` | other-party create / update / archive / restore |
| `PARTY.BRANCH.ASSIGN` | assignBranch / removeBranch on ALL four resources (scoped to the party's uid) |

Notes:
- **Create** uses `@perm.has('<R>.MANAGE')` (global). **Update/archive/restore/branch** use `@perm.scoped(#uid,'<resource>','<perm>')` — permission AND company-scope on the loaded entity are both checked.
- `ORG_ADMIN` is additively granted all nine parties permissions in `V2__parties.sql`. `rootadmin` bypasses all checks and is cross-tenant.
- The service layer ALSO calls `scopeGuard.assertCanActIn(...)` on the entity's company for every read/write — uid is NOT authorization (cross-company access via a guessed uid is denied).

## Enum values (verified) and behaviour drivers

| Enum | Values | Behaviour effect |
|---|---|---|
| `PartyType` | `INDIVIDUAL`, `BUSINESS` | BUSINESS **requires TIN** (BR-PARTY-04). DB `chk_<r>_party_type`. |
| `CustomerKind` | `CASH_WALK_IN`, `CREDIT_ACCOUNT` | `creditLimit` (`MoneyDto`) + `paymentTermsDays` only meaningful/shown for `CREDIT_ACCOUNT`; UI hides them for `CASH_WALK_IN`. DB `chk_customer_kind`, `chk_customer_credit_pair` (amount+currency must both be set or both null). |
| `SupplierKind` | `GOODS`, `SERVICE` | Sub-kind label only; no extra fields. DB `chk_supplier_kind`. |
| `AgentKind` | `INTERNAL`, `EXTERNAL` | INTERNAL **must** reference an ACTIVE non-root app user in the agent's company (`appUserId`); EXTERNAL **must not** (`appUserId` forced null). DB `chk_agent_user_kind` + service BR-PARTY-10/11. |
| `OtherParty.otherKind` | **free-text `String`** (not an enum), nullable | No enum constraint; informational tag only. |
| `MasterStatus` | `ACTIVE`, `INACTIVE`, `ARCHIVED` | Lifecycle. Create ⇒ `ACTIVE`. archive ⇒ `ARCHIVED`. restore ⇒ `ACTIVE`. (Parties never set `INACTIVE` via these endpoints; archive/restore is the only transition pair.) |

Other validation (verified in services / DB):
- BR-PARTY-06: `vrn` may only be set when `vatRegistered = true` (service + DB `chk_<r>_vrn_vat`).
- BR-PARTY-01: assigned branch must belong to the party's company (`PartyBranchGuard`, → 403 if cross-company).
- Duplicate branch association → `ConflictException` (409).
- `code` is server-generated per company+kind (`PartyCodeGenerator`, code prefixes `CUST`/`SUPP`/`AGNT`/`OTHR` — formatted `PREFIX-%04d`, e.g. `CUST-0001`; the `party_kind` keys are CUSTOMER/SUPPLIER/AGENT/OTHER); `companyId` and `code` are immutable (absent from Update DTOs).
- `displayName` `@NotBlank`; `companyId`, `partyType`, and the sub-kind enum `@NotNull` on create.

## Type / role variations exercised

| Dimension | Variations covered |
|---|---|
| User role | `rootadmin` (bypass, cross-tenant), `ORG_ADMIN` (all party perms), a VIEW-only custom role (e.g. `CUSTOMER.VIEW` w/o `MANAGE`), a MANAGE-without-`PARTY.BRANCH.ASSIGN` custom role, `SALES_MANAGER`/`SALES_REP`/`ACCOUNTANT`/`STOREKEEPER`/`PURCHASE_OFFICER` (per seeded grants), and the NO-PERMISSION user. |
| Customer | `INDIVIDUAL`×`CASH_WALK_IN`; `INDIVIDUAL`×`CREDIT_ACCOUNT`; `BUSINESS`×`CASH_WALK_IN`; `BUSINESS`×`CREDIT_ACCOUNT`; VAT-registered vs not. |
| Supplier | `GOODS` vs `SERVICE`; `INDIVIDUAL` vs `BUSINESS`. |
| Agent | `INTERNAL` (valid user / inactive user / root user / wrong-company user / no user) vs `EXTERNAL` (with/without user). |
| Other Party | with `otherKind` text vs blank; `INDIVIDUAL` vs `BUSINESS`. |
| Branch/company | default vs non-default branch; single- vs multi-branch company; user assigned to ONE / MANY / ALL branches; acting in an unassigned branch (denied); cross-company branch assign (denied). |
| Tenant | Company A vs Company B isolation; cross-tenant list/get/assign denied. |
| Screen state | loading / empty / error / forbidden for every list + detail. |

---

# TEST CASES

## A. Customers — list, search, four states, pagination

### TC-PARTY-001 — Customer list loads scoped to active company (idle state)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers` · `/api/v1/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as `ORG_ADMIN`; also as NO-PERMISSION user → expect forbidden (TC-PARTY-006)
- **Variation:** company = default; ≥1 customer seeded
- **Preconditions / Seed:** Company A has ≥3 customers (mix of kinds). Login `ORG_ADMIN` of Company A.
- **Steps:**
  1. Navigate to `/admin/customers`.
  2. Wait for the table to render.
- **Test Data:** seeded customers "Acme Traders", "Jane Walk-in", "Mega Distributors".
- **Expected Result:** Table lists customers of Company A only; each row shows code, displayName, partyType, customerKind, status badge. Company selector defaults to first company. HTTP 200; body is `ApiResponse` with `data[]` + `meta`.
- **Convention Assertions:** C2 envelope unwrapped; C4 idle state; C5 paginator present (hidden if 1 page); C7 only Company-A rows; C8 status badge text; **C1 no raw uid shown in any cell**; C6 axe clean.
- **Negative / Edge:** none here (states split into 002–006).

### TC-PARTY-002 — Customer list loading state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customers (`/admin/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Preconditions / Seed:** throttle/delay the `GET /api/v1/customers` response (route intercept).
- **Steps:** 1. Intercept the list call with a delay. 2. Navigate to `/admin/customers`.
- **Expected Result:** A loading indicator/skeleton is shown while `state==='loading'`, then resolves to the table.
- **Convention Assertions:** C4 loading distinct from empty/error.
- **Negative / Edge:** n/a.

### TC-PARTY-003 — Customer list empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customers (`/admin/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** company with zero customers
- **Preconditions / Seed:** a freshly created company in the org with no customers.
- **Steps:** 1. Navigate to `/admin/customers`. 2. Select the empty company in the company selector.
- **Expected Result:** Distinct empty message (no rows, `isEmpty` true), not an error; create form still reachable.
- **Convention Assertions:** C4 empty state; C5 paginator hidden (0 pages); C6 axe clean.
- **Negative / Edge:** ensure empty ≠ error styling.

### TC-PARTY-004 — Customer list error state (server 500)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customers (`/admin/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Preconditions / Seed:** intercept `GET /api/v1/customers` → 500.
- **Steps:** 1. Force the list endpoint to 500. 2. Navigate to `/admin/customers`.
- **Expected Result:** Error state shown (`state==='error'`), retry affordance; no stale rows.
- **Convention Assertions:** C4 error state distinct; C2 errors array surfaced.
- **Negative / Edge:** 500 vs 403 must map to error vs forbidden respectively.

### TC-PARTY-005 — Customer search by name / TIN / phone / code
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers` · `GET …?q=`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Preconditions / Seed:** customers with known displayName "Acme Traders", TIN `123456789`, phone `0712000111`, code `CUST-0007`.
- **Steps:**
  1. Navigate to `/admin/customers`.
  2. Type `Acme` in the search box (debounce 300 ms) → assert only name-matching rows.
  3. Clear; type the exact TIN `123456789` → assert the matching customer appears.
  4. Repeat with the exact phone, then the exact code.
  5. Click "Clear" → full list returns at page 0.
- **Test Data:** as above. Backend `search`: `displayName` LIKE (case-insensitive substring) OR `tin =` OR `phone =` OR `code =` (exact for the latter three).
- **Expected Result:** Name search is substring/case-insensitive; TIN/phone/code are exact-match only. Search resets to page 0.
- **Convention Assertions:** C5 page resets to first on new query; C1 results reference by name/code, not uid; C4 empty state when no match.
- **Negative / Edge:** partial TIN (`12345`) returns no match (exact-match field); blank query returns full list.

### TC-PARTY-006 — Customer screen forbidden for user lacking `CUSTOMER.VIEW`
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — runs as NO-PERMISSION user → expect forbidden
- **Steps:**
  1. Login as the NO-PERMISSION user.
  2. Confirm the "Customers" nav item is hidden.
  3. Navigate directly to `/admin/customers`.
- **Expected Result:** Route guard (`requirePermission('CUSTOMER.VIEW')`) blocks; forbidden state shown; nav item absent. Direct `GET /api/v1/customers` returns **403**.
- **Convention Assertions:** C3 RBAC (nav hidden + 403 + forbidden screen); C4 forbidden state distinct from error.
- **Negative / Edge:** also verify a user WITH `CUSTOMER.VIEW` but WITHOUT `CUSTOMER.MANAGE` sees the list but no create/edit/archive controls (TC-PARTY-015).

### TC-PARTY-007 — Customer list pagination controls
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Variation:** > 20 customers (page size 20)
- **Preconditions / Seed:** Company A has ≥45 customers.
- **Steps:**
  1. Navigate to `/admin/customers`.
  2. Assert paginator shows FIRST, PREVIOUS, page numbers, NEXT, LAST.
  3. Click NEXT → page index increments; rows change; `meta.page` advances.
  4. Click LAST → last page; NEXT disabled (`hasNext=false`).
  5. Click FIRST → page 0.
- **Test Data:** 45 customers → 3 pages of 20/20/5.
- **Expected Result:** Correct rows per page; controls enable/disable at boundaries; `meta {page,size,totalElements,totalPages,hasNext}` consistent.
- **Convention Assertions:** C5 full paginator; C2 meta fields; C4 idle.
- **Negative / Edge:** with exactly 20 rows (1 page) the paginator is self-hidden.

## B. Customer — create (all PartyType × CustomerKind variations)

### TC-PARTY-008 — Create INDIVIDUAL + CASH_WALK_IN customer (happy path)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers` · `POST /api/v1/customers`)
- **Permission / Role:** `CUSTOMER.MANAGE` — runs as `ORG_ADMIN`; also as VIEW-only role → create form/button absent (TC-PARTY-015)
- **Variation:** partyType=INDIVIDUAL; customerKind=CASH_WALK_IN; no TIN required
- **Preconditions / Seed:** active company selected.
- **Steps:**
  1. Navigate to `/admin/customers`; click "New Customer" (toggles inline create form).
  2. Enter display name "Jane Walk-in"; party type = Individual; kind = Cash / Walk-in.
  3. Submit.
- **Test Data:** displayName="Jane Walk-in", partyType=INDIVIDUAL, customerKind=CASH_WALK_IN.
- **Expected Result:** **201**; success alert; new row appears; server assigns `code` (`CUST-` prefix, e.g. `CUST-0001`), `status=ACTIVE`, generated `uid`. Credit limit / payment terms NOT sent (cash kind).
- **Convention Assertions:** C2 wrapped DTO; C8 dates ISO; **C1 no uid typed and none shown in list**; C9 status defaults ACTIVE; C6 axe clean on form.
- **Negative / Edge:** missing displayName → client error "Company and display name are required" (no POST).

### TC-PARTY-009 — Create BUSINESS + CREDIT_ACCOUNT customer with credit limit + terms
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Customers (`POST /api/v1/customers`, then `PUT …/uid/:uid` for credit fields)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Variation:** partyType=BUSINESS; customerKind=CREDIT_ACCOUNT; vatRegistered=true; creditLimit + paymentTermsDays set
- **Preconditions / Seed:** active company selected.
- **Steps:**
  1. Create via inline form: displayName "Mega Distributors", party type Business, TIN `100200300`, VAT-registered ✓, VRN `40-123456-X`, business reg no, kind Credit Account. Submit.
  2. Open the created customer at `/admin/customers/uid/:uid`.
  3. In the edit form confirm the **Credit Limit (amount+currency)** and **Payment Terms (days)** fields are now visible (kind=CREDIT_ACCOUNT); set amount `5,000,000`, currency `TZS`, terms `30`. Save.
- **Test Data:** creditLimit={amount:"5000000",currency:"TZS"}, paymentTermsDays=30.
- **Expected Result:** Create 201; edit 200. `creditLimit` stored as MoneyDto (amount string + TZS); `paymentTermsDays=30`. Detail shows money formatted `TZS 5,000,000.00`.
- **Convention Assertions:** C8 money string "TZS 5,000,000.00"; C1 picker/route uid hidden; C2 envelope; C3 MANAGE required.
- **Negative / Edge:** see TC-PARTY-012 (credit pair), TC-PARTY-011 (VRN-without-VAT), TC-PARTY-010 (business w/o TIN).

### TC-PARTY-010 — Reject BUSINESS customer without TIN (BR-PARTY-04)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Customers (`POST /api/v1/customers`)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Variation:** partyType=BUSINESS, TIN blank
- **Steps:**
  1. Open create form; party type = Business; leave TIN blank; displayName "No-TIN Ltd"; submit.
- **Expected Result:** UI client-guard blocks with "A business party must have a TIN (BR-PARTY-04)." If bypassed via API, server returns **400** with the same BR-PARTY-04 message (validated in `CustomerServiceImpl.validateIdentifiers`).
- **Convention Assertions:** C2 `errors[]` carries the message; C4 form error state.
- **Negative / Edge:** whitespace-only TIN also rejected (`isBlank`).

### TC-PARTY-011 — Reject VRN when not VAT-registered (BR-PARTY-06)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Customers (`POST` / `PUT /api/v1/customers`)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Variation:** vatRegistered=false but VRN provided
- **Steps (API):** POST a customer with `vatRegistered:false, vrn:"40-9999"`.
- **Expected Result:** **400**, message "VRN may only be set when the customer is VAT-registered (BR-PARTY-06)." Mirrors DB `chk_customer_vrn_vat`. In the UI, unchecking VAT clears the VRN field (`onNewVatRegisteredChange`), so the UI path cannot produce this combination.
- **Convention Assertions:** C2 envelope error; UI guides (VRN disabled/cleared when VAT off).
- **Negative / Edge:** VRN present + VAT on = accepted.

### TC-PARTY-012 — Reject partial credit-limit pair (amount without currency) (chk_customer_credit_pair)
- **Type:** Manual (API) + Automated where reachable
- **Priority:** P2
- **Module / Submodule:** Customers (`POST` / `PUT /api/v1/customers`)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Variation:** customerKind=CREDIT_ACCOUNT; creditLimit amount set, currency null
- **Steps (API):** PUT update with `creditLimit:{amount:"1000",currency:null}`.
- **Expected Result:** DB CHECK `chk_customer_credit_pair` rejects (amount+currency must both be set or both null) → **409/500-mapped error**. (UI `save()` only sends `creditLimit` when amount present and always pairs a currency default `TZS`, so the UI cannot emit a half-pair.)
- **Convention Assertions:** backend-enforced; note UI prevents this combination.
- **Negative / Edge:** both null = allowed (no credit limit); both set = allowed.

### TC-PARTY-013 — Credit fields hidden for CASH_WALK_IN, shown for CREDIT_ACCOUNT
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers/uid/:uid`)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Variation:** toggle customerKind on the detail edit form
- **Preconditions / Seed:** an existing CASH_WALK_IN customer.
- **Steps:**
  1. Open `/admin/customers/uid/:uid`.
  2. Confirm Credit Limit + Payment Terms fields are NOT shown (kind=CASH_WALK_IN, `isCreditAccount=false`).
  3. Change kind selector to "Credit Account" → fields appear.
  4. Change back to "Cash / Walk-in" → fields hide; on save, `creditLimit` and `paymentTermsDays` are sent as undefined.
- **Expected Result:** Conditional fields driven by `isCreditAccount`; switching to cash clears credit data on save.
- **Convention Assertions:** C4 conditional UI; C8 money only where applicable; C1 uid in route only.
- **Negative / Edge:** kind=CREDIT_ACCOUNT but credit amount left blank → no `creditLimit` sent (allowed; no half-pair).

### TC-PARTY-014 — Display name required on create (NotBlank)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customers (`POST /api/v1/customers`)
- **Permission / Role:** `CUSTOMER.MANAGE` — `ORG_ADMIN`
- **Steps:** 1. Open create form; leave display name empty; submit.
- **Expected Result:** Client guard blocks ("Company and display name are required."). API with blank displayName → **400** (`@NotBlank`).
- **Convention Assertions:** C2 validation error; C4 form error.
- **Negative / Edge:** whitespace-only display name rejected (trimmed).

### TC-PARTY-015 — VIEW-only role: no create/edit/archive affordances (`CUSTOMER.MANAGE` gate)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers`, `/admin/customers/uid/:uid`)
- **Permission / Role:** `CUSTOMER.MANAGE` — runs as a CUSTOM role having `CUSTOMER.VIEW` only → expect controls hidden + 403 on write
- **Steps:**
  1. Login as the view-only custom-role user.
  2. `/admin/customers`: list renders; "New Customer" button hidden (`canManage=false`).
  3. Open a detail page; Save / Archive controls hidden.
  4. Attempt `POST /api/v1/customers` directly → **403**; `PUT …/archive` → **403**.
- **Expected Result:** Read allowed; all mutations blocked in UI and API.
- **Convention Assertions:** C3 RBAC at UI + API; C1 uid still only in URL.
- **Negative / Edge:** confirm `PUT …/uid/:uid` also 403.

## C. Customer — get / update / archive / restore lifecycle

### TC-PARTY-016 — Open customer detail by route (uid in URL only)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`/admin/customers/uid/:uid` · `GET /api/v1/customers/uid/{uid}`)
- **Permission / Role:** `CUSTOMER.VIEW` — `ORG_ADMIN`
- **Steps:**
  1. From the list, click a customer row's link → navigates to `/admin/customers/uid/:uid`.
  2. Assert header shows code + displayName; the uid appears ONLY in the URL, never in visible labels/fields.
- **Expected Result:** Detail loads; edit form pre-filled (`patchForm`). HTTP 200 wrapped DTO.
- **Convention Assertions:** **C1 uid not shown on screen, navigation by link not typed uid**; C2 envelope; C4 loading→idle.
- **Negative / Edge:** unknown uid → 404 → detail error state (TC-PARTY-019).

### TC-PARTY-017 — Update customer common fields
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`PUT /api/v1/customers/uid/{uid}`)
- **Permission / Role:** `CUSTOMER.MANAGE` (scoped) — `ORG_ADMIN`
- **Steps:**
  1. Open detail; change phone, email, region, district; save.
- **Expected Result:** 200; success alert; `version` increments; `updatedAt`/`updatedBy` set; `companyId` and `code` unchanged (immutable, not in Update DTO).
- **Convention Assertions:** C9 append-style audit (CUSTOMER_UPDATE recorded); C8 dates ISO; C1 uid in route only.
- **Negative / Edge:** blank displayName on save → client error, no PUT.

### TC-PARTY-018 — Archive then restore a customer (lifecycle ACTIVE↔ARCHIVED)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`PUT …/archive` 204, `PUT …/restore` 204)
- **Permission / Role:** `CUSTOMER.MANAGE` (scoped) — `ORG_ADMIN`
- **Variation:** status transition ACTIVE→ARCHIVED→ACTIVE
- **Steps:**
  1. Open an ACTIVE customer; click Archive → status badge shows ARCHIVED; HTTP **204**.
  2. Click Restore → status badge shows ACTIVE; HTTP **204**.
- **Expected Result:** Soft-delete (status flip), record retained; audit events CUSTOMER_ARCHIVE/CUSTOMER_RESTORE with previous→new status.
- **Convention Assertions:** C9 soft-delete not hard-delete; C2 204 no body; C3 MANAGE required.
- **Negative / Edge:** archive an already-archived customer = idempotent (re-sets ARCHIVED); restore an active = idempotent (re-sets ACTIVE) — no illegal-transition error (only these two transitions exist).

### TC-PARTY-019 — Customer detail not-found / error / forbidden states
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customers (`/admin/customers/uid/:uid`)
- **Permission / Role:** `CUSTOMER.VIEW`
- **Steps:**
  1. Navigate to `/admin/customers/uid/<nonexistent>` → detail error state (404 mapped).
  2. Intercept get → 500 → error state.
  3. As NO-PERMISSION user → route guard forbidden.
- **Expected Result:** Each maps to its distinct state; no stale data.
- **Convention Assertions:** C4 four states on detail; C3 forbidden.
- **Negative / Edge:** 403 (cross-tenant uid) vs 404 (unknown) both surface as error/forbidden appropriately.

## D. Customer — branch associations (PARTY.BRANCH.ASSIGN, BR-PARTY-01)

### TC-PARTY-020 — Assign customer to a branch (same company) via cascading selectors
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Customers (`POST /api/v1/customers/uid/{uid}/branches`)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` (scoped) — `ORG_ADMIN`
- **Variation:** branch = non-default branch of the party's company
- **Preconditions / Seed:** customer in Company A; Company A has ≥2 branches.
- **Steps:**
  1. Open `/admin/customers/uid/:uid`; Branch Associations panel.
  2. Select the company (by name), then select the branch (shown as "CODE — Name"); click Assign.
- **Test Data:** branch chosen by `code — name`, e.g. "BR-02 — Mwanza".
- **Expected Result:** **201**; association appears in the list showing branch as "code — name" + assignedAt; `branchUid` sent under the hood (selected by name, never typed).
- **Convention Assertions:** **C1 branch chosen by name in selector; uid not typed, not displayed** (the association row shows code—name, not the uid); C2 envelope; C7 only same-company branches selectable.
- **Negative / Edge:** duplicate assign → 409 "already associated"; cross-company branch → 403 (BR-PARTY-01) — see TC-PARTY-022/023.

### TC-PARTY-021 — Remove a customer-branch association
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customers (`DELETE /api/v1/customers/uid/{uid}/branches/{branchUid}` 204)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` (scoped) — `ORG_ADMIN`
- **Preconditions / Seed:** customer already associated with a branch.
- **Steps:** 1. Open detail; in Branch Associations, click Remove on a row.
- **Expected Result:** **204**; row disappears; list reloads; `branchUid` resolved from the loaded branch map (by id→uid), not hand-typed.
- **Convention Assertions:** C1 uid resolved internally; C9 association removable (junction, not soft-delete); C2 204.
- **Negative / Edge:** removing a non-existent association is a no-op delete (still 204).

### TC-PARTY-022 — Duplicate branch assignment rejected (409)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Customers (`POST …/branches`)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` — `ORG_ADMIN`
- **Steps:** 1. Assign branch X. 2. Assign branch X again.
- **Expected Result:** Second call → **409** ConflictException "Customer is already associated with that branch." UI surfaces `assignError`.
- **Convention Assertions:** C2 errors[]; C4 form error.
- **Negative / Edge:** assigning a DIFFERENT branch succeeds.

### TC-PARTY-023 — Cross-company branch assignment denied (BR-PARTY-01)
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** Customers (`POST …/branches`)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` — `ORG_ADMIN` of Company A
- **Variation:** customer in Company A, branchUid belongs to Company B
- **Steps (API):** POST `/api/v1/customers/uid/{A-customer}/branches` with a Company-B branch uid.
- **Expected Result:** **403** ForbiddenException "Branch does not belong to the party's company (BR-PARTY-01)." (`PartyBranchGuard.resolveAndAssertSameCompany`). In the UI only same-company branches are selectable, so this is an API-level negative test.
- **Convention Assertions:** C7 multi-tenant branch isolation; C2 error.
- **Negative / Edge:** unknown branchUid → 404 "Branch not found".

### TC-PARTY-024 — Branch-assign hidden without `PARTY.BRANCH.ASSIGN`
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Customers (`/admin/customers/uid/:uid`)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` — runs as a CUSTOM role with `CUSTOMER.VIEW`+`CUSTOMER.MANAGE` but NOT `PARTY.BRANCH.ASSIGN`
- **Steps:** 1. Open a customer detail. 2. Inspect Branch Associations panel.
- **Expected Result:** Branch assign form / Remove buttons hidden (`canAssign=false`); list of existing associations still viewable (read uses `CUSTOMER.VIEW`). Direct POST/DELETE → **403**.
- **Convention Assertions:** C3 fine-grained RBAC (assign separate from manage); C1 uid in route only.
- **Negative / Edge:** confirm DELETE also 403.

### TC-PARTY-025 — Customer branch list four states
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Customers (`GET …/branches`)
- **Permission / Role:** `CUSTOMER.VIEW`
- **Steps:** 1. Open a customer with no associations → empty state. 2. With associations → list. 3. Intercept 500 → error. 4. NO-PERMISSION → forbidden.
- **Expected Result:** Each state distinct in the Branch Associations panel.
- **Convention Assertions:** C4 four states; C6 axe.
- **Negative / Edge:** n/a.

## E. Suppliers (mirror endpoints; SupplierKind variation)

### TC-PARTY-026 — Supplier list + search + pagination + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Suppliers (`/admin/suppliers` · `/api/v1/suppliers`)
- **Permission / Role:** `SUPPLIER.VIEW` — `ORG_ADMIN`; also NO-PERMISSION → forbidden (nav hidden + 403)
- **Preconditions / Seed:** Company A has ≥25 suppliers (GOODS and SERVICE).
- **Steps:** 1. Navigate `/admin/suppliers`. 2. Search by name, exact TIN, exact phone, exact code. 3. Page NEXT/LAST/FIRST. 4. Force loading/empty/error/forbidden via intercepts/role.
- **Expected Result:** Same behaviour as customers: scoped list, paginator, search semantics, four states.
- **Convention Assertions:** C1 no uid shown; C4 four states; C5 paginator; C7 company-scoped; C6 axe.
- **Negative / Edge:** partial TIN no match.

### TC-PARTY-027 — Create GOODS supplier (BUSINESS) happy path
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Suppliers (`POST /api/v1/suppliers`)
- **Permission / Role:** `SUPPLIER.MANAGE` — `ORG_ADMIN`
- **Variation:** partyType=BUSINESS; supplierKind=GOODS; TIN required
- **Steps:** 1. Open create; party type Business; TIN `200300400`; kind Goods; displayName "Bulk Supplies Ltd"; submit.
- **Expected Result:** **201**; status ACTIVE; code `SUPP-`-prefixed (e.g. `SUPP-0001`); row appears.
- **Convention Assertions:** C2 envelope; C1 uid not typed/shown; C8 ISO dates.
- **Negative / Edge:** Business without TIN → 400 "A business supplier must have a TIN (BR-PARTY-04)."

### TC-PARTY-028 — Create SERVICE supplier (INDIVIDUAL)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Suppliers (`POST /api/v1/suppliers`)
- **Permission / Role:** `SUPPLIER.MANAGE` — `ORG_ADMIN`
- **Variation:** partyType=INDIVIDUAL; supplierKind=SERVICE; no TIN
- **Steps:** 1. Create with party type Individual, kind Service, displayName "John Consulting"; submit.
- **Expected Result:** 201; supplierKind=SERVICE persisted and shown.
- **Convention Assertions:** C2; C1.
- **Negative / Edge:** invalid supplierKind value → DB `chk_supplier_kind` (enum bound by `@NotNull` + Jackson).

### TC-PARTY-029 — Update supplier (incl. supplierKind change GOODS↔SERVICE)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Suppliers (`PUT /api/v1/suppliers/uid/{uid}`)
- **Permission / Role:** `SUPPLIER.MANAGE` (scoped) — `ORG_ADMIN`
- **Steps:** 1. Open detail; change supplierKind from GOODS to SERVICE; change phone; save.
- **Expected Result:** 200; supplierKind updated; version increments; companyId/code immutable.
- **Convention Assertions:** C9 audit SUPPLIER_UPDATE; C1 uid route-only.
- **Negative / Edge:** VRN-without-VAT → 400 (BR-PARTY-06).

### TC-PARTY-030 — Archive/restore supplier; VIEW-only role denied writes
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Suppliers (`PUT …/archive` 204, `…/restore` 204)
- **Permission / Role:** `SUPPLIER.MANAGE` — `ORG_ADMIN`; also CUSTOM `SUPPLIER.VIEW`-only → controls hidden + 403
- **Steps:** 1. Archive an ACTIVE supplier → ARCHIVED (204). 2. Restore → ACTIVE (204). 3. As view-only role: buttons hidden, direct PUT → 403.
- **Expected Result:** Soft-delete lifecycle; RBAC enforced.
- **Convention Assertions:** C9 soft-delete; C3 RBAC; C2 204.
- **Negative / Edge:** idempotent re-archive/re-restore.

### TC-PARTY-031 — Supplier branch assign / remove / duplicate / cross-company
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Suppliers (`…/branches` POST 201 / DELETE 204)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` (scoped) — `ORG_ADMIN`; also without it → hidden + 403
- **Steps:** 1. Assign branch (same company, by name). 2. Remove. 3. Duplicate assign → 409. 4. (API) cross-company branch → 403 BR-PARTY-01.
- **Expected Result:** As customer (TC-PARTY-020..024) for the supplier resource.
- **Convention Assertions:** C1 branch by name not uid; C7 isolation; C3 RBAC; C4 four states on branch list.
- **Negative / Edge:** unknown branchUid → 404.

## F. Sales Agents (AgentKind INTERNAL/EXTERNAL + user link)

### TC-PARTY-032 — Agent list + search + pagination + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales Agents (`/admin/agents` · `/api/v1/agents`)
- **Permission / Role:** `AGENT.VIEW` — `ORG_ADMIN`; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** Company A has ≥25 agents (INTERNAL + EXTERNAL).
- **Steps:** 1. Navigate `/admin/agents`. 2. Search by name/TIN/phone/code. 3. Paginate. 4. Exercise loading/empty/error/forbidden.
- **Expected Result:** Scoped paged list; four states; agentKind shown.
- **Convention Assertions:** C1 no uid; C4; C5; C7; C6 axe.
- **Negative / Edge:** partial TIN no match.

### TC-PARTY-033 — Create EXTERNAL agent (no user link)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales Agents (`POST /api/v1/agents`)
- **Permission / Role:** `AGENT.MANAGE` — `ORG_ADMIN`
- **Variation:** agentKind=EXTERNAL; appUserId must be null
- **Steps:** 1. Open create; kind = External; displayName "Field Rep Co"; party type Individual; submit.
- **Expected Result:** **201**; `appUserId` null (forced by service); no user selector required (UI hides user picker for EXTERNAL).
- **Convention Assertions:** C2; C1 uid not typed/shown.
- **Negative / Edge:** EXTERNAL with appUserId provided → 400 "An external agent must not reference an app user (BR-PARTY-11)." / DB `chk_agent_user_kind`.

### TC-PARTY-034 — Create/Update INTERNAL agent linked to an ACTIVE company user
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Sales Agents (`POST` / `PUT /api/v1/agents`)
- **Permission / Role:** `AGENT.MANAGE` — `ORG_ADMIN`
- **Variation:** agentKind=INTERNAL; appUserId = an active user of the agent's company
- **Preconditions / Seed:** an ACTIVE non-root user (e.g. a `SALES_REP`) in Company A.
- **Steps:**
  1. Open the agent detail in edit; set kind = Internal → a **user selector** appears (`isInternal=true`).
  2. Choose the user by name (selector lists users; uid/id not typed).
  3. Save.
- **Test Data:** appUserId = the SALES_REP user (chosen by name).
- **Expected Result:** **200/201**; `appUserId` persisted; agent shows the linked user. The user is selected by NAME via the selector, never by typing an id.
- **Convention Assertions:** **C1 user chosen by name in selector, id stored under the hood**; C2 envelope; C3 MANAGE.
- **Negative / Edge:** see 035/036/037.

### TC-PARTY-035 — Reject INTERNAL agent with no user (BR-PARTY-10)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Agents (`POST` / `PUT /api/v1/agents`)
- **Permission / Role:** `AGENT.MANAGE` — `ORG_ADMIN`
- **Variation:** agentKind=INTERNAL, appUserId null
- **Steps (API):** POST agent kind INTERNAL without appUserId.
- **Expected Result:** **400** "An internal agent must reference an app user (BR-PARTY-10)." UI shows the user selector as required (aria-required) for INTERNAL.
- **Convention Assertions:** C2 error; C4 form error.
- **Negative / Edge:** also covered by DB `chk_agent_user_kind`.

### TC-PARTY-036 — Reject INTERNAL agent referencing an inactive / root / wrong-company user (BR-PARTY-10)
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** Sales Agents (`POST` / `PUT /api/v1/agents`)
- **Permission / Role:** `AGENT.MANAGE` — `ORG_ADMIN`
- **Variation:** appUserId references (a) an inactive user, (b) rootadmin, (c) a user of Company B
- **Steps (API):** Attempt each appUserId.
- **Expected Result:** **400** "The referenced app user must be an ACTIVE, non-root user belonging to the agent's company (BR-PARTY-10). The super-admin cannot be a sales agent." (`UserLookupService.isActiveUserInCompany`).
- **Convention Assertions:** C7 cross-company user rejected; C2 error.
- **Negative / Edge:** active same-company non-root user = accepted (TC-PARTY-034).

### TC-PARTY-037 — Switch agent INTERNAL→EXTERNAL clears the user link
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Sales Agents (`PUT /api/v1/agents/uid/{uid}`)
- **Permission / Role:** `AGENT.MANAGE` (scoped) — `ORG_ADMIN`
- **Preconditions / Seed:** an INTERNAL agent linked to a user.
- **Steps:** 1. Open detail; change kind to External (user selector hides); save.
- **Expected Result:** 200; service sets `appUserId=null` for EXTERNAL; agent no longer shows a user link.
- **Convention Assertions:** C4 conditional UI; C1 uid route-only.
- **Negative / Edge:** EXTERNAL→INTERNAL save without selecting a user → 400 BR-PARTY-10.

### TC-PARTY-038 — Agent archive/restore + VIEW-only denied writes
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Sales Agents (`PUT …/archive` 204, `…/restore` 204)
- **Permission / Role:** `AGENT.MANAGE` — `ORG_ADMIN`; CUSTOM `AGENT.VIEW`-only → controls hidden + 403
- **Steps:** 1. Archive ACTIVE agent → ARCHIVED. 2. Restore → ACTIVE. 3. View-only role: write 403.
- **Expected Result:** Soft-delete lifecycle; RBAC enforced.
- **Convention Assertions:** C9; C3; C2 204.
- **Negative / Edge:** idempotent transitions.

### TC-PARTY-039 — Agent branch assign / remove / duplicate / cross-company
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Sales Agents (`…/branches` POST 201 / DELETE 204)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` (scoped) — `ORG_ADMIN`; without it → hidden + 403
- **Steps:** Mirror TC-PARTY-020..024 for agents.
- **Expected Result:** Assign by name (same company), remove, duplicate→409, cross-company→403.
- **Convention Assertions:** C1 branch by name; C7 isolation; C3; C4.
- **Negative / Edge:** unknown branchUid → 404.

## G. Other Parties (free-text otherKind)

### TC-PARTY-040 — Other-party list + search + pagination + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Other Parties (`/admin/other-parties` · `/api/v1/other-parties`)
- **Permission / Role:** `OTHERPARTY.VIEW` — `ORG_ADMIN`; NO-PERMISSION → forbidden (nav hidden + 403)
- **Preconditions / Seed:** Company A has ≥25 other parties.
- **Steps:** 1. Navigate. 2. Search by name/TIN/phone/code. 3. Paginate. 4. Loading/empty/error/forbidden.
- **Expected Result:** Scoped paged list; four states.
- **Convention Assertions:** C1 no uid; C4; C5; C7; C6 axe.
- **Negative / Edge:** partial TIN no match.

### TC-PARTY-041 — Create other party with free-text otherKind
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Other Parties (`POST /api/v1/other-parties`)
- **Permission / Role:** `OTHERPARTY.MANAGE` — `ORG_ADMIN`
- **Variation:** partyType=BUSINESS; otherKind = "Landlord" (free text); TIN required
- **Steps:** 1. Open create; party type Business; TIN `300400500`; otherKind text "Landlord"; displayName "City Properties"; submit.
- **Expected Result:** **201**; `otherKind` stored verbatim (no enum validation); status ACTIVE; `OTHR-`-prefixed code (e.g. `OTHR-0001`).
- **Convention Assertions:** C2 envelope; C1 uid not typed/shown.
- **Negative / Edge:** Business without TIN → 400 (BR-PARTY-04, "A business …"). otherKind blank = allowed (nullable).

### TC-PARTY-042 — Update / archive / restore other party + VIEW-only denied
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Other Parties (`PUT /uid/{uid}`, `…/archive` 204, `…/restore` 204)
- **Permission / Role:** `OTHERPARTY.MANAGE` — `ORG_ADMIN`; CUSTOM `OTHERPARTY.VIEW`-only → controls hidden + 403
- **Steps:** 1. Update otherKind + contact fields. 2. Archive → ARCHIVED. 3. Restore → ACTIVE. 4. View-only role: write 403.
- **Expected Result:** Lifecycle + RBAC as other resources.
- **Convention Assertions:** C9 soft-delete; C3; C1 uid route-only.
- **Negative / Edge:** VRN-without-VAT → 400 (BR-PARTY-06).

### TC-PARTY-043 — Other-party branch assign / remove / duplicate / cross-company
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Other Parties (`…/branches` POST 201 / DELETE 204)
- **Permission / Role:** `PARTY.BRANCH.ASSIGN` (scoped) — `ORG_ADMIN`; without it → hidden + 403
- **Steps:** Mirror TC-PARTY-020..024 for other parties.
- **Expected Result:** Assign by name, remove, duplicate→409, cross-company→403.
- **Convention Assertions:** C1 branch by name; C7; C3; C4.
- **Negative / Edge:** unknown branchUid → 404.

## H. Cross-cutting: multi-tenancy, scoping, root bypass, conventions

### TC-PARTY-044 — Cross-tenant list isolation (company A cannot see company B)
- **Type:** Manual (API) + Automated where feasible
- **Priority:** P1
- **Module / Submodule:** All four list endpoints
- **Permission / Role:** `<R>.VIEW` — `ORG_ADMIN` of Company A
- **Variation:** request `companyId` = Company B
- **Steps (API):** `GET /api/v1/customers?companyId=<B>` as a Company-A admin.
- **Expected Result:** **403** — `scopeGuard.assertCanActIn` blocks BEFORE querying (prevents cross-company enumeration via client-supplied companyId). Same for suppliers/agents/other-parties.
- **Convention Assertions:** C7 tenant isolation enforced server-side; C3.
- **Negative / Edge:** own companyId returns own rows only.

### TC-PARTY-045 — Cross-tenant get/update via guessed uid denied (uid is not authorization)
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** All four `GET/PUT /uid/{uid}`
- **Permission / Role:** `<R>.VIEW`/`.MANAGE` — `ORG_ADMIN` of Company A
- **Variation:** uid belongs to a Company-B party
- **Steps (API):** GET then PUT a Company-B party's uid as a Company-A admin.
- **Expected Result:** **403** — service loads the entity then `assertCanActIn` on its companyId (the documented "uid is not authorization" fix). Applies to get, update, archive, restore, listBranches, assign/remove branch.
- **Convention Assertions:** C7 scoping on the loaded entity; C1 uid only in URL but not a trust boundary.
- **Negative / Edge:** unknown uid → 404 (not 403) to avoid existence leak only where appropriate.

### TC-PARTY-046 — rootadmin sees all tenants and bypasses permission checks
- **Type:** Automated (Playwright) + Manual (API)
- **Priority:** P1
- **Module / Submodule:** All four resources
- **Permission / Role:** none required — runs as `rootadmin`
- **Steps:** 1. Login rootadmin. 2. List customers/suppliers/agents/other-parties for Company A and Company B by switching `companyId`.
- **Expected Result:** rootadmin can list/get/create/update across companies (bypass + cross-tenant). Use rootadmin ONLY for positive/seed flows, never for negative-auth assertions.
- **Convention Assertions:** C3 documented bypass; C7 cross-tenant allowed for root only.
- **Negative / Edge:** rootadmin cannot be set as an INTERNAL agent's user (BR-PARTY-10 explicitly excludes super-admin) — see TC-PARTY-036.

### TC-PARTY-047 — Acting in a branch the user is not assigned to is denied
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Any write endpoint with `X-Branch-Uid`
- **Permission / Role:** `<R>.MANAGE` — a user assigned to ONE branch only
- **Variation:** set `X-Branch-Uid` to a branch the user is NOT assigned to (within their company)
- **Steps (API):** Send a create/update with a non-assigned active branch header.
- **Expected Result:** Request denied by branch-scope enforcement (acting branch must be one the user is assigned to). Compare: a user assigned to ALL branches succeeds in any; a user with MANY can switch among assigned ones.
- **Convention Assertions:** C7 branch scoping; C3.
- **Negative / Edge:** user assigned to that branch → allowed.

### TC-PARTY-048 — Money + date conventions on detail/list (C8)
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Customers detail (credit limit) + all created/updated timestamps
- **Permission / Role:** `CUSTOMER.VIEW`
- **Preconditions / Seed:** a CREDIT_ACCOUNT customer with creditLimit TZS 5,000,000.
- **Steps:** 1. Open detail. 2. Inspect credit limit rendering and audit timestamps.
- **Expected Result:** Money displayed "TZS 5,000,000.00" (currency + grouped 2dp; amount is a string on the wire); dates ISO `yyyy-MM-dd` (timestamps ISO-8601 strings).
- **Convention Assertions:** C8 money/date formatting; C2 amount-as-string.
- **Negative / Edge:** null credit limit renders blank, not "TZS 0.00".

### TC-PARTY-049 — Accessibility sweep on all party screens (C6)
- **Type:** Automated (Playwright + axe)
- **Priority:** P2
- **Module / Submodule:** All four list + detail components
- **Permission / Role:** `<R>.VIEW`/`.MANAGE` — `ORG_ADMIN`
- **Steps:** 1. For each of `/admin/customers`, `/suppliers`, `/agents`, `/other-parties` and one detail of each, run an axe scan; tab through the create/edit forms and paginator.
- **Expected Result:** Axe-clean (WCAG 2.1 AA); tables have captions + `scope`; inputs have labels; conditional controls (VRN, credit fields, user selector) carry `aria-required`/`aria-hidden` appropriately; paginator keyboard-operable.
- **Convention Assertions:** C6 a11y on every screen + state.
- **Negative / Edge:** re-run axe in empty/error/forbidden states.

### TC-PARTY-050 — Code is server-generated and immutable; uid never hand-typed (C1)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** All four create + update endpoints
- **Permission / Role:** `<R>.MANAGE` — `ORG_ADMIN`
- **Steps:**
  1. Create a party; observe the assigned `code` (`CUST`/`SUPP`/`AGNT`/`OTHR` prefix, formatted `PREFIX-%04d`) — not entered by the user.
  2. Confirm Update DTOs have no `code`/`companyId` fields (immutable) — UI shows them read-only.
  3. Across all flows confirm no input ever accepts a typed uid; navigation/selection is by row link or name selector.
- **Expected Result:** `code` auto-generated per company+kind (`PartyCodeGenerator`, prefixes `CUST`/`SUPP`/`AGNT`/`OTHR`); `companyId`+`code` immutable; uid appears only in `/admin/<r>/uid/:uid` URLs and is never displayed in tables/labels nor typed.
- **Convention Assertions:** **C1 (primary)** uid machine-only, code human-readable + server-issued; C9 immutable identity.
- **Negative / Edge:** attempting to PUT a changed code/companyId has no effect (fields ignored — not in DTO).

---

## Notes on UI vs backend coverage (accuracy)

- The **branch-association picker is implemented as cascading native selects** (company-by-name → branch shown as "code — name"), not the `<app-uid-picker>` component; it still satisfies C1 (resource chosen by human name; uid stored/sent under the hood, never typed or displayed as a raw uid). Assert by select label/option text.
- The **INTERNAL-agent user link is a user selector (by name)**, not `<app-uid-picker>`; the underlying `appUserId` (a numeric id) is sent under the hood. (Per ADR-0006 finding-4 note, the API currently takes `appUserId`; a switch to user-uid is a documented follow-up — tests assert selection-by-name, not the wire field name.)
- The list `companyId` query param is a numeric **database id** sent by the client; it is NOT exposed in any URL path and is validated server-side by `scopeGuard` (TC-PARTY-044). This is the one place a numeric id crosses the wire — it is a scoped query param, not an addressable identifier.
- Parties have only **two status transitions** via these endpoints: ACTIVE→ARCHIVED (archive) and ARCHIVED→ACTIVE (restore). There is no `INACTIVE` transition exposed and no other illegal-transition surface; archive/restore are idempotent (no error on re-applying the same target state).
- `OtherParty.otherKind` is **free text** (`String`, nullable), NOT an enum — there is no kind-conditional behaviour for other parties beyond PartyType's TIN rule.
