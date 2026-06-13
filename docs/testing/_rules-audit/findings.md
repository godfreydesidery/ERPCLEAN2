# Rules audit — 85 findings, 40 confirmed

## Confirmed violations

### [HIGH] GoodsReceiptDto.purchaseOrderId (Long id) used as uid in routerLink URL path

GENUINE VIOLATION of INVARIANT #3 (id must never appear in a URL/route path; URLs address entities by uid). Confirmed end-to-end against develop.

Evidence:
1. Template â€” web/src/app/features/admin/purchases/goods-receipt-detail.component.html:35 binds `[route

### [LOW] Route path 'roles/:uid' missing the /uid/ segment convention

CONFIRMED as a real but low-impact convention deviation; NOT an identity-discipline (id-in-URL) breach.

Evidence verified on develop:
- admin.routes.ts:37 â€” `path: 'roles/:uid'` confirmed. It omits the `/uid/` segment.
- role-list.component.html:89 â€” `[router

### [LOW] Route path 'users/:uid' missing the /uid/ segment convention

REAL but LOW (finding over-rated it as MEDIUM). Verified against develop. The route declaration is genuinely non-conformant: admin.routes.ts:55 declares `path: 'users/:uid'`, which yields `/admin/users/<uid>` instead of the canonical `/admin/users/uid/<uid>`. 

### [HIGH] Numeric productId in path: GET /api/v1/stock/on-hand/by-product/{productId}

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline). Confirmed at StockController.java:142-148: `@GetMapping("/on-hand/by-product/{productId}")` binds `@PathVariable Long productId` (line 145), placing the entity's internal numeric surrogate key directly i

### [HIGH] Numeric productId in path: GET /api/v1/stock-serials/product/{productId}

GENUINE VIOLATION of INVARIANT #3 (identity discipline). Confirmed in StockSerialController.java:75-78: @GetMapping("/product/{productId}") binds @PathVariable Long productId, placing an entity's internal numeric id in the URL path segment. PROJECT-CONVENTIONS

### [HIGH] grant-role: userUid free-text input (Grant form)

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline). Verified against develop. grant-role.component.html:26-29 renders a label "User UID" over `<input id="grantUserUid" type="text" [(ngModel)]="userUid" required placeholder="e.g. abc-123-def">` â€” a free-te

### [HIGH] grant-role: companyUid free-text input (Grant form)

CONFIRMED â€” genuine violation of INVARIANT #3 (a uid must never be hand-typed).

Evidence:
- grant-role.component.html:42-45 â€” label "Company UID" with `<input id="grantCompanyUid" type="text" ... [(ngModel)]="companyUid" required placeholder="e.g. xyz-456-uvw

### [MEDIUM] grant-role: branchUid free-text input (Grant form)

CONFIRMED â€” genuine Identity Discipline violation. grant-role.component.html:60-66 collects a uid via raw free-text input: `<input id="grantBranchUid" type="text" [(ngModel)]="branchUid" placeholder="leave blank for company-level">`, label "Branch UID (optiona

### [HIGH] grant-role: lookupUid free-text input (Lookup form)

GENUINE VIOLATION â€” confirmed against develop.

Evidence (grant-role.component.html:82-84):
- Label: `<label ... for="lookupUid">User UID</label>`
- Input: `<input id="lookupUid" name="lookupUid" type="text" class="form-control" [(ngModel)]="lookupUid" require

### [MEDIUM] audit-list: filterActorUid free-text uid input (operator must hand-type/paste a ULID)

GENUINE VIOLATION of INVARIANT #3 (identity discipline â€” a uid is a machine identifier, not something a human types into a form), but severity is MEDIUM, not HIGH.

Evidence:
- web/src/app/features/admin/audit/audit-list.component.html:33-36 is exactly as cite

### [HIGH] sales-return-create: deliveryUidInput free-text input

CONFIRMED â€” genuine INVARIANT #3 (identity discipline) violation on the "a uid must never be hand-typed by a human; use a picker/typeahead resolving to uid" dimension.

