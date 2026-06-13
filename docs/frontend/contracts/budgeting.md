# API Contract — budgeting module (Phase-B frontend)

**Nav group:** Budgeting & Management Accounting  
**Permission codes (use VERBATIM — do not invent):** BUDGETING.BUDGET.VIEW, BUDGETING.BUDGET.MANAGE, BUDGETING.BUDGET.SUBMIT, BUDGETING.BUDGET.APPROVE, BUDGETING.REPORT.VIEW, BUDGETING.REPORT.EXPORT

## Module notes
PAGINATION: Only GET /api/v1/budgets (list) is paged â€” it takes a Spring `Pageable` (use ?page=&size=&sort=) and returns the rows in ApiResponse.data with PageMeta in ApiResponse.meta (PageMeta.from(page) â€” page/size/totalElements/totalPages). All other GETs return a single object (no paging). Report endpoints (/variance, /departmental-actuals) return a single aggregate DTO, NOT a page.

RESPONSE ENVELOPE: All responses are auto-wrapped by ApiResponseAdvice in ApiResponse<T> = { data, meta?, ... }. The list endpoint explicitly returns ApiResponse<List<BudgetDto>>; the others return the raw DTO which the advice still wraps. FE should unwrap `.data`.

LIFECYCLE (BudgetVersionStatus state machine, ADR-0034 D-5): DRAFT --submit--> SUBMITTED --approve--> APPROVED; SUBMITTED --reject--> REJECTED (terminal); SUBMITTED --recall--> DRAFT; APPROVED --(when a newer version of same scope is approved)--> SUPERSEDED (terminal). Action endpoints all live on BudgetVersionController and key off the version's current status:
- submit (POST /budget-versions/uid/{uid}/submit, perm BUDGETING.BUDGET.SUBMIT): precondition status=DRAFT AND version has >=1 line (rejects zero-line versions, BR-BUD-11); locks lines from edit; stamps submittedAt/submittedBy.
- recall (POST .../recall, perm BUDGETING.BUDGET.SUBMIT): precondition status=SUBMITTED; returns to DRAFT to allow line edits.
- approve (POST .../approve, perm BUDGETING.BUDGET.APPROVE): precondition status=SUBMITTED; in one TX supersedes the prior APPROVED version of the same (company, FY, cost-centre) scope then sets this APPROVED; optional body ApproveBudgetVersionRequest{note}. DB partial-unique uq_budget_version_one_approved guarantees exactly one APPROVED per scope.
- reject (POST .../reject, perm BUDGETING.BUDGET.APPROVE): precondition status=SUBMITTED; REQUIRES body RejectBudgetVersionRequest{reason} (reason @NotBlank, max 500); terminal.
- upsert lines (PUT /budget-versions/uid/{uid}/lines, perm BUDGETING.BUDGET.MANAGE): EDITABLE ONLY while version status=DRAFT â€” rejected on SUBMITTED/APPROVED/REJECTED/SUPERSEDED (recall first).
- create new version / re-plan (POST /budgets/uid/{uid}/versions, perm BUDGETING.BUDGET.MANAGE scoped to budget): opens a new DRAFT version (version_no = max+1), optionally seeded from an existing version's lines via seedFromVersionUid. Body CreateBudgetVersionRequest is OPTIONAL (required=false) â€” sending no body creates a blank DRAFT.
NOTE: maker!=checker is NOT enforced in v1 â€” the same user may hold both SUBMIT and APPROVE.

