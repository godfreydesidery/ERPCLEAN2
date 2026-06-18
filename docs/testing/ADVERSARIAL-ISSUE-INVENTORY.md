# Adversarial System Test — Issue Inventory

> Generated 2026-06-18 by an 11-persona multi-agent adversarial test of the live ERP API (7 role-played users + 4 security probes: privilege-escalation, broken-auth, input-fuzzing, IDOR/tenant). Each finding was independently re-verified by a separate skeptical agent before inclusion. 22 agents · 676 tool calls.

**Confirmed defects:** 56 (raw reported 63) — CRITICAL 4 · HIGH 11 · MEDIUM 17 · LOW 14

## Remediation status (2026-06-18) — CLOSED-OUT TRACKER

Fixes shipped on `main` via PR #97 (`2b8406a`) — remediation Waves 1–3 + an optimistic-lock follow-up. Full `mvn verify` green at every wave (final: 806 IT tests, 0 failures). Code-only except two schema changes (V52 in-place edit, new V69) requiring a DB recreate (done on local + QA).

**Legend:** ✅ Fixed · ➖ Folded (duplicate) · ⏸ Deferred (low value / conscious)
**Commits:** W1 `4f14695` (CRITICAL + global exception-handler) · W2 `d4f6bbf` (HIGH) · W3 `b30c8ac` (MEDIUM/LOW) · OptLock `3e03d62`

