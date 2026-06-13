# API Contract — fixed-assets module (Phase-B frontend)

**Nav group:** Finance / Fixed Assets  
**Permission codes (use VERBATIM — do not invent):** FA.VIEW, FA.REGISTER.MANAGE, FA.DISPOSE, FA.CATEGORY.VIEW, FA.CATEGORY.MANAGE, FA.DEPRECIATE

## Module notes
RESPONSE ENVELOPE: Every endpoint is wrapped by ApiResponseAdvice into ApiResponse<T> = { data: T, errors: string[], meta: object|null }. Controllers that return ApiResponse<...> directly (the two list endpoints + category list) set meta themselves; all others return the raw payload and the advice wraps it (data = the DTO, meta = null). So even `FixedAssetDto get(...)` arrives on the wire as { data: FixedAssetDto, errors: [], meta: null }. Long ids serialize as JSON STRINGS globally; BigDecimal as strings; int counts (lifePeriods, periodSeq, scheduleVersion, assetCount, PageMeta fields) as JSON numbers.

CORRECT BASE PATHS (ADR-0030 D-15 text says `/api/fixed-assets` but the SHIPPED controllers use `/api/v1/...`): fixed-assets = /api/v1/fixed-assets ; categories = /api/v1/fixed-assets/categories ; depreciation-runs = /api/v1/fixed-assets/depreciation-runs. All by-uid paths are `/uid/{uid}` (literal `/uid/` segment, NOT `/{uid}`). acquire-from-bill is `POST /api/v1/fixed-assets/acquire-from-bill` (at base, no uid).

PAGINATION: fixed-assets list (GET /api/v1/fixed-assets) and depreciation-runs list (GET /api/v1/fixed-assets/depreciation-runs) are PAGINATED â€” they take a Spring Pageable (?page=&size=&sort=) and return meta = PageMeta { page, size, totalElements, totalPages, hasNext }. The fixed-assets list also takes required ?companyId= and optional ?status= (FixedAssetStatus enum). depreciation-runs list takes required ?companyId=. NOT paginated: category list (GET .../categories?companyId=, returns ApiResponse<List<AssetCategoryDto>> with meta=null), and all the per-asset sub-list GETs (schedule, revaluations) which return raw List<...> (wrapped: data = array). reconciliation returns a single object.

ASSET LIFECYCLE (FixedAssetStatus: DRAFT -> IN_SERVICE -> {DISPOSED | WRITTEN_OFF}; last two terminal). Preconditions / state machine:
- register (POST /) or acquire-from-bill: creates a DRAFT asset, allocates FA-#### number. acquire-from-bill requires an AP supplier bill that is MATCHED with an existing bill line; acquisition_cost = bill line NET (VAT excluded).
- update (PUT /uid/{uid}) + financial-field edits: allowed ONLY while DRAFT (non-financial name/location/tag via UpdateAssetRequest). 
- place-in-service (POST /uid/{uid}/place-in-service): DRAFT -> IN_SERVICE. Validates inputs, GENERATES the depreciation schedule, and POSTS the capitalisation journal (DR Fixed Asset / CR FIXED_ASSET_CLEARING at acquisition_cost). Requires an OPEN fiscal period for postingDate (FiscalPeriodResolver). Financial fields become immutable after this.
- transfer (POST /uid/{uid}/transfer): register edit only (branch/location/cost centre) â€” NO GL effect, audit only. All-optional body.
- dispose (POST /uid/{uid}/dispose, type=SALE): IN_SERVICE -> DISPOSED. First charges the outstanding scheduled depreciation up to the disposal period, then posts removal + gain/loss (gainLoss = proceeds - NBV; CR for gain, DR for loss). proceedsAmount >= 0. An asset disposes exactly once (uq on fixed_asset_id).
- write-off (POST /uid/{uid}/write-off, type=WRITE_OFF): IN_SERVICE -> WRITTEN_OFF. A disposal with proceeds=0, loss = full NBV.
- revalue (POST /uid/{uid}/revalue): asset must be IN_SERVICE. deltaAmount always POSITIVE; direction (UP|DOWN) carries the sign. UP -> DR Fixed Asset / CR REVALUATION_RESERVE, carrying_cost += delta. DOWN -> DR loss / CR Fixed Asset, carrying_cost -= delta. Either way the REMAINING schedule is regenerated. Multiple revaluations per asset allowed over its life.

