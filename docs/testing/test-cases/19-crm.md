# CRM — Test Cases (Leads, Opportunities, Pipeline, Stages, Activities)

Exhaustive, file-verified test cases for the CRM domain: lead capture and lifecycle (NEW → CONTACTED → QUALIFIED → CONVERTED / DISQUALIFIED), opportunities (OPEN → WON / LOST, advance-stage, lines, convert to Quotation/Sales Order), pipeline analytics (board, forecast, KPIs), pipeline-stage CRUD, and activities (CALL/EMAIL/MEETING/NOTE/TASK logged on a lead or opportunity, open-task inbox, complete).
All endpoints, permission codes, enum values and routes below were read directly from the controllers, DTOs, enums, the `V51__crm.sql` migration and the Angular components.

## Modules / submodules covered

| Submodule | Frontend route(s) | API base path · controller |
|---|---|---|
| Leads — list + inline create | `/admin/crm/leads` (`LeadListComponent`) | `/api/v1/crm/leads` · `LeadController` |
| Leads — detail + lifecycle | `/admin/crm/leads/uid/:uid` (`LeadDetailComponent`) | `/api/v1/crm/leads/uid/{uid}` (+ `/contact`, `/qualify`, `/disqualify`) |
| Opportunities — list | `/admin/crm/opportunities` (`OpportunityListComponent`) | `/api/v1/crm/opportunities` · `OpportunityController` |
| Opportunities — create | `/admin/crm/opportunities/create` (`OpportunityCreateComponent`) | `POST /api/v1/crm/opportunities` |
| Opportunities — detail + lifecycle + lines + convert | `/admin/crm/opportunities/uid/:uid` (`OpportunityDetailComponent`) | `/api/v1/crm/opportunities/uid/{uid}` (+ `/lines`, `/advance-stage`, `/win`, `/lose`, `/convert`) |
| Pipeline dashboard (board + forecast + KPIs) | `/admin/crm/pipeline` (`PipelineDashboardComponent`) | `/api/v1/crm/pipeline`, `/pipeline/forecast`, `/pipeline/kpis` · `PipelineController` |
| Pipeline stages (settings CRUD) | `/admin/crm/settings/pipeline-stages` (`PipelineStageListComponent`) | `/api/v1/crm/pipeline-stages` · `PipelineStageController` |
| Activities — open-task inbox + complete | `/admin/crm/activities` (`ActivityTasksComponent`) | `/api/v1/crm/activities/open-tasks`, `/activities/uid/{uid}/complete` · `ActivityController` |
| Activities — embedded panel (log + list, by-lead / by-opportunity) | embedded in lead-detail & opportunity-detail (`ActivityPanelComponent`) | `/api/v1/crm/activities`, `/activities/by-lead/{leadUid}`, `/activities/by-opportunity/{opportunityUid}` |

Nav: shell "CRM" group → Leads, Opportunities, Pipeline Dashboard, Pipeline Stages, CRM Activities (`shell.component.ts` lines 280-286), each hidden by its `permission`.

## Permission codes in scope (exact, from `V51__crm.sql` + `@PreAuthorize`)

- `CRM.LEAD.VIEW` — view leads (list, detail; also gates activity `by-lead` via `@perm.scoped`)
- `CRM.LEAD.MANAGE` — create/edit leads, contact, disqualify
- `CRM.LEAD.QUALIFY` — qualify a lead (link existing customer or promote a new one)
- `CRM.OPPORTUNITY.VIEW` — view opportunities; also gates pipeline-stage `getByUid` + `list`
- `CRM.OPPORTUNITY.MANAGE` — create/edit opportunities, lines, advance-stage, win, lose
- `CRM.OPPORTUNITY.CONVERT` — convert opportunity → quotation / sales order
- `CRM.ACTIVITY.VIEW` — view activities, by-lead/by-opportunity lists, open-tasks
- `CRM.ACTIVITY.MANAGE` — log + complete activities
- `CRM.PIPELINE.VIEW` — pipeline board, forecast, KPI report
- `CRM.STAGE.MANAGE` — create/rename/reorder/deactivate pipeline stages

Note the cross-permission gates verified in code: `PipelineStageController.getByUid` and `list` are gated by `CRM.OPPORTUNITY.VIEW` (NOT `CRM.STAGE.MANAGE`); `getByUid` uses `@perm.scoped(#uid,'pipelinestage','CRM.OPPORTUNITY.VIEW')`. Activity `by-lead` uses `CRM.ACTIVITY.VIEW` scoped on the **lead** uid; `by-opportunity` scoped on the **opportunity** uid.

## Enum values in scope (exact)

- `LeadStatus` = `NEW, CONTACTED, QUALIFIED, CONVERTED, DISQUALIFIED`
- `LeadSource` = `WEBSITE, REFERRAL, WALK_IN, CAMPAIGN, COLD_CALL, EXISTING_CUSTOMER, OTHER`
- `OpportunityStatus` = `OPEN, WON, LOST` (CONVERTED is **not** a status — recorded via `convertedDocumentUid`/`convertedDocumentKind`)
- `ActivityType` = `CALL, EMAIL, MEETING, NOTE, TASK` (only TASK carries dueDate/assigneeUserId/done)
- `ConvertTarget` = `QUOTATION, SALES_ORDER`
- `MasterStatus` = `ACTIVE, INACTIVE, ARCHIVED` (pipeline-stage record status)
- `CustomerKind` = `CASH_WALK_IN, CREDIT_ACCOUNT` (used in qualify→new-customer)

Seeded per-company stages (V51 backfill): `QUALIFICATION` (order 1, prob 10), `NEEDS_ANALYSIS` (2, 25), `PROPOSAL` (3, 50), `NEGOTIATION` (4, 75), `CLOSING` (5, 90).

## Lifecycle transitions (service-guarded — each gets a positive + an illegal case)

**Lead** (`LeadServiceImpl`): `NEW →(contact)→ CONTACTED`; `NEW|CONTACTED →(qualify)→ QUALIFIED` (sets customer); `QUALIFIED →(opportunity create with sourceLeadUid)→ CONVERTED`; `NEW|CONTACTED|QUALIFIED →(disqualify)→ DISQUALIFIED`. Illegal: `contact` only from NEW; `qualify`/`disqualify`/`update` rejected when CONVERTED or DISQUALIFIED (terminal). `chk_lead_qualified_customer`: QUALIFIED/CONVERTED require a customer; `chk_lead_disqualify_reason`: DISQUALIFIED requires a reason.

