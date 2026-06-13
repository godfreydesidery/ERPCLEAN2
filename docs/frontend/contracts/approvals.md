# API Contract — approvals module (Phase-B frontend)

**Nav group:** Approvals  
**Permission codes (use VERBATIM — do not invent):** APPROVALS.POLICY.VIEW, APPROVALS.POLICY.MANAGE, APPROVALS.REQUEST.VIEW, APPROVALS.DECIDE, APPROVALS.ADMIN

## Module notes
PAGINATION: Both list endpoints and the inbox are PAGINATED and return the ApiResponse envelope { data: [...], meta: PageMeta } where data is List<ApprovalPolicyDto> / List<ApprovalRequestDto>. They accept standard Spring Pageable query params (page, size, sort). Single-item GETs (getByUid) and ALL action POSTs (create, update, deactivate, approve, reject, recall, cancel) return the BARE DTO object â€” it is auto-wrapped into ApiResponse.data by ApiResponseAdvice, so the wire shape is { data: {...} } with no meta. POST create returns HTTP 201 CREATED; all other writes return 200.

FILTERS: GET /api/v1/approvals/policies requires query param companyId (Long) and accepts optional documentType (String, exact match, e.g. PURCHASE_ORDER). GET /api/v1/approvals/requests requires companyId (Long) and accepts optional status (String, one of the ApprovalRequestStatus literals). The inbox takes NO companyId â€” it is implicitly scoped to the caller's company/roles/branches.

ID SERIALISATION: every Long id/companyId/branchId/submittedBy/resolvedBy/sourcePolicyId field is serialised as a STRING on the wire by the global Jackson config; amount/minAmount/maxAmount (BigDecimal) are also strings. Treat all of them as strings in the FE. Timestamps (submittedAt, resolvedAt, decidedAt) are ISO-8601 Instants.

MONEY/CURRENCY: Policy bands use minAmount (inclusive) + maxAmount (exclusive; null = unbounded top band), base currency only (TZS), NUMERIC(19,4). ApprovalRequestDto.amount is the consumer-submitted amount that drove the policy match. currency is always the base currency (3-char code). Band is half-open [minAmount, maxAmount).