PERMISSION STYLE: list/create/report endpoints use @perm.has('CODE') + a ScopeGuard.assertCanActIn(companyId) inside the service/controller. Path-uid endpoints use @perm.scoped(#uid,'<targetType>','CODE') where targetType is 'budget' (BudgetController) or 'budgetversion' (BudgetVersionController). NEVER hasAuthority. Copy perm strings verbatim.

UPSERT-LINES MODES: UpsertBudgetLineRequest.mode is an EntryMode enum {DIRECT, ANNUAL_SPREAD, SEED}. DIRECT uses the `lines` array (LineInputDto: accountUid, fiscalPeriodUid, amount, lineMemo). ANNUAL_SPREAD uses `annualAmount` + `accountUid` (spread evenly across 12 periods, HALF_UP, remainder on last). SEED uses `seedFromVersionUid` (copy from another version). The PUT REPLACES the version's lines wholesale.

MONEY/CURRENCY: All money is BigDecimal NUMERIC(19,4), base currency only (TZS), HALF_UP. budget_lines.amount must be >= 0. currency on a line = company base currency (service asserts). On the wire Long/BigDecimal/Integer serialise as JSON strings (global Jackson config) â€” treat id/companyId/amount/etc. as strings in TS. Budgets POST NOTHING to GL â€” no journal, no posting; they are pure reference data joined to actuals at report time.

REPORT OUTPUT SHAPES:
- /variance returns VarianceReportDto = { header: {companyId, fiscalYearUid, fiscalYearCode, fromPeriodNo, toPeriodNo, costCentreValueUid?, costCentreValueName?, noApprovedBudget:boolean}, rows: VarianceRowDto[], totalsBudgetByType: Map<AccountType,BigDecimal>, totalsActualByType: Map<AccountType,BigDecimal>, totalsVarianceByType: Map<AccountType,BigDecimal> }. Each row has accountId/Code/Name, accountType, normalBalance, costCentreValueId?, costCentreValueName?, budgetAmount, actualAmount, varianceAmount (= actual - budget), variancePct (null when budget=0). noApprovedBudget=true => all budget amounts are 0 (no APPROVED version for scope). Favourable/adverse label is a UI concern derived from accountType â€” NOT in the payload. The totals maps are keyed by AccountType literal (ASSET/LIABILITY/EQUITY/INCOME/EXPENSE).
- /departmental-actuals returns DepartmentalActualsDto = { companyId, fiscalYearUid, fromDate:LocalDate, toDate:LocalDate, rows: DepartmentalActualsRowDto[] } where each row = {costCentreValueId?, costCentreValueName?, accountId, accountCode, accountName, accountType, normalBalance, actualAmount}. NULL costCentreValueId = the "Unallocated" bucket. No budget join.
Both report query params: companyId (required), fiscalYearUid (required), fromPeriodNo (default 1), toPeriodNo (default 12); /variance also takes optional costCentreValueUid and optional accountType (enum). Period range validated 1..12 in VarianceQuery constructor.

COST CENTRE = ADR-0025 dimension value: a budget's cost centre is a `dimension_values.uid` in the COST_CENTRE slot, passed as costCentreValueUid (null = company-wide budget). Budgeting builds NO cost-centre CRUD â€” the cost-centre picker on budget create/edit reads ADR-0025/costing's active values (gated by COSTING.VIEW, a SEPARATE module). Do not expect a budgeting cost-centre list endpoint.

CHILD/NESTED RESOURCES: Budget -> versions (BudgetDto.versions when fetching detail) -> lines (BudgetVersionDto.lines when fetching version detail). Versions are created under a budget (POST /budgets/uid/{uid}/versions) but otherwise managed via the separate /budget-versions resource. Lines have no standalone endpoint â€” they are upserted wholesale via PUT /budget-versions/uid/{uid}/lines and read embedded in the version detail. budget_lines are addressed only through their version (no line-level scope/route).

EXPORT GAP (IMPORTANT): The ADR documents a GET /api/v1/budgeting/variance/export?format=CSV endpoint gated by BUDGETING.REPORT.EXPORT, and BUDGETING.REPORT.EXPORT is seeded as a permission, but NO such endpoint exists in BudgetReportController (only /variance and /departmental-actuals are implemented). Do not build a UI that calls an export endpoint â€” it is not implemented. The EXPORT permission is currently unused by any controller.

PATH BASE: All paths are under /api/v1 (class @RequestMapping). Path var is literally {uid} (a VARCHAR(26) ULID), under a /uid/ segment, e.g. /api/v1/budgets/uid/{uid}, /api/v1/budget-versions/uid/{uid}/submit.


## Resource: `budgets`  (base `/api/v1/budgets`)

**Primary DTO fields:** id:string, uid:string, companyId:string, budgetNumber:string, name:string, fiscalYearId:string, fiscalYearUid:string, fiscalYearCode:string, costCentreValueId:string, costCentreValueUid:string, costCentreValueName:string, notes:string, version:string, createdAt:Instant, createdBy:string, updatedAt:Instant, updatedBy:string, versions:BudgetVersionDto[]

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/budgets` | `BUDGETING.BUDGET.VIEW` | list | query params: companyId:Long (required), fiscalYearUid:String (optional), costCentreValueUid:String (optional), versionStatus:BudgetVersionStatus (optional, enum DRAFT\|SUBMITTED\|APPROVED\|REJECTED\|SUPERSEDED), Pageable (page,size,sort) | ApiResponse<List<BudgetDto>> paged; data=BudgetDto[] (versions omitted in list), meta=PageMeta{page,size,totalElements,totalPages} |
| GET | `/api/v1/budgets/uid/{uid}` | `BUDGETING.BUDGET.VIEW` | getByUid |  | BudgetDto (includes versions: List<BudgetVersionDto> with their status) |
| POST | `/api/v1/budgets` | `BUDGETING.BUDGET.MANAGE` | create | CreateBudgetRequest{companyId:Long(required), name:String(required,max160), fiscalYearUid:String(required), costCentreValueUid:String(optional,null=company-wide), notes:String(optional,max500), initialVersionLabel:String(optional,max120)} | BudgetDto (HTTP 201; creates header + initial DRAFT version 1) |
| POST | `/api/v1/budgets/uid/{uid}/versions` | `BUDGETING.BUDGET.MANAGE` | create (new version / re-plan) | CreateBudgetVersionRequest{label:String(optional,max120), seedFromVersionUid:String(optional,null=blank)} â€” body OPTIONAL (required=false); scoped to 'budget' uid | BudgetVersionDto (HTTP 201; new DRAFT version, version_no=max+1) |

## Resource: `budget-versions`  (base `/api/v1/budget-versions`)

**Status enum:** DRAFT, SUBMITTED, APPROVED, REJECTED, SUPERSEDED

**Primary DTO fields:** id:string, uid:string, budgetId:string, budgetUid:string, companyId:string, fiscalYearId:string, costCentreValueId:string, versionNo:int(string), status:BudgetVersionStatus, label:string, seededFromVersionId:string, seededFromVersionUid:string, submittedAt:Instant, submittedBy:string, approvedAt:Instant, approvedBy:string, rejectedAt:Instant, rejectedBy:string, supersededAt:Instant, decisionReason:string, version:string, createdAt:Instant, createdBy:string, lines:BudgetLineDto[]

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/budget-versions/uid/{uid}` | `BUDGETING.BUDGET.VIEW` | getByUid |  | BudgetVersionDto (includes lines: List<BudgetLineDto>); scoped 'budgetversion' |
| PUT | `/api/v1/budget-versions/uid/{uid}/lines` | `BUDGETING.BUDGET.MANAGE` | update (upsert/replace lines, DRAFT only) | UpsertBudgetLineRequest{mode:EntryMode(DIRECT\|ANNUAL_SPREAD\|SEED, required), lines:LineInputDto[]{accountUid:String, fiscalPeriodUid:String, amount:BigDecimal, lineMemo:String(max255)} (for DIRECT), annualAmount:BigDecimal (for ANNUAL_SPREAD), accountUid:String (for ANNUAL_SPREAD/SEED), seedFromVersionUid:String (for SEED)}; scoped 'budgetversion' | BudgetVersionDto (with updated lines) |
| POST | `/api/v1/budget-versions/uid/{uid}/submit` | `BUDGETING.BUDGET.SUBMIT` | submit (DRAFT -> SUBMITTED; requires >=1 line) |  | BudgetVersionDto; scoped 'budgetversion' |
| POST | `/api/v1/budget-versions/uid/{uid}/recall` | `BUDGETING.BUDGET.SUBMIT` | recall (SUBMITTED -> DRAFT) |  | BudgetVersionDto; scoped 'budgetversion' |
| POST | `/api/v1/budget-versions/uid/{uid}/approve` | `BUDGETING.BUDGET.APPROVE` | approve (SUBMITTED -> APPROVED; supersedes prior approved of same scope) | ApproveBudgetVersionRequest{note:String(optional,max500)} â€” body OPTIONAL (required=false); scoped 'budgetversion' | BudgetVersionDto; scoped 'budgetversion' |
| POST | `/api/v1/budget-versions/uid/{uid}/reject` | `BUDGETING.BUDGET.APPROVE` | reject (SUBMITTED -> REJECTED, terminal) | RejectBudgetVersionRequest{reason:String(required @NotBlank, max500)} â€” body REQUIRED; scoped 'budgetversion' | BudgetVersionDto; scoped 'budgetversion' |

## Resource: `budgeting-reports`  (base `/api/v1/budgeting`)

**Primary DTO fields:** VarianceReportDto{header:HeaderDto{companyId:string, fiscalYearUid:string, fiscalYearCode:string, fromPeriodNo:int(string), toPeriodNo:int(string), costCentreValueUid:string, costCentreValueName:string, noApprovedBudget:boolean}, rows:VarianceRowDto[]{accountId:string, accountCode:string, accountName:string, accountType:AccountType, normalBalance:NormalBalance, costCentreValueId:string, costCentreValueName:string, budgetAmount:string, actualAmount:string, varianceAmount:string, variancePct:string}, totalsBudgetByType:Map<AccountType,string>, totalsActualByType:Map<AccountType,string>, totalsVarianceByType:Map<AccountType,string>}

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/budgeting/variance` | `BUDGETING.REPORT.VIEW` | report (budget-vs-actual variance) | query params: companyId:Long(required), fiscalYearUid:String(required), fromPeriodNo:int(default 1), toPeriodNo:int(default 12), costCentreValueUid:String(optional), accountType:AccountType(optional, ASSET\|LIABILITY\|EQUITY\|INCOME\|EXPENSE) | VarianceReportDto{header{companyId,fiscalYearUid,fiscalYearCode,fromPeriodNo,toPeriodNo,costCentreValueUid,costCentreValueName,noApprovedBudget}, rows:VarianceRowDto[], totalsBudgetByType, totalsActualByType, totalsVarianceByType (each Map<AccountType,BigDecimal>)} |
| GET | `/api/v1/budgeting/departmental-actuals` | `BUDGETING.REPORT.VIEW` | report (GL actuals by cost-centre x account, no budget join) | query params: companyId:Long(required), fiscalYearUid:String(required), fromPeriodNo:int(default 1), toPeriodNo:int(default 12) | DepartmentalActualsDto{companyId:Long, fiscalYearUid:String, fromDate:LocalDate, toDate:LocalDate, rows:DepartmentalActualsRowDto[]{costCentreValueId, costCentreValueName, accountId, accountCode, accountName, accountType:AccountType, normalBalance:NormalBalance, actualAmount:BigDecimal}} |