**Opportunity** (`OpportunityServiceImpl` + `OpportunityConversionServiceImpl`): `OPEN →(win)→ WON`; `OPEN →(lose)→ LOST`; advance-stage / edit / add-line / remove-line require OPEN. `win`/`lose` require OPEN. Convert: `QUOTATION` allowed when OPEN or WON (LOST rejected); `SALES_ORDER` requires WON; convert requires ≥1 line; convert is idempotent (second call returns the first document, guarded by `uq_opportunity_converted_document`). Source lead must be QUALIFIED to attach at create.

**Activity** (`ActivityServiceImpl`): `complete` allowed only for TASK and only when not already done.

---

## Type/role variations exercised

| Axis | Variations covered in cases |
|---|---|
| User role / permission | ORG_ADMIN (full CRM via V51 grant), rootadmin (superuser bypass), SALES_REP / SALES_MANAGER (where granted CRM perms via custom grant), a CUSTOM role with a permission subset (e.g. VIEW-only, MANAGE-without-QUALIFY, MANAGE-without-CONVERT), NO-PERMISSION user (forbidden / hidden nav) |
| Permission granularity | VIEW vs MANAGE vs QUALIFY vs CONVERT vs STAGE.MANAGE vs PIPELINE.VIEW — denied-pair asserted per action |
| LeadSource | WEBSITE, REFERRAL, WALK_IN, CAMPAIGN, COLD_CALL, EXISTING_CUSTOMER, OTHER |
| LeadStatus path | NEW→CONTACTED→QUALIFIED→CONVERTED; NEW→DISQUALIFIED; CONTACTED→QUALIFIED; terminal-edit rejections |
| Qualify mode | link existing customer (by picker) vs promote new customer (CASH_WALK_IN and CREDIT_ACCOUNT) |
| OpportunityStatus path | OPEN→WON, OPEN→LOST; convert OPEN→QUOTATION; convert WON→SALES_ORDER; LOST convert rejection |
| ConvertTarget | QUOTATION, SALES_ORDER |
| ActivityType | CALL, EMAIL, MEETING, NOTE (historical), TASK (due-date + complete) |
| Parent of activity | lead vs opportunity (exactly-one rule) |
| Company / branch scope | single-company; multi-company isolation (tenant A vs B); branch on header (X-Branch-Uid); user assigned vs not-assigned to acting branch; default vs non-default branch |
| Screen states | loading / empty / error / forbidden for every list + detail |
| Pagination | leads list, open-tasks inbox, activity panel (paginated); pipeline-stage list (NOT paginated — bare array) |

---

## TEST CASES

### TC-CRM-001 — Lead list loads with company scope, pagination, and four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Leads list (`/admin/crm/leads` · `GET /api/v1/crm/leads?companyId&page&size`)
- **Permission / Role:** `CRM.LEAD.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → expect nav item hidden + route forbidden
- **Variation:** single company selected (first company auto-selected)
- **Preconditions / Seed:** ≥ 21 leads in the company (to force 2 pages at size 20) via repeated `POST /api/v1/crm/leads` or prior TC-CRM-010
- **Steps:**
  1. Log in as ORG_ADMIN; navigate to `/admin/crm/leads`.
  2. Confirm the company selector auto-selects the first company and the list loads.
  3. Observe loading state, then idle list with rows.
  4. Exercise paginator: NEXT, page-2 number, PREVIOUS, FIRST, LAST.
- **Test Data:** company with 21 leads
- **Expected Result:** table shows lead rows (leadNumber, displayName, status badge, source); `meta` = `{page,size:20,totalElements:21,totalPages:2,hasNext:true}`; paginator visible on page 1.
- **Convention Assertions:** C2 envelope unwrap (`ApiResponse<List<LeadDto>>` + meta); C4 four-state (loading→idle); C5 full paginator controls; C7 only this company's leads shown; C3 nav hidden + route forbidden for NO-PERMISSION user; C6 axe-clean.
- **Negative / Edge:** company with 0 leads → empty state ("no leads") and paginator self-hidden (1 page); backend 500 → error state.

### TC-CRM-002 — Lead list empty state
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Leads list (`/admin/crm/leads`)
- **Permission / Role:** `CRM.LEAD.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** a company with zero leads
- **Steps:** navigate to `/admin/crm/leads`; select the empty company.
- **Expected Result:** distinct empty-state message; no error; paginator hidden (totalPages ≤ 1).
- **Convention Assertions:** C4 empty distinct from loading/error; C5 paginator self-hidden; C6 axe.
- **Negative / Edge:** N/A.

### TC-CRM-003 — Lead list forbidden state for user without VIEW
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Leads list (`/admin/crm/leads`)
- **Permission / Role:** `CRM.LEAD.VIEW` — runs as NO-PERMISSION user (and a CUSTOM role lacking CRM.LEAD.VIEW)
- **Steps:** log in as NO-PERMISSION user; (a) confirm CRM > Leads nav item is absent; (b) hit `/admin/crm/leads` directly; (c) call `GET /api/v1/crm/leads?companyId=1` with the user's token.
- **Expected Result:** nav item hidden (route guard `requirePermission('CRM.LEAD.VIEW')`); direct navigation blocked/forbidden; API returns HTTP 403.
- **Convention Assertions:** C3 RBAC by permission code; C4 forbidden state.
- **Negative / Edge:** rootadmin sees the nav + data (superuser bypass) — contrast assertion.

### TC-CRM-010 — Create a lead inline (each LeadSource)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Leads create (`/admin/crm/leads` inline form · `POST /api/v1/crm/leads`)
- **Permission / Role:** `CRM.LEAD.MANAGE` — runs as ORG_ADMIN; also as a CUSTOM role with VIEW but not MANAGE → "New lead" button hidden / API 403
- **Variation:** run once per `LeadSource` value (WEBSITE, REFERRAL, WALK_IN, CAMPAIGN, COLD_CALL, EXISTING_CUSTOMER, OTHER)
- **Preconditions / Seed:** at least one company exists
- **Steps:**
  1. Navigate to `/admin/crm/leads`; click "New lead" to reveal the inline form.
  2. Fill Display name (required), pick Lead source from the dropdown, optionally company name/contact/phone/email/notes.
  3. Submit.
