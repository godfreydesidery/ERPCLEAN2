# 20 — Projects (Job Costing)

End-to-end test cases for the PROJECTS domain: project CRUD + lifecycle, project tasks,
timesheets (record + paged view), issue-materials-to-project (COGS at moving-average tagged
to the project), and the costing read models (per-project P&L and the cross-project WIP report).
All endpoints are tenant-scoped (company + branch) and gated by `PROJECTS.*` permission codes.

## Modules / submodules covered

| Submodule | Frontend route | API base path | Controller |
|---|---|---|---|
| Project list + inline create | `/admin/projects` | `/api/v1/projects` | `ProjectController` |
| Project detail / edit / lifecycle (tasks, timesheets, issue, P&L panels) | `/admin/projects/uid/:uid` | `/api/v1/projects` (+ task/timesheet/issue/costing bases below) | `ProjectController` + others |
| Project tasks | embedded in project detail (Tasks panel) | `/api/v1/project-tasks` | `ProjectTaskController` |
| Project timesheets | embedded in project detail (Timesheets panel) | `/api/v1/project-timesheets` | `ProjectTimesheetController` |
| Issue materials to project ("Issue to Job") | embedded in project detail (Issue panel) | `/api/v1/project-issues` | `IssueToProjectController` |
| Project P&L | embedded in project detail (P&L report button) | `/api/v1/project-costing/projects/uid/{uid}/pnl` | `ProjectCostingController` |
| Cross-project WIP report | `/admin/projects/wip-report` | `/api/v1/project-costing/wip` | `ProjectCostingController` |

Nav (shell.component.ts): top-level "Projects" group → "Projects" (`/admin/projects`, gated `PROJECTS.PROJECT.VIEW`) and "WIP Report" (`/admin/projects/wip-report`, gated `PROJECTS.COSTING.VIEW`).

Backend-only / embedded notes:
- There is **no list-all-tasks list screen** and **no list-all-timesheets list screen** — both are panels inside the project-detail screen. `getTaskByUid` / `getProjectPnl` are called from the detail screen, not from standalone routes.
- The permission `PROJECTS.TAG.MANAGE` is seeded (V68) but **not referenced by any of the five controllers in scope** — it is consumed by foreign modules (GL/AP/Sales project-tagging via `ProjectTagResolver`). It is listed for completeness; out of scope for direct UI assertions here.
- Issue-to-project reuses the ADR-0020 COGS engine; the COGS GL leg posts in `REQUIRES_NEW`, and a null moving-average cost **skips the COGS leg but still deducts qty** (WARN + anomaly) — a real edge to test.

## Permission codes in scope (EXACT — from V68__projects_permissions.sql + @PreAuthorize)

| Code | Used by | Notes |
|---|---|---|
| `PROJECTS.PROJECT.VIEW` | `GET /projects` (list), `GET /projects/uid/{uid}` (scoped), route guards | view list + detail |
| `PROJECTS.PROJECT.CREATE` | `POST /projects` | create project |
| `PROJECTS.PROJECT.MANAGE` | `PUT /projects/uid/{uid}`, `PATCH .../status`, `PATCH .../archive` (all scoped) | edit + lifecycle + archive |
| `PROJECTS.TASK.VIEW` | `GET /project-tasks/uid/{uid}` (scoped `projecttask`), `GET /project-tasks/project/uid/{projectUid}` (scoped `project`) | view tasks |
| `PROJECTS.TASK.MANAGE` | `POST/PUT /project-tasks...`, `PATCH .../deactivate` (scoped) | create/edit/deactivate task |
| `PROJECTS.TIMESHEET.VIEW` | `GET /project-timesheets/project/uid/{projectUid}` (scoped) | view paged timesheets |
| `PROJECTS.TIMESHEET.RECORD` | `POST /project-timesheets/project/uid/{projectUid}` (scoped) | record timesheet |
| `PROJECTS.ISSUE.CREATE` | `POST /project-issues` (`@perm.has`) | issue materials to project |
| `PROJECTS.COSTING.VIEW` | `GET /project-costing/projects/uid/{uid}/pnl` (scoped), `GET /project-costing/wip` (`@perm.has`) | P&L + WIP report |
| `PROJECTS.TAG.MANAGE` | (foreign modules only — not on these controllers) | seeded, out of direct scope |

Scoping note: `@perm.scoped(#uid,'project'|'projecttask',CODE)` enforces both the permission AND that the
resource belongs to the caller's company/assigned branch — a user with the permission but in the wrong
tenant/branch is still denied (C3 + C7).

## Type / role variations exercised

| Dimension | Values exercised |
|---|---|
| User roles | `rootadmin` (superuser bypass — positive only); ORG_ADMIN (all 10 PROJECTS.*); ACCOUNTANT / SALES_MANAGER (costing-view candidates); STOREKEEPER (issue candidate); a CUSTOM role (e.g. VIEW-only, or VIEW+TIMESHEET.RECORD); a NO-PERMISSION user (forbidden/empty-nav) |
| ProjectStatus lifecycle | DRAFT, ACTIVE, ON_HOLD, COMPLETED (terminal), CANCELLED (terminal) — every legal + illegal transition |
| MasterStatus | ACTIVE (default list filter), INACTIVE (deactivated task), ARCHIVED (archived project) |
| ProjectCostType | MATERIAL (issue-to-project derives this), plus SUBCONTRACT/LABOUR/OVERHEAD/OTHER appear in P&L cost-by-type rows |
| Customer linkage | project with NO customer (internal) vs project linked to a CREDIT_ACCOUNT customer (picker) |
| Manager linkage | project with NO manager vs project with a manager user (picker) |
| Branch / company | default vs non-default branch; single- vs multi-branch company; user assigned to ONE vs MANY branches; acting in a branch NOT assigned (denied); cross-tenant isolation |
| Product type (issue lines) | GOODS (stockable — valid) vs SERVICE (not stockable — edge) |

---

## TEST CASES

