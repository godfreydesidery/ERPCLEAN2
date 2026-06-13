# API Contract — projects module (Phase-B frontend)

**Nav group:** Projects  
**Permission codes (use VERBATIM — do not invent):** PROJECTS.PROJECT.CREATE, PROJECTS.PROJECT.VIEW, PROJECTS.PROJECT.MANAGE, PROJECTS.TASK.VIEW, PROJECTS.TASK.MANAGE, PROJECTS.TIMESHEET.VIEW, PROJECTS.TIMESHEET.RECORD, PROJECTS.ISSUE.CREATE, PROJECTS.COSTING.VIEW, PROJECTS.TAG.MANAGE

## Module notes
PAGINATION: Project list (GET /api/v1/projects) and timesheet list (GET /api/v1/project-timesheets/project/uid/{projectUid}) are PAGINATED â€” they return ApiResponse<List<T>> with a PageMeta (Spring Pageable, @PageableDefault(size=20); use ?page=&size=&sort= params). The task list (GET /api/v1/project-tasks/project/uid/{projectUid}) and the WIP report (GET /api/v1/project-costing/wip) are NOT paginated â€” they return a plain List (task list returns a raw List<ProjectTaskDto>; WIP returns ApiResponse<List<...>> without PageMeta). NOTE on response wrapping: ALL responses are auto-wrapped in ApiResponse<T> by ApiResponseAdvice â€” even handlers whose Java return type is the bare DTO (e.g. ProjectDto, ProjectTaskDto, ProjectPnlDto). So the FE always reads response.data. The two list endpoints that explicitly return ApiResponse<List<T>> also populate response.meta (PageMeta: page, size, totalElements, totalPages).

LIFECYCLE / STATE MACHINE (ProjectStatus, the business lifecycle, distinct from MasterStatus soft-delete): DRAFT --activate--> ACTIVE --hold--> ON_HOLD --resume--> ACTIVE; ACTIVE|ON_HOLD --complete--> COMPLETED (terminal); any non-terminal --cancel--> CANCELLED (terminal). There is NO dedicated activate/hold/resume/complete/cancel endpoint â€” all transitions go through ONE endpoint: PATCH /api/v1/projects/uid/{uid}/status?targetStatus=<ProjectStatus> (perm PROJECTS.PROJECT.MANAGE). The FE chooses targetStatus to drive the transition (e.g. targetStatus=ACTIVE to activate or resume, ON_HOLD to hold, COMPLETED to complete, CANCELLED to cancel); the service enforces legal transitions. Preconditions: cannot transition out of terminal COMPLETED/CANCELLED; complete requires ACTIVE or ON_HOLD. Project tagging of postings is only allowed while projectStatus IN (ACTIVE, ON_HOLD); tags against DRAFT/COMPLETED/CANCELLED are rejected with a validation error (OQ-PROJ-06). The separate PATCH /uid/{uid}/archive does the MasterStatus soft-delete (status -> ARCHIVED), NOT a projectStatus change. The issue-to-job action (POST /api/v1/project-issues) requires the target project to be in a tagging-allowed status (ACTIVE/ON_HOLD).

MONEY / CURRENCY FIELDS: All amounts are BigDecimal NUMERIC(19,4) serialized as JSON strings (JacksonConfig global Long-and-BigDecimal-as-string). Project.budgetAmount + currency (base currency, VARCHAR(3)). Hours are NUMERIC(9,2) (plannedHours on task, hours on timesheet). plannedRateAmount on timesheet is informational money (posts NO GL in v1). Issue result: totalValue + per-line value (COGS at moving-average) + currency. Costing P&L money: revenue, totalCost, costByType[].amount, margin, marginPct (a ratio, guarded for zero-revenue), budget, budgetVariance, wip, currency. WIP report row: costIncurred, billed, wip, currency.