LIFECYCLE / ACTIONS (ApprovalRequestStatus state machine): A request is created in-process by consuming modules via the ApprovalEngine service (submitForApproval) â€” there is NO REST submit endpoint in v1. Created PENDING (with a frozen snapshot of the matched policy's steps) or, if no policy matched, created directly terminal APPROVED (autoApproved=true, no steps). Terminal set = {APPROVED, REJECTED, RECALLED, CANCELLED}; a terminal request rejects all further actions. Steps are sequential, single-approver, role-routed; the only actionable step is the current OPEN step = lowest-sequence PENDING step.
 - APPROVE (POST /uid/{uid}/approve, perm APPROVALS.DECIDE scoped): precondition request must be PENDING and target the current open step. Actor must hold APPROVALS.DECIDE + the open step's approverRoleCode (role membership) + branch access + NOT be the submitter (segregation of duties â€” 409/422 if they are). Effect: closes the open step APPROVED, advances to the next PENDING step; if none remain the request resolves APPROVED. NOTE: body is DecideRequest and the controller routes to the same decide() service method as reject â€” the FE MUST send action=APPROVE in the body even though the path says /approve.
 - REJECT (POST /uid/{uid}/reject, perm APPROVALS.DECIDE scoped): same preconditions/actor gate as approve. Effect: closes the open step REJECTED, marks all later PENDING steps SKIPPED, request resolves REJECTED (one reject kills the whole chain). Body is DecideRequest; send action=REJECT.
 - RECALL (POST /uid/{uid}/recall, perm APPROVALS.REQUEST.VIEW scoped â€” note the lighter perm; service enforces caller is the SUBMITTER or holds APPROVALS.ADMIN): precondition request must be PENDING. Effect: request -> RECALLED, all PENDING steps SKIPPED. No request body.
 - CANCEL (POST /uid/{uid}/cancel, perm APPROVALS.ADMIN scoped): precondition request must be non-terminal. Effect: request -> CANCELLED, PENDING steps SKIPPED. No request body.
 Concurrency: two approvers racing on the same step -> optimistic lock, one wins, the other gets a clean 'already decided' (409/422).

CONCURRENCY/OPTIMISTIC-LOCK: request aggregate is @Version guarded; expect 409/422 on lost races for decide actions.

POLICY ACTIONS: create (POST, APPROVALS.POLICY.MANAGE has) takes CreateApprovalPolicyRequest incl. a non-empty steps list (PolicyStepInputDto: sequence dense from 1 + approverRoleCode referencing an IAM roles.code). update (PUT /uid/{uid}) takes UpdateApprovalPolicyRequest (same fields plus a required active boolean; replaces the full step chain). deactivate (POST /uid/{uid}/deactivate) is the soft-delete (MasterStatus). Policy edits only affect FUTURE submissions â€” in-flight requests keep their frozen snapshot. branchUid is required when branchScope=BRANCH and must be null when COMPANY_WIDE (validated in service). Band overlap is rejected at save with a friendly error.

NO REPORT ENDPOINTS in this module. No money posting / no GL â€” the engine only gates.

CHILD / NESTED RESOURCES (read-only, embedded â€” NOT separately addressable by their own URL): 
 - ApprovalPolicyDto.steps -> List<ApprovalPolicyStepDto>{ id, uid, sequence:int, approverRoleCode }.
 - ApprovalRequestDto.steps -> List<ApprovalRequestStepDto>{ id, uid, sequence:int, approverRoleCode, status:ApprovalStepStatus(PENDING|APPROVED|REJECTED|SKIPPED), resolvedBy, resolvedAt, decisions }.
 - ApprovalRequestStepDto.decisions -> List<ApprovalDecisionDto>{ id, uid, approvalRequestStepId, action:DecisionAction(APPROVE|REJECT), decidedBy, decidedAt, comment } â€” the append-only decision history per step. Steps and decisions have no own endpoints; they are addressed via the parent policy/request uid.

SUGGESTED NAV/ROUTES (from ADR): /approvals/inbox (landing work-queue, APPROVALS.DECIDE), /approvals/requests + /approvals/requests/:uid (APPROVALS.REQUEST.VIEW), /approvals/policies (APPROVALS.POLICY.VIEW/MANAGE).


## Resource: `approval-policies`  (base `/api/v1/approvals/policies`)

**Status enum:** MasterStatus: ACTIVE | INACTIVE | ARCHIVED (the policy soft-delete lifecycle). PolicyBranchScope: COMPANY_WIDE | BRANCH (the policy scope discriminator).

**Primary DTO fields:** ApprovalPolicyDto: id:string, uid:string, companyId:string, documentType:string, name:string, branchScope:PolicyBranchScope(COMPANY_WIDE|BRANCH), branchId:string, minAmount:string, maxAmount:string, currency:string, active:boolean, status:MasterStatus(ACTIVE|INACTIVE|ARCHIVED), notes:string, steps:List<ApprovalPolicyStepDto>{id:string,uid:string,sequence:string(int),approverRoleCode:string}

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| POST | `/api/v1/approvals/policies` | `@perm.has('APPROVALS.POLICY.MANAGE')` | create | CreateApprovalPolicyRequest: companyId:string(required), documentType:string(required), name:string(required), branchScope:PolicyBranchScope(required, COMPANY_WIDE\|BRANCH), branchUid:string(required iff branchScope=BRANCH else null), minAmount:string(required,>=0), maxAmount:string(null=top band), notes:string, steps:List<PolicyStepInputDto>{sequence:string(int,>=1),approverRoleCode:string(required)}(non-empty) | ApprovalPolicyDto (HTTP 201; id, uid, companyId, documentType, name, branchScope, branchId, minAmount, maxAmount, currency, active, status, notes, steps) |
| PUT | `/api/v1/approvals/policies/uid/{uid}` | `@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.MANAGE')` | update | UpdateApprovalPolicyRequest: documentType:string(required), name:string(required), branchScope:PolicyBranchScope(required), branchUid:string, minAmount:string(required,>=0), maxAmount:string, notes:string, active:boolean(required), steps:List<PolicyStepInputDto>{sequence,approverRoleCode}(non-empty, replaces chain) | ApprovalPolicyDto |
| POST | `/api/v1/approvals/policies/uid/{uid}/deactivate` | `@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.MANAGE')` | deactivate |  | ApprovalPolicyDto (status reflects MasterStatus soft-delete) |
| GET | `/api/v1/approvals/policies/uid/{uid}` | `@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.VIEW')` | getByUid |  | ApprovalPolicyDto |
| GET | `/api/v1/approvals/policies` | `@perm.has('APPROVALS.POLICY.VIEW')` | list | query params: companyId:string(required), documentType:string(optional exact-match filter), Pageable(page,size,sort) | ApiResponse<List<ApprovalPolicyDto>> (PAGINATED â€” data[] + meta:PageMeta) |

## Resource: `approval-requests`  (base `/api/v1/approvals/requests`)

**Status enum:** ApprovalRequestStatus: PENDING | APPROVED | REJECTED | RECALLED | CANCELLED (terminal = APPROVED,REJECTED,RECALLED,CANCELLED). Nested step status ApprovalStepStatus: PENDING | APPROVED | REJECTED | SKIPPED. Decision action DecisionAction: APPROVE | REJECT.

**Primary DTO fields:** ApprovalRequestDto: id:string, uid:string, companyId:string, branchId:string, requestNumber:string, documentType:string, documentUid:string, amount:string, currency:string, status:ApprovalRequestStatus(PENDING|APPROVED|REJECTED|RECALLED|CANCELLED), autoApproved:boolean, sourcePolicyId:string, sourcePolicyUid:string, summary:string, submittedBy:string, submittedAt:Instant, resolvedAt:Instant, resolvedBy:string, steps:List<ApprovalRequestStepDto>{id:string,uid:string,sequence:string(int),approverRoleCode:string,status:ApprovalStepStatus,resolvedBy:string,resolvedAt:Instant,decisions:List<ApprovalDecisionDto>{id:string,uid:string,approvalRequestStepId:string,action:DecisionAction,decidedBy:string,decidedAt:Instant,comment:string}}

| Method | Path | Perm | Purpose | Request DTO | Response DTO |
|---|---|---|---|---|---|
| GET | `/api/v1/approvals/requests/inbox` | `@perm.has('APPROVALS.DECIDE')` | list | query params: Pageable(page,size,sort) only â€” NO companyId; implicitly scoped to caller's company/roles/branches | ApiResponse<List<ApprovalRequestDto>> (PAGINATED â€” PENDING requests whose current open step the caller can decide, excluding own submissions) |
| GET | `/api/v1/approvals/requests/uid/{uid}` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.REQUEST.VIEW')` | getByUid |  | ApprovalRequestDto (full detail incl. steps + nested decisions history) |
| GET | `/api/v1/approvals/requests` | `@perm.has('APPROVALS.REQUEST.VIEW')` | list | query params: companyId:string(required), status:string(optional, one of ApprovalRequestStatus literals), Pageable(page,size,sort) | ApiResponse<List<ApprovalRequestDto>> (PAGINATED â€” data[] + meta:PageMeta) |
| POST | `/api/v1/approvals/requests/uid/{uid}/approve` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')` | approve | DecideRequest: action:DecisionAction(required, send APPROVE), comment:string(optional) | ApprovalRequestDto (open step closed APPROVED; advances or resolves APPROVED). Precondition: request PENDING, target is current open step, caller holds step role + not submitter |
| POST | `/api/v1/approvals/requests/uid/{uid}/reject` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')` | reject | DecideRequest: action:DecisionAction(required, send REJECT), comment:string(optional) | ApprovalRequestDto (open step REJECTED, later steps SKIPPED, request resolves REJECTED â€” one reject kills chain). Precondition: request PENDING |
| POST | `/api/v1/approvals/requests/uid/{uid}/recall` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.REQUEST.VIEW')` | recall |  | ApprovalRequestDto (request -> RECALLED). Precondition: request PENDING; service enforces caller is submitter OR holds APPROVALS.ADMIN |
| POST | `/api/v1/approvals/requests/uid/{uid}/cancel` | `@perm.scoped(#uid,'approvalrequest','APPROVALS.ADMIN')` | cancel |  | ApprovalRequestDto (request -> CANCELLED). Precondition: request non-terminal (admin force-cancel) |