### TC-PROJ-001 — Projects nav group visible only with PROJECTS.PROJECT.VIEW
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Projects nav (`/admin/projects`, `/admin/projects/wip-report`)
- **Permission / Role:** `PROJECTS.PROJECT.VIEW` (Projects link) + `PROJECTS.COSTING.VIEW` (WIP Report link) — runs as ORG_ADMIN (sees both); also as NO-PERMISSION user → both items hidden
- **Preconditions / Seed:** Two users: ORG_ADMIN (all PROJECTS.*), NO-PERMISSION user.
- **Steps:**
  1. Log in as ORG_ADMIN; open the shell nav.
  2. Expand the "Projects" group; assert "Projects" and "WIP Report" links are present.
  3. Log out; log in as the NO-PERMISSION user.
  4. Assert the "Projects" nav group / its child links are not rendered.
- **Test Data:** n/a
- **Expected Result:** ORG_ADMIN sees both nav items; NO-PERMISSION user sees neither.
- **Convention Assertions:** C3 RBAC (nav hidden without permission); C6 axe scan on the nav for ORG_ADMIN.
- **Negative / Edge:** A user with only `PROJECTS.COSTING.VIEW` (no PROJECT.VIEW) sees "WIP Report" but not "Projects".

### TC-PROJ-002 — Project list loads, four states, pagination, company scope
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Project list (`/admin/projects` · `GET /api/v1/projects?companyId=`)
- **Permission / Role:** `PROJECTS.PROJECT.VIEW` — runs as ORG_ADMIN; also as NO-PERMISSION user → forbidden / route guard blocks
- **Variation:** company = the seeded company with ≥ 21 ACTIVE projects (to force ≥ 2 pages at size 20)
- **Preconditions / Seed:** Seed ≥ 21 projects via `POST /api/v1/projects` (TC-PROJ-010) so `totalPages ≥ 2`.
- **Steps:**
  1. Navigate to `/admin/projects` as ORG_ADMIN.
  2. Observe the loading state, then the populated table (Project #, name, status badge, dates, budget).
  3. Confirm the company selector shows the user's company; the list is scoped to it.
  4. Use the shared paginator: First / Previous / page-numbers / Next / Last.
  5. Navigate to `/admin/projects` as the NO-PERMISSION user (route guard `requirePermission('PROJECTS.PROJECT.VIEW')`).
- **Test Data:** companyId = selected company's numeric id (sent as query param by the UI, not typed by user).
- **Expected Result:** Loading→idle table; `ApiResponse<List>` with `meta {page,size,totalElements,totalPages,hasNext}`; paginator navigates pages; NO-PERMISSION user is blocked (forbidden / redirected).
- **Convention Assertions:** C2 envelope+meta; C3 RBAC; C4 four states (loading/empty/error/forbidden); C5 paginator (First/Prev/numbers/Next/Last, self-hidden at 1 page); C8 money "CUR 1,234.56" + ISO dates; C6 axe.
- **Negative / Edge:** Empty state for a company with no ACTIVE projects; error state if the list call fails (assert distinct empty vs error rendering).

### TC-PROJ-003 — Project list filters by ProjectStatus and MasterStatus
- **Type:** Both
- **Priority:** P2
- **Module / Submodule:** Project list (`/admin/projects` · `GET /api/v1/projects?status=&projectStatus=`)
- **Permission / Role:** `PROJECTS.PROJECT.VIEW` — runs as ORG_ADMIN
- **Variation:** projectStatus filter = ACTIVE; status (MasterStatus) filter = ARCHIVED
- **Preconditions / Seed:** ≥ 1 project per ProjectStatus (DRAFT/ACTIVE/ON_HOLD/COMPLETED/CANCELLED) and ≥ 1 ARCHIVED project.
- **Steps:**
  1. As ORG_ADMIN, open `/admin/projects`.
  2. (API) Confirm `GET /projects?companyId=&projectStatus=ACTIVE` returns only ACTIVE projects; default `status` is `ACTIVE` (MasterStatus) when omitted.
  3. (API) Confirm `GET /projects?companyId=&status=ARCHIVED` returns the archived project (and excludes it from the default ACTIVE filter).
- **Test Data:** projectStatus=ACTIVE; status=ARCHIVED
- **Expected Result:** Filtered list matches the requested ProjectStatus / MasterStatus; default MasterStatus filter is ACTIVE (archived projects hidden by default).
- **Convention Assertions:** C2 envelope; C9 soft-delete (archived excluded from default list, still retrievable by filter).
- **Negative / Edge:** Invalid enum value in `projectStatus` query → 400 (enum bind failure).

### TC-PROJ-010 — Create project (minimal: name only) via inline form
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Project list inline create (`/admin/projects` · `POST /api/v1/projects`)
- **Permission / Role:** `PROJECTS.PROJECT.CREATE` — runs as ORG_ADMIN; also as a VIEW-only CUSTOM role → "New Project" hidden / 403 on POST
- **Variation:** project with NO customer, NO manager; branch = company default
- **Preconditions / Seed:** A company with a default branch exists.
- **Steps:**
  1. As ORG_ADMIN, navigate to `/admin/projects`.
  2. Click "New Project" (visible only when `canCreate` = `PROJECTS.PROJECT.CREATE`).
  3. Fill Name; leave dates/budget/notes blank; submit.
  4. Observe success alert showing the generated project number; the list reloads.
- **Test Data:** name = "Office Fit-out 2026".
- **Expected Result:** 200 with `ProjectDto`; `projectNumber` matches `PRJ-####` (e.g. `PRJ-0001`); `projectStatus = DRAFT`; `status = ACTIVE`; `currency` defaulted to the company base currency (TZS); record appears in list. Success alert shows the project number.
- **Convention Assertions:** C1 (project number `PRJ-####` shown — NOT the uid; uid only in URL after navigating to detail); C2 envelope; C3 RBAC (button hidden + 403 for VIEW-only); C8 currency string.
- **Negative / Edge:** Blank name → client-side "Project name is required." (no POST); name > 160 chars → 400 (`@Size(max=160)`); VIEW-only role → 403.

### TC-PROJ-011 — Create project with customer + manager chosen via picker (by name)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Project detail edit (`/admin/projects/uid/:uid` · `PUT /api/v1/projects/uid/{uid}`) — customer/manager are set on the **edit** form via `<app-uid-picker>` (the inline create form only takes name/dates/budget/notes)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` — runs as ORG_ADMIN; also as VIEW-only role → edit form read-only / 403
- **Variation:** customer = BUSINESS + CREDIT_ACCOUNT; manager = an existing user
- **Preconditions / Seed:** A DRAFT project (TC-PROJ-010); a BUSINESS/CREDIT_ACCOUNT customer; a user to be the manager.
- **Steps:**
  1. Create a project, then open its detail at `/admin/projects/uid/:uid`.
  2. In the edit panel, open the Customer `<app-uid-picker>` and choose the customer **by display name**.
  3. Open the Manager `<app-uid-picker>` and choose the user **by name** (hint = username).
  4. Save.
- **Test Data:** customer display name "Acme Traders Ltd"; manager "Jane Mwita".
- **Expected Result:** `PUT` succeeds; returned `ProjectDto.customerId` set to the chosen customer's id, `managerUserId` set. The screen never displays raw uids; selection was by name.
- **Convention Assertions:** C1 (picker chooses by name; uid stored under the hood; no raw uid on screen; uid only in URL path); C2 envelope; C3 RBAC.
- **Negative / Edge:** Customer uid that belongs to a different company → `NotFoundException` 404 ("Customer not found"); clearing the customer picker and saving → `customerId` set to null (update sets null when `customerUid` omitted).

### TC-PROJ-012 — Edit project mutable fields (name, dates, budget, notes)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Project detail edit (`/admin/projects/uid/:uid` · `PUT /api/v1/projects/uid/{uid}`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` — runs as ORG_ADMIN; also as VIEW-only → save disabled / 403
- **Preconditions / Seed:** An existing project.
- **Steps:**
  1. Open the project detail.
  2. Edit name, planned start/end dates, budget, notes; save.
  3. Re-load and confirm persisted values.
- **Test Data:** name="Office Fit-out 2026 (rev B)"; start=2026-07-01; end=2026-12-31; budget=15000000; notes="phase 2".
- **Expected Result:** `PUT` returns updated `ProjectDto`; values persisted; `updatedAt`/`updatedBy` stamped (audit `PROJECT_UPDATE`).
- **Convention Assertions:** C2 envelope; C3 RBAC; C8 dates ISO yyyy-MM-dd, budget money string.
- **Negative / Edge:** Blank name → client validation blocks save; name > 160 → 400; notes > 500 chars → 400 (`@Size(max=500)`).

### TC-PROJ-013 — getByUid returns project; uid only in URL path
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Project detail (`/admin/projects/uid/:uid` · `GET /api/v1/projects/uid/{uid}`)
- **Permission / Role:** `PROJECTS.PROJECT.VIEW` (scoped) — runs as ORG_ADMIN; also as NO-PERMISSION user → forbidden
- **Preconditions / Seed:** An existing project in the user's company.
- **Steps:**
  1. From the list, click a project row to navigate to `/admin/projects/uid/:uid`.
  2. Assert the header shows project number + name + status badge (not the uid).
  3. Assert the uid appears only in the browser URL path.
- **Test Data:** n/a
- **Expected Result:** `GET .../uid/{uid}` returns the `ProjectDto`; detail header renders human fields; loading/error states handled.
- **Convention Assertions:** C1 (uid in URL only, never in visible labels); C4 four states (the detail screen has loading/error for the project header); C6 axe.
- **Negative / Edge:** Unknown uid → 404 ("Project not found") → error state; project in another company → scoped 403 / not found (C7).

### TC-PROJ-014 — Archive project (soft-delete to MasterStatus ARCHIVED)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Project detail (`/admin/projects/uid/:uid` · `PATCH /api/v1/projects/uid/{uid}/archive`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` (scoped) — runs as ORG_ADMIN; also as VIEW-only → archive button hidden / 403
- **Preconditions / Seed:** An existing project.
- **Steps:**
  1. Open the project detail; click "Archive".
  2. Confirm success alert; the header `status` badge shows ARCHIVED.
  3. Navigate to `/admin/projects` — the project no longer appears under the default (ACTIVE) MasterStatus filter.
- **Test Data:** n/a
- **Expected Result:** `PATCH .../archive` sets `status = ARCHIVED` (ProjectStatus unchanged); audit `PROJECT_ARCHIVE`; project hidden from default list, retrievable via `status=ARCHIVED` filter.
- **Convention Assertions:** C9 soft-delete (archived, not hard-deleted); C2 envelope; C3 RBAC.
- **Negative / Edge:** Archive on an already-archived project — still succeeds (idempotent set); no hard delete endpoint exists.

### TC-PROJ-020 — Lifecycle transition DRAFT → ACTIVE (activate)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Project detail lifecycle (`PATCH /api/v1/projects/uid/{uid}/status?targetStatus=ACTIVE`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` (scoped) — runs as ORG_ADMIN; also as VIEW-only → buttons hidden / 403
- **Variation:** from DRAFT
- **Preconditions / Seed:** A DRAFT project.
- **Steps:**
  1. Open the DRAFT project detail; the only lifecycle button shown is "Activate".
  2. Click "Activate".
- **Test Data:** targetStatus=ACTIVE
- **Expected Result:** `projectStatus = ACTIVE`; `activatedAt` stamped; audit `PROJECT_STATUS_CHANGE` from=DRAFT to=ACTIVE; "Hold"/"Complete"/"Cancel" now offered.
- **Convention Assertions:** C2 envelope; C3 RBAC; C9 append-only (status change is auditable, not destructive).
- **Negative / Edge:** see illegal transitions in TC-PROJ-025.

### TC-PROJ-021 — Lifecycle transition ACTIVE → ON_HOLD (hold) and ON_HOLD → ACTIVE (resume)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Project detail lifecycle (`PATCH .../status?targetStatus=ON_HOLD` then `=ACTIVE`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` (scoped) — runs as ORG_ADMIN
- **Variation:** from ACTIVE then from ON_HOLD
- **Preconditions / Seed:** An ACTIVE project.
- **Steps:**
  1. Open the ACTIVE project; click "Hold" → status ON_HOLD.
  2. Click "Resume" → status back to ACTIVE.
- **Test Data:** targetStatus=ON_HOLD; targetStatus=ACTIVE
- **Expected Result:** ON_HOLD has no timestamp stamp; resume to ACTIVE re-stamps `activatedAt`; both audited.
- **Convention Assertions:** C2 envelope; C3 RBAC.
- **Negative / Edge:** ON_HOLD → ON_HOLD is illegal (ON_HOLD only reachable from ACTIVE) → 409/illegal-state.

### TC-PROJ-022 — Lifecycle transition ACTIVE → COMPLETED (terminal)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Project detail lifecycle (`PATCH .../status?targetStatus=COMPLETED`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` (scoped) — runs as ORG_ADMIN
- **Variation:** from ACTIVE (also valid from ON_HOLD)
- **Preconditions / Seed:** An ACTIVE project.
- **Steps:**
  1. Open the ACTIVE project; click "Complete".
  2. Confirm status COMPLETED; no further lifecycle buttons offered (terminal — UI `isTerminal` true).
- **Test Data:** targetStatus=COMPLETED
- **Expected Result:** `projectStatus = COMPLETED`; `completedAt` stamped; audited; lifecycle buttons hidden.
- **Convention Assertions:** C2 envelope; C3 RBAC.
- **Negative / Edge:** COMPLETED → anything is illegal (see TC-PROJ-025); ON_HOLD → COMPLETED is also legal (cover as a second variation).

### TC-PROJ-023 — Lifecycle transition to CANCELLED from each non-terminal state
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Project detail lifecycle (`PATCH .../status?targetStatus=CANCELLED`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` (scoped) — runs as ORG_ADMIN
- **Variation:** from DRAFT, from ACTIVE, from ON_HOLD (all legal)
- **Preconditions / Seed:** Three projects, one each in DRAFT / ACTIVE / ON_HOLD.
- **Steps:**
  1. For each project, click "Cancel".
- **Test Data:** targetStatus=CANCELLED
- **Expected Result:** All three move to CANCELLED; `cancelledAt` stamped; audited; terminal afterward.
- **Convention Assertions:** C2 envelope; C3 RBAC.
- **Negative / Edge:** CANCELLED → anything (incl. CANCELLED) is illegal (TC-PROJ-025).

### TC-PROJ-024 — Cannot revert any state to DRAFT
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Lifecycle (`PATCH .../status?targetStatus=DRAFT`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` — runs as ORG_ADMIN
- **Preconditions / Seed:** An ACTIVE project.
- **Steps:**
  1. Call `PATCH .../status?targetStatus=DRAFT`.
- **Test Data:** targetStatus=DRAFT
- **Expected Result:** Rejected — `validateTransition` returns false for any → DRAFT (`IllegalStateException`, "Invalid project status transition"). Status unchanged.
- **Convention Assertions:** C2 envelope (error array); C9 append-only.
- **Negative / Edge:** The UI never offers a "back to DRAFT" button — this is API-only enforcement.

### TC-PROJ-025 — Illegal lifecycle transitions are rejected (exhaustive matrix)
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** Lifecycle (`PATCH .../status?targetStatus=`)
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` — runs as ORG_ADMIN
- **Preconditions / Seed:** Projects in each state, or re-create per assertion.
- **Steps / Matrix (each row = one rejected call → IllegalStateException, status unchanged):**
  1. DRAFT → ON_HOLD (only ACTIVE→ON_HOLD allowed) — reject.
  2. DRAFT → COMPLETED (needs ACTIVE/ON_HOLD) — reject.
  3. ACTIVE → DRAFT — reject.
  4. ACTIVE → ACTIVE (no self-transition path; only DRAFT/ON_HOLD→ACTIVE) — reject.
  5. ON_HOLD → ON_HOLD — reject.
  6. COMPLETED → ACTIVE / ON_HOLD / COMPLETED / CANCELLED — all reject (terminal).
  7. CANCELLED → ACTIVE / ON_HOLD / COMPLETED / CANCELLED / DRAFT — all reject (terminal).
- **Test Data:** as above
- **Expected Result:** Every listed call returns an error (IllegalStateException surfaced in `ApiResponse.errors`); project status unchanged in each case.
- **Convention Assertions:** C2 envelope error array; C9.
- **Negative / Edge:** Legal transitions to confirm the matrix is exact: DRAFT→ACTIVE→ON_HOLD→ACTIVE→COMPLETED; and *→CANCELLED for non-terminal.

### TC-PROJ-026 — Lifecycle buttons rendered per current status (UI gating)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Project detail header
- **Permission / Role:** `PROJECTS.PROJECT.MANAGE` — runs as ORG_ADMIN
- **Preconditions / Seed:** Projects in DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED.
- **Steps:**
  1. DRAFT detail → only "Activate" + "Cancel" offered (no Hold/Complete/Resume).
  2. ACTIVE detail → "Hold", "Complete", "Cancel".
  3. ON_HOLD detail → "Resume", "Complete", "Cancel".
  4. COMPLETED / CANCELLED detail → no lifecycle buttons (`isTerminal`).
- **Test Data:** n/a
- **Expected Result:** Button set matches legal transitions; each button carries an aria-label (e.g. "Activate project", "Hold project", "Resume project", "Complete project", "Cancel project").
- **Convention Assertions:** C6 a11y (aria-labels on lifecycle buttons); C3 (buttons absent entirely for a user without MANAGE).
- **Negative / Edge:** VIEW-only user sees the header but no lifecycle buttons; direct API call still 403.

### TC-PROJ-030 — Create project task (billable + planned hours)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Tasks panel (`/admin/projects/uid/:uid` · `POST /api/v1/project-tasks/project/uid/{projectUid}`)
- **Permission / Role:** `PROJECTS.TASK.MANAGE` (scoped on project) — runs as ORG_ADMIN; also as a role lacking TASK.MANAGE → "Add Task" hidden / 403
- **Variation:** billable = true, plannedHours = 40
- **Preconditions / Seed:** An ACTIVE project.
- **Steps:**
  1. Open the project detail; in the Tasks panel click "Add Task".
  2. Enter task code, name, planned hours; toggle billable on; save.
- **Test Data:** taskCode="SITE-PREP"; name="Site preparation"; plannedHours=40; billable=true.
- **Expected Result:** `POST` returns `ProjectTaskDto` with `status = ACTIVE`, `parentId = null`; task appears in the panel list; audit `PROJECT_TASK_CREATE`.
- **Convention Assertions:** C2 envelope; C3 RBAC; C1 (no uid shown — task referenced by code/name).
- **Negative / Edge:** Blank task code → client "Task code is required."; blank name → "Task name is required."; taskCode > 30 → 400 (`@Size(max=30)`); name > 160 → 400.

### TC-PROJ-031 — List tasks by project; status filter; not paginated
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Tasks panel (`GET /api/v1/project-tasks/project/uid/{projectUid}?status=`)
- **Permission / Role:** `PROJECTS.TASK.VIEW` (scoped on project) — runs as ORG_ADMIN; also as NO-permission → panel error/forbidden
- **Variation:** status default ACTIVE; status=INACTIVE for deactivated tasks
- **Preconditions / Seed:** A project with ≥ 1 ACTIVE and ≥ 1 INACTIVE task.
- **Steps:**
  1. Open the project detail; Tasks panel renders the ACTIVE tasks (returns a **plain array**, not paginated).
  2. (API) `GET .../project/uid/{uid}?status=INACTIVE` returns the deactivated task(s).
- **Test Data:** n/a
- **Expected Result:** Default filter returns ACTIVE tasks; INACTIVE filter returns deactivated; response is `List<ProjectTaskDto>` (no PageMeta — confirm no paginator on the tasks panel).
- **Convention Assertions:** C4 (tasks panel loading/empty/error states); C9 (deactivated task excluded from default).
- **Negative / Edge:** Empty task list → empty-state message; task list for a project in another tenant → scoped denied.

### TC-PROJ-032 — Edit project task
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Tasks panel (`PUT /api/v1/project-tasks/uid/{uid}`)
- **Permission / Role:** `PROJECTS.TASK.MANAGE` (scoped on projecttask) — runs as ORG_ADMIN
- **Preconditions / Seed:** A project with a task.
- **Steps:**
  1. In the Tasks panel, click edit on a task row; the form is prefilled.
  2. Change name + planned hours + billable; save.
- **Test Data:** name="Site preparation (rev)"; plannedHours=48; billable=false.
- **Expected Result:** `PUT` returns updated `ProjectTaskDto`; values persisted; audit `PROJECT_TASK_UPDATE`. (Note: update uses the same `CreateProjectTaskRequest` shape — taskCode is editable.)
- **Convention Assertions:** C2 envelope; C3 RBAC.
- **Negative / Edge:** Blank code/name on edit → client validation; size violations → 400.

### TC-PROJ-033 — Deactivate project task (MasterStatus INACTIVE)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Tasks panel (`PATCH /api/v1/project-tasks/uid/{uid}/deactivate`)
- **Permission / Role:** `PROJECTS.TASK.MANAGE` (scoped) — runs as ORG_ADMIN; also as TASK.VIEW-only → deactivate hidden / 403
- **Preconditions / Seed:** A project with an ACTIVE task.
- **Steps:**
  1. In the Tasks panel, click "Deactivate" on the task row.
  2. Confirm the task drops out of the default (ACTIVE) list.
- **Test Data:** n/a
- **Expected Result:** `status = INACTIVE`; audit `PROJECT_TASK_DEACTIVATE`; soft-delete (not hard-deleted), still retrievable via `status=INACTIVE`.
- **Convention Assertions:** C9 soft-delete; C2 envelope; C3 RBAC.
- **Negative / Edge:** Deactivating an already-INACTIVE task — idempotent; no hard-delete path.

### TC-PROJ-034 — getTaskByUid scoped view
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** Task by uid (`GET /api/v1/project-tasks/uid/{uid}`, scoped `projecttask` `PROJECTS.TASK.VIEW`)
- **Permission / Role:** `PROJECTS.TASK.VIEW` — runs as ORG_ADMIN; also cross-tenant user → denied
- **Preconditions / Seed:** A task in company A.
- **Steps:**
  1. As a company-A user with TASK.VIEW, `GET /project-tasks/uid/{uid}` → 200 the `ProjectTaskDto`.
  2. As a company-B user, same call → scoped denied (403 / not found).
- **Test Data:** n/a
- **Expected Result:** Owner sees the task; cross-tenant denied.
- **Convention Assertions:** C7 multi-tenancy; C3 RBAC.
- **Negative / Edge:** Unknown task uid → 404 "ProjectTask not found".

### TC-PROJ-040 — Record timesheet at project level (no task)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Timesheets panel (`/admin/projects/uid/:uid` · `POST /api/v1/project-timesheets/project/uid/{projectUid}`)
- **Permission / Role:** `PROJECTS.TIMESHEET.RECORD` (scoped) — runs as ORG_ADMIN; also as a role lacking RECORD → "Record Time" hidden / 403
- **Variation:** projectTaskUid omitted (project-level timesheet); billable=true
- **Preconditions / Seed:** A project; a user id to record against.
- **Steps:**
  1. Open project detail; in Timesheets panel click "Record Time".
  2. Enter user (note: `userId` is a numeric Long, not a uid), work date, hours; toggle billable; save.
- **Test Data:** userId="7"; workDate=2026-06-10; hours="6.5"; billable=true.
- **Expected Result:** `POST` returns `ProjectTimesheetDto` with `projectTaskId = null`, `status = ACTIVE`; appears in the paged list; audit `PROJECT_TIMESHEET_RECORD`.
- **Convention Assertions:** C2 envelope; C3 RBAC; C8 hours as decimal string, ISO workDate.
- **Negative / Edge:** Missing userId/workDate/hours → client validation messages; hours = 0 → 400 (`@DecimalMin("0.01")`); negative hours → 400; missing required fields → 400 (`@NotNull`).

### TC-PROJ-041 — Record timesheet against a specific task (chosen via picker)
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Timesheets panel (`POST .../project/uid/{projectUid}` with `projectTaskUid`)
- **Permission / Role:** `PROJECTS.TIMESHEET.RECORD` (scoped) — runs as ORG_ADMIN
- **Variation:** projectTaskUid set; task chosen by NAME via `<app-uid-picker>`
- **Preconditions / Seed:** A project with at least one ACTIVE task.
- **Steps:**
  1. In the Record Time form, open the Task `<app-uid-picker>` and choose the task by name (hint = task code).
  2. Fill user/date/hours; save.
- **Test Data:** task "Site preparation"; userId="7"; workDate=2026-06-11; hours="3".
- **Expected Result:** `projectTaskId` set to the chosen task's id; persisted.
- **Convention Assertions:** C1 (task chosen by name via picker; uid stored under the hood; no raw uid shown); C2 envelope.
- **Negative / Edge:** A task uid that does not belong to this project → 404 ("Task not found or does not belong to project") — `findByUidAndProjectId` guard.

### TC-PROJ-042 — Timesheets list is paginated
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Timesheets panel (`GET /api/v1/project-timesheets/project/uid/{projectUid}` paged)
- **Permission / Role:** `PROJECTS.TIMESHEET.VIEW` (scoped) — runs as ORG_ADMIN; also as role lacking VIEW → panel forbidden/error
- **Preconditions / Seed:** A project with ≥ 21 timesheets (force ≥ 2 pages at size 20).
- **Steps:**
  1. Open the project detail; the Timesheets panel shows page 1 with the shared paginator.
  2. Navigate First/Prev/numbers/Next/Last.
- **Test Data:** n/a
- **Expected Result:** `ApiResponse<List>` + `meta` paginated; paginator drives `loadTimesheets(page)`; self-hidden when 1 page.
- **Convention Assertions:** C2 envelope+meta; C5 paginator full control set; C4 (timesheets panel loading/empty/error states).
- **Negative / Edge:** Project with no timesheets → empty-state; canTimesheetView=false → panel not shown.

### TC-PROJ-050 — Issue materials to project (GOODS lines; COGS at moving average)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** Issue-to-Job panel (`/admin/projects/uid/:uid` · `POST /api/v1/project-issues`)
- **Permission / Role:** `PROJECTS.ISSUE.CREATE` (`@perm.has`) — runs as ORG_ADMIN (and as STOREKEEPER if granted); also as role lacking it → "Issue Materials" hidden / 403
- **Variation:** project = ACTIVE (taggable); product lines = GOODS (stockable) with established avg cost
- **Preconditions / Seed:** ACTIVE project; ≥ 1 GOODS product with on-hand stock and an established moving-average cost in the issuing branch; GL keys (COGS/INVENTORY) configured.
- **Steps:**
  1. Open the project detail (ACTIVE); in the Issue-to-Job panel click "Issue Materials".
  2. Add a product line via the product `<app-uid-picker>` (choose by name; hint = code); enter qty.
  3. Optionally set issue date and reason; submit.
- **Test Data:** product "Cement 50kg"; qty="20"; reason="phase 1 pour".
- **Expected Result:** `POST` returns `IssueToProjectResultDto` with `issueNumber` (`PJI-####`, e.g. `PJI-0001` — per-company `PROJECT_ISSUE` sequence), per-line `value` at moving average, `totalValue`, `currency`, and a `cogsGlEntryUid`. Stock movement `ISSUE_TO_PROJECT` posts −qty tagged with the project (and task) dimension; GL DR COGS / CR INVENTORY (cost-type MATERIAL) in a new transaction. Success alert shows the issue number.
- **Convention Assertions:** C1 (product + project chosen by name; issue number shown, NOT uid); C2 envelope; C3 RBAC; C7 (company/branch scoped); C8 money string; C9 (stock + GL append-only, no edit).
- **Negative / Edge:** see TC-PROJ-051..054.

### TC-PROJ-051 — Issue to a non-taggable project is rejected
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** Issue-to-Job (`POST /api/v1/project-issues` → `ProjectTagResolver`)
- **Permission / Role:** `PROJECTS.ISSUE.CREATE` — runs as ORG_ADMIN
- **Variation:** project status = DRAFT / COMPLETED / CANCELLED (none allow tagging)
- **Preconditions / Seed:** Projects in DRAFT, COMPLETED, CANCELLED.
- **Steps:**
  1. Attempt to issue materials to a DRAFT project (UI: the Issue panel is gated by `isTaggable`, so it is hidden — assert via API).
  2. Repeat for COMPLETED and CANCELLED via API.
- **Test Data:** product "Cement 50kg"; qty="5".
- **Expected Result:** Rejected — `IllegalStateException` "Project … is not open for tagging (status=…)". No stock movement, no GL post. Only ACTIVE / ON_HOLD allow issue (`allowsTagging()`).
- **Convention Assertions:** C2 envelope error; C3 RBAC; in the UI the Issue panel is hidden unless `isTaggable` (ACTIVE/ON_HOLD).
- **Negative / Edge:** ON_HOLD project DOES allow issue (positive control — confirm it succeeds).

### TC-PROJ-052 — Issue with null moving-average cost still deducts qty, skips COGS leg
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Issue-to-Job (`POST /api/v1/project-issues`)
- **Permission / Role:** `PROJECTS.ISSUE.CREATE` — runs as ORG_ADMIN
- **Variation:** product with NO established avg cost (no prior receipt valuation)
- **Preconditions / Seed:** ACTIVE project; a GOODS product with on-hand qty but `avg_cost` not yet established.
- **Steps:**
  1. Issue qty of the product to the ACTIVE project.
- **Test Data:** product "New SKU"; qty="3".
- **Expected Result:** Per ADR-0020 D-2 edge: `costIssue` returns null → COGS leg skipped (WARN logged, anomaly recorded), line `value = 0`, `totalValue` excludes it, `cogsGlEntryUid` may be null if all lines are zero; stock movement still posts −qty. No transaction rollback.
- **Convention Assertions:** C2 envelope; C9 (qty still moves, append-only).
- **Negative / Edge:** Mixed batch — one line with avg cost + one without → only the costed line forms the COGS leg.

### TC-PROJ-053 — Issue validation: empty lines, unknown product, cross-tenant project
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Issue-to-Job (`POST /api/v1/project-issues`)
- **Permission / Role:** `PROJECTS.ISSUE.CREATE` — runs as ORG_ADMIN
- **Preconditions / Seed:** ACTIVE project in company A.
- **Steps / assertions:**
  1. Empty `lines` → 400 (`@NotEmpty`).
  2. Line with blank `productUid` → 400 (`@NotBlank`).
  3. Line `qty` null → 400 (`@NotNull`).
  4. Unknown product uid → 404 "Product not found".
  5. Company A user issues against a company-B project uid → `ProjectTagResolver` "Project not found or belongs to a different company" (404) — tenant isolation (BR-PROJ-01).
  6. Unknown company/branch uid → 404.
- **Test Data:** as above
- **Expected Result:** Each case returns the stated error; no stock/GL side effects.
- **Convention Assertions:** C2 envelope error array; C7 cross-tenant denial.
- **Negative / Edge:** Negative qty in a line — service takes `qty.abs()` (always positive magnitude); confirm the deduction magnitude is positive.

### TC-PROJ-054 — Issue with a task dimension; task must belong to the project
- **Type:** Manual (API)
- **Priority:** P3
- **Module / Submodule:** Issue-to-Job (`POST /api/v1/project-issues` with `projectTaskUid`)
- **Permission / Role:** `PROJECTS.ISSUE.CREATE` — runs as ORG_ADMIN
- **Variation:** projectTaskUid set
- **Preconditions / Seed:** ACTIVE project P1 with task T1; another project P2 with task T2.
- **Steps:**
  1. Issue to P1 with `projectTaskUid = T1` → success; stock movement tagged with projectTaskId.
  2. Issue to P1 with `projectTaskUid = T2` (belongs to P2) → 404 ("ProjectTask not found or does not belong to project").
- **Test Data:** as above
- **Expected Result:** Valid task tags the movement; mismatched task is rejected by `ProjectTagResolver`.
- **Convention Assertions:** C2 envelope; C1 (in the UI the task is chosen via picker, not typed).
- **Negative / Edge:** Note the current UI issue form does not expose a task picker on the issue lines — task-dimension issuance is API-capable; flag as UI gap if a task tag is required from the screen.

### TC-PROJ-060 — Project P&L report (revenue, cost-by-type, margin, WIP, recon)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** P&L panel (`/admin/projects/uid/:uid` · `GET /api/v1/project-costing/projects/uid/{uid}/pnl`)
- **Permission / Role:** `PROJECTS.COSTING.VIEW` (scoped) — runs as ORG_ADMIN / ACCOUNTANT; also as role lacking it → "View P&L" hidden / 403
- **Variation:** project with MATERIAL costs (from issue-to-project) and tagged INCOME (revenue)
- **Preconditions / Seed:** ACTIVE project with ≥ 1 issue-to-project (MATERIAL cost) and a tagged sales/income posting; budget set on the project.
- **Steps:**
  1. Open the project detail; click "View P&L" (gated `canCosting`).
  2. Observe revenue, total cost, cost-by-type rows (MATERIAL etc.), margin, margin %, budget, budget variance, WIP, and the recon bar.
- **Test Data:** n/a (driven by seeded postings)
- **Expected Result:** `ProjectPnlDto` returned; `costByType` shows ProjectCostType rows; `wip = max(0, cost − revenue)`; recon `balanced = true` (computed == GL Σ by account-type, BR-PROJ-09).
- **Convention Assertions:** C1 (P&L identifies the project by number/name, not uid); C2 envelope; C3 RBAC; C8 money strings + currency; C4 (P&L panel idle/loading/error states).
- **Negative / Edge:** Project with no postings → zero revenue/cost, wip 0; recon still balanced; P&L for a cross-tenant project → scoped denied; `recon.balanced = false` would indicate a backend bug (assert it is true).

### TC-PROJ-061 — Cross-project WIP report (list, totals, four states, company scope)
- **Type:** Automated (Playwright)
- **Priority:** P1
- **Module / Submodule:** WIP report (`/admin/projects/wip-report` · `GET /api/v1/project-costing/wip?companyId=`)
- **Permission / Role:** `PROJECTS.COSTING.VIEW` (`@perm.has`) — runs as ORG_ADMIN / ACCOUNTANT; also as NO-permission user → route guard blocks / forbidden state
- **Variation:** company = a company with several projects having cost incurred + billed
- **Preconditions / Seed:** ≥ 2 projects with cost + billing activity in the selected company.
- **Steps:**
  1. Navigate to `/admin/projects/wip-report`.
  2. Choose the company; click "Load Report".
  3. Observe per-project rows (project #, name, cost incurred, billed, WIP) and the footer totals (cost incurred / billed / WIP).
- **Test Data:** n/a
- **Expected Result:** `ApiResponse<List<ProjectWipRowDto>>` (NOT paginated — no PageMeta). `wip = max(0, costIncurred − billed)` per row; footer sums match. Forbidden state if 403.
- **Convention Assertions:** C2 envelope (list, no meta); C3 RBAC; C4 four states (loading/empty/error/forbidden — note `forbidden` is set on HTTP 403); C7 company-scoped; C8 money strings.
- **Negative / Edge:** Company with no projects → empty-state (`isEmpty` when loaded and rows empty); 403 (permission revoked mid-session) → forbidden state distinct from error; the WIP report is intentionally **not** paginated — assert no paginator present.

### TC-PROJ-070 — Multi-tenant isolation: company A cannot see company B projects
- **Type:** Both
- **Priority:** P1
- **Module / Submodule:** All project endpoints (list, detail, tasks, timesheets, issue, P&L, WIP)
- **Permission / Role:** full `PROJECTS.*` granted in each tenant — runs as a company-A ORG_ADMIN
- **Variation:** cross-tenant
- **Preconditions / Seed:** Company A and company B each with projects.
- **Steps:**
  1. As a company-A user, `GET /projects?companyId={B}` → scoped denied (`ScopeGuard.assertCanActIn`).
  2. `GET /projects/uid/{B-project}` → scoped denied / not found.
  3. `POST /project-issues` with company B uids → denied.
  4. `GET /project-costing/wip?companyId={B}` → denied.
- **Test Data:** companyId of B
- **Expected Result:** Every cross-tenant access denied (403 / not-found); company A only ever sees its own data.
- **Convention Assertions:** C7 multi-tenancy; C3 RBAC; C2 envelope error.
- **Negative / Edge:** rootadmin (superuser) CAN cross tenants — positive control, never used for the negative assertion.

### TC-PROJ-071 — Branch scoping: user acting in an unassigned branch is denied
- **Type:** Manual (API)
- **Priority:** P2
- **Module / Submodule:** Project create / issue (branch-bearing endpoints)
- **Permission / Role:** `PROJECTS.PROJECT.CREATE` / `PROJECTS.ISSUE.CREATE` — runs as a user assigned to branch X only
- **Variation:** user assigned to ONE branch; attempts to act in branch Y (not assigned)
- **Preconditions / Seed:** Multi-branch company; user assigned only to branch X.
- **Steps:**
  1. Set `X-Branch-Uid` to branch X → create/issue succeeds.
  2. Set `X-Branch-Uid` to branch Y (unassigned) → denied.
- **Test Data:** branch X (assigned), branch Y (unassigned)
- **Expected Result:** Acting in the assigned branch works; acting in an unassigned branch is denied (scope guard / branch enforcement).
- **Convention Assertions:** C7 branch scoping; C3 RBAC.
- **Negative / Edge:** User assigned to ALL branches succeeds in any branch; default vs non-default branch both work when assigned.

### TC-PROJ-080 — RBAC matrix: each PROJECTS action denied to a user lacking the exact code
- **Type:** Manual (API)
- **Priority:** P1
- **Module / Submodule:** All five controllers
- **Permission / Role:** one CUSTOM role per row holding everything EXCEPT the code under test
- **Preconditions / Seed:** A CUSTOM role and user per assertion (or toggle a single user's grants).
- **Steps / Matrix (each → 403 when the listed code is absent):**
  1. `GET /projects` & `/projects/uid/{uid}` without `PROJECTS.PROJECT.VIEW`.
  2. `POST /projects` without `PROJECTS.PROJECT.CREATE`.
  3. `PUT /projects/uid/{uid}`, `PATCH .../status`, `PATCH .../archive` without `PROJECTS.PROJECT.MANAGE`.
  4. `GET /project-tasks/...` without `PROJECTS.TASK.VIEW`.
  5. `POST/PUT /project-tasks...`, `.../deactivate` without `PROJECTS.TASK.MANAGE`.
  6. `GET /project-timesheets/...` without `PROJECTS.TIMESHEET.VIEW`.
  7. `POST /project-timesheets/...` without `PROJECTS.TIMESHEET.RECORD`.
  8. `POST /project-issues` without `PROJECTS.ISSUE.CREATE`.
  9. `GET /project-costing/.../pnl` & `/project-costing/wip` without `PROJECTS.COSTING.VIEW`.
- **Test Data:** as above
- **Expected Result:** Each call returns 403; the corresponding UI control is hidden (`canCreate`/`canManage`/`canTaskManage`/`canTimesheetRecord`/`canTimesheetView`/`canIssue`/`canCosting` computed signals).
- **Convention Assertions:** C3 RBAC (API 403 + UI hidden); C2 envelope error.
- **Negative / Edge:** A user holding ONLY the tested code (and the prerequisite VIEW) succeeds — positive control per row.

### TC-PROJ-081 — VIEW-only user: read screens render, all mutating controls hidden
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** Project list + detail
- **Permission / Role:** CUSTOM role = `PROJECTS.PROJECT.VIEW` + `PROJECTS.TASK.VIEW` + `PROJECTS.TIMESHEET.VIEW` + `PROJECTS.COSTING.VIEW` (no CREATE/MANAGE/RECORD/ISSUE)
- **Preconditions / Seed:** A project with tasks + timesheets.
- **Steps:**
  1. Open `/admin/projects` → list visible, "New Project" hidden.
  2. Open the project detail → edit save / lifecycle buttons / "Add Task" / "Record Time" / "Issue Materials" all hidden; tasks + timesheets + P&L visible.
- **Test Data:** n/a
- **Expected Result:** All read panels render; every mutating affordance is absent; direct API mutations return 403.
- **Convention Assertions:** C3 RBAC; C4 four states; C6 axe on the read-only detail.
- **Negative / Edge:** Granting TIMESHEET.RECORD additively reveals only the "Record Time" affordance.

### TC-PROJ-090 — Accessibility + convention sweep across project screens
- **Type:** Automated (Playwright)
- **Priority:** P2
- **Module / Submodule:** `/admin/projects`, `/admin/projects/uid/:uid`, `/admin/projects/wip-report`
- **Permission / Role:** `PROJECTS.PROJECT.VIEW` + `PROJECTS.COSTING.VIEW` — runs as ORG_ADMIN
- **Preconditions / Seed:** A populated project with tasks/timesheets/costs.
- **Steps:**
  1. Run an axe scan on each of the three screens (list, detail, WIP report).
  2. Keyboard-navigate the list table, paginator, lifecycle buttons, and the pickers.
  3. Confirm tables have captions/scope; pickers and lifecycle buttons have aria-labels.
- **Test Data:** n/a
- **Expected Result:** axe-clean (WCAG 2.1 AA); all controls keyboard-operable; no raw uid visible anywhere on screen (only in URL).
- **Convention Assertions:** C1 (no uid on screen, pickers by name); C5 (paginator on list + timesheets); C6 a11y; C8 money/date formatting.
- **Negative / Edge:** Verify the error and empty states are also axe-clean and screen-reader-announced.
