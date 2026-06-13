# API Contract — cost-centre module (Phase-B frontend)

**Nav group:** Costing  
**Permission codes (use VERBATIM — do not invent):** COSTING.VIEW, COSTING.MANAGE, GL.VIEW

## Module notes
PAGINATION: Only GET /api/v1/dimension-values is paginated (Spring Pageable -> response wraps content[] + PageMeta meta{page,size,totalElements,totalPages,hasNext}). GET /api/v1/dimensions (dimension types) is NOT paginated â€” returns a plain List. The report endpoint is NOT paginated.

ENVELOPE: every endpoint returns ApiResponse<T> ({data, meta?}). Long/BigDecimal serialize as JSON STRINGS (global Jackson config) â€” treat id, companyId, dimensionId, parentId, valueId, accountId, totalDebit, totalCredit, net as strings on the wire. PageMeta.totalElements/totalPages are real JSON numbers (int). DELETE returns 204 No Content (no body); POST create returns 201 Created.

NO STATE-MACHINE / NO LIFECYCLE ACTIONS: This module is master-data CRUD only. There is NO release/complete/cancel/approve/reject/post/file/disburse workflow. The only "actions" are: (1) deactivate a dimension value (PATCH .../deactivate) â€” sets is_active=false, excludes value from NEW tagging but leaves it on historical journal lines (FR-CC-04); precondition: none beyond scope/perm. (2) activate (PATCH .../activate) â€” reverses deactivate. (3) set a dimension type mandatory/optional (PATCH /dimensions/uid/{uid}/mandatory with body {mandatory:bool}) â€” when mandatory=true every posting must carry a value for that slot (FR-CC-13); off by default. (4) hard DELETE a value â€” REJECTED by the service if the value has ANY journal-line postings referencing it (FR-CC-03/BR-CC-05); the FE should prefer /deactivate when postings exist and surface the rejection error. status field (MasterStatus: ACTIVE/INACTIVE/ARCHIVED) is the master lifecycle and is distinct from the boolean active (the tagging gate) on dimension values.

DIMENSION TYPES ARE SEEDED, NOT CREATED: /api/v1/dimensions has NO create/delete endpoint â€” dimension types (Cost Centre, Department) are seeded per company. Only list, getByUid, and the mandatory toggle exist. There are 4 slots in the enum (COST_CENTRE, DEPARTMENT, DIMENSION_3, DIMENSION_4); v1 activates only COST_CENTRE and DEPARTMENT â€” DIMENSION_3/4 are reserved (no dimension row occupies them).