Evidence (web/src/app/features/admin/sales/sales-return-create.component.html):
- Lines 17-

### [HIGH] account-ledger: accountUid free-text input

CONFIRMED genuine violation of INVARIANT #3 (identity discipline â€” a uid must never be hand-typed by a human; use a picker/select/typeahead resolving to uid).

Evidence:
- web/src/app/features/admin/reporting/account-ledger.component.html:47-52 â€” label "Accoun

### [HIGH] document-list: filterSourceUid free-text filter and newSourceUid free-text Render-form input

CONFIRMED â€” genuine violation of INVARIANT #3 (Identity Discipline: a uid is a machine identifier and must NEVER be something a human types into a form; use a picker/select/typeahead resolving to uid). Both cited inputs are verified verbatim in web/src/app/fea

### [HIGH] enter-bill: purchaseOrderUid free-text input (uid hand-entered)

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline). Verified against develop.

Evidence:
- web/src/app/features/admin/ap/enter-bill.component.html:271-280 â€” label "Purchase Order UID (optional â€” for 3-way match)" sits over a plain free-text `<input id="pu

### [MEDIUM] enter-bill: per-line poLineUid is a free-text input (uid hand-entered)

GENUINE VIOLATION of INVARIANT #3 (identity discipline â€” a uid must never be hand-typed into a form).

Evidence (web/src/app/features/admin/ap/enter-bill.component.html):
- Lines 308: column header "PO Line UID (opt.)".
- Lines 343-348: each bill line renders 

### [HIGH] budget-version-detail: per-line accountUid free-text input (DIRECT mode)

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline). Verified against develop.

Evidence â€” the cited code is exactly as described:
- web/src/app/features/admin/budgeting/budget-version-detail.component.html:87-94 (DIRECT mode) renders label "Account UID *"

### [MEDIUM] budget-version-detail: per-line fiscalPeriodUid free-text input (DIRECT mode)

GENUINE VIOLATION of INVARIANT #3 (identity discipline â€” a uid must never be hand-entered; use a picker/select/typeahead resolving to uid).

Evidence:
1. Cited code confirmed. web/src/app/features/admin/budgeting/budget-version-detail.component.html:96-105 â€” i

### [MEDIUM] budget-version-detail: fSpreadAccountUid free-text input (ANNUAL_SPREAD mode)

GENUINE VIOLATION (severity adjusted HIGH->MEDIUM).

Evidence at web/src/app/features/admin/budgeting/budget-version-detail.component.html:144-149 â€” confirmed verbatim:
  <label ... for="fSpreadAccUid">Account UID *</label>
  <input id="fSpreadAccUid" name="fS

### [MEDIUM] budget-version-detail: fSeedVersionUid free-text input (SEED mode)

GENUINE VIOLATION of INVARIANT #3 IDENTITY DISCIPLINE ("a uid must NEVER be something a human types into a form â€” use a picker/select/typeahead resolving to uid").

Evidence â€” cited code is real and accurate in substance:
- D:\My_Works\ERP\ERPCLEAN2\web\src\ap

### [MEDIUM] budget-detail: fSeedFromUid free-text input (New Version form)

GENUINE VIOLATION of INVARIANT #3 Identity Discipline (uid must never be hand-typed into a form).

Evidence:
- web/src/app/features/admin/budgeting/budget-detail.component.html:80-84 â€” label "Seed from version UID (optional)" with `<input id="fSeedFromUid" nam

### [HIGH] crm/opportunity-create: fCustomerUid free-text input (hand-entered uid)

CONFIRMED â€” genuine violation of INVARIANT #3 (Identity Discipline). At web/src/app/features/admin/crm/opportunity-create.component.html:53-59 the form renders Label "Customer UID *" with <input id="fCustomerUid" type="text" placeholder="Paste the customer UID

### [MEDIUM] crm/opportunity-create: fSourceLeadUid free-text input requires human to hand-type a uid