- **Test Data:** displayName="Acme Trading"; leadSource=REFERRAL; phone="+255700000001"; email="buyer@acme.test"
- **Expected Result:** HTTP 201; success toast with the generated `leadNumber` (e.g. `LEAD-0001`); list reloads and shows the new row with status badge **NEW**. `companyId` taken from selected company; `branchId` from active-branch context.
- **Convention Assertions:** C1 company chosen from selector by name (no uid typed/shown; leadNumber, not uid, surfaced); C2 envelope; C3 button hidden + 403 for VIEW-only; C8 dates ISO; C6 axe.
- **Negative / Edge:** blank Display name → client validation "Display name is required." (no POST); missing `leadSource` → `@NotNull` 400; missing/blank `companyId` server-side → 400; no active branch in context → IllegalState ("No active branch in context.").

### TC-CRM-011 — Lead detail loads + status badge + four states
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Lead detail (`/admin/crm/leads/uid/:uid` · `GET /api/v1/crm/leads/uid/{uid}`)
- **Permission / Role:** `CRM.LEAD.VIEW` (scoped `@perm.scoped(#uid,'lead','CRM.LEAD.VIEW')`) — ORG_ADMIN; also NO-PERMISSION → forbidden
- **Preconditions / Seed:** a lead exists (TC-CRM-010)
- **Steps:** from the list, open a lead row; land on `/admin/crm/leads/uid/:uid`.
- **Expected Result:** header shows leadNumber, displayName, status badge; edit form pre-filled; action buttons gated by status (see TC-CRM-012..015); embedded activity panel present.
- **Convention Assertions:** C1 uid only in URL, not rendered as a field; C4 loading/error states; C6 axe; C7 scope — opening another tenant's lead uid → forbidden.
- **Negative / Edge:** unknown uid → NotFound ("Lead not found")/error state; cross-tenant uid → 403 via ScopeGuard.

### TC-CRM-012 — Lead transition: NEW → CONTACTED (contact)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Lead lifecycle (`PUT /api/v1/crm/leads/uid/{uid}/contact`)
- **Permission / Role:** `CRM.LEAD.MANAGE` (scoped) — ORG_ADMIN; also a CUSTOM role lacking MANAGE → button hidden / 403
- **Variation:** lead status = NEW
- **Preconditions / Seed:** a NEW lead
- **Steps:** open the lead; click "Mark as contacted".
- **Expected Result:** status becomes **CONTACTED** (badge `text-bg-info`); success toast; "Mark as contacted" button now disabled/hidden.
- **Convention Assertions:** C3 RBAC; C9 status change not a delete; C6 axe.
- **Negative / Edge:** lead already CONTACTED/QUALIFIED/CONVERTED/DISQUALIFIED → `IllegalStateException` "Only NEW leads can move to CONTACTED" (button hidden by `canContact = isNew && canManage`); API direct call returns the guarded error.

### TC-CRM-013 — Lead qualify by linking an EXISTING customer (via picker)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Lead lifecycle (`POST /api/v1/crm/leads/uid/{uid}/qualify`)
- **Permission / Role:** `CRM.LEAD.QUALIFY` (scoped) — ORG_ADMIN; also a role with MANAGE but NOT QUALIFY → qualify action hidden / API 403
- **Variation:** qualifyMode = existing; lead = NEW or CONTACTED
- **Preconditions / Seed:** a non-terminal lead + an existing Parties customer in the same company
- **Steps:**
  1. Open lead; open the Qualify form; choose "Link existing customer".
  2. Choose the customer by NAME (the customer is selected from the loaded customer list, stored as uid under the hood).
  3. Submit.
- **Test Data:** existing customer "Zanaco Ltd"
- **Expected Result:** lead status → **QUALIFIED**, `customerUid`/`customerId` set, `qualifiedAt` stamped; badge `text-bg-primary`; success toast with leadNumber.
- **Convention Assertions:** C1 customer chosen by name (uid stored, not shown/typed); C3 QUALIFY gate (denied for MANAGE-only); C7 customer must belong to same company (ScopeGuard); C6 axe.
- **Negative / Edge:** customer uid blank → "Customer UID is required." (no POST); existing customer of another tenant → ScopeGuard 403; qualify on CONVERTED/DISQUALIFIED lead → "Cannot qualify a terminal lead"; neither `existingCustomerUid` nor `newCustomerDetails` → "Either existingCustomerUid or newCustomerDetails must be provided."

### TC-CRM-014 — Lead qualify by PROMOTING a new customer (CASH_WALK_IN and CREDIT_ACCOUNT)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Lead lifecycle (`POST /api/v1/crm/leads/uid/{uid}/qualify` → CustomerService.create)
- **Permission / Role:** `CRM.LEAD.QUALIFY` — ORG_ADMIN
- **Variation:** qualifyMode = new; run once with customerKind=CREDIT_ACCOUNT and once with CASH_WALK_IN; promoted PartyType is BUSINESS (v1 default in `LeadServiceImpl`)
- **Preconditions / Seed:** a NEW or CONTACTED lead
- **Steps:**
  1. Open the Qualify form; choose "Create new customer".
  2. Fill Customer name (required), select customer kind, optional phone/email/address.
  3. Submit.
- **Test Data:** name="Mlimani Hardware"; kind=CREDIT_ACCOUNT; phone="+255700000099"
- **Expected Result:** a new Parties customer created via CustomerService; lead → QUALIFIED linked to the new customer; `qualifiedAt` set; toast shown.
- **Convention Assertions:** C1 new customer created by name (no uid typed); CRM never writes the customers table directly (promotes via CustomerService); C3 QUALIFY; C9 append-create; C6 axe.
- **Negative / Edge:** blank Customer name → "Customer name is required." (no POST); missing customerKind server-side → `@NotNull` on `NewCustomerDetailsDto`.