PERMISSIONS: COSTING.VIEW (read dimensions/values/report), COSTING.MANAGE (create/update/deactivate/activate/delete values, set-mandatory). Scoped variants use @perm.scoped(#uid,'dimension'|'dimensionvalue', CODE) for uid-addressed endpoints; unscoped @perm.has(CODE) for company-wide list/create. The report ALSO requires GL.VIEW in addition to COSTING.VIEW (both ANDed). NOTE ADR-0025 mentions a COSTING.TAG perm for document pickers, but it does NOT appear in any of these three controllers â€” do not wire it to these screens.

MONEY/CURRENCY: No currency field anywhere in this module. The report rows carry BigDecimal totalDebit, totalCredit, net (net = totalDebit - totalCredit). A dimension SLICE does NOT net to zero (BR-CC-01) â€” do not present it as a balanced TB; show the raw net per row.

REPORT ENDPOINT SHAPE: GET /api/v1/costing/reports/sliced-trial-balance returns ApiResponse<DimensionSlicedTbDto> = {dimensionSlot: enum, rollUp: bool, rows: DimensionSlicedTbRowDto[]}. Each row: {valueId, valueUid, valueCode, valueName, accountId, accountUid, accountCode, accountName, accountType, totalDebit, totalCredit, net}. Query params: companyId (Long, required), slot (DimensionSlot enum, required: COST_CENTRE|DEPARTMENT|DIMENSION_3|DIMENSION_4), valueUid (String, optional â€” null = all values for the slot), rollUp (boolean, default false â€” true includes descendant values via parent_id chain, FR-CC-16), periodId (Long, optional â€” null = all-time). No pagination on the report.

CHILD / NESTED RESOURCES: dimension-values are children of dimensions (each value has dimensionUid + dimensionId, and a self-referential parent via parentUid/parentId for roll-up hierarchy). The values list is fetched by ?dimensionUid=... (required query param), NOT a nested path. UpdateDimensionValueRequest is a PARTIAL update: name (null = unchanged), parentUid (set new parent), and clearParent (Boolean flag to explicitly null the parent / make root vs leave untouched). CreateDimensionValueRequest needs dimensionUid + code + name; parentUid optional (null = root).


## Resource: `dimensions`  (base `/api/v1/dimensions`)

**Status enum:** ACTIVE | INACTIVE | ARCHIVED (MasterStatus)

**Primary DTO fields:** id:string, uid:string, companyId:string, slot:enum(COST_CENTRE|DEPARTMENT|DIMENSION_3|DIMENSION_4), code:string, name:string, builtIn:boolean, mandatory:boolean, status:enum(ACTIVE|INACTIVE|ARCHIVED)

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/dimensions` | `@perm.has('COSTING.VIEW')` | list | (query param) companyId:Long(required) | ApiResponse<List<DimensionDto>> â€” DimensionDto{id:string, uid:string, companyId:string, slot:enum, code:string, name:string, builtIn:boolean, mandatory:boolean, status:enum} (NOT paginated) |
| GET | `/api/v1/dimensions/uid/{uid}` | `@perm.scoped(#uid,'dimension','COSTING.VIEW')` | getByUid |  | ApiResponse<DimensionDto> (same fields as list) |
| PATCH | `/api/v1/dimensions/uid/{uid}/mandatory` | `@perm.scoped(#uid,'dimension','COSTING.MANAGE')` | update (set mandatory/optional toggle) | SetDimensionMandatoryRequest{mandatory:boolean} | ApiResponse<DimensionDto> (updated dimension) |

## Resource: `dimension-values`  (base `/api/v1/dimension-values`)

**Status enum:** ACTIVE | INACTIVE | ARCHIVED (MasterStatus)

**Primary DTO fields:** id:string, uid:string, companyId:string, dimensionId:string, dimensionUid:string, slot:string(DimensionSlot name), code:string, name:string, parentId:string, parentUid:string, active:boolean, status:enum(ACTIVE|INACTIVE|ARCHIVED)

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| POST | `/api/v1/dimension-values` | `@perm.has('COSTING.MANAGE')` | create | CreateDimensionValueRequest{dimensionUid:string, code:string, name:string, parentUid:string(nullable, null=root)} | ApiResponse<DimensionValueDto>{id:string, uid:string, companyId:string, dimensionId:string, dimensionUid:string, slot:string, code:string, name:string, parentId:string, parentUid:string, active:boolean, status:enum} â€” HTTP 201 |
| GET | `/api/v1/dimension-values/uid/{uid}` | `@perm.scoped(#uid,'dimensionvalue','COSTING.VIEW')` | getByUid |  | ApiResponse<DimensionValueDto> (same fields) |
| GET | `/api/v1/dimension-values` | `@perm.has('COSTING.VIEW')` | list | (query params) dimensionUid:String(required), Pageable(page,size,sort) | ApiResponse<List<DimensionValueDto>> with PageMeta{page:int, size:int, totalElements:int, totalPages:int, hasNext:boolean} (PAGINATED) |
| PUT | `/api/v1/dimension-values/uid/{uid}` | `@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')` | update | UpdateDimensionValueRequest{name:string(null=unchanged), parentUid:string(null=clear/make root), clearParent:Boolean(explicit flag: set parent null vs leave untouched)} | ApiResponse<DimensionValueDto> (updated) |
| PATCH | `/api/v1/dimension-values/uid/{uid}/deactivate` | `@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')` | deactivate (action: excludes value from new tagging; precondition none â€” historical lines retain it) |  | ApiResponse<DimensionValueDto> (active=false) |
| PATCH | `/api/v1/dimension-values/uid/{uid}/activate` | `@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')` | activate (action: reactivate a deactivated value) |  | ApiResponse<DimensionValueDto> (active=true) |
| DELETE | `/api/v1/dimension-values/uid/{uid}` | `@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')` | delete (hard-delete; REJECTED if value has any postings â€” BR-CC-05; use deactivate instead) |  | void â€” HTTP 204 No Content |

## Resource: `costing-reports`  (base `/api/v1/costing/reports`)

**Primary DTO fields:** dimensionSlot:enum(COST_CENTRE|DEPARTMENT|DIMENSION_3|DIMENSION_4), rollUp:boolean, rows:DimensionSlicedTbRowDto[]{valueId:string, valueUid:string, valueCode:string, valueName:string, accountId:string, accountUid:string, accountCode:string, accountName:string, accountType:string, totalDebit:string, totalCredit:string, net:string}

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/costing/reports/sliced-trial-balance` | `@perm.has('COSTING.VIEW') and @perm.has('GL.VIEW')` | report (dimension-sliced trial balance) | (query params) companyId:Long(required), slot:DimensionSlot(required enum), valueUid:String(optional, null=all values for slot), rollUp:boolean(default false, true=include descendants FR-CC-16), periodId:Long(optional, null=all-time) | ApiResponse<DimensionSlicedTbDto>{dimensionSlot:enum, rollUp:boolean, rows:[{valueId:string, valueUid:string, valueCode:string, valueName:string, accountId:string, accountUid:string, accountCode:string, accountName:string, accountType:string, totalDebit:string, totalCredit:string, net:string}]} (NOT paginated; slice does NOT net to zero) |