REPORT ENDPOINTS (2): (1) Project P&L â€” GET /api/v1/project-costing/projects/uid/{projectUid}/pnl returns a single ProjectPnlDto: header (projectUid, projectNumber, name, customerId), revenue (Î£ tagged INCOME credits), totalCost (Î£ tagged EXPENSE debits), costByType: List<ProjectCostingRowDto{costType: ProjectCostType, amount}>, margin, marginPct, budget, budgetVariance, wip = max(0, cost-billed) (UNBOOKED â€” reported figure only, no GL), currency, and recon: ReconDto{computedRevenue, computedCost, glRevenue, glCost, balanced:boolean} â€” a structural self-check bar (balanced should always be true; false = bug). ProjectCostType enum: MATERIAL, SUBCONTRACT, LABOUR, OVERHEAD, OTHER. (2) Cross-project WIP report â€” GET /api/v1/project-costing/wip?companyId=<Long> returns ApiResponse<List<ProjectWipRowDto>> (NOT paginated): each row {projectUid, projectNumber, name, costIncurred, billed, wip, currency}.

CHILD / NESTED RESOURCES: project-tasks and project-timesheets are children of a project, addressed via the project's uid for create/list (POST & GET .../project/uid/{projectUid}), but a task is read/updated/deactivated by its OWN uid (.../uid/{uid}). Tasks are one level deep in v1 (parentId reserved, always null). Timesheets have no GET-by-uid or update/delete endpoint â€” only record (POST) and list-by-project (paginated GET); they are informational labour estimates with no GL posting. The issue-to-job (project-issues) resource has only a single POST (no list/detail) â€” the issue is recorded as tagged stock_movements + a COGS journal, no issue-header table; results come back in IssueToProjectResultDto.