DEPRECIATION RUN (DepreciationRunStatus = {POSTED} only â€” a run is created already POSTED): 
- preview (POST /api/v1/fixed-assets/depreciation-runs/preview?companyId=&fiscalPeriodUid=): READ-ONLY, posts/persists nothing. Returns DepreciationRunPreviewDto (per-asset planned lines + totals). NOTE preview takes its two args as QUERY PARAMS, not a body. perm FA.DEPRECIATE.
- post (POST /api/v1/fixed-assets/depreciation-runs): body RunDepreciationRequest{companyId, fiscalPeriodUid, postingDate}. IDEMPOTENT per (company, fiscal period) via uq_depreciation_run_company_period â€” re-posting an already-run period returns the existing run (no-op), it does NOT error. Requires the period to be OPEN. Posts ONE GL journal (per-category DR Depreciation Expense / CR Accumulated Depreciation legs). 201 CREATED. perm FA.DEPRECIATE.
- run detail GET /uid/{uid} includes the full per-asset lines (DepreciationRunLineDto[]).

MONEY/CURRENCY FIELDS: all amounts are base-currency BigDecimal NUMERIC(19,4) serialized as strings: acquisitionCost, salvageValue, carryingCost, accumulatedDepreciation, nbv (DERIVED = carryingCost - accumulatedDepreciation, read-only), revaluationReserveBalance, reducingRate (rate %, NUMERIC(9,4)), totalChargeAmount, chargeAmount, plannedCharge, proceedsAmount, nbvAtDisposal, gainLossAmount (signed: + gain / - loss), deltaAmount, carryingBefore/After. currency is a 3-char base-currency code string on run/disposal/revaluation DTOs.

REPORT / RECON ENDPOINT SHAPE: GET /api/v1/fixed-assets/reconciliation?companyId= (perm FA.VIEW) returns FixedAssetReconciliationDto = two recon bars: { registerCostSum, glCostBalance, costTies(bool), registerAccumDepSum, glAccumDepBalance, accumDepTies(bool) }. registerCostSum = Î£ carrying_cost where IN_SERVICE vs GL Fixed Assets debit balance; accum-dep bar similarly (GL balance negated for positive presentation).

CHILD / NESTED RESOURCES of an asset (all under /api/v1/fixed-assets/uid/{uid}/...): schedule (GET .../schedule -> DepreciationScheduleLineDto[]), revaluations (GET .../revaluations -> AssetRevaluationDto[]). Disposals + revaluations are NOT separately scoped â€” they reuse the parent 'fixedasset' scope via the asset uid (no own ScopeGuard case). There is NO list-all-disposals and NO standalone disposal GET endpoint in these controllers; a disposal is only returned as the dispose/write-off response. Depreciation run lines are embedded in DepreciationRunDto.lines (no separate run-line endpoint).

ENUMS ON THE WIRE: FixedAssetStatus = DRAFT|IN_SERVICE|DISPOSED|WRITTEN_OFF. DepreciationMethod = STRAIGHT_LINE|REDUCING_BALANCE (reducingRate required iff REDUCING_BALANCE). AssetDisposalType = SALE|WRITE_OFF. RevaluationDirection = UP|DOWN. DepreciationRunStatus = POSTED. AssetCategory.status = MasterStatus (ACTIVE|... soft-delete; DELETE archives, returns the archived AssetCategoryDto). FA.VERIFY perm is documented in the ADR (D-14) as reserved but is NOT referenced by any of the three controllers â€” omitted from permissionCodes which lists only codes actually present in controller annotations.


## Resource: `asset-categories`  (base `/api/v1/fixed-assets/categories`)

**Status enum:** ACTIVE|INACTIVE|ARCHIVED (MasterStatus)

