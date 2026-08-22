I verified the section line-by-line against the repo. Here is the corrected version.

---

## Approvals

Approvals are the app's only write surface, and the reason the owner will open it more than once a week. Everything in this section is grounded in the shipped engine; anything not shipped is marked **PROPOSED** and carries a `[code-only]` or `[schema]` tag. Claims I could not settle from the code are marked **(UNVERIFIED)**.

### A.0 What the engine actually is today (read this before designing a screen)

| Fact | Evidence |
|---|---|
| Seven REST endpoints, all under `/api/v1/approvals/requests` (`/inbox`, `/uid/{uid}`, list, approve, reject, recall, cancel) | `backend/src/main/java/com/erp/api/ApprovalRequestController.java:29-91` |
| **`documentType` is an opaque `VARCHAR(60)`**, not an enum — deliberately (ADR-0022 D-1/D-12) | `backend/src/main/resources/db/migration/V18__approvals_engine.sql:76`; `.../modules/approvals/domain/entity/ApprovalRequest.java:44-46` |
| **Exactly two document types are wired end-to-end**: `PURCHASE_ORDER` and `SALES_ORDER` | 3 call sites of `new SubmitForApprovalRequest(` — `.../purchases/service/PoApprovalGate.java:195`, `.../sales/service/SalesApprovalGate.java:98`, `.../sales/service/SalesOrderServiceImpl.java:390` |
| **`SALES_ORDER` approvals are OFF by default per company.** `sales_settings.so_approval_enabled` defaults `false`; the gate returns "no approval needed" when the row is missing or the flag is off, *before* the threshold is even read | `V79__sales_settings.sql:19-20`; `SalesSettings.java:31-37`; `SalesApprovalGate.java:57-62` |
| The mobile app **can never create** an approval request — `submitForApproval` is an in-process interface, not REST | `ApprovalRequestController.java:25-26` javadoc; `.../approvals/service/ApprovalEngine.java` |
| No match against any policy → **auto-approve**, `autoApproved = true`, zero steps, terminal `APPROVED` at submit | `.../approvals/service/ApprovalEngineImpl.java:201-227`; band query `ApprovalPolicyRepository.findMatchCandidates` |
| Six other approval surfaces bypass the engine entirely and **will not appear in the inbox** | §A.2.3 below |

Two documentation defects to be aware of while reading the module, neither functional: the migration file `V18__approvals_engine.sql` opens with the header comment `-- V20 — Approvals Workflow Engine`, and `ApprovalRequestController.java:23` says "Perms seeded in V20__approvals_engine.sql" — no such file exists. The approvals permission codes are seeded in `R__seed_permissions.sql:23-27`.

**The single most dangerous fact for a mobile client:** `POST …/approve` and `POST …/reject` are the *same code path*. Both call `decisionService.decide(uid, request)` (`ApprovalRequestController.java:68` and `:76`); the verb comes **only** from `DecideRequest.action` in the body (`.../domain/dto/DecideRequest.java:104-107`, `@NotNull DecisionAction action`). `POST /uid/{uid}/approve` with `{"action":"REJECT"}` **rejects**. The Dart client must construct path and action from one sealed value:

```dart
enum Decision {
  approve('approve', 'APPROVE'),
  reject('reject', 'REJECT');
  // path segment and body action can never be set independently
}
```
and a widget test must assert that the two agree for every enum member. This is exactly the `route-guard ↔ endpoint permission parity` class of trap, one layer down.

---

### A.1 The approvals inbox screen

#### A.1.1 The endpoint, and what it will and will not do

```
GET /api/v1/approvals/requests/inbox?page=0&size=20
@PreAuthorize("@perm.has('APPROVALS.DECIDE')")     ApprovalRequestController.java:39-44
→ ApiResponse<List<ApprovalRequestDto>> + PageMeta
```

| Behaviour | Reality | Evidence |
|---|---|---|
| **Cross-branch** | ✅ Yes — the query `LEFT JOIN`s `UserBranch` on `(callerId, request.branchId)` and requires `ub.userId IS NOT NULL`, so it returns every branch the approver holds a `user_branch` row for. `X-Branch-Uid` does **not** narrow it | `.../repository/ApprovalRequestStepRepository.java:72-91`; branch exclusion IT-covered at `ApprovalsEngineIT.java:354-381` |
| **Cross-company** | ❌ No. One company: `principal.companyId()` | `ApprovalDecisionServiceImpl.java:279-289` |
| **Excludes own submissions** | ✅ `r.submittedBy <> :callerId` (SoD at query level) | `ApprovalRequestStepRepository.java:81` |
| **Sorting** | ❌ **Silently ignored.** `findAllById(requestIds)` takes no `Sort`; `?sort=amount,desc` is a no-op | `ApprovalDecisionServiceImpl.java:295-299` |
| **Filtering** | ❌ None. The controller takes only `Pageable` | `ApprovalRequestController.java:41` |
| **Pagination** | ⚠️ Cosmetic. Loads *all* matching rows, maps every one through `engine::toDto` (N+1 per row), then `subList`s in memory | `ApprovalDecisionServiceImpl.java:288-303`; N+1 acknowledged at `ApprovalEngineImpl.java:276-278` |
| **Empty for a root account with no role grants** | ⚠️ Yes — `resolveRoleCodes` reads `user_role`; empty → `Page.empty` **before** any root consideration. There is no root bypass in `inbox()` at all | `ApprovalDecisionServiceImpl.java:284-287`; `StepApproverResolver.java:89-96` |

**⚠️ A real hole, found while verifying the branch claim.** The inbox join and `StepApproverResolver.canDecide` both check branch access with a predicate that ignores revocation:

- inbox: `LEFT JOIN UserBranch ub ON ub.userId = :callerId AND ub.branch.id = r.branchId` — no `revokedAt`/`active` predicate (`ApprovalRequestStepRepository.java:75-76`, `:82`)
- decide: `userBranches.findByUserIdAndBranchId(userId, branchId)` (`StepApproverResolver.java:63-65`), whose own repository javadoc states it "filters on neither `revokedAt` nor `active`" (`UserBranchRepository.java:15-29`)

Meanwhile the JWT filter refuses an `X-Branch-Uid` switch unless the assignment is live: `findByUserIdAndBranchIdAndRevokedAtIsNullAndActiveTrue` (`JwtRequestContextFilter.java:229-233`). So a user whose branch assignment was revoked **cannot switch into that branch on the web, but can still see and decide that branch's approvals**. Not caused by the mobile app, but the mobile app is the first product that makes it a headline feature. Add it to the punch list (§A.8, P13).

**Consequence for the owner persona, stated plainly:** routing is by *role code frozen onto the policy step* (`ApprovalPolicyStepDto.approverRoleCode`, snapshotted at submit — `ApprovalEngineImpl.java:177-182`). `R__seed_permissions.sql:309-321` seeds twelve role bundles and **none of them is `OWNER`, `GENERAL_MANAGER` or `CEO`**. If the GM's account is root-with-no-roles, his inbox is empty by construction and the app looks broken on day one. This is a prerequisite, not a nice-to-have — see §A.8.

#### A.1.2 Card anatomy

`ApprovalRequestDto` (`.../domain/dto/ApprovalRequestDto.java:20-45`) already carries branch, submitter name and a human summary as **server-side read-time enrichments** (`ApprovalEngineImpl.toDto:233-265`), so the card needs **no second fetch**. That is the single best thing about this module.

```
┌────────────────────────────────────────────────────┐
│ Purchase order                          waiting 2d │   documentType → label; age computed client-side
│                                                    │
│ TZS 42,500,000                                     │   amount + currency, never abbreviated (see l10n)
│                                                    │
│ PO-DAR-0912 — Shenzhen Electro Import Co.          │   summary  (the ONLY human sentence the API gives)
│ Dar es Salaam HQ · from Rehema Salum               │   branchName · submittedByName
│ Step 2 of 3 · Finance Director                     │   DERIVED from steps[] — see warning below
└────────────────────────────────────────────────────┘
```

Field-by-field provenance — every field below exists on the shipped DTO; nothing here is invented:

| Card element | Source | Note |
|---|---|---|
| Type label | `documentType` (raw code) | **No label comes from the API.** Port `web/src/app/features/admin/approvals/document-type.util.ts:21-38` into Dart. Its header comment at `:9-13` ("only `PURCHASE_ORDER` is actually wired") is **stale** — `SALES_ORDER` ships too. Unmapped codes degrade to the raw code with no link, never to a wrong screen — keep that rule. |
| Amount / currency | `amount` (`BigDecimal` → JSON **number**), `currency` (`String`, from `CurrencyCode.value()` — `ApprovalEngineImpl.java:259`) | Parse as `num`/Decimal, never `int` — `wire-serialization-number-vs-string` |
| Reference + counterparty | `summary` (`VARCHAR(500)`, `V18:85`) | Built at `PoApprovalGate.java:167-169` (`"PO {orderNumber} — {supplierName}"`) and `SalesApprovalGate.java:94-96` (`"SO {orderNumber} — {customerName}"`, falling back to `"SO {orderNumber}"` when the customer lookup misses). The ratification variant reads `"RATIFY goods already received (no LPO) — {orderNumber} — {supplierName}"` (`PoApprovalGate.java:185-188`) — surface that prefix as a distinct **amber** card style, because the goods are already in stock and a rejection is a dispute to chase, not a purchase to prevent. |
| Branch | `branchName` / `branchCode` | Already enriched server-side (`toDto:252-257`); null when the branch row is missing |
| Submitter | `submittedByName` | Already enriched; null-safe |
| Age | `submittedAt` (`Instant`) | Compute client-side against the server clock (skew rule from the NFR section). Amber > 1 day, red > 3 days. |
| Step chip | **`steps[]`, NOT `currentStepSequence`** | ⚠️ **`currentStepSequence` is always `null`.** It is declared (`ApprovalRequest.java:63-66`, DTO `:33`, column `V18:81`) but a repo-wide grep for `setCurrentStepSequence` returns **zero writers**. Derive the open step as *lowest-`sequence` step whose `status == PENDING`* — the same rule the server uses (`stepRepo.findPendingStepsOrdered(...)`, `ApprovalDecisionServiceImpl.java:87-91`). |
| `autoApproved` badge | `autoApproved` | Never shown in the inbox (auto-approved requests are terminal), but it belongs on the history screen — see the currency defect in §A.8. |

**Deliberately not on the card:** `id`, `companyId`, `branchId`, `submittedBy`, `resolvedBy`, `sourcePolicyId` — internal numerics that cost bytes and mean nothing to an owner (per the data-cost budget: 24 fields → 9).

#### A.1.3 Sorting, filtering, badges — all client-side in v1

Because the server ignores `sort` and offers no filters, and because it materialises the whole result set anyway:

- **Fetch the whole inbox once** (`size=200`), sort and filter **in the client**. This is honest about what the server does and avoids a paging illusion. Cap the render at 50 rows with "show more".
- **Default sort: oldest first.** The owner's stated complaint is "an approval that quietly sits somewhere I never look" — age, not amount, is the thing that hurts. Secondary sort: amount descending.
- **Filter chips (client-side):** *All* · *Purchase orders* · *Sales orders* · one chip per branch present in the result · *Over TZS 10M*. Chips are generated from the data, so they can never offer a filter that returns nothing.
- **Badge count:** there is no count endpoint. `GET /inbox?size=1` returns `meta.totalElements` via `PageMeta.from(page)` (`ApprovalRequestController.java:43`) — but it costs the server the *full* load anyway, so there is no saving. Use the count from the full fetch; refresh on push, on tab open, and on app resume.
  - `GET /api/v1/notifications/unread-count` (`NotificationController.java:58-63`, `NOTIFICATION.VIEW`) counts *all* in-app notifications, not approvals. Do not use it for the approvals badge.

**PROPOSED — N-APR-3 `[code-only]`, do this before v1 ships to more than one company:**
```
GET /api/v1/approvals/requests/inbox/count → { "pending": 7, "oldestSubmittedAt": "…" }
@perm.has('APPROVALS.DECIDE')
```
A `SELECT count(*)` over the existing `findInboxRequestIds` query. Turns a badge refresh from an N+1 full load into one statement — this is what makes push-driven badge updates affordable.

#### A.1.4 Cross-company: the structural gap

`ApprovalDecisionServiceImpl.inbox` is pinned to `principal.companyId()` (`:279-289`), and the only scope override in the product is `X-Branch-Uid` — which moves the active company only as a side effect of resolving a branch (`JwtRequestContextFilter.java:238-240`, `branch.getCompany().getId()`). **There is no `X-Company-Uid`** — a repo-wide grep across `backend/src/main/java` returns nothing.

So a Group GM over 4 companies must, today, header-switch into a branch of each company and poll four times — with the browser-equivalent cost of four full inbox loads. That is unacceptable as the app's headline feature.

**PROPOSED — N-APR-1 (= N2 in the reports workstream) `[code-only]`:**
```
GET /api/v1/mobile/exec/approvals/inbox?organisationUid={uid}&page&size
@PreAuthorize("@perm.has('APPROVALS.DECIDE')")
→ ApiResponse<List<ApprovalRequestDto>>  (+ companyUid, companyName added to the record)
```
Implementation is confined to `inbox()`: loop the caller's accessible companies (`GET /api/v1/companies/accessible?organisationUid=…` — `CompanyController.java:50-54`, `@PreAuthorize("isAuthenticated()")`, and note `organisationUid` is a **required** request param), keeping the existing per-company `scopeGuard.assertCanActIn` and `StepApproverResolver.resolveRoleCodes(userId, companyId)` untouched — the role/SoD filter must stay per-company or the isolation model breaks. Fix the in-memory `subList` while you are in there.

**Approve/reject need no change** — they are already uid-addressed and re-derive the company from the loaded request (`decideWithRetry:78-80`), which is the correct "scope from the loaded entity, never a caller param" pattern.

**Interim, before N-APR-1 lands:** the app shows a company switcher and one company's inbox at a time, with the switcher labelled honestly (`Showing Tembo Trading only — 2 other companies not checked`). A silently single-company inbox on an app sold as "group-wide" is the same defect class as the BI dashboard stamping a branch label on company-wide figures.

---

### A.2 The approval detail screen, per document type

#### A.2.1 What the approvals API gives you, and what it does not

`GET /api/v1/approvals/requests/uid/{uid}` — `@perm.scoped(#uid,'approvalrequest','APPROVALS.REQUEST.VIEW')` (`ApprovalRequestController.java:46-50`; the `approvalrequest` scope key resolves at `ScopeGuard.java:568`). Note the **permission is different from the inbox's**: `APPROVALS.REQUEST.VIEW`, not `APPROVALS.DECIDE`. Both are seeded to `SALES_MANAGER`, `BRANCH_MANAGER`, `PROCUREMENT_MANAGER` and `FINANCE_DIRECTOR` (`R__seed_permissions.sql:633-634, 697-698, 780-781, 884-887`), so in practice they travel together — but a custom role that holds only `APPROVALS.DECIDE` gets an inbox it cannot open. The app must check both codes from `/auth/me.permissions` and, if `APPROVALS.REQUEST.VIEW` is missing, render the inbox from the list payload with a banner rather than a 403 on tap.

The detail response is the same `ApprovalRequestDto`, now with `steps[]` fully populated:

- `ApprovalRequestStepDto` (`.../dto/ApprovalRequestStepDto.java:60-70`): `id`, `uid`, `sequence`, `approverRoleCode`, `approverRoleName` (enriched), `status` (`PENDING|APPROVED|REJECTED|SKIPPED`), `resolvedBy`, `resolvedAt`, `decisions[]`
- `ApprovalDecisionDto` (`.../dto/ApprovalDecisionDto.java:85-94`): `id`, `uid`, `approvalRequestStepId`, `action`, `decidedBy`, `decidedByName` (enriched), `decidedAt`, `comment`

**What the approvals module cannot tell you, at all:**

| Missing | Consequence |
|---|---|
| **Why this needs *your* approval** — the DTO carries `sourcePolicyId`/`sourcePolicyUid` but not the matched band | A second call to the policy read gated `APPROVALS.POLICY.VIEW` — which `SALES_MANAGER` (`:633-634`) and `PROCUREMENT_MANAGER` (`:780-781`) **do not hold** (`BRANCH_MANAGER` does, `:699`; `FINANCE_DIRECTOR` does, `:886`). For two of the four approver roles the question is unanswerable in-app. |
| **Any document detail** — line items, supplier, dates | Only `documentType` + `documentUid`, both opaque scalars with no FK (`ApprovalRequest.java:44-50`). Lines need the owning module's permission (below). |
| **Attachments** | Zero attachment surface in `modules/approvals/`. The documents module (`DocumentController.java:45-111`, `DOCUMENT.RENDER` / `DOCUMENT.VIEW`) has no link from an approval request. |
| **Who else holds this role** | No query exists. |