### TC-CRM-015 — Lead disqualify (reason required)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Lead lifecycle (`POST /api/v1/crm/leads/uid/{uid}/disqualify`)
- **Permission / Role:** `CRM.LEAD.MANAGE` (scoped) — ORG_ADMIN; also VIEW-only role → action hidden / 403
- **Variation:** lead = NEW, CONTACTED, or QUALIFIED (any non-terminal)
- **Preconditions / Seed:** a non-terminal lead
- **Steps:** open Disqualify form; enter a reason; submit.
- **Test Data:** reason="Budget too low"
- **Expected Result:** status → **DISQUALIFIED** (badge `text-bg-danger`), `disqualifyReason` + `disqualifiedAt` set.
- **Convention Assertions:** C3 RBAC; C9 not a hard delete; C6 axe.
- **Negative / Edge:** blank reason → client "Reason is required." (no POST) and server `@NotBlank` 400 + `chk_lead_disqualify_reason`; disqualify on already-terminal lead → "Cannot disqualify a terminal lead".

### TC-CRM-016 — Lead edit blocked on terminal leads; edit allowed on non-terminal
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Lead edit (`PUT /api/v1/crm/leads/uid/{uid}`)
- **Permission / Role:** `CRM.LEAD.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** non-terminal (editable) vs CONVERTED/DISQUALIFIED (locked)
- **Preconditions / Seed:** one NEW lead and one DISQUALIFIED lead
- **Steps:** (a) edit fields on NEW lead, save; (b) attempt edit on DISQUALIFIED lead.
- **Expected Result:** (a) fields persist, success toast; (b) edit controls disabled (`canEdit = !isTerminal && canManage`); a forced API PUT returns "Cannot edit a terminal lead".
- **Convention Assertions:** C1 no uid shown; C3 RBAC; C9 terminal append-only intent; C6 axe.
- **Negative / Edge:** blank displayName on save → "Display name is required." (no PUT).

### TC-CRM-017 — Lead transition: QUALIFIED → CONVERTED via creating an opportunity from the lead
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Lead↔Opportunity seam (`POST /api/v1/crm/opportunities` with `sourceLeadUid`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` (create) — ORG_ADMIN
- **Variation:** source lead = QUALIFIED
- **Preconditions / Seed:** a QUALIFIED lead (TC-CRM-013/014)
- **Steps:** go to `/admin/crm/opportunities/create`; the source-lead picker lists only QUALIFIED leads (filtered in `loadPickerOptions`); choose the lead by name; complete and submit.
- **Expected Result:** opportunity created; the source lead's status flips to **CONVERTED** with `convertedAt` set; `sourceLeadUid` recorded on the opportunity.
- **Convention Assertions:** C1 lead + customer chosen by name via picker (`<app-uid-picker>` `sourceLeadOptions`/`customerOptions`); C7 scope; C9 lead becomes terminal CONVERTED; C6 axe.
- **Negative / Edge:** attaching a NON-QUALIFIED lead (e.g. NEW) → "Source lead must be QUALIFIED to create an opportunity".

### TC-CRM-020 — Opportunity list loads with scope + four states + pagination
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opportunities list (`/admin/crm/opportunities` · `GET /api/v1/crm/opportunities?companyId&page&size`)
- **Permission / Role:** `CRM.OPPORTUNITY.VIEW` — ORG_ADMIN; also NO-PERMISSION → nav hidden + forbidden
- **Preconditions / Seed:** ≥ 21 opportunities to force pagination
- **Steps:** navigate to `/admin/crm/opportunities`; observe loading→idle; exercise paginator.
- **Expected Result:** rows show opportunityNumber, title, customer, status badge, estimated value formatted as money; `meta` with totalPages ≥ 2; paginator visible.
- **Convention Assertions:** C2 envelope+meta; C4 four-state; C5 paginator; C7 company-scoped; C8 money formatted "CUR 1,234.56"; C6 axe.
- **Negative / Edge:** empty company → empty state + paginator hidden; 403 for VIEW-less role; backend error → error state.

### TC-CRM-021 — Create opportunity (customer + stage via pickers; default probability follows stage)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opportunity create (`/admin/crm/opportunities/create` · `POST /api/v1/crm/opportunities`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` — ORG_ADMIN; also VIEW-only role → create route guarded / 403
- **Variation:** stage = PROPOSAL (default prob 50); winProbability omitted (inherits stage default)
- **Preconditions / Seed:** a customer + active pipeline stages (seeded by V51) in the company
- **Steps:**
  1. Navigate to `/admin/crm/opportunities/create`.
  2. Pick customer by NAME (uid-picker); select a pipeline stage from the active-stage dropdown; enter title; currency defaults to TZS.
  3. Optionally estimated value / expected close date; submit.
- **Test Data:** title="Q3 hardware refresh"; customer="Zanaco Ltd"; stage="PROPOSAL"; currency="TZS"; estimatedValue="5000000"
- **Expected Result:** HTTP 201; redirect to `/admin/crm/opportunities/uid/:uid`; status **OPEN**; `winProbability`=50 (stage default applied because none supplied); success toast with opportunityNumber.
- **Convention Assertions:** C1 customer + stage selected by name (no uid typed/shown; only the human label visible); C2 envelope; C3 MANAGE gate; C8 currency string TZS; C6 axe.
- **Negative / Edge:** blank title/customer/stage/currency → client validation messages (no POST); `@NotBlank` 400 server-side for each; choosing a deactivated stage → "Pipeline stage is deactivated"; stage of another company → "Pipeline stage does not belong to this company".

### TC-CRM-022 — Opportunity detail: add line (product + unit pickers, qty > 0)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opportunity lines (`POST /api/v1/crm/opportunities/uid/{uid}/lines`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` (scoped) — ORG_ADMIN; VIEW-only → add-line hidden / 403
- **Variation:** opportunity = OPEN; product = GOODS (and a SERVICE product as a second pass)
- **Preconditions / Seed:** an OPEN opportunity; ≥ 1 product + ACTIVE unit-of-measure in the company
- **Steps:**
  1. Open opportunity detail; in the Add-line form type a product search term (debounced 300ms) and select a product from results (stored as uid).
  2. Select a unit from the ACTIVE units dropdown; enter qty; optional unit price + discount %.
  3. Add.
- **Test Data:** product="WIDGET-01 — Widget"; unit="EACH"; qty="10"; unitPrice="2500"; discount%="5"
- **Expected Result:** line appended (line_no auto-incremented); opportunity reloaded; line shows product code/name, unit, qty, estimated price; estimated unit price defaults to 0 if omitted.
- **Convention Assertions:** C1 product + unit selected by search/name (no uid shown); C3 MANAGE; C8 line money formatting; C6 axe.
- **Negative / Edge:** qty ≤ 0 → client "Quantity must be > 0." + server `@DecimalMin("0.000001")`/`chk_opportunity_line_qty`; missing product/unit → client messages; discount% outside 0–100 → `chk_opportunity_line_disc`; add-line on a closed (WON/LOST) opportunity → "Cannot modify a closed opportunity".