GENUINE VIOLATION of INVARIANT #3 (identity discipline â€” a uid is a machine identifier and must never be hand-typed into a form). Confirmed at web/src/app/features/admin/crm/opportunity-create.component.html:112-116: label "Source Lead UID (optional)", placeho

### [HIGH] crm/lead-detail: qualifyCustomerUid free-text input (qualify-to-existing-customer path)

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline â€” a uid is a machine identifier and must NEVER be hand-typed by a human; use a picker/typeahead resolving to uid).

EVIDENCE:
- web/src/app/features/admin/crm/lead-detail.component.html:101-110 â€” when qual

### [HIGH] projects/project-detail: fCustomerUid free-text input (uid hand-entered)

CONFIRMED â€” genuine violation of INVARIANT #3 (Identity Discipline: a uid is a machine identifier that must never be hand-typed into a form; use a picker/typeahead resolving to uid).

Evidence:
- Cited code matches exactly. project-detail.component.html:154-15

### [MEDIUM] projects/project-detail: fManagerUid free-text input (hand-entered user uid)

GENUINE VIOLATION (confirmed), severity downgraded HIGH->MEDIUM.

Evidence:
- web/src/app/features/admin/projects/project-detail.component.html:159-164 â€” label "Manager UID (optional)", `<input id="fManagerUid" name="fManagerUid" type="text" class="form-contro

### [MEDIUM] projects/project-detail: tsTaskUid free-text input in timesheet form

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline). Confirmed against develop.

Evidence:
- web/src/app/features/admin/projects/project-detail.component.html:381-384 â€” label "Task UID (optional)" with `<input id="tsTaskUid" type="text" ...>` bound two-way

### [HIGH] projects/project-detail: per-line productUid free-text input in issue-to-job form

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline â€” a uid must never be hand-typed into a form).

Evidence:
- web/src/app/features/admin/projects/project-detail.component.html:518-527 â€” each issue line renders label "Product UID *" with `<input type="text

### [HIGH] manufacturing/work-order-list: newProductUid free-text input (Create form)

CONFIRMED genuine INVARIANT #3 (identity discipline) violation. At web/src/app/features/admin/manufacturing/work-order-list.component.html:75-81 the "New Work Order" create form renders label "Finished Product UID *" (L76), placeholder "product uidâ€¦" (L79), an

### [HIGH] manufacturing/work-order-list: newBranchUid free-text input (Create form)

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline â€” a uid is a machine identifier and must never be hand-typed into a form).

EVIDENCE:
- web/src/app/features/admin/manufacturing/work-order-list.component.html:96-104 â€” a `<div class="col-sm-3">` with labe

### [MEDIUM] manufacturing/work-order-list: newBomUid free-text input (Create form)

GENUINE violation of INVARIANT #3 (IDENTITY DISCIPLINE) as stated in the audit standard. Verified against develop.

EVIDENCE:
- web/src/app/features/admin/manufacturing/work-order-list.component.html:107-113 â€” label "BOM UID (optional)", `<input id="newBomUid"

### [HIGH] manufacturing/work-order-detail: fBomUid and fBranchUid free-text inputs (Edit form)

GENUINE VIOLATION (confirmed). At web/src/app/features/admin/manufacturing/work-order-detail.component.html the Edit Work Order form (shown when canEdit()) contains two raw free-text uid inputs:
- Lines 129-132: label "BOM UID (override)" -> <input id="fBomUid

### [MEDIUM] manufacturing/work-order-detail: releaseBomUid free-text input (Release action)

GENUINE VIOLATION of INVARIANT #3 (Identity discipline). Confirmed at web/src/app/features/admin/manufacturing/work-order-detail.component.html:186-192: the Release panel renders label "BOM UID (optional override)" over an <input type="text" id="releaseBomUid"

### [MEDIUM] manufacturing/work-order-detail: costOperationUid free-text input (Apply Cost action)

GENUINE VIOLATION of INVARIANT #3 (IDENTITY DISCIPLINE). Confirmed at web/src/app/features/admin/manufacturing/work-order-detail.component.html:263-266: a plain free-text `<input id="costOperationUid" type="text">` labeled "Operation UID (optional)", two-way b

### [HIGH] hr-payroll/loan-list: fEmployeeUid AND fEmployeeId both free-text inputs

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline). Confirmed at web/src/app/features/admin/hr-payroll/loan-list.component.html:65-68: a raw <input type="text"> labeled "Employee UID *" bound to fEmployeeUid() (placeholder "uid"), i.e. a uid hand-typed by

### [HIGH] hr-payroll/leave-request-list: fEmployeeUid free-text input (uid hand-entry)

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline). Confirmed against code on develop.

Evidence:
- Template (web/src/app/features/admin/hr-payroll/leave-request-list.component.html:58-64): label "Employee UID *", a raw `<input id="lrEmpUid" type="text" p