**An approver holding only `APPROVALS.DECIDE` approves blind, off a 500-character `summary`.** That is the central design problem of this screen.

#### A.2.2 Per-type decision packs — what the approver must see, and where it comes from

**`PURCHASE_ORDER`** — the dominant type, and the one that moves money out of the group.

| Must see | Source | Permission |
|---|---|---|
| Supplier, order number, order date, currency, order total | `GET /api/v1/purchase-orders/uid/{uid}` (`PurchaseOrderController.java:66-71`) | `PURCHASE.ORDER.VIEW` **or** `PURCHASE.RECEIVE` |
| Line items: product, qty, unit cost, line total | `GET /api/v1/purchase-orders/uid/{uid}/lines` (`:147-150`) | `PURCHASE.ORDER.VIEW` or `PURCHASE.RECEIVE` |
| **Is this a ratification?** (goods already received, no LPO) | `summary` prefix `"RATIFY goods already received (no LPO)"` (`PoApprovalGate.java:185-188`); PO `origin == DIRECT_RECEIPT` (`PoApprovalGate.java:154-155`) | — |
| Supplier's current AP exposure | `GET /api/v1/ap/statement/balance?companyId=&supplierId=` (`ApStatementController.java:23`, `:39-41`) | `AP.VIEW` |
| Chain: who approved before me, who is after me | `steps[]` on the request DTO | already held |

**`SALES_ORDER`** — gated first by `sales_settings.so_approval_enabled` (default **false**) and then by `so_approval_threshold_amount` (`SalesApprovalGate.java:57-72`); with the flag off, or below the threshold, no request is raised at all.

| Must see | Source | Permission |
|---|---|---|
| Customer, order number, gross total, currency | `GET /api/v1/sales-orders/uid/{uid}` (`SalesOrderController.java:52-55`) | `SALES.ORDER.VIEW` |
| Lines: product, qty, unit price, **discount** | `GET /api/v1/sales-orders/uid/{uid}/lines` (`:70-73`) | `SALES.ORDER.VIEW` |
| **Customer's current AR balance vs credit limit** — the question an owner actually asks about a big order | `GET /api/v1/ar/balance?companyId=&customerId=` (`ArStatementController.java:24`, `:84-86`) + `creditLimit` (a `MoneyDto`, not a scalar) on `CustomerDto` (`.../parties/domain/dto/CustomerDto.java:34`) | `AR.VIEW` + `CUSTOMER.VIEW` |
| Customer ageing (are they already 90 days late?) | `GET /api/v1/ar/statement?companyId=&customerId=&asAt=` (`ArStatementController.java:39-41`) | `AR.STATEMENT.VIEW` — **`BRANCH_MANAGER` does not hold this** (holds only `AR.VIEW`, `:695`) |

**This is the permission trap, and it is worse than "independent codes".** Verified directly against `R__seed_permissions.sql`:

- **`FINANCE_DIRECTOR` holds neither `PURCHASE.ORDER.VIEW` nor `SALES.ORDER.VIEW`** (its 83-grant block runs `:812-895`; neither code appears). The Finance Director is the role most likely to sit on the *final* step of a money-out chain — and today he literally cannot open the purchase order he is approving. He does hold `AP.VIEW`, `AR.VIEW`, `AR.STATEMENT.VIEW`, `CUSTOMER.VIEW`, `SUPPLIER.VIEW`.
- **`PROCUREMENT_MANAGER` holds `PURCHASE.ORDER.VIEW` (`:757`) but not `AP.VIEW`** — so no supplier exposure figure.
- **`BRANCH_MANAGER` holds `PURCHASE.ORDER.VIEW` (`:690`), `SALES.ORDER.VIEW` (`:659`), `AR.VIEW` and `AP.VIEW` — but not `AR.STATEMENT.VIEW`.**

Build and test this screen as a non-root `FINANCE_DIRECTOR` and a non-root `BRANCH_MANAGER`, never as root — root's `permissions` array is empty by design (`AuthServiceImpl.java:167-171`) and masks every gap.

**PROPOSED — N-APR-2 `[code-only]`, the highest-value new endpoint in this section:**
```
GET /api/v1/mobile/approvals/uid/{uid}/context
@PreAuthorize("@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')")
```
One call, one permission, server-composed per `documentType`:
```java
public record ApprovalContextDto(
    ApprovalRequestDto request,          // reuse verbatim
    String  documentTypeLabel,           // server-owned label — stops the client map drifting
    String  counterpartyName,            // supplier or customer
    String  counterpartyRef,             // supplier/customer code
    BigDecimal counterpartyOutstanding,  // AP balance or AR balance
    BigDecimal counterpartyCreditLimit,  // SO only; null for PO
    int     lineCount,
    List<ApprovalLineDto> topLines,      // LIMIT 5, biggest by value, + "and N more"
    String  thresholdReason,             // "Above TZS 20,000,000 (Procurement Manager band)"
    String  policyName,
    boolean ratification,                // PO origin == DIRECT_RECEIPT
    Instant generatedAt) {}
```
Given the FINANCE_DIRECTOR finding above, this is no longer merely "nice" — without it, either the pack endpoint composes the data server-side under `APPROVALS.DECIDE`, or the `FINANCE_DIRECTOR` bundle grows two new grants (a reviewed `R__` edit, owner approval required). The endpoint is the smaller change and the one that does not widen anybody's web access.

Composition must go through **`..domain.dto..` only** — no cross-module entity or service imports, or `ModuleBoundaryTest` fails the build (CLAUDE.md invariant 1). In practice that means a small read-facade per consuming module exposing a DTO, or a query service in the mobile package that calls the existing public service interfaces' DTO methods.

`thresholdReason` is the item worth arguing for. It answers "why is this on my phone", it removes the `APPROVALS.POLICY.VIEW` dependency two of the four approver roles lack, and it is derivable server-side from `sourcePolicyId` + the matched band (`ApprovalPolicy.java:54-63`, `ApprovalPolicyMatcher.match:40-63`) with no extra permission check.

#### A.2.3 Approval surfaces that are NOT in the engine

These have their own permission, their own state machine, and **will never appear in the approvals inbox**. Paths verified against each controller's `@RequestMapping`:

| Surface | Endpoint | Permission | Body |
|---|---|---|---|
| Purchase order (legacy direct path) | `POST /api/v1/purchase-orders/uid/{uid}/approve` \| `/reject` (`PurchaseOrderController.java:211-224`) | `PURCHASE.ORDER.APPROVE` | `ApprovePoRequest` — `companyUid` `@NotBlank`, `reason` (unvalidated despite the javadoc calling it mandatory on reject) |
| Purchase requisition | `POST /api/v1/purchase-requisitions/uid/{uid}/approve` \| `/reject` (`PurchaseRequisitionController.java:68-81`) | `PURCHASE.REQUISITION.APPROVE` | approve: none; **reject takes `?reason=` as a query param, not a body** (`:79`) |
| Leave request | `POST /api/v1/hr/leave-requests/uid/{uid}/decide` (`HrLeaveController.java:27`, `:60-64`) | `HR.LEAVE.APPROVE` | `DecideLeaveRequest` |
| Payroll run | `POST /api/v1/hr/payroll-runs/uid/{uid}/approve` (`HrPayrollController.java:32`, `:86-89`) | `HR.PAYROLL.APPROVE` | none |
| Employee loan | `POST /api/v1/hr/loans/uid/{uid}/approve` (`HrLoanController.java:26`, `:59-62`) | `HR.LOAN.MANAGE` | none |
| Budget version | `POST /api/v1/budget-versions/uid/{uid}/approve` \| `/reject` (`BudgetVersionController.java:32`, `:66-79`) | `BUDGETING.BUDGET.APPROVE` | `ApproveBudgetVersionRequest` / `RejectBudgetVersionRequest` |

And two surfaces the owner will *ask about* that are **not approvals at all** — be precise with him:

- **Discount over the cashier's ceiling.** `DiscountApprovalAction.APPROVE` (`.../sales/domain/enums/DiscountApprovalAction.java:19-25`) rejects the line unless a manager authorised it *synchronously at the till* via `POST /api/v1/auth/verify-authority` holding `SALES.DISCOUNT.OVERRIDE` (`DiscountAuthorisationGuard.java:80`, `:189` — refusal code `DISCOUNT_APPROVAL_REQUIRED`). It never becomes a request, never reaches an inbox, and cannot be approved from a phone — by design: the customer is standing at the counter. The mode is `OFF` by default per company (`V95__sales_discount_policy.sql:32`).
- **Credit sale over limit.** `SalesInvoiceServiceImpl.java:379-396` is a **hard inline block** requiring `SALES.CREDIT.OVERRIDE` on the *acting* user. There is no request, no queue, no asynchronous approval. If the owner wants "the customer went over their limit, ask me" on his phone, that is new work: a new `documentType` (`CREDIT_OVERRIDE`) submitted through `ApprovalEngine.submitForApproval`, which the opaque `document_type` column already permits with **no migration**.
- **Payment batches and journals have no approval surface anywhere.** `PAYMENT_RUN` and `AP_BILL` appear in the web util's map (`document-type.util.ts:34-35`) as forward-mapping only; nothing produces them.

**Design consequence:** a single "Approvals" tab is a *lie* unless the app either (a) aggregates the six non-engine surfaces client-side — six more permission codes, six more list endpoints, six more decide shapes, six independent failure modes — or (b) the backend routes them through the engine. **Recommendation: (b), incrementally, and until then the tab is honestly scoped.** v1 ships engine approvals only (POs and, where the company has enabled it, SOs) and the tab header reads `Purchase & sales approvals`. Leave, payroll and budget approvals stay on the web. Do not build six bespoke client integrations for an audience of nine people.

---

### A.3 The decide flow

#### A.3.1 The gesture

**No swipe-to-approve.** A swipe on a phone in a pocket is how a TZS 42.5M PO gets released by accident, and it makes a 48 dp touch target impossible. Tap → detail → fixed bottom bar:

- **Approve** — primary, full width
- **Send back** — secondary, outline. *Not* labelled "Reject": `handleReject` (`ApprovalDecisionServiceImpl.java:164-182`) bulk-SKIPs every remaining step via a single `UPDATE` (`ApprovalRequestStepRepository.updatePendingToSkipped:56-63`) and resolves the whole request `REJECTED` — the chain dies. The confirm sheet is titled `Reject — this cancels the request`.

Approve is **two deliberate actions**: tap → confirm sheet restating amount, counterparty, and what happens next (`This releases the order for Finance to pay` / `This is the final approval`, derived from whether a `PENDING` step remains after this one — the same test the server makes at `handleApprove:152-155`) → then the auth gesture.

**Never render an Approve button over cached facts.** If the detail payload is older than 15 minutes, force a refresh before the buttons appear. Offline, the button is **absent with an explanation**, not present-and-disabled (§A.3.6).

#### A.3.2 Step-up re-authentication — and why `verify-authority` is the wrong primitive

`POST /api/v1/auth/verify-authority` (`StepUpController.java:41-46`, `@PreAuthorize("isAuthenticated()")`) **cannot serve this flow.** `StepUpAuthServiceImpl.java:185-190` refuses self-approval unconditionally ("NOBODY MAY APPROVE THEMSELVES") — on the exec app the approver *is* the authenticated caller, so it returns `authorised: false` with refusal reason `SELF_APPROVAL` every single time. Relaxing that would gut the till control it exists for. It is a *supervisor-at-someone-else's-terminal* primitive and mints no token material by design (`AuthorityVerificationDto` javadoc: "Deliberately carries NO token material of any kind").

What this app needs is **re-authentication of the same user** — a different question.

**Thresholds (client policy, enforced server-side once N-APR-4 lands):**

| Condition | Gate |
|---|---|
| Reject / send back, any amount | Confirm sheet + mandatory reason. No biometric. |
| Approve, amount < company step-up threshold (default **TZS 5,000,000**, configurable per company) | Confirm sheet + **biometric/device-credential unlock** (local only — proves presence on this device, proves nothing to the server) |
| Approve, amount ≥ threshold | Confirm sheet + biometric + **server-verified re-auth ticket** |
| Approve, **final step** of a chain (no further `PENDING` step) | Always the ticket path, regardless of amount — this is the release |
| Any approve after the app has been backgrounded > 2 min | Re-unlock first |

**PROPOSED — N-APR-4 `[code-only]`, no schema:**
```
POST /api/v1/auth/reauth        body { password } | { totp }     @PreAuthorize("isAuthenticated()")
→ { ticket, expiresAt }          single-use, ~3 min, in-memory
```
The ticket is **bound to `{userId, deviceId, resourceUid, amount, currency}`**. Binding to resource *and* amount is what stops a ticket minted for a TZS 40,000 stationery PO being replayed against a TZS 400,000,000 payment. Model the in-memory store on `StepUpAuthServiceImpl`'s throttle map (`:105`, a `ConcurrentHashMap<Long, Throttle>`), which is deliberately schema-free.

Enforcement: `ApprovalRequestController.approve`/`reject` (`:64-77`) require an `X-Approval-Ticket` header **only when the session carries an `X-Device-Id`**. Neither header exists in the backend today (grep across `backend/src/main/java` returns nothing for either), and neither the web client nor OrbixPOS sends one, so no parity break is introduced.

> A device-local biometric is **not evidence to a server**. It gates the refresh token at rest and proves someone was present at this handset. Only the ticket (or, in v2, a device-bound P-256 signature over `{nonce, approvalUid, amount, currency, timestamp}`) makes the biometric mean anything server-side. Never let the biometric alone stand between "open app" and "release 42 million shillings".

#### A.3.3 Reject with a reason — mandatory in the client

`DecideRequest.comment` is optional and carries **no `@Size`** (`DecideRequest.java:104-107`), while the DB column is `VARCHAR(1000)` (`V18__approvals_engine.sql:158`). So:

- The app **requires** a reason on reject (min 10 chars) and **caps input at 1000 chars client-side** — an unbounded comment is a driver-level failure, not a validation error, and would surface as the generic "something went wrong".
- On approve, the comment is optional and free.
- Send `{"action":"REJECT","comment":"…"}` to `POST …/uid/{uid}/reject`, never to `/approve` (see the landmine in §A.0).

A rejection with no reason creates a phone call. The engine will accept one; the app will not offer one.

#### A.3.4 Conflict handling — the exact strings and the exact UI

All conflicts are `ConflictException` → **HTTP 409** (`GlobalExceptionHandler.java:180-184`), body `ApiResponse.errors` as a `List<String>` (`.../platform/common/api/ApiResponse.java:15`). Dart must accept a bare `String` in `errors[]` — the `pos-generic-400-masks-real-error` fix at `pos_app/lib/core/api/api_exception.dart:149-172` is the code to lift.

| Server string (verbatim, `ApprovalDecisionServiceImpl`) | Line | UI |
|---|---|---|
| `"This request is already APPROVED."` / `REJECTED` / `RECALLED` / `CANCELLED` | `:82-84` | **Neutral** sheet titled `Already decided` — not red. Nothing went wrong; someone else acted. Show it verbatim, refresh the inbox, pop back. |
| `"No open step found on this request."` | `:88-90` | Same neutral sheet; refresh. |
| `"You are not authorised to decide this step: you must hold role 'FINANCE_DIRECTOR' and not be the submitter (SoD)"` | `:94-98` | ⚠️ **An authorisation failure that arrives as 409, not 403**, and it **leaks the role code** — borderline against the error-hygiene rule. Do **not** show it verbatim. Render `You can't decide this step. It's waiting for a different approver.` and log the raw string. Worth a backend follow-up to split into a 403 with a clean message (P11). |
| `"Step 2 has already been decided by a concurrent approver."` | `:135-143` | Optimistic-lock loss after **one automatic server-side retry** (`@Version` on `approval_requests`, `V18:90`; retry at `:142`). Neutral `Already decided` + refresh. **The client must not retry** — the server already did. |
| `"Cannot recall a terminal request: APPROVED"` / `"Only the submitter may recall a request (use cancel for admin override)"` | `:192`, `:204` | Recall is out of scope for v1 (the exec is never the submitter). |

**Note the recall trap for later:** the endpoint is gated `APPROVALS.REQUEST.VIEW` (`ApprovalRequestController.java:80-84`) but the service enforces submitter-only (`:198-205`). Since `APPROVALS.REQUEST.VIEW` is seeded only to the four manager roles, a `SALESPERSON` or `PROCUREMENT_OFFICER` who actually submitted a document **cannot recall it** — 403 at the gate. Don't build recall into the exec app; it belongs on the submitter's screen, and it's broken there.

#### A.3.5 Idempotency — the top hardening item