### TC-CRM-023 — Opportunity detail: remove line
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Opportunity lines (`DELETE /api/v1/crm/opportunities/uid/{uid}/lines/{lineUid}`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** opportunity = OPEN
- **Preconditions / Seed:** an OPEN opportunity with ≥ 1 line
- **Steps:** open detail; click Remove on a line row.
- **Expected Result:** HTTP 204; line removed; opportunity reloads without the line.
- **Convention Assertions:** C1 line referenced by row, not typed uid; C3 MANAGE; C6 axe.
- **Negative / Edge:** remove on closed opportunity → "Cannot modify a closed opportunity"; unknown lineUid → NotFound.

### TC-CRM-024 — Opportunity detail: list lines
- **Type:** Automated (Playwright)
- **Priority:** P3
- **Module / Submodule:** Opportunity lines (`GET /api/v1/crm/opportunities/uid/{uid}/lines`)
- **Permission / Role:** `CRM.OPPORTUNITY.VIEW` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** opportunity with multiple lines
- **Steps:** open detail; observe the lines table ordered by line_no.
- **Expected Result:** lines listed in line_no order with product/unit/qty/price.
- **Convention Assertions:** C1 no uid shown; C8 money; C6 axe.
- **Negative / Edge:** opportunity with 0 lines → empty lines section.

### TC-CRM-025 — Opportunity transition: advance stage (probability follows stage or override)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opportunity lifecycle (`POST /api/v1/crm/opportunities/uid/{uid}/advance-stage`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** advance PROPOSAL → NEGOTIATION (default prob 75); second pass with a manual probability override
- **Preconditions / Seed:** an OPEN opportunity at PROPOSAL; ≥ 2 active stages
- **Steps:** open Advance-stage form; choose target stage from active-stages dropdown; optionally set probability override; submit.
- **Expected Result:** opportunity `pipelineStageId` updated; `winProbability` = stage default (75) unless override supplied; success toast.
- **Convention Assertions:** C1 stage chosen by name (active stages only); C3 MANAGE; C6 axe.
- **Negative / Edge:** no stage selected → "Select a target stage."; advance on WON/LOST → "Cannot modify a closed opportunity"; target stage deactivated/other-company → resolveStage errors.

### TC-CRM-026 — Opportunity transition: OPEN → WON
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Opportunity lifecycle (`POST /api/v1/crm/opportunities/uid/{uid}/win`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` (scoped) — ORG_ADMIN; VIEW-only → Win hidden / 403
- **Variation:** opportunity = OPEN; wonAt provided vs defaulted to now
- **Preconditions / Seed:** an OPEN opportunity
- **Steps:** open Win form; optionally set Won-at date; submit (defaults to now if blank).
- **Expected Result:** status → **WON** (badge `text-bg-success`), `wonAt` stamped (`chk_opportunity_won_at`); edit/add-line/advance/win/lose actions now hidden; Convert still available.
- **Convention Assertions:** C3 MANAGE; C8 date ISO; C9 status not delete; C6 axe.
- **Negative / Edge:** win on a non-OPEN opportunity (already WON/LOST) → "Only OPEN opportunities can be won".

### TC-CRM-027 — Opportunity transition: OPEN → LOST (loss reason required)
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Opportunity lifecycle (`POST /api/v1/crm/opportunities/uid/{uid}/lose`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** opportunity = OPEN
- **Preconditions / Seed:** an OPEN opportunity
- **Steps:** open Lose form; enter loss reason; submit.
- **Test Data:** lossReason="Lost to competitor on price"
- **Expected Result:** status → **LOST** (badge `text-bg-danger`), `lostAt` + `lossReason` set (`chk_opportunity_lost`).
- **Convention Assertions:** C3 MANAGE; C9; C6 axe.
- **Negative / Edge:** blank reason → client "Loss reason is required." (no POST) + server `@NotBlank`; lose on non-OPEN → "Only OPEN opportunities can be lost".

### TC-CRM-028 — Opportunity edit blocked once closed; allowed while OPEN
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Opportunity edit (`PUT /api/v1/crm/opportunities/uid/{uid}`)
- **Permission / Role:** `CRM.OPPORTUNITY.MANAGE` (scoped) — ORG_ADMIN
- **Variation:** OPEN (editable) vs WON/LOST (locked)
- **Preconditions / Seed:** one OPEN and one WON opportunity
- **Steps:** (a) edit title/estimated value/probability/close-date on OPEN, save; (b) attempt edit on WON.
- **Expected Result:** (a) persists, toast; (b) edit controls hidden (`canEdit = isOpen && canManage`); forced PUT → "Cannot modify a closed opportunity".
- **Convention Assertions:** C1 no uid shown; C3; C8; C6 axe.
- **Negative / Edge:** blank title → "Title is required." (no PUT).

### TC-CRM-030 — Convert OPEN opportunity → QUOTATION
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opportunity convert (`POST /api/v1/crm/opportunities/uid/{uid}/convert`)
- **Permission / Role:** `CRM.OPPORTUNITY.CONVERT` (scoped) — ORG_ADMIN; also a role with MANAGE but NOT CONVERT → Convert hidden / 403
- **Variation:** target = QUOTATION; status = OPEN; ≥ 1 line
- **Preconditions / Seed:** an OPEN opportunity with at least one line
- **Steps:** open Convert form; target QUOTATION; optional valid-until (defaults to today+30); convert.
- **Expected Result:** a Quotation is created with the opportunity's customer/currency/lines copied; result shows `convertedDocumentKind=QUOTATION` + document number; opportunity stamped `convertedDocumentUid`; "Open document" link navigates to `/admin/quotations/uid/:uid`.
- **Convention Assertions:** C1 result references quote by number + provides link (uid only in URL); C3 CONVERT gate (denied for MANAGE-without-CONVERT); C7 scope; C6 axe.
- **Negative / Edge:** opportunity with 0 lines → client "Add at least one line before converting." + server "Opportunity has no lines"; converting a LOST opportunity to QUOTATION → "Cannot convert a LOST opportunity".

### TC-CRM-031 — Convert WON opportunity → SALES_ORDER (gate enforced)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Opportunity convert (`POST .../convert` target SALES_ORDER)
- **Permission / Role:** `CRM.OPPORTUNITY.CONVERT` (scoped) — ORG_ADMIN
- **Variation:** target = SALES_ORDER requires status WON
- **Preconditions / Seed:** a WON opportunity with ≥ 1 line
- **Steps:** mark WON (TC-CRM-026); open Convert form; target SALES_ORDER; convert.
- **Expected Result:** Sales Order created with copied lines; `convertedDocumentKind=SALES_ORDER`; link navigates to `/admin/sales-orders/uid/:uid`.
- **Convention Assertions:** C1 SO referenced by number/link; C3 CONVERT; C6 axe.
- **Negative / Edge:** SALES_ORDER convert while OPEN (not WON) → client "Sales Order conversion requires status WON." + server "Converting to a SALES_ORDER requires the opportunity to be WON".

### TC-CRM-032 — Convert is idempotent (second convert returns the first document)
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Opportunity convert idempotency (`uq_opportunity_converted_document`)
- **Permission / Role:** `CRM.OPPORTUNITY.CONVERT` — ORG_ADMIN
- **Preconditions / Seed:** an already-converted opportunity (TC-CRM-030)
- **Steps:** invoke convert again on the same opportunity (any target).
- **Expected Result:** no second document; returns the original `convertedDocumentKind` + `convertedDocumentUid` (idempotent return, document number null on the repeat).
- **Convention Assertions:** C9 append-only/no-duplicate; C2 envelope; C3 CONVERT.
- **Negative / Edge:** the DB unique constraint backstops any race.

### TC-CRM-040 — Pipeline dashboard: board summary by stage (company + branch scope)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Pipeline board (`/admin/crm/pipeline` · `GET /api/v1/crm/pipeline?companyId&branchId`)
- **Permission / Role:** `CRM.PIPELINE.VIEW` — ORG_ADMIN; also NO-PERMISSION / role lacking PIPELINE.VIEW → nav hidden + 403
- **Variation:** branchId required (default branch auto-selected); multi-branch company switch
- **Preconditions / Seed:** open opportunities across multiple stages in the branch
- **Steps:** navigate to `/admin/crm/pipeline`; first company + first branch auto-selected; observe the stage board.
- **Expected Result:** `PipelineSummaryDto` rendered grouped by stage (counts + values); loading→idle.
- **Convention Assertions:** C2 envelope; C4 four-state (incl. forbidden on 403); C7 company+branch scoped; C8 money; C6 axe.
- **Negative / Edge:** branch not selected → no report runs; user not assigned to selected branch → scope enforced (403); 403 → forbidden state distinct from error.

### TC-CRM-041 — Pipeline dashboard: weighted forecast (date range)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Forecast (`GET /api/v1/crm/pipeline/forecast?companyId&branchId&from&to`)
- **Permission / Role:** `CRM.PIPELINE.VIEW` — ORG_ADMIN
- **Variation:** from/to default to current-month-start..+3 months; user-adjusted range
- **Preconditions / Seed:** open opportunities with expectedCloseDate inside the range and win probabilities
- **Steps:** open dashboard; adjust From/To; click Apply.
- **Expected Result:** `ForecastDto` weighted forecast recomputed for the new range.
- **Convention Assertions:** C8 dates ISO yyyy-MM-dd + money string; C4 states; C6 axe.
- **Negative / Edge:** missing from/to → no fetch (client guard); inverted range (from>to) → empty/edge result.

### TC-CRM-042 — Pipeline dashboard: win-rate + cycle-time KPIs
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** KPIs (`GET /api/v1/crm/pipeline/kpis?companyId&branchId&from&to`)
- **Permission / Role:** `CRM.PIPELINE.VIEW` — ORG_ADMIN
- **Preconditions / Seed:** some WON and LOST opportunities in the period
- **Steps:** open dashboard; observe KPI panel; adjust dates → Apply.
- **Expected Result:** `CrmKpiDto` win-rate + average cycle-time displayed for the period.
- **Convention Assertions:** C2 envelope; C4 states; C6 axe.
- **Negative / Edge:** period with no closed deals → zero/empty KPIs (not error).

### TC-CRM-050 — Pipeline stages: list (not paginated; ordered by displayOrder)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Stages list (`/admin/crm/settings/pipeline-stages` · `GET /api/v1/crm/pipeline-stages?companyId`)
- **Permission / Role:** route guarded `CRM.STAGE.MANAGE`; API list gated `CRM.OPPORTUNITY.VIEW` — ORG_ADMIN; also a role with OPPORTUNITY.VIEW but not STAGE.MANAGE → can hit the API but the settings route nav item is hidden
- **Preconditions / Seed:** V51 seeded 5 stages per company
- **Steps:** navigate to settings stages; observe rows ordered by displayOrder.
- **Expected Result:** bare array of stages (QUALIFICATION..CLOSING) with name, displayOrder, defaultProbability, active/status badge; **no paginator** (list is intentionally unpaginated).
- **Convention Assertions:** C4 loading/empty/error/forbidden; C5 N/A (explicitly unpaginated — assert paginator absent); C7 company-scoped; C6 axe.
- **Negative / Edge:** company with no stages → empty state; 403 → forbidden state.

### TC-CRM-051 — Pipeline stage: create (unique name, displayOrder, probability 0–100)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Stage create (`POST /api/v1/crm/pipeline-stages`)
- **Permission / Role:** `CRM.STAGE.MANAGE` — ORG_ADMIN; also a role lacking STAGE.MANAGE → settings route forbidden
- **Preconditions / Seed:** a company
- **Steps:** click "New stage"; enter name, displayOrder, defaultProbability; submit.
- **Test Data:** name="DEMO"; displayOrder=6; defaultProbability=40
- **Expected Result:** HTTP 201; stage appended; success toast; list reloads.
- **Convention Assertions:** C2 envelope; C3 STAGE.MANAGE; C6 axe.
- **Negative / Edge:** duplicate name (e.g. "PROPOSAL") → "Pipeline stage name already exists" (uq_pipeline_stage_company_name); probability outside 0–100 → client "Default probability must be 0–100." + server `@DecimalMin/@DecimalMax` + `chk_pipeline_stage_probability`; non-numeric order → client validation; duplicate displayOrder → `uq_pipeline_stage_company_order`.

### TC-CRM-052 — Pipeline stage: inline edit (rename, reorder, reprobability, toggle active)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Stage edit (`PUT /api/v1/crm/pipeline-stages/uid/{uid}`)
- **Permission / Role:** `CRM.STAGE.MANAGE` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** an existing stage
- **Steps:** click edit on a row; change name/order/probability/active toggle; save.
- **Expected Result:** row updated in place; toast; `active=false` sets the stage inactive (badge changes).
- **Convention Assertions:** C1 no uid shown (row identified visually); C3; C9 deactivate is a flag, not a delete; C6 axe.
- **Negative / Edge:** blank name → "Stage name is required."; probability out of range → client + server reject.

### TC-CRM-053 — Pipeline stage: deactivate (soft, not delete) and effect on opportunity stage selection
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Stage deactivate (`DELETE /api/v1/crm/pipeline-stages/uid/{uid}` → isActive=false)
- **Permission / Role:** `CRM.STAGE.MANAGE` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** a stage with no special protection; an OPEN opportunity create flow open in another tab
- **Steps:** deactivate a stage; then attempt to create/advance an opportunity onto that stage via API.
- **Expected Result:** DELETE returns 204; stage `isActive=false` (record NOT removed); the stage disappears from the active-stage dropdowns (UI filters `s.active`); choosing it via API → "Pipeline stage is deactivated".
- **Convention Assertions:** C9 soft-deactivate (MasterStatus / isActive, not hard delete); C3 STAGE.MANAGE; C6 axe.
- **Negative / Edge:** deactivated stage still resolvable by uid for historical display but rejected for new use.

### TC-CRM-060 — Activity panel: log a NOTE/CALL/EMAIL/MEETING on a lead (historical, no due date)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Activity log (`POST /api/v1/crm/activities`, embedded panel on lead-detail)
- **Permission / Role:** `CRM.ACTIVITY.MANAGE` — ORG_ADMIN; also a role with ACTIVITY.VIEW but not MANAGE → log form/button hidden / API 403
- **Variation:** run per type CALL, EMAIL, MEETING, NOTE; parent = lead
- **Preconditions / Seed:** a lead exists
- **Steps:** open lead detail; in the activity panel open "Log activity"; pick type; enter subject (required) + optional body/occurred-at; submit.
- **Test Data:** type=CALL; subject="Intro call"; body="Discussed needs"
- **Expected Result:** HTTP 201; activity appears at top of the by-lead list (latest-first); activityNumber generated; for non-TASK types the dueDate/assignee fields are not sent.
- **Convention Assertions:** C1 parent lead from URL context (leadUid passed under the hood, not typed); C3 ACTIVITY.MANAGE; C8 occurredAt ISO; C6 axe.
- **Negative / Edge:** blank subject → client "Subject is required." (no POST) + server `@NotBlank`; sending dueDate/assignee on a non-TASK → "Only TASK activities may have due_date or assigneeUserId" (`chk_activity_task_fields`).

### TC-CRM-061 — Activity panel: log a TASK with due date on an opportunity
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Activity log TASK (`POST /api/v1/crm/activities`, panel on opportunity-detail)
- **Permission / Role:** `CRM.ACTIVITY.MANAGE` — ORG_ADMIN
- **Variation:** type = TASK; parent = opportunity; dueDate required for TASK
- **Preconditions / Seed:** an opportunity exists
- **Steps:** open opportunity detail; activity panel; choose type TASK (due-date field appears via `isTask`); enter subject + due date; submit.
- **Test Data:** type=TASK; subject="Send proposal"; dueDate="2026-07-01"
- **Expected Result:** HTTP 201; TASK created with dueDate; appears in by-opportunity list; later surfaces in the open-task inbox.
- **Convention Assertions:** C1 parent opportunity from context; C3 ACTIVITY.MANAGE; C8 dueDate ISO yyyy-MM-dd; C6 axe.
- **Negative / Edge:** TASK without dueDate → "TASK activities must have a due_date."; providing both leadUid and opportunityUid (or neither) on the API → "Exactly one of leadUid or opportunityUid must be provided." (`chk_activity_parent`).

### TC-CRM-062 — Activity panel: paginated by-lead / by-opportunity lists, latest-first
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Activity lists (`GET /api/v1/crm/activities/by-lead/{leadUid}`, `.../by-opportunity/{opportunityUid}`)
- **Permission / Role:** `CRM.ACTIVITY.VIEW` (scoped on lead / opportunity uid) — ORG_ADMIN; role lacking ACTIVITY.VIEW → panel shows nothing / API 403
- **Preconditions / Seed:** ≥ 11 activities on one lead (panel size = 10) to force pagination
- **Steps:** open lead detail; observe the activity panel list + paginator; page through.
- **Expected Result:** activities ordered occurredAt-desc; paginator visible at size 10; `meta` reflects totals.
- **Convention Assertions:** C2 envelope+meta; C4 loading/empty/error (panel has its own states); C5 paginator; C7 scoped to the lead's/opportunity's company; C6 axe.
- **Negative / Edge:** lead with 0 activities → empty panel state; cross-tenant lead uid → 403.

### TC-CRM-063 — Open-task inbox: list + complete a TASK
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Open-task inbox (`/admin/crm/activities` · `GET /api/v1/crm/activities/open-tasks?companyId[&assigneeUserId]`; `POST .../uid/{uid}/complete`)
- **Permission / Role:** list `CRM.ACTIVITY.VIEW`; complete `CRM.ACTIVITY.MANAGE` (scoped) — ORG_ADMIN; also a role with VIEW but not MANAGE → Complete button hidden / 403; NO-PERMISSION → nav hidden + forbidden
- **Variation:** company scope; optional assignee filter
- **Preconditions / Seed:** ≥ 21 open TASK activities (force 2 pages at size 20)
- **Steps:**
  1. Navigate to `/admin/crm/activities`; first company auto-selected; observe open tasks list.
  2. Exercise paginator.
  3. Click "Complete" on a task row.
- **Expected Result:** only open TASKs listed (done=false); on complete → task disappears from inbox (now done), success toast; list reloads.
- **Convention Assertions:** C2 envelope+meta; C4 loading/empty/error/forbidden; C5 paginator; C7 company-scoped (and `ix_activities_open_tasks` semantics); C3 MANAGE gate on complete; C6 axe.
- **Negative / Edge:** complete on a non-TASK activity → "Only TASK activities can be completed"; complete an already-done task → "Activity is already done."; empty company → empty inbox state.

### TC-CRM-064 — Activity detail fetch by uid (scope-guarded)
- **Type:** Both
- **Priority:** P3
- **Module / Submodule:** Activity get (`GET /api/v1/crm/activities/uid/{uid}`)
- **Permission / Role:** `CRM.ACTIVITY.VIEW` (scoped) — ORG_ADMIN
- **Preconditions / Seed:** an activity exists
- **Steps:** call the endpoint with the activity uid (used internally by panels).
- **Expected Result:** ActivityDto returned for same-company user.
- **Convention Assertions:** C2 envelope; C7 scope; C3 VIEW.
- **Negative / Edge:** cross-tenant uid → 403; unknown uid → NotFound.

---

## Cross-cutting / convention test cases

### TC-CRM-070 — Multi-tenant isolation across all CRM lists and detail screens
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All CRM controllers (scope via `ScopeGuard.assertCanActIn`)
- **Permission / Role:** full CRM perms — runs as an ORG_ADMIN of tenant A
- **Variation:** tenant A vs tenant B; companyId param + entity company ownership
- **Preconditions / Seed:** two organisations/companies, each with its own leads/opportunities/stages/activities
- **Steps:** as tenant-A user, (a) list leads/opportunities for company A; (b) attempt `GET .../uid/{uid}` for a tenant-B lead/opportunity/activity; (c) attempt list with `companyId` of tenant B.
- **Expected Result:** (a) only company-A records; (b) 403 from ScopeGuard; (c) 403 — no cross-tenant leakage.
- **Convention Assertions:** C7 multi-tenancy enforced everywhere; C3 RBAC; C2 error envelope.
- **Negative / Edge:** rootadmin (superuser) DOES see cross-tenant — contrast assertion that only the bootstrap superuser bypasses scope.

### TC-CRM-071 — Branch context: acting branch must be assigned; create stamps active branch
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Lead/Opportunity/Activity create (require active branch from context)
- **Permission / Role:** `CRM.LEAD.MANAGE` / `CRM.OPPORTUNITY.MANAGE` / `CRM.ACTIVITY.MANAGE`
- **Variation:** user assigned to ONE branch vs MANY vs ALL; default vs non-default branch; acting in an unassigned branch
- **Preconditions / Seed:** a multi-branch company; a user assigned to branch B1 only
- **Steps:** (a) with active branch B1 (X-Branch-Uid), create a lead — succeeds, `branchId=B1`; (b) switch active branch to a non-default assigned branch and create — stamps that branch; (c) attempt to act in branch B2 the user is NOT assigned to.
- **Expected Result:** (a)(b) created records carry the active branchId; (c) denied (scope/branch enforcement) — and with no active branch, "No active branch in context.".
- **Convention Assertions:** C7 branch scoping; C1 branch chosen via header/switcher (not typed uid); C3 RBAC.
- **Negative / Edge:** pipeline board requires an explicit branchId param — verify the dashboard's branch switch re-scopes results (TC-CRM-040).

### TC-CRM-072 — uid never shown to users; resources always chosen via picker by name
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** All CRM screens (`<app-uid-picker>` in lead-detail qualify, opportunity-create; product/unit search in opportunity-detail; lead source-lead picker)
- **Permission / Role:** full CRM perms — ORG_ADMIN
- **Steps:** across leads list/detail, opportunity create/detail, stages, activities: scan visible text for any 26-char uid; verify every cross-resource reference (customer, source lead, product, unit, pipeline stage) is selected by human name/code.
- **Expected Result:** no raw uid rendered in tables/labels/detail text; uid appears only inside `/admin/.../uid/:uid` URLs; pickers present where a resource is referenced; the user reference (leadNumber/opportunityNumber/activityNumber) is what is shown, never the uid.
- **Convention Assertions:** C1 uid-not-shown / picker-used (the central convention); C6 axe (pickers keyboard-operable, labelled).
- **Negative / Edge:** the opportunity-create "Customer UID is required." copy is internal validation only — assert the user still selects by name, never typing a uid.

### TC-CRM-073 — Money + date formatting across CRM
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Opportunity list/detail, lines, pipeline forecast/KPIs
- **Permission / Role:** `CRM.OPPORTUNITY.VIEW` / `CRM.PIPELINE.VIEW`
- **Steps:** inspect estimated value, line prices, forecast totals, and all dates (expectedCloseDate, dueDate, wonAt) across the CRM screens.
- **Expected Result:** money rendered as a currency string "CUR 1,234.56" (currency on the wire is a string); dates ISO yyyy-MM-dd; base currency TZS default on create.
- **Convention Assertions:** C8 money/date conventions; C2 string-money on the wire; C6 axe.
- **Negative / Edge:** zero estimated value shows formatted 0.00, not blank.

### TC-CRM-074 — RBAC matrix: each CRM action denied for a role lacking its exact permission
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** All CRM endpoints
- **Permission / Role:** one CUSTOM role per scenario, granted all CRM perms EXCEPT the one under test
- **Steps:** for each permission (`CRM.LEAD.MANAGE`, `CRM.LEAD.QUALIFY`, `CRM.OPPORTUNITY.MANAGE`, `CRM.OPPORTUNITY.CONVERT`, `CRM.ACTIVITY.MANAGE`, `CRM.STAGE.MANAGE`, `CRM.PIPELINE.VIEW`, and each `*.VIEW`), drive the corresponding action with a role missing exactly that code.
- **Expected Result:** UI control hidden/disabled per the component `canX` computed signals; direct API call returns HTTP 403; the matching VIEW-gated screen shows forbidden state.
- **Convention Assertions:** C3 RBAC by permission code (never role name); C4 forbidden state; C2 403 envelope.
- **Negative / Edge:** confirm the cross-gates: stage list/getByUid use `CRM.OPPORTUNITY.VIEW` (a STAGE.MANAGE-only role can still hit them only if it also has OPPORTUNITY.VIEW); activity by-lead requires `CRM.ACTIVITY.VIEW` scoped on the lead.