### [HIGH] approvals/approval-policy-detail: fBranchUid free-text input

GENUINE VIOLATION of INVARIANT #3 (Identity Discipline â€” a uid must never be something a human types into a form; use a picker/select/typeahead resolving to uid).

EVIDENCE (verbatim, confirmed on develop):
- web/src/app/features/admin/approvals/approval-polic

### [HIGH] approvals/approval-policy-list: newBranchUid free-text input (Create form)

GENUINE VIOLATION of INVARIANT #3 (identity discipline â€” a uid is a machine identifier and must NEVER be hand-typed into a form; use a picker/select/typeahead resolving to uid).

Evidence:
1. web/src/app/features/admin/approvals/approval-policy-list.component.

### [MEDIUM] fixed-asset-create: fBranchId free-text input (raw branch id hand-typed, should be a picker)

GENUINE VIOLATION of INVARIANT #3 (identity discipline â€” machine identifiers, id or uid, must never be hand-typed). Confirmed at web/src/app/features/admin/fixed-assets/fixed-asset-create.component.html:64-67: a `<label>`"Branch ID *" over `<input id="fBranchI

### [HIGH] quotation-list: prev/next only â€” missing FIRST, LAST, page-number controls

GENUINE VIOLATION (confirmed against develop).

Evidence:
- File: D:\My_Works\ERP\ERPCLEAN2\web\src\app\features\admin\sales\quotation-list.component.html, lines 257-277. The pagination block is `@if (meta().totalPages > 1)` and contains exactly two controls: 

### [HIGH] sales-order-list: prev/next only â€” missing FIRST, LAST, page-number links

GENUINE violation of the audited PAGINATION invariant. At sales-order-list.component.html:256-274 the pagination nav (inside `@if (meta().totalPages > 1)`) renders only a Previous chevron button (prevPage(), L258-263) and a Next chevron button (nextPage(), L26


## Refuted (false positives)


## Low severity

- Route path 'companies/:companyUid/branches' missing the /uid/ prefix segment (web/src/app/features/admin/admin.routes.ts:25)
- documents.service.ts HTTP calls use /${uid} not /uid/${uid} â€” backend DocumentController deviates from /uid/{uid} convention (web/src/app/features/admin/documents/documents.service.ts:76)
- CORRECTLY IMPLEMENTED (not a violation) â€” post-journal accountUid: <select> from chart-of-accounts (web/src/app/features/admin/gl/post-journal.component.html:138-151)
- CORRECTLY IMPLEMENTED (not a violation) â€” gl-config setAccountUid: <select> from accounts (web/src/app/features/admin/gl/gl-config.component.html:68-78)
- CORRECTLY IMPLEMENTED (not a violation) â€” opening-valuation selectedStockOnHandUid: <select> from unvaluedRows (web/src/app/features/admin/inventory-valuation/opening-valuation.component.html:115-127)
- CORRECTLY IMPLEMENTED (not a violation) â€” goods-receipt-create PO picker: typeahead resolves to PO uid (web/src/app/features/admin/purchases/goods-receipt-create.component.html:38-66)