**There is none.** No `Idempotency-Key` header on decide, no dedupe key over `(requestUid, userId, stepId)`. `decideWithRetry` resolves the open step **at execution time**:

```java
List<ApprovalRequestStep> pendingSteps = stepRepo.findPendingStepsOrdered(request.getId());
if (pendingSteps.isEmpty()) { throw new ConflictException("No open step found on this request."); }
ApprovalRequestStep openStep = pendingSteps.get(0);
```
(`ApprovalDecisionServiceImpl.java:87-91`)

It decides *whatever step is open when the request arrives*. The consequences of a retried POST on a flaky 3G link:

- **Single-step chain:** the retry hits a terminal request → 409. Harmless.
- **Multi-step chain where the same person holds two consecutive roles** — normal in a small Tanzanian group where the GM is both above the manager threshold *and* the final gate: the retry approves **step 2 as well**. One human decision releases two. `@Version` does not help; it prevents two concurrent writers colliding, not one writer acting twice in sequence. SoD (`StepApproverResolver.java:45-48`) blocks only the *submitter*, never the same approver on consecutive steps, and there is no quorum.

This is a real double-approve path, and it exists whether or not the app ever queues offline — an ambiguous POST on a mobile network is unavoidable.

**Client mitigation, ships in v1 regardless:**
1. Every decide carries `Idempotency-Key` (ULID, generated once per *user decision*, reused across every retry of that decision) and a durable `X-Request-Id` — the `pos_app/lib/core/api/api_client.dart:49-56` interceptor pattern lifts directly (it already stamps both, and `X-Branch-Uid`).
2. **Never blind-retry.** On a timeout with no response (`ApiException.isAmbiguousWrite`, `api_exception.dart:68`): show `We're not sure that went through. Checking…`, then **re-read** `GET /approvals/requests/uid/{uid}` and inspect `steps[]`. If the step the user acted on is now non-`PENDING` with a `decisions[]` entry whose `decidedBy` is the user, it went through. Only if it is still `PENDING` do we offer `Try again` — resending the **same** key. This is the read-back pattern that `GET /api/v1/pos/sales/idempotency/{key}` (`PosSaleController.java:79`) exists for on the sale path.

**PROPOSED — N-APR-5, the server side of the same problem `[schema]` + `[code-only]`:**

- `[code-only]` — **`expectedStepUid` + `expectedVersion` on `DecideRequest`.** The client sends the uid of the step it rendered (`ApprovalRequestStepDto.uid` is already on the wire) and the `@Version` of the request it read; the server refuses with a distinct 409 (`This request changed since you looked at it. Open it again.`) if either differs. This converts *"decide whatever is open"* into *"decide **this** step"* and is the single most valuable change in the module. Additive optional fields — the web client and existing callers are unaffected. **Note the request `version` is not currently exposed on `ApprovalRequestDto`**, so this change also adds that field.
- `[schema]` — a `decision_idempotency` table mirroring `pos_sale_idempotency` (`V70__pos_sale_idempotency.sql`): `UNIQUE (company_id, idem_key)`, reserved **inside** the decide transaction before the decision row is written. **Needs explicit owner approval** per the standing rule, and must be boot-tested against a restored customer copy per the trigger-outage rule.

Build `expectedStepUid`/`expectedVersion` first. It is free, needs no migration, and closes most of the risk on its own.

#### A.3.6 Offline: the app refuses to queue a decision

**A queued approval is never shipped.** Four reasons, all from shipped code:

1. **No idempotency + step resolved at execution time** (§A.3.5) — a queue that fires on reconnect *and* again on resume approves two steps.
2. **The decision is a judgement about state the approver cannot re-read.** Offline, the amount on screen may be hours old, and meanwhile the submitter can `recall()` (`:184-220`), an admin can `cancel()` (`:222-247`), or an earlier step can reject and kill the chain.
3. **The 409 has nowhere to land.** The server's conflict strings are written for a human looking at a screen; delivered four hours later to a phone in a pocket, there is no honest interaction model.
4. **The audit timestamp becomes false.** `audit.record(...)` is called inside the decide TX and `AuditServiceImpl` stamps the row server-side (`ApprovalDecisionServiceImpl.java:126-131`; `AuditServiceImpl.record:36-56` resolves actor/company/branch/org/ip from `RequestContext`, never from the caller). A decision made at 09:00 and flushed at 14:00 is recorded as 14:00. For the last gate before money leaves the group, that is not a UX wrinkle.

**What the app *does* do offline:** render the cached inbox (greyed, with `Showing your inbox from 07:12. You need a connection to approve.`), open cached cards read-only, let the user draft a reason locally against a pending request, and set a "remind me at 09:00" local notification. The Approve/Send-back buttons are **absent** with that explanation — never present-but-inert.

---

### A.4 Push notifications

#### A.4.1 What already exists

| Piece | Status | Evidence |
|---|---|---|
| `PUSH` channel | **Already declared and reserved** | `.../notifications/domain/enums/NotificationChannel.java:13-14` — *"Reserved — not sent in v1"* |
| DB permits a PUSH row | ✅ **No migration needed to write one** | `V21__notifications.sql:78` — `CHECK (channel IN ('IN_APP','EMAIL','SMS','PUSH','WEBHOOK'))`, same CHECK on `notification_deliveries` |
| Approval trigger + audience | **Already runs** | `ApprovalSubmittedNotificationHandler.java:48-73` consumes `APPROVAL.SUBMITTED` off the outbox → raises `APPROVAL_PENDING`; `AudienceResolver.java:29-36` resolves the users holding the audience permission, branch-narrowed when the type is branch-scoped |
| Channel fan-out with per-channel suppression | ✅ | `NotificationRaiserImpl.java:127-160` (muted → `MUTED` rows; per-user channel opt-out → `CHANNEL_DISABLED` rows) |
| Deep-link target | ✅ | `notification_types.link_route` seeded as `"/approvals/{sourceUid}"` (`NotificationTypeSeeder.java:73`; `V21__notifications.sql:232`), surfaced as `NotificationDto.linkRoute` (`:19`) |
| Sender template to copy | ✅ | `EmailSender.java:28` `@ConditionalOnBean(JavaMailSender.class)` → absent bean = silently skipped channel. A `PushSender` degrades the same way. |
| Device token registry | ❌ **Does not exist** | grep for `fcm\|apns\|device_token\|firebase` across `backend/src/main` returns nothing |

**🔴 A live defect that will make push look broken on day one — and it is worse than a seeder typo.** The `APPROVAL_PENDING` type sets `audience_permission = 'PURCHASE.PO.APPROVE'`. That code **does not exist** in `R__seed_permissions.sql` — `grep -c` returns **0**. The real PO code is `PURCHASE.ORDER.APPROVE` (`R__seed_permissions.sql:167`), and the correct audience for engine approvals is `APPROVALS.DECIDE`. The handler passes `null` to fall back to the catalogue (`ApprovalSubmittedNotificationHandler.java:68`; the fallback is applied at `NotificationRaiserImpl.java:97-100`), so **the audience resolves to nobody** — the raiser writes a `NO_AUDIENCE` suppressed delivery row (`:107-114`) and returns. Textbook `phantom-permission-codes`.

The phantom code sits in **three** places, and that changes the fix:

1. `ApprovalSubmittedNotificationHandler.java:37` — `DEFAULT_APPROVER_PERM` constant (currently unused; the handler passes `null`)
2. `NotificationTypeSeeder.java:69` — the Java seeder's `TypeSpec`
3. **`V21__notifications.sql:226` — an already-applied Flyway migration**, which is what actually populated the live client's `notification_types` row

Because `NotificationTypeSeeder` **skips any type that already exists** (`:77-79`, `if (types.findByCompanyIdAndTypeKey(...).isPresent()) continue;`), editing the seeder fixes nothing for a company already provisioned — which is every company in production. And `V21` cannot be edited (schema frozen). So the fix is **provisioning code that converges the existing row**, per the standing rule: a reconciler that updates `audience_permission` (and `default_channels`) to the shipped values for system-seeded types. The admin API cannot do it either — `SetCompanyTypeStateRequest` carries only `enabled` (`SetCompanyTypeStateRequest.java`), so `PUT /api/v1/admin/notifications/types/{typeKey}/state` can toggle the type on and off but cannot change its audience or channels.

Two smaller items in the same area:

- The two seed sources **disagree on channels**: `V21:227` seeds `'IN_APP,EMAIL'`; the Java seeder sets `IN_APP` only (`NotificationTypeSeeder.java:86`). Whichever ran for a given company decides whether approvals already email. Converge them in the same reconciler.
- The body template renders `"User#{id}"` (`ApprovalSubmittedNotificationHandler.java:60`) instead of a name. Resolve the display name in the handler — `ApprovalEngineImpl` already has the pattern (`resolveUserName:280-290`).

Silver lining: the failure is **observable**. Every suppressed dispatch writes a `notification_deliveries` row with outcome/reason, readable at `GET /api/v1/admin/notifications/deliveries?outcome=…` (`NotificationAdminController.java:79-98`, `NOTIFICATION.ADMIN`). Check it on the client's box before and after the fix.

#### A.4.2 Which events push

| Event | Push? | Rationale |
|---|---|---|
| `APPROVAL_PENDING` — something needs **you** | ✅ **Immediate** | The product's reason to exist |
| Approval waiting > 24 h, still yours | ✅ **One reminder, 09:00 local** | The stated complaint is "an approval that quietly sits somewhere I never look". PROPOSED `[code-only]` — a scheduled sweep over `approval_request_steps` where `status='PENDING'` and the parent's `submitted_at < now() - 24h` |
| An approval **you decided** resolved further down the chain | ⛔ In-app only | `APPROVAL.RESOLVED` already publishes to the outbox (`ApprovalDecisionServiceImpl.publishResolved:315-322`) — a natural future hook, but no notification handler consumes it today, and it should not buzz |
| Approval you were going to decide got recalled/cancelled | ⛔ Silent inbox refresh | Removing a card is the notification |
| Anything from the reports side (daily brief, health checks) | ⛔ v1 | One buzz, one meaning. Dilute it and he mutes the app. |

#### A.4.3 Payload — nothing sensitive on a lock screen

A push payload transits Google and Apple servers and renders on a lock screen that may be sitting on a boardroom table. **No amounts, no supplier or customer names, no branch, no document number.**

```json
{
  "typeKey": "APPROVAL_PENDING",
  "notificationUid": "01J…",
  "sourceUid": "01J…",                       // the approval request uid
  "linkRoute": "/approvals/01J…",            // from notification_types.link_route
  "title": "Approval waiting",
  "body": "A purchase order needs your decision.",
  "collapseKey": "approvals",
  "companyUid": "01J…"
}
```

The body names the **type**, never the value. The exec unlocks the phone, the app fetches the real card, and the amount appears behind the device lock and the app's own unlock gate. Push bodies stay plain English at a level a manager can forward — no ERP jargon, no uids in the visible text. (Note the shipped in-app template is the opposite: `"Approval required: {documentType} {documentUid}"` puts a raw code and a ULID in the title — fine behind a login, unacceptable on a lock screen. The push title must be rendered separately, not reused.)

`sourceUid` is the request uid (the handler sets it from `payload.requestUid()`, `:61`), so `GET /approvals/requests/uid/{sourceUid}` is a direct tap-through. On a foreground message, **refresh the badge from the count endpoint (N-APR-3) rather than trusting the payload**.

#### A.4.4 Deep-linking

`notification_types.link_route` is already `"/approvals/{sourceUid}"` — a web route. The mobile app maps `/approvals/{uid}` → its own approval-detail route. **Do not change the seeded value**; both clients read the same template and the web depends on it. Map, don't rewrite.

Cold-start ordering matters: tap → app launches → biometric unlock → *then* fetch the request. If the fetch 404s or 409s (already decided while the phone was in a pocket), land on the inbox with a neutral `That request has already been decided.` — never a crash, never an empty detail screen.

#### A.4.5 Quiet hours, digest, collapse

The preference model today is **`muted` (bool) + `channelsEnabled` (comma string)** only (`.../domain/entity/NotificationPreference.java:27-31`, `NotificationPreferenceDto.java:12-13`). There is **no quiet-hours column and no digest machinery**.