**Primary DTO fields:** id:string, uid:string, companyId:string, code:string, name:string, defaultMethod:enum(STRAIGHT_LINE|REDUCING_BALANCE), defaultLifePeriods:int, defaultReducingRate:string, assetAccountId:string, accumDepAccountId:string, depExpenseAccountId:string, status:enum(MasterStatus e.g. ACTIVE)

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/fixed-assets/categories` | `FA.CATEGORY.VIEW` | list | query params: companyId:Long (required) | ApiResponse<List<AssetCategoryDto>> (NOT paginated; meta=null) |
| POST | `/api/v1/fixed-assets/categories` | `FA.CATEGORY.MANAGE` | create | CreateAssetCategoryRequest{companyId:Long, code:string, name:string, defaultMethod:enum, defaultLifePeriods:int(min1), defaultReducingRate:BigDecimal(opt), assetAccountId:Long, accumDepAccountId:Long, depExpenseAccountId:Long} | AssetCategoryDto (201 CREATED) |
| GET | `/api/v1/fixed-assets/categories/uid/{uid}` | `@perm.scoped(#uid,'assetcategory','FA.CATEGORY.VIEW')` | getByUid |  | AssetCategoryDto |
| PUT | `/api/v1/fixed-assets/categories/uid/{uid}` | `@perm.scoped(#uid,'assetcategory','FA.CATEGORY.MANAGE')` | update | UpdateAssetCategoryRequest{name:string, defaultMethod:enum, defaultLifePeriods:int(min1), defaultReducingRate:BigDecimal(opt), assetAccountId:Long, accumDepAccountId:Long, depExpenseAccountId:Long} | AssetCategoryDto |
| DELETE | `/api/v1/fixed-assets/categories/uid/{uid}` | `@perm.scoped(#uid,'assetcategory','FA.CATEGORY.MANAGE')` | archive (soft-delete; returns archived DTO) |  | AssetCategoryDto |

## Resource: `fixed-assets`  (base `/api/v1/fixed-assets`)

**Status enum:** DRAFT|IN_SERVICE|DISPOSED|WRITTEN_OFF

**Primary DTO fields:** id:string, uid:string, companyId:string, branchId:string, assetNumber:string, categoryId:string, name:string, status:enum(DRAFT|IN_SERVICE|DISPOSED|WRITTEN_OFF), acquisitionCost:string, salvageValue:string, depreciationMethod:enum(STRAIGHT_LINE|REDUCING_BALANCE), lifePeriods:int, reducingRate:string, acquisitionDate:date, depreciationStartDate:date, carryingCost:string, accumulatedDepreciation:string, nbv:string(derived=carryingCost-accumulatedDepreciation), revaluationReserveBalance:string, supplierId:string, sourceBillUid:string, location:string, costCentreId:string, assetTag:string, capitalisedGlEntryUid:string, disposedAt:date

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/fixed-assets` | `FA.VIEW` | list | query params: companyId:Long (required), status:FixedAssetStatus (optional), Pageable(page,size,sort) | ApiResponse<List<FixedAssetDto>> PAGINATED (meta=PageMeta{page,size,totalElements,totalPages,hasNext}) |
| POST | `/api/v1/fixed-assets` | `FA.REGISTER.MANAGE` | create (register a DRAFT asset; allocates FA-#### number) | RegisterAssetRequest{companyId:Long, branchId:Long, categoryId:Long, name:string, acquisitionCost:BigDecimal, salvageValue:BigDecimal(opt), depreciationMethod:enum, lifePeriods:int(min1), reducingRate:BigDecimal(opt), acquisitionDate:date, depreciationStartDate:date, location:string(opt), costCentreId:Long(opt), assetTag:string(opt)} | FixedAssetDto (201 CREATED) |
| POST | `/api/v1/fixed-assets/acquire-from-bill` | `FA.REGISTER.MANAGE` | create (acquire asset from a MATCHED AP supplier bill line; cost = bill line net) | AcquireFromBillRequest{companyId:Long, branchId:Long, billUid:string, billLineUid:string, categoryId:Long, name:string, salvageValue:BigDecimal(opt), depreciationMethod:enum, lifePeriods:int(min1), reducingRate:BigDecimal(opt), acquisitionDate:date, depreciationStartDate:date, location:string(opt), costCentreId:Long(opt), assetTag:string(opt)} | FixedAssetDto (201 CREATED) |
| GET | `/api/v1/fixed-assets/uid/{uid}` | `@perm.scoped(#uid,'fixedasset','FA.VIEW')` | getByUid |  | FixedAssetDto |
| PUT | `/api/v1/fixed-assets/uid/{uid}` | `@perm.scoped(#uid,'fixedasset','FA.REGISTER.MANAGE')` | update (non-financial fields; allowed while DRAFT) | UpdateAssetRequest{name:string, location:string(opt), assetTag:string(opt), costCentreId:Long(opt)} | FixedAssetDto |
| POST | `/api/v1/fixed-assets/uid/{uid}/place-in-service` | `@perm.scoped(#uid,'fixedasset','FA.REGISTER.MANAGE')` | place-in-service (DRAFT->IN_SERVICE; generates schedule + posts capitalisation journal; needs OPEN period) | PlaceInServiceRequest{postingDate:date} | FixedAssetDto |
| POST | `/api/v1/fixed-assets/uid/{uid}/transfer` | `@perm.scoped(#uid,'fixedasset','FA.REGISTER.MANAGE')` | transfer (register edit only, NO GL; branch/location/cost-centre) | TransferAssetRequest{branchId:Long(opt), location:string(opt), costCentreId:Long(opt)} | FixedAssetDto |
| POST | `/api/v1/fixed-assets/uid/{uid}/dispose` | `@perm.scoped(#uid,'fixedasset','FA.DISPOSE')` | dispose (SALE; IN_SERVICE->DISPOSED; charges final depreciation then posts removal+gain/loss) | DisposeAssetRequest{disposalDate:date, proceedsAmount:BigDecimal(>=0), reason:string(opt)} | AssetDisposalDto{id, uid, companyId, branchId, fixedAssetId, disposalType:enum(SALE\|WRITE_OFF), disposalDate:date, fiscalPeriodId, proceedsAmount, nbvAtDisposal, gainLossAmount(signed), glEntryUid, currency, reason} |
| POST | `/api/v1/fixed-assets/uid/{uid}/write-off` | `@perm.scoped(#uid,'fixedasset','FA.DISPOSE')` | write-off (WRITE_OFF; IN_SERVICE->WRITTEN_OFF; proceeds=0, loss=full NBV) | WriteOffAssetRequest{disposalDate:date, reason:string(opt)} | AssetDisposalDto (same fields as dispose; disposalType=WRITE_OFF, proceedsAmount=0) |
| POST | `/api/v1/fixed-assets/uid/{uid}/revalue` | `@perm.scoped(#uid,'fixedasset','FA.DISPOSE')` | revalue (asset IN_SERVICE; UP->reserve, DOWN->expense; regenerates remaining schedule) | RevalueAssetRequest{direction:enum(UP\|DOWN), deltaAmount:BigDecimal(positive), revaluationDate:date, reason:string(opt)} | AssetRevaluationDto{id, uid, companyId, branchId, fixedAssetId, revaluationDate:date, fiscalPeriodId, direction:enum(UP\|DOWN), deltaAmount, carryingBefore, carryingAfter, glEntryUid, currency, reason} |
| GET | `/api/v1/fixed-assets/uid/{uid}/schedule` | `@perm.scoped(#uid,'fixedasset','FA.VIEW')` | list (child: depreciation schedule lines for the asset) |  | List<DepreciationScheduleLineDto>{id, uid, fixedAssetId, periodSeq:int, scheduleVersion:int, periodDate:date, plannedCharge, accumulatedAfter, nbvAfter, posted:bool, depreciationRunId} (raw list, wrapped as data[]) |
| GET | `/api/v1/fixed-assets/uid/{uid}/revaluations` | `@perm.scoped(#uid,'fixedasset','FA.VIEW')` | list (child: revaluation history for the asset) |  | List<AssetRevaluationDto> (raw list, wrapped as data[]) |
| GET | `/api/v1/fixed-assets/reconciliation` | `FA.VIEW` | report (FA-to-GL reconciliation bars) | query params: companyId:Long (required) | FixedAssetReconciliationDto{registerCostSum, glCostBalance, costTies:bool, registerAccumDepSum, glAccumDepBalance, accumDepTies:bool} |

## Resource: `depreciation-runs`  (base `/api/v1/fixed-assets/depreciation-runs`)

**Status enum:** POSTED

**Primary DTO fields:** id:string, uid:string, companyId:string, runNumber:string, fiscalPeriodId:string, postingDate:date, status:enum(POSTED), totalChargeAmount:string, assetCount:int, glEntryUid:string, currency:string, executedAt:instant(timestamp), lines:DepreciationRunLineDto[]{id:string, uid:string, fixedAssetId:string, scheduleLineId:string, chargeAmount:string, accumDepAfter:string, nbvAfter:string}

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/fixed-assets/depreciation-runs` | `FA.VIEW` | list | query params: companyId:Long (required), Pageable(page,size,sort) | ApiResponse<List<DepreciationRunDto>> PAGINATED (meta=PageMeta) |
| POST | `/api/v1/fixed-assets/depreciation-runs/preview` | `FA.DEPRECIATE` | report (read-only preview of the run; nothing posted) | query params: companyId:Long (required), fiscalPeriodUid:String (required) â€” NOT a JSON body | DepreciationRunPreviewDto{companyId, fiscalPeriodUid:string, assetCount:int, totalChargeAmount, lines:DepreciationRunPreviewLineDto[]{fixedAssetId, fixedAssetUid, assetNumber, assetName, categoryId, periodSeq:int, plannedCharge}} |
| POST | `/api/v1/fixed-assets/depreciation-runs` | `FA.DEPRECIATE` | post (post the run; idempotent per company+period; created already POSTED) | RunDepreciationRequest{companyId:Long, fiscalPeriodUid:string, postingDate:date} | DepreciationRunDto (201 CREATED; re-posting an already-run period returns the existing run, no error) |
| GET | `/api/v1/fixed-assets/depreciation-runs/uid/{uid}` | `@perm.scoped(#uid,'depreciationrun','FA.VIEW')` | getByUid |  | DepreciationRunDto (incl. lines[]) |