PERMISSION STYLE: Collection/create endpoints use @perm.has('CODE'); uid-addressed endpoints use @perm.scoped(#uid,'<targetType>','CODE') where targetType is 'project' for project-scoped paths, 'projecttask' for task read/update/deactivate. PROJECTS.TAG.MANAGE exists in the seed (re-tag of posted lines) but is NOT used by any endpoint in these 5 controllers â€” re-tagging endpoint is not present in this module's controllers (tag rides the foreign document's own permission at entry time). PROJECTS.TIMESHEET.VIEW gates the timesheet list; PROJECTS.TIMESHEET.RECORD gates recording.

PATH-VAR NAMES: project endpoints use {uid}; task create/list use {projectUid}, task get/update/deactivate use {uid}; timesheet endpoints use {projectUid}; costing pnl uses {projectUid}.

ENUMS ON THE WIRE: ProjectStatus (DRAFT/ACTIVE/ON_HOLD/COMPLETED/CANCELLED) is the business lifecycle. MasterStatus (ACTIVE/INACTIVE/ARCHIVED) is the soft-delete status used by the list ?status= filter and is the .status field on ProjectDto/ProjectTaskDto/ProjectTimesheetDto. The project list also accepts ?projectStatus= (ProjectStatus) and ?status= (MasterStatus) as independent optional filters; companyId (Long) is REQUIRED on the project list.

REQUEST QUIRKS: CreateProjectRequest takes companyUid+branchUid (uids, not ids) â€” but the list endpoint filters by companyId (Long). CreateTimesheetRequest has projectTaskUid OPTIONAL (timesheet may be at project level) but userId is a raw Long (NOT a uid) and is @NotNull. Task update reuses CreateProjectTaskRequest (same body as create). IssueToProjectRequest.lines is @NotEmpty List<IssueLine{productUid, qty}>; issueDate + reason optional.


## Resource: `projects`  (base `/api/v1/projects`)

**Status enum:** ProjectStatus: DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED | MasterStatus (soft-delete .status field): ACTIVE, INACTIVE, ARCHIVED

**Primary DTO fields:** id:string, uid:string, companyId:string, branchId:string, projectNumber:string, name:string, customerId:string, managerUserId:string, projectStatus:ProjectStatus, plannedStartDate:LocalDate, plannedEndDate:LocalDate, budgetAmount:string, currency:string, notes:string, status:MasterStatus, activatedAt:Instant, completedAt:Instant, cancelledAt:Instant

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| POST | `/api/v1/projects` | `PROJECTS.PROJECT.CREATE` | create | CreateProjectRequest{companyUid:string(NotBlank), branchUid:string(NotBlank), name:string(NotBlank,max160), customerUid:string(optional), managerUid:string(optional), plannedStartDate:LocalDate, plannedEndDate:LocalDate, budgetAmount:string(BigDecimal), notes:string(max500)} | ProjectDto{id:string, uid:string, projectNumber:string, name:string, projectStatus:ProjectStatus, budgetAmount:string, currency:string} |
| GET | `/api/v1/projects/uid/{uid}` | `PROJECTS.PROJECT.VIEW` | getByUid |  | ProjectDto{id:string, uid:string, companyId:string, branchId:string, projectNumber:string, name:string, customerId:string, managerUserId:string, projectStatus:ProjectStatus, plannedStartDate:LocalDate, plannedEndDate:LocalDate, budgetAmount:string, currency:string, notes:string, status:MasterStatus, activatedAt:Instant, completedAt:Instant, cancelledAt:Instant} |
| GET | `/api/v1/projects` | `PROJECTS.PROJECT.VIEW` | list | query params: companyId:string(Long, REQUIRED), status:MasterStatus(optional), projectStatus:ProjectStatus(optional), page/size/sort (Pageable, default size 20) | ApiResponse<List<ProjectDto>> + PageMeta{page,size,totalElements,totalPages} (PAGINATED) |
| PUT | `/api/v1/projects/uid/{uid}` | `PROJECTS.PROJECT.MANAGE` | update | UpdateProjectRequest{name:string(NotBlank,max160), customerUid:string, managerUid:string, plannedStartDate:LocalDate, plannedEndDate:LocalDate, budgetAmount:string(BigDecimal), notes:string(max500)} | ProjectDto (same fields as getByUid) |
| PATCH | `/api/v1/projects/uid/{uid}/status` | `PROJECTS.PROJECT.MANAGE` | changeStatus (activate/hold/resume/complete/cancel â€” chosen via targetStatus) | query param: targetStatus:ProjectStatus (REQUIRED) â€” one of DRAFT/ACTIVE/ON_HOLD/COMPLETED/CANCELLED; service enforces legal transition | ProjectDto (reflects new projectStatus + transition stamp) |
| PATCH | `/api/v1/projects/uid/{uid}/archive` | `PROJECTS.PROJECT.MANAGE` | archive (MasterStatus soft-delete -> ARCHIVED; NOT a projectStatus change) |  | ProjectDto (status=ARCHIVED) |

## Resource: `project-tasks`  (base `/api/v1/project-tasks`)

**Status enum:** MasterStatus: ACTIVE, INACTIVE, ARCHIVED

**Primary DTO fields:** id:string, uid:string, projectId:string, companyId:string, branchId:string, taskCode:string, name:string, parentId:string (reserved, null in v1), plannedHours:string, billable:boolean, status:MasterStatus

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| POST | `/api/v1/project-tasks/project/uid/{projectUid}` | `PROJECTS.TASK.MANAGE` | create | CreateProjectTaskRequest{taskCode:string(NotBlank,max30), name:string(NotBlank,max160), plannedHours:string(BigDecimal), billable:boolean} | ProjectTaskDto{id:string, uid:string, projectId:string, taskCode:string, name:string, plannedHours:string, billable:boolean, status:MasterStatus} |
| GET | `/api/v1/project-tasks/uid/{uid}` | `PROJECTS.TASK.VIEW` | getByUid |  | ProjectTaskDto (full) |
| GET | `/api/v1/project-tasks/project/uid/{projectUid}` | `PROJECTS.TASK.VIEW` | list (by project; NOT paginated) | query param: status:MasterStatus (optional) | List<ProjectTaskDto> (plain list, auto-wrapped in ApiResponse, no PageMeta) |
| PUT | `/api/v1/project-tasks/uid/{uid}` | `PROJECTS.TASK.MANAGE` | update | CreateProjectTaskRequest{taskCode:string, name:string, plannedHours:string, billable:boolean} (reuses create body) | ProjectTaskDto |
| PATCH | `/api/v1/project-tasks/uid/{uid}/deactivate` | `PROJECTS.TASK.MANAGE` | deactivate (soft-delete the task) |  | ProjectTaskDto (status changed) |

## Resource: `project-timesheets`  (base `/api/v1/project-timesheets`)

**Status enum:** MasterStatus: ACTIVE, INACTIVE, ARCHIVED

**Primary DTO fields:** id:string, uid:string, projectId:string, projectTaskId:string, companyId:string, branchId:string, userId:string, workDate:LocalDate, hours:string, billable:boolean, plannedRateAmount:string, notes:string, status:MasterStatus

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| POST | `/api/v1/project-timesheets/project/uid/{projectUid}` | `PROJECTS.TIMESHEET.RECORD` | record (create a timesheet entry; no GL in v1) | CreateTimesheetRequest{projectTaskUid:string(optional), userId:string(Long, NotNull), workDate:LocalDate(NotNull), hours:string(BigDecimal, NotNull, DecimalMin 0.01), billable:boolean, plannedRateAmount:string(BigDecimal, informational), notes:string} | ProjectTimesheetDto{id:string, uid:string, projectId:string, projectTaskId:string, userId:string, workDate:LocalDate, hours:string, billable:boolean, plannedRateAmount:string, notes:string, status:MasterStatus} |
| GET | `/api/v1/project-timesheets/project/uid/{projectUid}` | `PROJECTS.TIMESHEET.VIEW` | list (by project; PAGINATED) | query params: page/size/sort (Pageable, default size 20) | ApiResponse<List<ProjectTimesheetDto>> + PageMeta (PAGINATED) |

## Resource: `project-issues`  (base `/api/v1/project-issues`)

**Primary DTO fields:** issueUid:string, issueNumber:string, projectUid:string, lines:List<IssueLineResultDto{productUid:string, qty:string, value:string}>, cogsGlEntryUid:string, totalValue:string, currency:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| POST | `/api/v1/project-issues` | `PROJECTS.ISSUE.CREATE` | issue (issue-materials-to-job: consume stock + post COGS at moving avg, tagged to project) | IssueToProjectRequest{companyUid:string(NotBlank), branchUid:string(NotBlank), projectUid:string(NotBlank), projectTaskUid:string(optional), lines:List<IssueLine{productUid:string(NotBlank), qty:string(BigDecimal, NotNull)}>(NotEmpty), issueDate:LocalDate(optional), reason:string(optional)} | IssueToProjectResultDto{issueUid:string, issueNumber:string, projectUid:string, lines:List<IssueLineResultDto{productUid:string, qty:string, value:string}>, cogsGlEntryUid:string, totalValue:string, currency:string} |

## Resource: `project-costing`  (base `/api/v1/project-costing`)

**Status enum:** ProjectCostType (cost bucket enum): MATERIAL, SUBCONTRACT, LABOUR, OVERHEAD, OTHER

**Primary DTO fields:** ProjectPnlDto: projectUid:string, projectNumber:string, name:string, customerId:string, revenue:string, totalCost:string, costByType:ProjectCostingRowDto[], margin:string, marginPct:string, budget:string, budgetVariance:string, wip:string, currency:string, recon:ReconDto | ProjectWipRowDto: projectUid:string, projectNumber:string, name:string, costIncurred:string, billed:string, wip:string, currency:string

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/project-costing/projects/uid/{projectUid}/pnl` | `PROJECTS.COSTING.VIEW` | report (Project P&L + cost-by-type + margin + budget variance + WIP + recon) |  | ProjectPnlDto{projectUid:string, projectNumber:string, name:string, customerId:string, revenue:string, totalCost:string, costByType:List<ProjectCostingRowDto{costType:ProjectCostType, amount:string}>, margin:string, marginPct:string, budget:string, budgetVariance:string, wip:string, currency:string, recon:ReconDto{computedRevenue:string, computedCost:string, glRevenue:string, glCost:string, balanced:boolean}} |
| GET | `/api/v1/project-costing/wip` | `PROJECTS.COSTING.VIEW` | report (cross-project WIP; NOT paginated) | query param: companyId:string(Long, REQUIRED) | ApiResponse<List<ProjectWipRowDto{projectUid:string, projectNumber:string, name:string, costIncurred:string, billed:string, wip:string, currency:string}>> |