**v1: do quiet hours on the client.** Android notification channels and iOS interruption levels let the OS hold non-urgent notifications; the app ships two channels — `Approvals` (default importance, respects the OS's own Do Not Disturb) and `Reminders` (low importance). Default quiet window 21:00–06:00 `Africa/Dar_es_Salaam`, user-adjustable in Settings. Zero backend change, and it respects the OS controls the exec already knows.

**Collapse:** one `collapseKey` per company (`"approvals"`), so twelve approvals in an hour produce one buzz and one updating notification reading `7 approvals waiting`, not twelve. Android `setGroup` + a summary notification; iOS `thread-id`. This is the difference between an app he keeps and an app he mutes.

**Digest — PROPOSED, only if quiet hours prove insufficient `[schema]`:** a `quiet_from`/`quiet_to`/`digest_mode` triple on `notification_preferences`, plus a scheduled digest raiser. Defer. Client-side quiet hours plus collapse solves 90% of it for zero migration.

#### A.4.6 The backend pieces push needs

| Piece | Tag |
|---|---|
| **`user_devices` table** — `id, uid, organisation_id, user_id, platform, push_token, app_version, device_label, locale, status, last_seen_at, …` with `UNIQUE (push_token)` (handles a token migrating between users on a refurbished handset) and `status` per the soft-delete convention; `organisation_id` per ADR-0062 D-9. Next free version is **V105** (highest applied: `V104__multitenancy_constrain.sql`; 104 versioned migrations on `develop`) | **`[schema]` — explicit owner approval required** |
| `POST /api/v1/devices` (idempotent upsert on token) + `DELETE /api/v1/devices/uid/{uid}`, flat under `com.erp.api` per the controller convention. Gate on the caller's own identity — a user may always register their own device — or a new `DEVICE.ENROL` code, which must then be seeded | `[code-only]` (+ `R__` edit if a new code) |
| `PushSender` in `modules/notifications/service/`, mirroring `EmailSender`: `@Async`, transitions the pre-created PENDING row via `NotificationDelivery.complete(...)`. **FCM HTTP v1** (covers Android natively, iOS via APNs — one credential). `@ConditionalOnProperty` so an unconfigured client degrades cleanly. On `UNREGISTERED`/`NOT_FOUND`, flip the device row to `INACTIVE` or the delivery log fills with permanent failures forever | `[code-only]` |
| Wire `Optional<PushSender>` into `NotificationRaiserImpl` beside `Optional<EmailSender>` (constructor `:56-72`) and add a `CHANNEL_PUSH` branch in the channel loop (`:151-157`). Fan-out is **per device**, so one notification row can produce several delivery rows — the notification unique key is `(company_id, trigger_key, recipient_user_id, channel)` (`V21:74`), so per-device detail belongs in `notification_deliveries` | `[code-only]` |
| Turn PUSH on for `APPROVAL_PENDING` **and fix the phantom audience permission on already-provisioned companies** (§A.4.1) — a converging reconciler, not a seeder edit alone, and not a Flyway backfill | `[code-only]` |
| **No new outbox handler needed** — `ApprovalSubmittedNotificationHandler` already runs | — |

**Owner decision required before phase 5:** one Firebase project owned by us (every client's push titles transit our Google account — generic titles only, needs an ADR) or one per client (reintroduces per-client app configuration and kills the one-binary property). Also worth naming to the client: **push is the one part of this app that must reach the public internet regardless of the self-hosted server** — the private-CA topology (ADR-0061) covers the API, not FCM/APNs.

---

### A.5 Delegation / out-of-office

**The backend does not support it. At all.** A repo-wide grep for `delegat` across `backend/src/main/java` returns only unrelated javadoc ("delegates to…", "delegated to"). There is no delegation entity, no column, no `acting_for`, no date-bounded substitution. ADR-0022 (`docs/decisions/0022-approvals.md:417`) defers escalation, SLA, delegation, quorum, parallel steps and push notifications explicitly, and notes each is additive against the v1 row shape.

Nor is there a workaround worth using. Routing is by **role code frozen onto the policy step** (`ApprovalPolicyStepDto.approverRoleCode`), resolved through `user_role` at decide time (`StepApproverResolver.holdsRoleCode:78-84`, which correctly honours `revokedAt`). The only way to cover an absent approver today is to **grant a second person the same role code** — which is permanent, unaudited as a delegation, invisible on the request, and indistinguishable in `audit_log` from that person always having held the role. For a group where the Finance Director travels, that is how a permanent authority grant gets made by accident and never revoked.

There is one accidental mitigation: because the step names a *role*, **any** holder of that role can decide it, and `handleApprove` advances on the first decision (`:146-162`) — there is no quorum. So a role with two holders is already a de-facto pool. That is not delegation; it is the absence of named approvers.

#### PROPOSED — `approval_delegations` `[schema]`

Only worth building if the owner confirms travel is a real operational problem. Sketch, for the decision — **not written, not approved**:

```sql
-- PROPOSED. Not written. Needs explicit owner approval + boot test against a restored customer copy.
CREATE TABLE approval_delegations (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uid               VARCHAR(26)  NOT NULL,
    organisation_id   BIGINT       NOT NULL,          -- ADR-0062 D-9
    company_id        BIGINT       NOT NULL,
    delegator_user_id BIGINT       NOT NULL,
    delegate_user_id  BIGINT       NOT NULL,
    role_code         VARCHAR(64),                    -- NULL = all roles the delegator holds (matches approver_role_code length)
    document_type     VARCHAR(60),                    -- NULL = all types
    max_amount        NUMERIC(19,4),                  -- NULL = no ceiling; a delegate need not inherit everything
    currency          VARCHAR(3),
    starts_at         TIMESTAMPTZ  NOT NULL,
    ends_at           TIMESTAMPTZ  NOT NULL,          -- NOT NULL: an open-ended delegation is a role grant
    reason            VARCHAR(200),
    status            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    revoked_at        TIMESTAMPTZ, revoked_by BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(), created_by BIGINT NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_approval_delegation_uid UNIQUE (uid),
    CONSTRAINT chk_ad_window CHECK (ends_at > starts_at),
    CONSTRAINT chk_ad_not_self CHECK (delegator_user_id <> delegate_user_id)
);
```

Non-negotiable behaviours if it is built:

1. **`ends_at NOT NULL`.** An open-ended delegation is a role grant wearing a disguise.
2. **SoD survives.** The delegate must still not be the submitter — `StepApproverResolver.canDecide`'s first check (`:45-48`) stays, evaluated against the *delegate*, and additionally the delegate must not be the submitter *via* the delegator.
3. **No chaining.** A delegate cannot re-delegate. One hop, enforced in the resolver.
4. **`max_amount` is a real ceiling**, not decoration — the whole point is "cover me for the routine ones".
5. **The decision records both people.** `ApprovalDecision` already stores `decidedBy`; the delegation uid goes in the `audit_log` detail JSON, and `ApprovalDecisionDto.decidedByName` renders `Grace Mhina (for Bakari Mbaga)` in both the web and mobile timelines. A decision that hides who actually pressed the button is worse than no delegation.
6. **Endpoints:** `POST /api/v1/approvals/delegations`, `GET ?companyId=`, `POST /uid/{uid}/revoke`, plus `GET /me` for the self-service "I'm travelling" switch. New permission `APPROVALS.DELEGATE` (self-service, seeded to every role that holds `APPROVALS.DECIDE`) and `APPROVALS.DELEGATE.ADMIN` folded into `APPROVALS.ADMIN`. Seed both in `R__seed_permissions.sql`.
7. **Both parties are notified** on create and on revoke.

**Recommendation: not in v1.** Ask the owner whether an approval has ever actually been stuck because someone travelled. If the honest answer is "a second person already holds the role", the correct fix is a policy step with a role that has two holders, and this table is never written.

---

### A.6 Bulk approve

**No bulk endpoint exists.** Every write is `/uid/{uid}/…` (`ApprovalRequestController.java:64-91`). N approvals = N round trips, each with its own optimistic-lock retry loop.

**Should we? Narrowly, and not in v1.**

The case against is strong and specific to *this* engine: with no idempotency and step-resolution-at-execution-time (§A.3.5), a bulk call is a loop of the exact operation that already double-approves on retry — now with N times the chance of a partial failure and no natural place to report it. And the audience is the one for whom "I approved it without reading it" is the failure mode the control exists to prevent. A bulk button on a 42-million-shilling purchase order is a way of not deciding.

The case for is real for a different shape: nineteen sales orders of TZS 80,000 each from the same route agent, all sitting because a policy band is set too low. Making the GM tap through nineteen confirm sheets teaches him to stop reading.

**PROPOSED — N-APR-6 `[code-only]`, phase 4 at the earliest, under all seven guardrails:**

```
POST /api/v1/approvals/requests/bulk-approve
@PreAuthorize("@perm.has('APPROVALS.DECIDE')")
body { items: [{ uid, expectedStepUid, expectedVersion }], comment, idempotencyKey }
→ { approved: [uid], failed: [{ uid, reason }] }        partial result carried in the ApiResponse envelope
```

| # | Guardrail |
|---|---|
| 1 | **N-APR-5 ships first.** No bulk without `expectedStepUid` + `expectedVersion` per item. The client asserts *this* step, not "whatever is open". |
| 2 | **Amount ceiling.** Only requests below a per-company `bulk_approve_max_amount` are selectable. Default: the lowest policy band's `maxAmount`. Anything above is decided one at a time, with the full detail screen. |
| 3 | **One document type per batch.** Mixed POs and SOs in one gesture is a batch nobody read. |
| 4 | **Hard cap of 20 items.** |
| 5 | **Step-up ticket required on the batch** (§A.3.2), bound to the item-uid set and the batch total — not to a single resource. |
| 6 | **The confirm sheet lists every line** — reference, counterparty, amount, plus a batch total in full (never abbreviated). The user scrolls the list before the button enables. |
| 7 | **Partial failure is normal and must be shown**, item by item, with the server's own 409 string per item. Each item is its own transaction; a failure never rolls back the successes. `audit_log` gets **one row per item** (§A.7) plus a batch correlation id in `detail`. |

Never allow bulk **reject** — rejection kills the whole chain and always needs a reason (`handleReject:164-182`).

---

### A.7 Audit expectations for a mobile approval

#### A.7.1 What is written today

`audit_log` columns (`.../platform/audit/AuditLog.java:33-87`): `actor_user_id`, `action`, `target_type`, `target_id`, `target_uid`, `company_id`, `branch_id`, `organisation_id`, `detail` (JSONB), `at`, `ip`. **There is no `user_agent` column and no `device_id` column.**

Actor, company, branch, organisation and IP are resolved from `RequestContext` inside the service, not by the caller (`AuditServiceImpl.record:36-56`) — the call site cannot forge them. The table is append-only, enforced by the application: `AuditService` declares no update or delete and `AuditLog` exposes getters only (CLAUDE.md invariant 7).

A decide writes exactly one row (`ApprovalDecisionServiceImpl.java:126-131`):

```java
audit.record(AuditEvent.of(AuditActions.APPROVAL_STEP_DECIDE, "approval_requests",
        request.getId(), request.getUid())
        .detail(Map.of(
                "action",   req.action().name(),
                "stepSeq",  String.valueOf(openStep.getSequence()),
                "stepRole", openStep.getApproverRoleCode())));
```

Action constants (`.../platform/audit/AuditActions.java:287-293`): `APPROVAL.POLICY.CREATE`, `APPROVAL.POLICY.UPDATE`, `APPROVAL.POLICY.DEACTIVATE`, `APPROVAL.REQUEST.SUBMIT`, `APPROVAL.STEP.DECIDE`, `APPROVAL.REQUEST.RECALL`, `APPROVAL.REQUEST.CANCEL`.

Separately, `ApprovalDecision` is appended as a **domain** row (`:105-109`) carrying `action`, `decidedBy`, `comment`, and surfaced in `ApprovalDecisionDto` — that is the business-visible timeline, distinct from the audit log. The `action` column is `CHECK (action IN ('APPROVE','REJECT'))` (`V18:167`), so no third verb can ever be recorded there.

⚠️ **`recall` and `cancel` write NO `approval_decisions` row** (`:207-216`, `:234-243` write only the request + audit). The in-app decision timeline will never show who cancelled — only `resolvedBy`/`resolvedByName` on the request.

#### A.7.2 What a mobile approval must add — all `[code-only]`

The `detail` JSONB is the right home; no migration is needed. Extend the decide audit to:

```json
{
  "action":          "APPROVE",
  "stepSeq":         "2",
  "stepRole":        "FINANCE_DIRECTOR",
  "documentType":    "PURCHASE_ORDER",
  "documentUid":     "01J…",
  "amount":          "42500000.0000",
  "currency":        "TZS",
  "channel":         "MOBILE",
  "deviceId":        "01J…",
  "deviceLabel":     "Samsung A54",
  "appVersion":      "1.0.3+12",
  "platform":        "ANDROID",
  "userAgent":       "OrbixExec/1.0.3 (Android 14)",
  "stepUpTicket":    "01J…",
  "stepUpMethod":    "PASSWORD",
  "idempotencyKey":  "01J…",
  "requestId":       "01J…",
  "expectedVersion": "3",
  "commentPresent":  true
}
```

Sourcing, precisely:

- `channel`, `deviceId`, `deviceLabel`, `appVersion`, `platform`, `userAgent` — from a new `X-Device-Id` header and `User-Agent`, threaded into `RequestContext.Principal`, whose record today is `(userId, username, root, companyId, branchId, ip, organisationId)` (`JwtRequestContextFilter.java:238-240`) — it carries no device or agent field. The same plumbing would finally populate the dead `refresh_tokens.device_info` / `user_agent` / `ip_address` columns (`V1__baseline.sql:208-210`, entity `RefreshToken.java:52-60`; the setters exist at `:125`, `:133`, `:141` and **nothing calls them**). Do this once in the filter and every audit row in the product gets it for free.
- `ip` — already populated from `RequestContext.ip()`; on the prod stack it is Caddy's, so `infra/prod/Caddyfile` must forward `X-Forwarded-For` and the filter must honour it, or every mobile approval is attributed to the reverse proxy. **(UNVERIFIED — I did not audit the Caddyfile or the filter's forwarded-header handling; confirm before relying on the IP in the record.)**
- `stepUpTicket` / `stepUpMethod` — proof that a fresh re-auth backed this decision (N-APR-4). This is what makes the biometric mean something in the record.
- `amount` / `currency` — snapshotted from the request row at decide time (both are `updatable = false` on the entity, so they cannot drift), serialised as an exact decimal **string** in the JSON.
- `commentPresent` rather than the comment text — the comment already lives on `approval_decisions`; duplicating free text into an append-only log invites PII where nobody expects it.

Two additional audit rows the mobile flow should write, both `[code-only]`:

| Action | When | Detail |
|---|---|---|
| `AUTH.REAUTH.SUCCESS` / `.FAIL` | Every `POST /auth/reauth` (N-APR-4) | `deviceId`, `method`, `resourceUid`, `amount` — mirrors the existing `AUTH.STEP_UP.SUCCESS` / `AUTH.STEP_UP.FAIL` constants (`AuditActions.java:54`, `:59`) |
| `DEVICE.ENROL` / `DEVICE.REVOKE` | Device registration and removal | `deviceId`, `platform`, `label` |

#### A.7.3 What we do **not** do

- **No client-asserted timestamp in v1.** A `decidedAt` supplied by the phone alongside the server's received-at would need its own column and only becomes meaningful if offline queueing ships — which it does not (§A.3.6). The server's `Instant.now()` is the truth precisely because the app refuses to decide offline.
- **No signature persistence in v1.** Device-bound P-256 approval signatures (the WebAuthn-without-a-browser design) would be genuinely non-repudiable, but they need a `user_devices.public_key_der` and a home for the signature. Cheapest route when it comes: the `audit_log` detail JSON — `[code-only]`. A column on `approval_requests` would be `[schema]`.
- **No GPS.** It adds a privacy liability, an OS permission prompt, and answers no question anyone has asked.

---

### A.8 Blocking prerequisites and the backend punch list

| # | Item | Blocking? | Tag |
|---|---|---|---|
| P1 | **A role bundle the executives can actually hold.** No `OWNER`/`GENERAL_MANAGER`/`CEO` is seeded (`R__seed_permissions.sql:309-321`), and inbox routing is by role code. Without one, the GM's inbox is empty by construction. Add a `GENERAL_MANAGER` bundle (at minimum `APPROVALS.DECIDE`, `APPROVALS.REQUEST.VIEW`, `APPROVALS.POLICY.VIEW`, `PURCHASE.ORDER.VIEW`, `SALES.ORDER.VIEW`, `AR.VIEW`, `AR.STATEMENT.VIEW`, `AP.VIEW`, `BI.VIEW`/`BI.FINANCE.VIEW`/`BI.OPS.VIEW`/`BI.CRM.VIEW`) **and name it in the relevant policy steps** — the bundle alone does nothing until a policy step routes to it. | **Yes — v1 is unusable without it** | `R__` seed edit — **owner approval required** |
| P2 | **Fix the phantom `APPROVAL_PENDING` audience** — `PURCHASE.PO.APPROVE` → `APPROVALS.DECIDE`. Three sites: `ApprovalSubmittedNotificationHandler.java:37`, `NotificationTypeSeeder.java:69`, and the already-applied `V21__notifications.sql:226`. Requires a **converging provisioning reconciler**, because the seeder skips existing rows and V21 cannot be edited. Also converge `default_channels` (V21 says `IN_APP,EMAIL`, the seeder says `IN_APP`). | Yes, for push (and for the in-app approval notification, which is also dead today) | `[code-only]` |
| P3 | **`expectedStepUid` + `expectedVersion` on `DecideRequest`** (and expose the request `version` on `ApprovalRequestDto`) — closes the double-approve path | **Yes** | `[code-only]` |
| P4 | **Cross-company inbox** (N-APR-1) | No for one company; yes for the group premise | `[code-only]` |
| P5 | `POST /auth/reauth` step-up ticket (N-APR-4) + `X-Approval-Ticket` enforcement, device-scoped only | Yes, for high-value approvals | `[code-only]` |
| P6 | Inbox count endpoint (N-APR-3) | No, but push badges are expensive without it | `[code-only]` |
| P7 | Approval context pack (N-APR-2) — removes the blind-approval problem **and the fact that `FINANCE_DIRECTOR` cannot open a purchase order at all** (§A.2.2) | Effectively yes for a Finance-Director approver; otherwise the screen is weak | `[code-only]` |
| P8 | `user_devices` table (V105) + `POST/DELETE /api/v1/devices` + `PushSender` | Yes, for push | **`[schema]` — owner approval required** |
| P9 | Stamp device/user-agent into `RequestContext.Principal` → audit `detail` and the dead `refresh_tokens` columns | No | `[code-only]` |
| P10 | `decision_idempotency` table (mirroring `pos_sale_idempotency`, V70) | No if P3 ships | **`[schema]` — owner approval required** |
| P11 | Split the 409-that-is-really-a-403 at `ApprovalDecisionServiceImpl.java:94-98`, and stop leaking the role code in the user-facing string | No | `[code-only]` |
| P12 | `approval_delegations` (§A.5) | No — recommend deferring pending an owner answer | **`[schema]`** |
| P13 | **Revoked branch assignments still permit inbox visibility and decisions.** `ApprovalRequestStepRepository.findInboxRequestIds` (`:75-76`, `:82`) and `StepApproverResolver.canDecide` (`:63-65`) use branch predicates with no `revokedAt`/`active` filter, while `JwtRequestContextFilter:229-233` correctly requires both. Switch both to `findByUserIdAndBranchIdAndRevokedAtIsNullAndActiveTrue` / add the predicates to the JPQL. | No for v1 function, **yes for the security story** — and a mobile app makes it visible | `[code-only]` |

**Three live defects worth surfacing to the owner independently of this app**, because a mobile screen will make them visible to the person least equipped to explain them:

1. **Policy currency is hardcoded `"TZS"`** at create (`ApprovalPolicyServiceImpl.java:76` — the literal `"TZS"` is passed to the `ApprovalPolicy` constructor; `CreateApprovalPolicyRequest` has no currency field at all, `:19-31`) and `ApprovalPolicyMatcher.match` takes no currency (`:40-43`), matching purely on `(companyId, documentType, amount)` (`ApprovalPolicyRepository.findMatchCandidates:32-41`). A USD 5,000 purchase order is matched against TZS bands, falls in the lowest band, and — if no band covers it — **auto-approves** with `autoApproved = true`. On a phone that reads as "approved automatically" against a number the owner recognises as large.
2. **`effectiveFrom` / `effectiveTo` are dead fields.** The columns exist (`V18:19-20`), the entity has them with setters (`ApprovalPolicy.java:68-77`), the DTO returns them (`ApprovalPolicyDto.java:24-25`) — but **neither `CreateApprovalPolicyRequest` nor `UpdateApprovalPolicyRequest` accepts them**, and `findMatchCandidates` has no date predicate. A manager cannot set or rely on a validity window.
3. **`currentStepSequence` is a permanently-null column on a live table** (§A.1.2) — declared, migrated, serialised on every API response, and written by nothing. Any client that trusts it renders "Step null of 3".