| # | Sev | Status | Where |
|---|-----|--------|-------|
| 1 | CRIT | ✅ | W1 — cross-customer AR allocation guard |
| 2 | CRIT | ✅ | W1 — control-account guard (all sourceTypes + cash path) |
| 3 | CRIT | ✅ | W1 — 3-way match HELD bill-number |
| 4 | HIGH | ✅ | W2 — system-role permission-strip guard |
| 5 | HIGH | ✅ | W2 — SO-confirm location-aware stock lookup |
| 6 | HIGH | ✅ | W2 — negative unitPriceOverride reject |
| 7 | HIGH | ➖ | folded into #16 (duplicate) |
| 8 | HIGH | ✅ | W1 — cross-supplier bill guard |
| 9 | HIGH | ✅ | W2 — GL reversal idempotency + flag |
| 10 | HIGH | ✅ | W1 — reserved sourceTypes (auto-closed by #2: postManual forces MANUAL) |
| 11 | HIGH | ✅ | W1 — control-account post now 409 (guard + handler) |
| 12 | HIGH | ✅ | W2 — stock on-hand/allowNegative + cost-moves-with-units |
| 13 | HIGH | ✅ | W2 — leave-type CRUD + graceful FK |
| 14 | HIGH | ✅ | W2 — loan PENDING→approve lifecycle |
| 15 | MED | ✅ | W2 (SO) + W3 (quotation) — @Positive line qty |
| 16 | MED | ✅ | W3 — @Positive receipt/allocation amount |
| 17 | MED | ✅ | W3 — quotation validUntil≥quoteDate guard |
| 18 | MED | ✅ | W3 — bill cost @PositiveOrZero + dueDate≥billDate |
| 19 | MED | ✅ | W3 — purchase-return @Valid/@Positive |
| 20 | MED | ✅ | W3 — contract endDate≥startDate |
| 21 | MED | ✅ | W2 (leaveTypeId→404) + W3 (fromDate≤toDate) |
| 22 | MED | ✅ | W3 — department/branch existence check |
| 23 | MED | ✅ | W3 — glAccount existence check |
| 24 | MED | ✅ | W3 — V69 unique national_id/tin |
| 25 | MED | ✅ | W3 — installmentAmount≤principal |
| 26 | MED | ✅ | W3 — statutory-rate range (+W1 handler net) |
| 27 | MED | ✅ | W3 — PAYE-band range validation |
| 28 | MED | ✅ | W3 — @Digits(15,4) on journal amounts |
| 29 | MED | ✅ | W3 — @NotEmpty journal lines |
| 30 | MED | ✅ | W3 — reverse honours date/reason |
| 31 | MED | ✅ | W3 — FX self-currency rate must=1 |
| 32 | LOW | ✅ | W3 — V69 unique email |
| 33 | LOW | ✅ | W3 — username @Pattern |
| 34 | MED | ⏸ | deferred — Angular auto-escapes on render; input sanitization not added |
| 35 | LOW | ⏸ | deferred — unknown currency code not validated |
| 36 | LOW | ⏸ | deferred — 404-vs-403 ordering (conscious; 403-for-nonexistent is safe) |
| 37 | LOW | ✅ | W3 — employee @Email + gender validation |
| 38 | LOW | ⏸ | deferred — DOB far-future/after-hire not validated |
| 39 | LOW | ✅ | W1 — enum FQCN scrubbed |
| 40 | LOW | ⏸ | deferred — param-binding-before-authz ordering (conscious) |
| 41 | LOW | ✅ | W1 — 'date null' leak suppressed (explicit postingDate @NotNull not added) |
| 42 | LOW | ✅ | W1 — over-length → 400 via handler (explicit @Size not added) |
| 43 | LOW | ⏸ | deferred — far-future transferDate not validated |
| 44 | LOW | ⏸ | accepted — companyId ignored, no cross-tenant leak (no security impact) |
| 45 | LOW | ⏸ | deferred — null DTO product/location fields (cosmetic enrichment) |
| 46 | LOW | ⏸ | deferred — inverted report date range not validated |
| 47 | LOW | ✅ | W1 — unsupported Content-Type → 415 |

**Plus (new, not in original inventory):** optimistic-lock conflict → 409 instead of 500 (`3e03d62`) — surfaced by concurrent stock-on-hand writes during re-seed.

**Totals:** 37 fixed · 1 folded · 9 deferred (8 LOW + 1 MED #34 mitigated by Angular output-encoding). The 9 deferred are low-value/edge validations + two conscious error-ordering items — a candidate "Wave 4" if a fully-closed list is wanted.

## Root-cause themes

1. Unhandled DB/JPA exceptions surface as generic HTTP 500 instead of 4xx: the global @ControllerAdvice does not translate DataIntegrityViolationException (CHECK/UNIQUE/FK/varchar-length/numeric-overflow), HttpMediaTypeNotSupportedException, or NullPointerException. ~18 distinct 500s collapse to this one root cause across GL, AR, AP, Sales, Quotations, HR, Stock, and Auth.
2. Missing/un-triggered bean validation on request DTOs and nested line records: @Positive/@NotEmpty/@Valid declared but not enforced (SO/quotation/purchase-return line qty, requisition empty lines, unit cost). Several controllers likely missing @Valid, and @Valid not cascading into nested collection records. Sibling endpoints (invoice line, supplier-bill) validate the SAME field correctly — strong evidence of inconsistent wiring rather than intentional design.
3. Cross-entity ownership/consistency guards absent across finance: AR receipt and AR credit note can target another customer's invoice; supplier bill can reference another supplier's PO. No 'subject must own/match the referenced document' rule layer.
4. Control-account protection is incomplete and bypassable: the allowManualPosting=false guard fires only when sourceType=MANUAL on /gl/journals, and the /cash/entries path applies no guard at all — two independent routes to corrupt subledger-to-GL reconciliation.
5. System-role / privileged-state protection asymmetry: DELETE of a system role is guarded but PUT /permissions is not; no last-admin-role safeguard, enabling irrecoverable admin lockout.
6. No idempotency / state-transition guards on lifecycle actions: GL journal reverse is unbounded (over-reversal, original never flagged), HR loan approve is a no-op and double-approvable, loans skip PENDING and are ACTIVE on create (segregation-of-duties bypass).
7. Stock movement endpoints (instant-complete, dispatch) perform no on-hand / allowNegative check, driving locations arbitrarily negative; cost is not moved with units, breaking valuation (positive value on negative qty, negative average cost).
8. Missing uniqueness on real-world identifiers: user email, employee nationalId and TIN; and missing format constraints (username charset, employee email/gender, displayName sanitization). UNIQUE constraint surfaces as 500 where it does exist (statutory rates, PAYE bands, FX rate upsert).
9. Missing temporal/range and reference-data validation: date-order checks (validUntil<quoteDate, due<bill, contract end<start, leave from>to, DOB after hire, inverted report ranges), unknown currency code, far-future dates, FX self-conversion rate!=1, reserved system sourceTypes accepted, unknown FK ids (department/branch/leaveType/glAccount) returning 500.
10. Inconsistent error semantics / information disclosure: not-found masked as 403 (scope guard before existence check) module-wide; param-binding 400 before RBAC 403; enum error leaks fully-qualified Java class name; internal 'date null' message leaked.
11. API contract drift: OpenAPI publishes the wrong (RFQ) line shape for purchase-requisition and purchase-return endpoints, so schema-conforming clients fail.
12. Whole HR Leave feature non-functional: every leaveTypeId returns 500 and no leave-type CRUD endpoint exists.

---

This inventory consolidates 42 independently confirmed defects from a multi-persona adversarial test of the ERP API (org-admin, sales, procurement, accountant, store, hr, branch-mgr, broken-auth, input-fuzz). After deduplication by root cause, the dominant story is two-fold: (1) **financial/data-integrity guards are missing or bypassable** — cross-customer AR allocation/credit notes, cross-supplier bills, a control-account posting guard that only fires for `sourceType=MANUAL` (and is absent entirely on the cash path), unbounded journal reversal, and stock movements that ignore on-hand/allowNegative; and (2) **a single error-handling gap** — the global exception handler does not translate `DataIntegrityViolationException`, `HttpMediaTypeNotSupportedException`, or `NullPointerException` — which produces roughly eighteen generic HTTP 500s across nearly every module where bad-but-validatable input reaches the DB. A pervasive secondary theme is bean-validation that is declared on DTOs but not enforced (missing `@Valid` / non-cascading `@Valid` on nested line records), proven by sibling endpoints that validate the identical field correctly. The CRITICAL items are financial-correctness breaks an authorized user can trigger today; most MEDIUM/LOW items are unhandled-500s and missing validations that are individually low-impact but collectively erode API trust and pollute error monitoring.

---

## CRITICAL

### AR / Receipts & Credit Notes

**1. Cross-customer AR allocation accepted (receipt and credit note applied to another customer's invoice)**
- **Module:** AR / Receipts, AR / Credit Notes
- **Endpoints:** `POST /api/v1/ar/receipts`, `POST /api/v1/ar/credit-notes`
- **Repro:** Create a receipt (or credit note) for CUST-0002 (`customerUid=01KVD5W3DPGTKPPZ7FHBWZRFHN`) with an allocation against `arInvoiceUid=01KVDR0XE2N53KATGCY09DS1FQ`, which is owned by CUST-0001 (customerId=1).
- **Expected:** 400/409 — a receipt/credit note for customer B cannot be allocated to an invoice owned by customer A (BR-AR customer-match).
- **Actual:** HTTP 201. Receipt created with `customerId=2` allocating against `arInvoiceId=1`; credit note created `status=APPLIED` against `arInvoiceId=1`. Both customers' subledgers are corrupted (customer 2's payment/credit reduces customer 1's outstanding).
- **Evidence:** Receipt uid `01KVDRSJZQ0FNS2Z3C9EY4YGQ6` `{"customerId":"2", allocations:[{"arInvoiceId":"1","allocatedAmount":100}], status:"PARTIAL"}`; credit note uid `01KVDRT2XVF1PWWYQ83QYJ3FD4` `{"customerId":"2","arInvoiceId":"1","status":"APPLIED"}`.
- **Fix hint:** In `ArReceiptServiceImpl.recordAndAllocate` and the credit-note service, before persisting each allocation, assert the target `ArInvoice.customerId == request.customerId` and throw a domain `ConflictException`. Same guard needed on both paths (shared validator).

### GL / CASHBANK — control-account protection

**2. Control-account manual-posting guard bypassable via non-MANUAL sourceType, and absent on the cash path**
- **Module:** GL, CASHBANK
- **Endpoints:** `POST /api/v1/gl/journals`, `POST /api/v1/cash/entries`
- **Repro (GL):** As accountant, POST a balanced journal debiting the AR control account (1200, `allowManualPosting=false`) with `sourceType` set to any value other than `MANUAL` (e.g. `SALES`, `COGS` into INVENTORY 1300, `CASH_DIRECT`/`AP_BILL`). **Repro (cash):** POST a cash entry whose `counterGlAccountUid` is a control account (AR/AP/INVENTORY/TAX).
- **Expected:** Direct posting to any `allowManualPosting=false` control account from a user-driven path must be rejected (409), regardless of `sourceType` and regardless of module.
- **Actual:** Both return 201. The GL guard is enforced only when `sourceType=MANUAL`; every other sourceType skips it. The cash module generates a balanced GL journal posting directly to the control account with no guard at all — a second independent bypass.
- **Evidence:** GL `sourceType=MANUAL` into 1200 → 409 "Account 1200 does not allow manual posting"; `sourceType=SALES` into 1200 → 201 (uid `01KVDRJNFYX96AWEQHY13292WD`); `COGS`→INVENTORY, `CASH_DIRECT`→VAT 2200, `AP_BILL`→AP all 201. Cash OUT counterGl=AR → 201 (journal uid `01KVDRNPEP4DR099EYTGK50R1N`, line accountCode 1200 debit 25). Account-ledger for 1200 shows phantom rows with no subledger behind them.
- **Fix hint:** Move the `allowManualPosting` check out of the `sourceType==MANUAL` branch in the GL posting service so it runs for every line on the user-driven journal endpoint. Apply the identical control-account check in the cash-entry → journal generation path (likely `CashEntryServiceImpl`). Closely related to issue **#16** (reserved sourceTypes accepted) which enables this.

### AP / Supplier Bills — 3-way match

**3. 3-way match returns HTTP 500 on any over-tolerance variance, making variance review unreachable**
- **Module:** AP / Supplier Bills (3-way match)
- **Endpoint:** `POST /api/v1/ap/supplier-bills/uid/{billUid}/match/run` (and consequently `.../match/accept-variance`)
- **Repro:** PO 10@100, place; GRN receive 10; create bill with `unitCostAmount=150` (50% over) or `billedQty=12` (over received); `POST match/run {}`. Within tolerance (101 = 1%) → 200 MATCHED; any variance ≥ ~5% or qty over-bill → 500.
- **Expected:** 200 with bill status HELD/NEEDS_REVIEW and per-line variance figures so it can be reviewed/accepted, or a clean 4xx.
- **Actual:** HTTP 500. The bill stays DRAFT (never HELD), so `accept-variance` always returns 409 "Bill ... is not HELD" — the entire variance workflow is dead.
- **Evidence:** cost 100→200 MATCHED, 101→200 (priceVariancePct 1.0), 150→500, billedQty 12→500. `backend-local.log`: `DataIntegrityViolationException ... violates check constraint "chk_supplier_bill_number_when_posted" ... status HELD, bill_number null`.
- **Fix hint:** Design contradiction: `BillMatchServiceImpl` (~line 220) sets HELD with `bill_number=null`, but DB CHECK `chk_supplier_bill_number_when_posted` forbids null number for that status. Either relax the CHECK to permit `bill_number IS NULL` when status=HELD, or assign a bill number when transitioning to HELD. Then the HELD path persists and `accept-variance` becomes reachable.

---

## HIGH

### IAM / Roles

**4. System role ORG_ADMIN can have all permissions stripped via PUT /permissions (irrecoverable admin lockout)**
- **Module:** IAM / Roles
- **Endpoint:** `PUT /api/v1/roles/uid/{uid}/permissions`
- **Repro:** As an admin with ROLE.MANAGE, PUT `{"permissionCodes":["USER.VIEW"]}` to the system ORG_ADMIN role (`uid=0000000000XVKF7J9FAGX51RMQ`, `system=true`). Re-login: GET /roles, /audit, POST /users all now 403.
- **Expected:** A `system=true` role should be protected from destructive permission replacement the same way it is from deletion (403), and/or IAM-critical perms (ROLE.MANAGE/USER.MANAGE) must not be removable from the last admin role.
- **Actual:** HTTP 200, permission set silently replaced, acting admin loses ROLE.MANAGE/USER.MANAGE/AUDIT.VIEW. No API path remains to restore (requires DB/rootadmin).
- **Evidence:** PUT → 200 with `permissionCodes:["USER.VIEW"]`; subsequent GET /roles → 403; DELETE on the same role correctly → 403. Source: `RoleServiceImpl.archiveByUid` guards `if (role.isSystem()) throw ConflictException` (lines 96-104) but `setPermissions` (lines 73-94) has no such check. **Live state: ORG_ADMIN is currently left stripped; full original 223-code list saved at /tmp/perms.json.**
- **Fix hint:** Add the same `isSystem()` guard to `RoleServiceImpl.setPermissions`; additionally add a last-admin-role safeguard preventing removal of ROLE.MANAGE/USER.MANAGE from the only role that grants them.

### Sales Orders

**5. Sales-order confirm returns HTTP 500 due to duplicate stock_on_hand rows (NonUniqueResultException)**
- **Module:** Sales Orders
- **Endpoint:** `PUT /api/v1/sales-orders/uid/{uid}/confirm`
- **Repro:** Confirm an SO containing PROD-0001 or PROD-0002 → 500; PROD-0003..0008 → 204.
- **Expected:** 204 or a clear domain error.
- **Actual:** HTTP 500. `StockOnHandRepository.findByCompanyIdAndBranchIdAndProductId` returns 2 rows for those products.
- **Evidence:** `backend-local.log`: `NonUniqueResultException: Query did not return a unique result: 2 results were returned` at `StockReservationServiceImpl.doApply:52` ← `SalesOrderServiceImpl.doConfirm:277`.
- **Fix hint:** Add a UNIQUE constraint on `stock_on_hand(company_id, branch_id, product_id)` (and dedupe existing rows), and/or change the repository finder to handle multiplicity. Root cause is missing uniqueness on stock_on_hand.

**6. Negative unitPriceOverride accepted on SO/invoice line — goods ship but invoice value silently 0**
- **Module:** Sales Orders / Invoices
- **Endpoint:** `POST /api/v1/sales-orders/uid/{uid}/lines`
- **Repro:** Add SO line with `unitPriceOverride:-500`, qty 2; confirm; create delivery qty 2.
- **Expected:** 400 — unit price must be ≥ 0.
- **Actual:** Line accepted (201) with `unitPriceAmount=-500`, `priceOverridden=false`, `netAmount=0`/`grossAmount=0` (negative silently clamped to 0). SO confirms, delivery succeeds — stock leaves inventory while line value is 0 (revenue leakage + unitPrice-vs-net inconsistency).
- **Evidence:** SO line 201 `{"unitPriceAmount":-500,"netAmount":0,"grossAmount":0}`; delivery qty 2 → 201 CONFIRMED.
- **Fix hint:** Validate `unitPriceOverride >= 0` in the add-line request/service; do not clamp negatives to 0 in the rollup — reject them.

**7. Negative receipt/credit-note amounts already covered under #11 below; this slot intentionally folded.**

**8. Supplier bill can be created against a PO belonging to a different supplier**
- **Module:** AP / Supplier Bills
- **Endpoint:** `POST /api/v1/ap/supplier-bills`
- **Repro:** Place a PO for supplier A (id=1). POST a bill with `supplierUid`=supplier B (id=2) but `purchaseOrderUid`=supplier A's PO.
- **Expected:** 400/409 — bill supplier must match the referenced PO's supplier.
- **Actual:** 201. Bill shows `supplierId=2` linked to a PO whose `supplierId=1`.
- **Evidence:** bill uid `01KVDS4S1WF4JJRPKBZDBYGHS9` `{"supplierId":"2","purchaseOrderUid":"01KVDQJ53GDS9CZDH46Y74FST7"}`; that PO is SUPP-0001 (supplierId=1).
- **Fix hint:** In the supplier-bill create service, when `purchaseOrderUid` is supplied, assert `po.supplierId == request.supplierId`. Same cross-entity-ownership pattern as #1.

### GL

**9. GL journal can be reversed unlimited times (no idempotency guard) — over-reversal**
- **Module:** GL
- **Endpoint:** `POST /api/v1/gl/journals/uid/{uid}/reverse`
- **Repro:** Create a manual journal, call reverse 3× on its uid.
- **Expected:** A journal is reversible at most once; subsequent attempts → 409; original flagged `reversed=true`/`reversedByEntryId` set.
- **Actual:** Every call → 201, creating a new reversal entry all pointing at the same original; original stays `reversed=false`, `reversedByEntryId=null`. GL double/triple-counts the reversal.
- **Evidence:** journal uid `01KVDRQH2TVP85BSWF46BW6KZ8` (id 4471) reversed ×3 → JB-4465/4466/4467 each `reversalOfId=4471`; original re-fetch `reversed:false`.
- **Fix hint:** In the reverse service, guard `if (entry.isReversed()) throw ConflictException`; within the same transaction set `original.reversed=true` and `reversedByEntryId`.

**10. Manual journal endpoint accepts reserved system sourceTypes (YEAR_END_CLOSE, DEPRECIATION, etc.)**
- **Module:** GL
- **Endpoint:** `POST /api/v1/gl/journals`
- **Repro:** POST a manual journal with `sourceType=YEAR_END_CLOSE` (also SALES, COGS, CASH_DIRECT, AP_BILL).
- **Expected:** User-driven endpoint should only allow `MANUAL` (or a restricted whitelist); reserved system types rejected.
- **Actual:** 201 — hand-crafted entries are mislabeled as system-generated, polluting source-based filtering/reconciliation. This is the enabler for the control-account bypass (#2).
- **Evidence:** `sourceType=YEAR_END_CLOSE` → 201 (uid `01KVDRKNA5H2QXSJRSQNZSZC8S`); sourceType is an open 34-value enum with no whitelist.
- **Fix hint:** On the public journal controller, force/validate `sourceType=MANUAL` (or a small whitelist). Fixing this both restores audit-source trust and closes the #2 bypass.

**11. 500 when sourceType matches the target control account's controlType (AR→AR, AP→AP)**
- **Module:** GL
- **Endpoint:** `POST /api/v1/gl/journals`
- **Repro:** POST `sourceType=AR` debiting AR control (1200); also `sourceType=AP` into AP (2100).
- **Expected:** Clean 4xx (409) as with `sourceType=MANUAL`.
- **Actual:** HTTP 500 — the control-account code path throws unhandled when sourceType equals controlType.
- **Evidence:** `sourceType=AR`→AR(1200) → 500; `sourceType=AP`→AP → 500; `sourceType=MANUAL` same accounts → clean 409. Reproduced with fresh postingDate (not a dup-key artifact).
- **Fix hint:** In the control-account branch of the GL posting service, handle the sourceType==controlType case explicitly (return the same 409) instead of falling into an unguarded path.

### Stock / Transfers & Valuation

**12. Stock transfer (instant-complete and in-transit dispatch) ignores on-hand / allowNegative; valuation reconciliation then breaks**
- **Module:** Stock / Transfers, Stock / Valuation
- **Endpoints:** `PATCH /api/v1/stock-transfers/uid/{uid}/complete-instant`, `PATCH /api/v1/stock-transfers/uid/{uid}/dispatch`, `GET /api/v1/stock/valuation/report`
- **Repro:** From source location with `allowNegative=false` and limited on-hand, create an INSTANT transfer for qty > on-hand and `complete-instant` (or IN_TRANSIT + `dispatch`). Then GET the valuation report and by-product on-hand.
- **Expected:** Completion/dispatch must verify on-hand at source and reject when `allowNegative=false`; on-hand must never go negative; cost must move with units so `value = quantity × avgCost`; negative quantity must not carry positive valuation; avgCost never negative.
- **Actual:** Both paths return 200 and drive the source location arbitrarily negative (e.g. -10066, -20075) with no availability check. Cost is not moved: destination gets quantity but `onHandValue=0`/`avgCost=null`, source keeps full value on negative quantity → valuation report shows `quantity=-19982, avgCost=-81.11 (negative), value=1620787.179 (positive)`.
- **Evidence:** complete-instant → 200 COMPLETED, loc 11 qty -10066; dispatch → 200 DISPATCHED `errors:[]`, loc 11 -20075; valuation `{"productCode":"PROD-0002","quantity":-19982.0,"avgCost":-81.11,"value":1620787.179}`; dest loc 9 qty 93 value 0 avgCost null.
- **Fix hint:** Add an on-hand/allowNegative guard in the transfer completion and dispatch services (`StockTransferServiceImpl`) before applying movements. Fix the cost-movement logic so transferred units carry their cost layer to the destination; the valuation report's `avgCost = value/quantity` derivation must be guarded against negative/zero quantity. Likely shares the missing stock_on_hand uniqueness root cause with #5.

### HR / Leave & Loans

**13. HR Leave is entirely non-functional: every leaveTypeId returns 500 and no leave-type CRUD endpoint exists**
- **Module:** HR / Leave
- **Endpoint:** `POST /api/v1/hr/leave-requests/employee/{employeeUid}`
- **Repro:** POST a fully valid leave request for leaveTypeId 1..6 — all 500. OpenAPI exposes no leave-type create/list path.
- **Expected:** Valid request → 201; a leave-type master manageable via API.
- **Actual:** All six leaveTypeIds → 500; no way to seed leave types, so the feature cannot work.
- **Evidence:** leaveTypeId 1..6 each → 500; api-docs HR paths only `/hr/leave-requests`, `/employee/{uid}`, `/uid/{uid}`, `/uid/{uid}/decide`.
- **Fix hint:** Seed/provide a leave-type master (migration + CRUD controller), and confirm `LeaveType` rows exist; the current 500 is likely an FK/null lookup on a missing leave_type table/rows. Also translate the FK failure to 404 (see #18).

**14. HR loans are ACTIVE on creation with no approval; approve endpoint is a no-op and double-approvable**
- **Module:** HR / Loans
- **Endpoints:** `POST /api/v1/hr/loans/employee/{uid}`, `POST /api/v1/hr/loans/uid/{uid}/approve`
- **Repro:** Create a loan → status ACTIVE immediately, `approvedBy=null`. Approve twice → 200 both times, status/approvedBy unchanged.
- **Expected:** New loan PENDING; approval transitions to ACTIVE and records approvedAt/approvedBy; second approve → 409.
- **Actual:** Loan ACTIVE on create (bypasses segregation-of-duties); approve does nothing; double-approve returns 200.
- **Evidence:** create → 201 `status=ACTIVE approvedBy=null`; approve → 200 (still ACTIVE, null); re-approve → 200.
- **Fix hint:** Default new loans to PENDING in the create service; implement the approve transition (set status ACTIVE + approvedAt/approvedBy) with a state guard rejecting already-ACTIVE loans.

---

## MEDIUM

### Cross-module: missing @Positive / date-order validation surfaces as 500

**15. Negative/zero quantity on sales-order and quotation lines returns 500 instead of 400**
- **Module:** Sales Orders, Quotations
- **Endpoints:** `POST /api/v1/sales-orders/uid/{uid}/lines`, `POST /api/v1/quotations/uid/{uid}/lines`
- **Repro:** Add a line with `quantity:-1` or `quantity:0`.
- **Expected:** 400 "quantity: must be greater than 0" (the invoice-line endpoint returns exactly this for the same input).
- **Actual:** 500 on both endpoints — they lack the `@Positive` validation the invoice line has; bad qty reaches downstream.
- **Evidence:** SO line qty -1/0 → 500; quotation line qty -3/0 → 500; sales-invoices line same input → 400 "quantity: must be greater than 0".
- **Fix hint:** Add `@Positive` to the qty field on the SO and quotation add-line DTOs and ensure `@Valid` cascades into the line record (the invoice DTO is the reference implementation).

**16. Negative/zero AR receipt amount and negative receipt allocation amount return 500 instead of 400**
- **Module:** AR / Receipts
- **Endpoint:** `POST /api/v1/ar/receipts`
- **Repro:** `amount:-5000` or `amount:0`; or valid amount with an allocation `allocatedAmount:-500`.
- **Expected:** 400 "amount must be greater than 0" / "allocation amount must be > 0". (Over-allocation is already handled: 409 "Allocation exceeds outstanding".)
- **Actual:** 500. No service-layer guard; the negative amount hits DB CHECK `chk_ar_receipt_amount`.
- **Evidence:** amount -5000/0 → 500; `backend-local.log` `violates check constraint "chk_ar_receipt_amount"` at `ArReceiptServiceImpl.recordAndAllocate:170`; alloc -500 → 500 vs over-alloc 999999 → 409.
- **Fix hint:** Validate receipt `amount > 0` and each `allocatedAmount > 0` in the DTO/service before persistence.

**17. Quotation validUntil before quoteDate returns 500 instead of 400**
- **Module:** Quotations
- **Endpoint:** `POST /api/v1/quotations`
- **Repro:** `quoteDate:2026-06-18`, `validUntil:2026-01-01`.
- **Expected:** 400 "validUntil must be on/after quoteDate".
- **Actual:** 500. (Omitting validUntil correctly → 400 "must not be null".)
- **Fix hint:** Add a cross-field date-order check (class-level constraint or service guard) on the quotation create DTO.

**18. Supplier bill: negative unitCostAmount and dueDate-before-billDate return 500 instead of 400**
- **Module:** AP / Supplier Bills
- **Endpoint:** `POST /api/v1/ap/supplier-bills`
- **Repro:** Line `unitCostAmount:-100` (positive qty); or `billDate:2026-06-18, dueDate:2020-01-01`.
- **Expected:** 400 (unit cost ≥ 0; dueDate ≥ billDate), consistent with `billedQty` which correctly returns 400.
- **Actual:** 500 — both hit DB CHECK constraints (`chk_supplier_bill_amounts` V12:62, `chk_supplier_bill_dates` V12:68) unvalidated at the service layer.
- **Evidence:** unitCostAmount -100 → 500 vs billedQty -2 → 400; dueDate<billDate → 500.
- **Fix hint:** Mirror the existing `@Positive` billedQty validation onto `unitCostAmount`, and add a date-order guard for dueDate/billDate.

**19. Purchase return with negative/zero returnedQty returns 500 instead of 400**
- **Module:** Purchase Returns
- **Endpoint:** `POST /api/v1/purchase-returns`
- **Repro:** Return line `returnedQty:-5` or `:0`. (Over-return → 400; valid → 201.)
- **Expected:** 400 — return qty must be > 0.
- **Actual:** 500 — `@Positive` on returnedQty not enforced (the `@Valid` cascade to the nested line record does not fire).
- **Evidence:** returnedQty -5/0 → 500; control 9999 → 400 "exceeds returnable qty".
- **Fix hint:** Ensure `@Valid` cascades to the nested `LineRequest` so `@Positive` on returnedQty fires. Same non-cascading root cause as #15.

### HR — date-order / FK validation surfaces as 500

**20. Contract endDate before startDate returns 500 instead of 400**
- **Module:** HR / Contracts
- **Endpoint:** `POST /api/v1/hr/contracts/employee/{employeeUid}`
- **Repro:** `startDate:2025-12-31, endDate:2025-01-01`.
- **Expected:** 400 "endDate must be on/after startDate".
- **Actual:** 500.
- **Fix hint:** Cross-field date-order validation on the contract create DTO/service.

**21. Leave request with fromDate after toDate, or nonexistent leaveTypeId, returns 500**
- **Module:** HR / Leave
- **Endpoint:** `POST /api/v1/hr/leave-requests/employee/{employeeUid}`
- **Repro:** `fromDate:2025-03-31, toDate:2025-03-01`; or `leaveTypeId:999999`. (Negative days → 400.)
- **Expected:** 400 "fromDate must be before toDate" / 404 "leave type not found".
- **Actual:** 500 (FK leak for unknown leaveTypeId; unvalidated date order). Related to #13.
- **Fix hint:** Add date-order validation and resolve leaveTypeId with a not-found → 404. (Note all valid leaveTypeIds also 500 per #13.)

**22. Nonexistent departmentId/branchId on employee create returns 500 instead of 400/404**
- **Module:** HR / Employees
- **Endpoint:** `POST /api/v1/hr/employees`
- **Repro:** `departmentId:888888` or `branchId:777777`.
- **Expected:** 400/404 "department/branch not found".
- **Actual:** 500 (FK violation leaks).
- **Fix hint:** Look up department/branch before save and throw NotFoundException; or translate `DataIntegrityViolationException` (FK) globally (see cross-cutting #38).

**23. Nonexistent glAccountId returns 500 across loans and pay-components**
- **Module:** HR / Loans + Pay Components
- **Endpoints:** `POST /api/v1/hr/loans/employee/{uid}`, `POST /api/v1/hr/pay-components`
- **Repro:** `glAccountId:999999` on either.
- **Expected:** 400/404 "GL account not found".
- **Actual:** 500 (FK leak). Note: HR role can't GET /gl/accounts (403), so there's no in-role way to discover valid GL account ids.
- **Fix hint:** Resolve glAccountId before save → 404; consider a read-only GL-account picker endpoint for HR.

### HR — uniqueness / rate validation surfaces as 500

**24. Two employees can be created with identical nationalId and identical TIN (no uniqueness)**
- **Module:** HR / Employees
- **Endpoint:** `POST /api/v1/hr/employees`
- **Repro:** POST same `nationalId` twice; POST same `tin` twice.
- **Expected:** 409/400 — nationalId and TIN are government-unique.
- **Actual:** Both → 201.
- **Fix hint:** Add UNIQUE constraints (per company) on `nationalId` and `tin`, plus a pre-save uniqueness check returning 409.

**25. Loan installmentAmount may exceed principal (no consistency check)**
- **Module:** HR / Loans
- **Endpoint:** `POST /api/v1/hr/loans/employee/{uid}`
- **Repro:** `principalAmount:1000, installmentAmount:999999`.
- **Expected:** 400 — installment cannot exceed principal/outstanding.
- **Actual:** 201.
- **Fix hint:** Service-level guard `installmentAmount <= principalAmount`.

**26. Negative/absurd statutory rates and duplicate statutory rate return 500; negative ceilingAmount accepted**
- **Module:** HR / Statutory Rates
- **Endpoint:** `POST /api/v1/hr/statutory/rates`
- **Repro:** `employeeRate:-10` or `:999999` → 500; duplicate `rateType+effectiveFrom` → 500; `ceilingAmount:-5000` → 201.
- **Expected:** 400 (rate 0–100, ceiling ≥ 0); 409 on duplicate.
- **Actual:** Negative/huge rate → 500 (DB CHECK/overflow); duplicate → 500 (unique-constraint leak); negative ceiling stored (201).
- **Fix hint:** Add `@DecimalMin/@DecimalMax` (0–100) on rate fields and `@PositiveOrZero` on ceilingAmount; pre-check duplicate → 409; translate the unique-constraint violation globally.

**27. PAYE band set: negative/absurd band values and duplicate effectiveFrom all return 500**
- **Module:** HR / Statutory PAYE Bands
- **Endpoint:** `POST /api/v1/hr/statutory/paye-bands`
- **Repro:** Duplicate effectiveFrom → 500; `lowerBound:-100000/marginalRate:-5/cumulativeFixedTax:-999` → 500; `marginalRate:9999` → 500.
- **Expected:** 409 for duplicate; 400 for negative/absurd values.
- **Actual:** All 500 (a clean valid set → 201).
- **Fix hint:** Bean-validate band numeric ranges and pre-check duplicate effectiveFrom → 409; same global DataIntegrity translation.

### GL — 500 on edge inputs

**28. GL journal POST returns 500 on large in-range amounts (numeric overflow not validated)**
- **Module:** GL / Journals
- **Endpoint:** `POST /api/v1/gl/journals`
- **Repro:** Balanced 2-line journal with `debitAmount/creditAmount=1e15`. Boundary: 1e14 → 201; 1e15 and 1e30 → 500.
- **Expected:** 400/422 "amount exceeds maximum precision/scale".
- **Actual:** 500 — amount overflows the NUMERIC column unvalidated.
- **Fix hint:** Add `@Digits(integer=…, fraction=…)` matching the column precision/scale on amount fields.

**29. GL journal POST returns 500 when 'lines' is null or omitted (NPE before size check)**
- **Module:** GL / Journals
- **Endpoint:** `POST /api/v1/gl/journals`
- **Repro:** `lines:null`, or omit `lines`. (`lines:[]` correctly → 400 BR-GL-01.)
- **Expected:** 400 "lines must not be null / requires at least 2 lines".
- **Actual:** 500 — NPE before the BR-GL-01 size check (which only runs when lines is non-null).
- **Fix hint:** Add `@NotNull @NotEmpty @Valid` on the `lines` field so the null/absent case is caught by bean validation and routed to the same BR-GL-01 message.

### Reverse / FX

**30. Reverse endpoint ignores supplied reversalDate and reason**
- **Module:** GL
- **Endpoint:** `POST /api/v1/gl/journals/uid/{uid}/reverse`
- **Repro:** POST `{"reason":"...","reversalDate":"2026-03-16"}` on a journal originally posted 2026-03-15 (period 3).
- **Expected:** Honor reversalDate/reason, or reject unknown fields.
- **Actual:** Reversal posts on current system date (2026-06-18, period 6) with a fixed "Reversal of entry <uid>" description; supplied date and reason discarded — distorts period results and loses audit reason.
- **Fix hint:** Bind and use `reversalDate`/`reason` in the reverse service (default to today/derived only when absent); reject reversal into a different/closed period if not intended.

**31. FX rate accepted for identical from/to currency with rate ≠ 1**
- **Module:** FX
- **Endpoint:** `POST /api/v1/fx/rates`
- **Repro:** `fromCurrency:TZS, toCurrency:TZS, rate:2.5`.
- **Expected:** Reject (400) or force rate to 1.0 for same-currency.
- **Actual:** 200 — TZS→TZS=2.5 persisted active; would mis-value any base-currency lookup. (Re-posting the same row 500s on duplicate key — minor robustness issue on this upsert endpoint.)
- **Fix hint:** Validate `fromCurrency != toCurrency` (or enforce rate==1 when equal) in the FX rate create/upsert service; make the upsert idempotent rather than 500 on duplicate key.

---

## LOW

### IAM / Users

**32. Duplicate email accepted on user creation (username unique, email not)**
- **Endpoint:** `POST /api/v1/users` — Two users share `advtest1@erp.local`, both 201; duplicate username correctly → 409.
- **Expected:** 409 on duplicate email if email is a usable identifier, or a documented decision.
- **Fix hint:** Add a UNIQUE constraint + pre-check on `email` (CreateUserRequest already declares `@Email`, signaling it is an identifier). Schema V1__baseline.sql:160 has UNIQUE(username) only.

**33. Username accepts spaces and special characters (no format validation)**
- **Endpoint:** `POST /api/v1/users` — `"adv test user!@#"` accepted (201). Only blank/length validated.
- **Fix hint:** Add `@Pattern` (alphanumeric/dot/underscore) on CreateUserRequest.username.

### IAM / Users — validation (MEDIUM, grouped here for module continuity)

**34. displayName stored verbatim without sanitization (stored-XSS payload persisted)** *(severity: MEDIUM)*
- **Endpoint:** `POST /api/v1/users` — `<script>alert(1)</script>ADVTEST` stored and echoed verbatim.
- **Expected:** Reject/sanitize HTML/script in identity fields, or guarantee output-encoding everywhere.
- **Actual:** Raw payload persisted; stored-XSS risk depends on frontend encoding (Angular auto-escapes by default, mitigating impact).
- **Fix hint:** Sanitize/encode free-text identity fields at the API layer; verify all Angular consumers use interpolation (not `[innerHTML]`).

### Sales Orders / Quotations

**35. Sales order / quotation accept unknown currency code (no validation)**
- **Endpoint:** `POST /api/v1/sales-orders` — `currency:"XXX"` → 201.
- **Fix hint:** Validate currency against the currency master / ISO list in the create DTO/service.

**36. Not-found quotation (and sales-order) operations return 403 instead of 404**
- **Endpoint:** `GET/PUT /api/v1/quotations/uid/{uid}` (accept/reject) — unknown uid → 403; real uid → 200.
- **Note:** Inconsistency is module-wide — sales-orders also return 403 for unknown uids, while `/api/v1/customers` correctly returns 404. (The original report's claim that sales-orders returns 404 is inaccurate.)
- **Fix hint:** Run the existence check before the scope/permission guard in the affected controllers/services so unknown ids return 404 consistently with customers.

### HR

**37. Employee email format and gender enum not validated on create (inconsistent with next-of-kin)**
- **Endpoint:** `POST /api/v1/hr/employees` — `email:"@@notvalid@@"`, `gender:"BANANA"` stored verbatim (201); next-of-kin endpoint rejects the same email with 400.
- **Fix hint:** Add `@Email` to employee.email and an enum/`@Pattern` constraint to gender (next-of-kin DTO is the reference).

**38. Employee dateOfBirth accepts far-future dates and can be after hireDate**
- **Endpoint:** `POST /api/v1/hr/employees` — `dob:2200-01-01` accepted; born 2000 / hired 1990 accepted.
- **Fix hint:** Add `@Past` on dateOfBirth and a cross-field check `dateOfBirth < hireDate`.

**39. Enum validation error leaks internal Java class/package name**
- **Endpoint:** `POST /api/v1/hr/statutory/rates` — `basis:"XXXX"` → 400 "No enum constant com.erp.modules.hr.domain.enums.StatutoryBasis.XXXX" (information disclosure).
- **Fix hint:** Customize enum-deserialization / @ControllerAdvice handling to emit "invalid value for basis" without the FQCN.

**40. Param-binding validation runs before authorization on /hr/contracts list**
- **Endpoint:** `GET /api/v1/hr/contracts` — without employeeId → 400 "Missing required request parameter"; with employeeId → 403. Inconsistent ordering, minor endpoint-shape disclosure.
- **Fix hint:** Ensure the security filter/RBAC check runs before parameter binding for this endpoint.

### GL

**41. NULL/omitted postingDate not validated; leaks internal 'date null' message with 409**
- **Endpoint:** `POST /api/v1/gl/journals` — omitting postingDate → 409 "No OPEN fiscal period found for company 1 and date null...".
- **Expected:** 400 "postingDate must not be null".
- **Fix hint:** Add `@NotNull` on postingDate so it fails fast before the fiscal-period lookup.

**42. GL journal POST returns 500 on over-length string fields (description/sourceRef)**
- **Endpoint:** `POST /api/v1/gl/journals` — ~10000-char (and even ~262-char) description/sourceRef → 500 (varchar overflow).
- **Fix hint:** Add `@Size(max=…)` matching column length on description/sourceRef.

### Stock / Transfers & On-hand

**43. Stock transfer accepts a far-future transferDate (2099-12-31) with no validation**
- **Endpoint:** `POST /api/v1/stock-transfers` — accepted (201, DRAFT).
- **Fix hint:** Bound transferDate / bind to an open fiscal period in the create DTO/service.

**44. companyId query param silently ignored on transfer create/list (no cross-tenant leak)**
- **Endpoint:** `POST /api/v1/stock-transfers?companyId=2` with a company-1 token → record created under company 1. Token governs; no data exposure — consistency/UX nit only.
- **Fix hint:** Reject (400/403) when the supplied companyId conflicts with the authenticated company instead of silently overriding.

**45. On-hand by-product/by-location DTOs return null for resolved product/location fields**
- **Endpoint:** `GET /api/v1/stock/on-hand/by-product/uid/{productUid}`, `GET /api/v1/stock/on-hand/by-location` — productUid/Code/Name (and location fields on by-product) null; numeric ids present. Valuation report populates them correctly.
- **Fix hint:** Populate the resolved DTO fields in the on-hand mapper (mirror the valuation-report projection).

### Reporting / BI

**46. Financial reports accept inverted date range (fromDate > toDate); BI dashboard silently ignores it**
- **Endpoint:** `GET /api/v1/reports/income-statement`, `GET /api/v1/bi/dashboard` — income-statement returns 200 with inverted periodLabel "2026-06-18 - 2026-01-01" and a garbage comparative window; bi/dashboard silently falls back to the default period.
- **Fix hint:** Add a `fromDate <= toDate` guard in `ReportingServiceImpl` (L50-81, before building the statement and the ComparativeWindowResolver call) → 400.

### Auth / global request handling

**47. Unsupported Content-Type on POST returns 500 instead of 415 (unauthenticated-reachable on /auth/login)**
- **Endpoint:** `POST /api/v1/auth/login` (also `POST /api/v1/customers`) — non-JSON Content-Type (form-urlencoded, text/plain, xml, or none) → 500 with the generic envelope.
- **Expected:** 415 Unsupported Media Type (or 400) with the standard {data,errors,meta} envelope. (Malformed JSON with correct Content-Type → clean 400, confirming this is an unhandled-exception gap.)
- **Fix hint:** Add a `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` to the global @ControllerAdvice returning 415 in the standard envelope. Part of the cross-cutting unhandled-exception theme.