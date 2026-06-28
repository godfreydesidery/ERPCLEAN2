# Security findings log

> Running log of security findings across slices, with status. Per-slice review narratives live in
> `slice<N>-*-review.md`; this file is the at-a-glance tracker (the "no silent fix / audit trail"
> rule). Status: OPEN · FIXED · MITIGATED · ACCEPTED · DEFERRED.

| # | Slice | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| F1 | 3 | BLOCKER | Cross-company escalation — a permission check (`hasPermission('CODE')`) wasn't a scope check; a user could act on another company's resource by uid. | FIXED | ADR-0002 `@perm.scoped(#uid,'type','CODE')` asserts target company == active company. `RbacEnforcementHttpIT`. |
| F2 | 3 | HIGH | Invalid 1-arg `hasPermission` SpEL → 400 on create/list gates. | FIXED | Replaced evaluator with `@perm.has(...)` bean expression. |
| F3 | 3 | HIGH | `RequestContext` null over HTTP (filter ordering before bearer auth) → `/auth/me` 401, gates saw null principal. | FIXED | `JwtRequestContextFilter` registered after `BearerTokenAuthenticationFilter`. |
| F4 | 3 | MEDIUM | Method-security denial returned 500, not 403 (catch-all swallowed `AuthorizationDeniedException`). | FIXED | Explicit `AccessDeniedException → 403` envelope handler. |
| F5 | 3 | HIGH | Root bypass unaudited (full audit aspect is Slice 6). | FIXED | Slice 6 (ADR-0004 D-9): every root ACTION is audited by its own `audit_log` row (actor=root); a distinct `ROOT.BYPASS` row records root acting cross-company. Interim per-check `log.info` demoted to DEBUG. The root-flag-revoke residual is closed by F9. |
| F6 | 4 | MEDIUM | **Archived branch could still scope a login session** — `AuthServiceImpl.issueSession` resolved the default branch without a status filter, and `BranchServiceImpl.archiveByUid` didn't touch `user_branch.is_default` rows pointing at the archived branch. No cross-tenant exposure (the company is still validly owned), but continued access scoped to a decommissioned branch. | FIXED | Login now skips a non-ACTIVE default branch (user lands read-only until reassigned); `archiveByUid` clears `user_branch.is_default` for the branch and promotes each affected user's earliest-remaining (D-D). Covered by Slice-4 IT. |
| F7 | 5 | (design erratum) | Branch-switch override 403 wasn't rendered: ADR-0003 assumed a thrown `AccessDeniedException` would reach the chain's `accessDeniedHandler`, but `JwtRequestContextFilter` runs *downstream* of `ExceptionTranslationFilter`, so it escaped uncaught (would be a container 500). Caught by `Slice5HttpIT` (HTTP-level). | FIXED | Filter catches `AccessDeniedException` and renders via `SecurityErrorResponder.handle` directly; returns without continuing the chain; `RequestContext` still cleared in `finally`. ADR-0003 D-2 erratum recorded. |
| F8 | 5 | LOW | **Override into an ACTIVE branch under an ARCHIVED company isn't blocked** — the override (and `issueSession`) checked `branch.status` but not `branch.company.status`. | FIXED | Slice 6 (ADR-0004 D-8): `Branch.isUsableForSession()` (branch ACTIVE && company ACTIVE) applied in `JwtRequestContextFilter.resolvePrincipal` (via `findWithCompanyByUid`) + `AuthServiceImpl.issueSession`. Login lands read-only, override → 403. `AuditF8HttpIT`. |
| F9 | 5 | LOW | **A user disabled mid-session keeps scope until the access-token TTL expires** — the filter trusted the still-valid JWT without re-checking `user.isActive()`. | FIXED | Slice 6 (ADR-0004 D-8): `JwtRequestContextFilter` re-checks `existsByIdAndStatus(userId, ACTIVE)` per request; a disabled user is refused (401) on the next request. One indexed PK lookup. `AuditF9HttpIT`. Also closes the F5 root-flag-revoke residual. |
| F10 | 6 | MEDIUM | **Audit read was org-wide** — any `AUDIT.VIEW` holder saw all rows incl. other companies'. Became live once `AUDIT.VIEW` was granted to a non-root role. | FIXED | `AuditReadService.search` now adds a `company_id = activeCompany` predicate for non-root callers (root stays org-wide); fail-closed (no active company ⇒ matches nothing). `AuditHttpIT.getAudit_nonRootHolder_seesOnlyOwnCompanyRows`. Branch-level scoping deferred (company-level chosen). |
| F11 | 6 | MEDIUM | **Deploy-time `REVOKE UPDATE, DELETE ON audit_log` not documented/applied** — the third leg of append-only (ADR-0004 D-5). Today append-only rests on app code + the package-scoped ArchUnit rule only; the app DB role likely holds full DML. | OPEN (pre-prod) | Add `REVOKE UPDATE, DELETE ON audit_log FROM <app_role>` to the deploy/runbook before production; record it in the deploy docs as the ADR promises. |
| F12 | Parties | BLOCKER | **Parties list endpoints trusted a client-supplied `companyId`** — a non-root holder of `CUSTOMER.VIEW` (etc.) could `GET /customers?companyId=<other company>` and read another company's entire party master (names, TIN, VRN, mobile-money, addresses, credit limits). F10/F1-class cross-tenant read. The existing list tests passed only `companyId=own`, giving false assurance. | FIXED | Each `list(...)` service (customer/supplier/agent/other) now calls `scopeGuard.assertCanActIn(RequestContext.get(), companyId)` before querying (root org-wide + audited; non-root confined to active company; fail-closed). Regression test `PartiesHttpIT.listCustomers_crossCompanyId_nonRootUser_returns403`. |
| F13 | Parties | HIGH | **`getByUid` and `listBranches` read any party cross-company** — uid is not authorization; the read paths skipped the `assertCanActIn` that the write paths already had. The tenant-safe `findByCompanyIdAndUid` existed on all repos but was unused. | FIXED | `getByUid`/`listBranches` (all 4 kinds) now `assertCanActIn` on the loaded entity's company. Regression tests `PartiesHttpIT.getCustomerByUid_crossCompany…` / `listCustomerBranches_crossCompany…` → 403. |
| F14 | Parties | MEDIUM | **Internal-agent → IAM user link not company-scoped** — an INTERNAL agent could reference an ACTIVE user belonging to another company (cross-tenant identity ref + sequential-id enumeration oracle). | FIXED | `UserLookupService.isActiveUserInCompany(userId, companyId)` requires the user be ACTIVE and assigned (via `user_branch`) to a branch of the agent's company. `AgentServiceImplIT.create_internalAgent_userBelongsOnlyToOtherCompany_…`. Follow-up noted: prefer user-uid over raw id in the request (removes the oracle) — not done this pass. |
| F15 | Products | HIGH | **`setPrice`/`removePrice` linked a product to a price list resolved by an unscoped `priceLists.findByUid`** — a `PRODUCT.MANAGE` holder in company A could `POST /products/uid/{A-product}/prices` with another company's `priceListUid` (the gate only scopes the product). The row persisted with `company_id=A` (invisible to tenant reads) and every `GET .../prices` returned the foreign price list's code/name/uid via `ProductPriceDto.from` — a cross-tenant disclosure (F12-class via the link) plus a corrupt cross-tenant FK. The tenant-safe `findByCompanyIdAndUid` existed but was unused. | FIXED | `setPrice`/`removePrice` resolve the price list with `priceLists.findByCompanyIdAndUid(p.getCompanyId(), uid)` → a foreign list is `NotFound`; the cross-tenant link cannot be created. Regression `ProductServiceImplIT.setPrice_crossCompanyPriceList_*`. |
| F16 | Products | MEDIUM | **`removeBarcode`/`removeBulkPack` deleted a child resolved by global `findByUid` without checking it belongs to the URL's product** — scope-checked the parent but not the child. Cross-tenant not practical for non-root (parent must be own-company + child uid is an unguessable ULID); the real exposure is same-tenant cross-product deletion by a `PRODUCT.MANAGE` holder (an integrity/least-privilege defect; sibling `removePrice`/`removeComponent`/`removeBranch` already key the child by the parent). | FIXED | Added `findByUidAndProductId(uid, productId)` finders; both removes resolve the child scoped to the parent → a mismatch is `NotFound`. Regression `ProductServiceImplIT.removeBarcode_otherProductsBarcode_*` / `removeBulkPack_*`. |

### Sales module security review (2026-06-07)

The Sales-module review found **no Critical/High/Medium cross-tenant, IDOR, or auth-bypass vulnerability** — it correctly mirrors the patched F15 (refs via `findByCompanyIdAndUid`) and F16 (children via `findByUidAndInvoiceId`) patterns, guards every read path with `assertCanActIn`, takes `branch_id` from the validated `RequestContext`, enforces lifecycle immutability + paid-in-full server-side, and matches every `@PreAuthorize` code to a V5-seeded permission. The items below are correctness / completeness fixes raised by the review (none were exploitable vulnerabilities).

| # | Module | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| S1 | Sales | LOW (correctness) | **Business-rule state rejections returned HTTP 500** — `finalise` of an unpaid/empty invoice, mutating a finalised invoice, voiding a non-finalised invoice throw `IllegalStateException`, which was unmapped → generic 500 "An unexpected error occurred" (confirmed in browser-verify). No security leak (handler doesn't echo internals; thrown after guards), but a poor/misleading status. | FIXED | Added `@ExceptionHandler(IllegalStateException.class)` → **409 Conflict** with the rule message in `GlobalExceptionHandler`. Existing service ITs (which assert `IllegalStateException`) still pass. |
| S2 | Sales | LOW (audit gap) | **`removePayment` wrote no audit row**, unlike every sibling mutator (draft-only, own-company op — completeness gap, not a boundary failure). | FIXED | Added `SALES.INVOICE.PAYMENT.REMOVE` audit action; `removePayment` emits it (target_type `sales_invoice_payments`). Regression `SalesInvoiceServiceImplIT.removePayment_writesAuditRow`. |
| S3 | Sales | LOW (consistency) | **Auto-default agent skipped the active-status check** — an ARCHIVED internal agent could be silently auto-attached on create (same company + user, so no boundary crossed; BR-PARTY-10 consistency nit). | FIXED | `AgentRepository.findInternalAgentIdByCompanyAndUser` now filters `status = ACTIVE`, so an archived internal agent no longer auto-resolves → caller falls back to the BR-SALES-06 explicit-agent rejection. (Internal-agent lifecycle already covered by `AgentServiceImplIT`.) |
| S4 | Sales | INFORMATIONAL | `SALES.INVOICE.OVERRIDE` permission is seeded and `overrideLinePrice`/`updateLine` service methods exist but have **no wired controller endpoint** (dead path, not exploitable). If exposed later, the endpoint MUST carry `@perm.scoped(#uid,'invoice','SALES.INVOICE.OVERRIDE')` — `overrideLinePrice` sets an unbounded `unitPriceAmount`. | NOTED | No action now; flagged for whoever wires the override endpoint. |
| — | IAM | (pre-existing, out of scope) | `ScopeGuard` enforces tenant isolation at **company** granularity, so a `*.VIEW` holder can read other-branch documents within their own company. Platform-wide design shared by all modules, not introduced by Sales. If branch-level read isolation on financial documents is wanted, it's an ADR-level decision. | OPEN (design) | Carried for solutions-architect consideration; not a Sales finding. |

### Slice 6 LOW (post-ship hardening, not blocking)
- ArchUnit append-only rule fences the **package**, not the verb — a future method inside `platform.audit` could call `delete*`. Add a no-mutator assertion or a read-only repo interface.
- `usernameAttempted` (unauthenticated `LOGIN.FAIL` path) is written to `audit_log.detail` without a length cap — cheap pre-auth storage amplification. Truncate at the auth boundary.
- `ROOT.BYPASS` detail records raw numeric company ids, not uids (inconsistent with the uid wire convention). Cosmetic.

### Tenant-isolation e2e audit (2026-06-26)

An empirical end-to-end probe (two real tenants, every uid/id-addressed endpoint exercised cross-company) found **28 confirmed cross-company leaks across 11 modules** — all FIXED this pass. The leaks fell into two bug classes, both generalisations of F12–F16:

- **(a) Confused-deputy via a secondary identifier.** An endpoint scope-checks a **caller-supplied `companyId`** (the attacker passes their *own* company, so the check passes) and then loads the actual data by a **separate, unverified numeric id/uid** (`accountId`, `productId`, `glAccountId`, …). The scoped parameter and the loaded entity are different objects, so the guard never constrains the data returned.
- **(b) Missing write-scope.** uid-addressed **mutators** loaded the target by uid without re-checking the loaded entity's company — you could **write a record you could not read**. Worst case (IAM): user **update / disable / enable / unlock / setPassword** were exploitable cross-tenant → **account takeover via cross-tenant password reset**. Also fixed: cashbank confused-deputy reads, and `/companies` enumeration (see below).

**Authoritative rule (now enforced):** derive the scope-company from the **LOADED entity**, never from a caller-supplied parameter; apply the same guard to **reads AND writes**. Fixes are service-layer only — company-scoped repository finders (`findByCompanyIdAndUid` / scoped `findById…`) plus ownership re-checks on the loaded entity. The isolation **read oracle is unchanged** and there is **no schema change**.

| # | Module | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| F17 | IAM | BLOCKER | **Cross-tenant user mutation → account takeover.** `update`/`disable`/`enable`/`unlock`/`setPassword` loaded the target user by uid without re-checking the user's company (class (b)). A holder of the relevant `USER.*` permission in company A could reset another company's user's password and take over the account. | FIXED | Each mutator now re-checks the loaded user's company against the active company (root bypasses, audited); scoped finders replace the bare uid load. Regression covered by the 2026-06-26 audit IT suite. |
| F18 | cashbank + 9 others | HIGH | **Confused-deputy reads/writes across 11 modules (28 endpoints).** Endpoints scope-checked a caller-supplied `companyId` then loaded data by an unverified `accountId`/`productId`/`glAccountId`/etc. (class (a)); several uid mutators also missed the write-side re-check (class (b)). cashbank account reads were the canonical confused-deputy case. | FIXED | All 28 endpoints reworked to derive scope from the loaded entity via company-scoped finders + ownership re-checks; guards applied symmetrically to reads and writes. |
| F19 | IAM | HIGH | **`GET /api/v1/companies` enumerated all organisation companies** — any `COMPANY.VIEW` holder received every company's master data, regardless of membership. | FIXED | The list now applies the same **assigned-or-root** filter as `/companies/accessible` (root sees all; everyone else sees only companies they belong to). Company master data is no longer enumerable cross-tenant. |
| F20 | (cross-cutting) | (regression guard) | No build-time fence prevented a future `..service..` class from re-introducing a bare, unscoped `findById`/`getReferenceById` (the root cause of classes (a)/(b)). | FIXED | Added ArchUnit `FreezingArchRule` **`TenantScopingRulesTest`**: a `..service..` class making a bare `findById`/`getReferenceById` on a Spring Data repository **fails the build**, forcing company-scoped finders. The **~197 pre-existing audited calls are frozen as a baseline** (`allowStoreUpdate=false`), so the baseline cannot grow. |

## F21 — Reference-data picker reads hard-403 narrowly-scoped operational roles (role-grant read-closure gap)

**2026-06-28 — business-operations simulation (16 real Tembo-Group personas, non-root, non-ORG_ADMIN).**
Source: [`docs/simulation/ISSUES-REGISTER.md`](../simulation/ISSUES-REGISTER.md) ISSUE-001..006.

Operational/finance roles (hand-curated permission subsets) were blocked from core screens because a
**supporting reference-data read the screen fires on load returned 403 and blanked the whole page** —
the role held the screen's primary verb but not the supporting `*.VIEW` read-dependency. Not phantom
codes (all four are seeded) and not a tenant leak — a **role-grant vs screen-read-dependency closure
gap**. The DB schema/seed is frozen, so this is fixed in the **gate layer**, not by editing grants.

| # | Source | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| F21 | sim 2026-06-28 (ISSUE-001..006) | HIGH (blocker for the affected roles) | **Supporting picker reads (`GET /branches`, `GET /products`, `GET /wht/types`, `GET /purchase-orders`) 403'd narrowly-scoped operational/finance roles**, blanking Record-receipt/payment, supplier-bill, work-order/BOM, stock-transfer and POS screens. Root/ORG_ADMIN never hit it (they hold every code), so CI's gate-exists / code-seeded checks stayed green. | FIXED | Relaxed the **picker** gates to a within-tenant read floor, **without weakening isolation** (every list service keeps its own company-scope predicate; the branch gate keeps the company resolution in `scopedOrMember`): branch list → `@perm.scopedOrMember(#companyUid,'company','BRANCH.VIEW')`; product list → `@perm.hasOrMember('PRODUCT.VIEW')`; WHT-type list → `@perm.hasOrMember('WHT.VIEW')`. New `@perm` helpers back these (`PermissionChecks.hasOrMember` / `scopedOrMember`, `PermissionResolver.isMember` = caller holds ≥1 role in their active company). **PO list left transactional-gated** but broadened to `PURCHASE.ORDER.VIEW or AP.BILL.ENTER` so the supplier-bill three-way-match picker loads for bill-enterers without a blanket purchasing read. No migration; endpoints stay permission-gated (fail closed for a non-member / cross-company target). |

**Decision rationale.** Branch/product/WHT lists are low-sensitivity, company-scoped reference data
used as pickers by nearly every operational screen — a member of the company legitimately reads them.
"Member" = the caller resolves ≥1 role in their *own* active company; membership is computed from the
caller's own grants, and the company-scope predicate is unchanged (service `assertCanActIn`, or the
gate's company resolution for branches), so a member can only ever read **their own** company's data —
no cross-tenant widening, read-only, never a write. POs are transactional documents (amounts,
suppliers), so they were NOT opened to plain membership — only to the adjacent `AP.BILL.ENTER`
capability that the dependent screen's own primary verb already requires.

**Residual / follow-ups.**
- ISSUE-006 (PRODUCTION_OFFICER cannot view products *at all*, a core-job gap, not just a side-load):
  the `hasOrMember` floor unblocks the product picker, but whether PRODUCTION_OFFICER should also hold
  a product **create/manage** capability is a role-spec question for system-analyst — out of scope for
  this gate fix.
- ISSUE-008 (no test asserts a role's grants are closed over its screens' reference reads):
  `RolePermissionClosureTest` is a qa-engineer deliverable; this gate fix removes the runtime blocker
  but does not replace that guard.
- **Frontend:** the picker reads no longer hard-403 the affected roles, but defence-in-depth still
  wants the pickers to **degrade gracefully** on any future 403 (e.g. branch picker → user's default
  branch) rather than blanking a screen — flagged for frontend-engineer.

## F22 — Customer/supplier party-picker reads are correctly gated; the fix is role-composition (NOT a gate relaxation)

**2026-06-28 — business-operations simulation deep-run (`docs/simulation/run-2026-06-28/deep-run.json`).**
Same READ-CLOSURE family as F21, but the **opposite remediation** — recorded explicitly so the
distinction is on the audit trail (the "no silent fix" rule) and so a future reviewer does not
"complete" F21 by relaxing these two gates.

A CASHIER (role holds cash/receipt perms, not `CUSTOMER.VIEW`) opened Record-Receipt (which loads
after the F21 fix) but its **customer picker** hard-failed: `GET /api/v1/customers?companyId=3 → 403`
(`@perm.has('CUSTOMER.VIEW')`, `CustomerController.list`). The analogous supplier picker is
`SupplierController.list @perm.has('SUPPLIER.VIEW')`.

| # | Source | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| F22 | sim 2026-06-28 (deep-run) | HIGH (blocker for the affected roles) | **Customer/supplier party pickers 403 narrowly-scoped cash/AR/AP roles** because the role omits `CUSTOMER.VIEW` / `SUPPLIER.VIEW`. Surfaced on Record-Receipt (cashier) and supplier-bill (AP). Same read-closure shape as F21 (the codes are seeded; not a tenant leak). | ACCEPTED (gate correct) → ROLE-SPEC fix | **The gate is NOT changed.** Customer/supplier master data (names, TIN, VRN, mobile-money account numbers, credit limits, balances, addresses) is the **most sensitive party master on the platform** — more sensitive than the branch/product/WHT reference lists F21 opened to a member read-floor, and at least as sensitive as the POs F21 deliberately **left transactional-gated**. So `CUSTOMER.VIEW` / `SUPPLIER.VIEW` stay as the gate; the read-closure gap is closed by **role composition**: a cash/AR role MUST include `CUSTOMER.VIEW` and an AP role MUST include `SUPPLIER.VIEW` as a CORE grant. No code change, no migration. |

**Decision rationale — why (b) role-spec, not (a) a `hasOrMember` read-floor.**
F21 established a sensitivity gradient and acted on it: low-sensitivity ambient reference data
(branch/product/WHT) was opened to a within-tenant member read-floor, while transactional documents
(POs — "amounts, suppliers") were **not** opened to plain membership and only broadened to the
adjacent `AP.BILL.ENTER` verb. Customer/supplier master sits on the **sensitive** side of that line:
- It carries financial + tax + payment-instrument fields (TIN, VRN, mobile-money, credit limit,
  balance) — disclosure-grade data, not an ambient dropdown of codes/names.
- Relaxing to `@perm.hasOrMember` would let **any** role-holder in the company (a stock clerk, a POS
  cashier with no customer remit, a production officer) enumerate the entire customer/supplier master
  including those financials. That is a least-privilege regression, and it would contradict the exact
  boundary F21 drew by declining to open POs to plain membership.
- `CUSTOMER.VIEW` / `SUPPLIER.VIEW` are *select* permissions by design (seed description: "View and
  select customers/suppliers"). A role that records customer receipts but cannot view customers — or
  enters supplier bills but cannot view suppliers — is **mis-specified**: the picker is the core data
  of the task, not a supporting side-load. The gate is right; the role is incomplete.

Tenant isolation is unchanged either way: `CustomerServiceImpl.list` / `SupplierServiceImpl.list`
already call `scopeGuard.assertCanActIn(RequestContext.get(), companyId)` before querying (F12 fix),
so a holder reads only their own company's parties regardless of the gate form.

**Required action (deployment / sim onboarding — NOT a code change).**
- Grant **`CUSTOMER.VIEW`** to every cash/AR-facing role (cashier, AR clerk, receipts/credit roles).
- Grant **`SUPPLIER.VIEW`** to every AP-facing role (AP clerk, supplier-bill/payment roles).
- These are seeded permission codes (`R__seed_permissions.sql` lines for `CUSTOMER.VIEW` /
  `SUPPLIER.VIEW`); the fix is a role/provisioning grant, applied via the IAM admin UI or the
  onboarding/provisioning seed for the simulation personas — no migration, no gate edit.

**Follow-ups.**
- Same residual as F21/ISSUE-008: a `RolePermissionClosureTest` (qa-engineer) should assert that each
  role's grants are closed over the *core-data* reads of the screens it is meant to operate — this
  would have flagged a receipts role missing `CUSTOMER.VIEW` at build time, not in the sim.
- Frontend defence-in-depth (carried from F21): a party picker should degrade to a friendly,
  permission-aware empty state on a 403 rather than blanking the whole screen.

## F21/F22 follow-up — Read-closure manifest + CI guard (ADR-0047)

| # | Source | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| F21-FU | F21/F22 follow-up | CLOSED | **No build-time assertion that a screen's supporting reads are correctly gated and seeded** (ISSUE-008 residual). Root/ORG_ADMIN always bypassed; CI only checked a gate exists, not that its closure was declared and seeded. | FIXED | ADR-0047: `backend/src/test/resources/security/screen-read-closure.json` (manifest, 9 screens × supporting reads) + `RolePermissionClosureTest` (surefire, no DB) asserts gate-honesty + required-closure-seeded + no-phantom. Completes the four-link parity chain. |
| F22-FU | F21/F22 follow-up | CLOSED | **The F22 "grant CUSTOMER.VIEW / SUPPLIER.VIEW to cash/AR/AP roles" rule lived only in the findings memo** — no checked artefact kept it honest against future gate relaxation. | FIXED | ADR-0047 manifest pins `CUSTOMER.VIEW` (gate=`has`) on `ar.record-receipt` and `SUPPLIER.VIEW` (gate=`has`) on `ap.record-payment`/`ap.enter-supplier-bill`. If a reviewer relaxes either gate to `hasOrMember`, check (a) in `RolePermissionClosureTest` goes red — the manifest becomes the enforcement anchor. |

## F23 — Goods-receipt-create PO read-closure: a storekeeper could open the GR screen but not read the POs it receives against

**2026-06-28 — business-operations simulation (`docs/simulation/run-2026-06-28`, Saidi Karume / STOREKEEPER).**
Same F21/F22 read-closure family. Closed the WHOLE goods-receipt-create read-dependency in one pass
(the F22 lesson: a transactional screen's read-dependency peels one 403 layer at a time — fix them all
together so a re-run doesn't just hit the next read).

Enumerated closure of `/admin/goods-receipts/create` (storekeeper = PURCHASE.RECEIVE + the F21
within-company read-floor): `GET /organisations/current` (`isAuthenticated()`, OK), `GET
/companies/accessible` (`isAuthenticated()`, OK), `GET /purchase-orders` (**was 403**), `GET
/purchase-orders/uid/{uid}` (**was 403**), `GET /purchase-orders/uid/{uid}/lines` (**was 403**),
`POST /goods-receipts` (`PURCHASE.RECEIVE`, the write, already OK).

| # | Source | Severity | Finding | Status | Resolution |
|---|---|---|---|---|---|
| F23 | sim 2026-06-28 (STOREKEEPER) | HIGH (blocker for the storekeeper's core daily job) | **The GR-create screen loads under PURCHASE.RECEIVE but its three PO read-dependencies were gated only on PURCHASE.ORDER.VIEW / AP.BILL.ENTER** — the PO picker list (`@perm.has('PURCHASE.ORDER.VIEW') or @perm.has('AP.BILL.ENTER')`), the chosen PO header and its lines (`@perm.scoped(..,'purchaseorder','PURCHASE.ORDER.VIEW')`). A storekeeper holding PURCHASE.RECEIVE (route-guard + GR write, parity-correct + seeded) but not PURCHASE.ORDER.VIEW could open the screen and not read the POs to receive against — picker 403, blank page. Seeded codes, not a tenant leak: a role-grant vs screen-read-dependency closure gap. | FIXED | Gate-layer read-floor (no grant, no migration): list gate → `@perm.has('PURCHASE.ORDER.VIEW') or @perm.has('AP.BILL.ENTER') or @perm.has('PURCHASE.RECEIVE')`; PO header + lines reads → `@perm.scoped(#uid,'purchaseorder','PURCHASE.ORDER.VIEW') or @perm.scoped(#uid,'purchaseorder','PURCHASE.RECEIVE')`. Tenant isolation **unchanged**: `PurchaseOrderServiceImpl.list` keeps `assertCanActIn(ctx, companyId)`, and `getByUid`/`listLines` scope from the **loaded** PO's company; both scoped gate branches run `ScopeGuard.canActOn` against the loaded PO ('purchaseorder' target type → `purchaseOrders.findCompanyIdByUid`), so a PURCHASE.RECEIVE holder reads only their OWN company's POs. POs stay transactional-gated (NOT opened to plain membership) — broadened only to the adjacent PURCHASE.RECEIVE verb whose own GR write already requires it, exactly mirroring the AP.BILL.ENTER precedent. Manifest: added `purchases.goods-receipt-create` (accessPermission=PURCHASE.RECEIVE) to `screen-read-closure.json`, pinned by `RolePermissionClosureTest`. Test: `ReferenceDataReadClosureIT` adds a PURCHASE.RECEIVE-only storekeeper → 200 on own-company PO list, 403 cross-tenant; non-member still 403. |

**Decision rationale.** This is F21's PO branch extended, not F22's customer/supplier branch. POs are
transactional documents, so they are NOT opened to plain company membership; the gate is broadened only
to the **adjacent transactional verb** (PURCHASE.RECEIVE) whose dependent screen's own primary verb
already requires it — the identical reasoning that previously admitted AP.BILL.ENTER to the same list.
The read-floor is applied symmetrically to all three PO reads the screen fires (list + header + lines)
so the closure is complete and a re-run cannot peel to a next 403. No disclosure-grade gate was relaxed.

**Residual / follow-ups.**
- Frontend defence-in-depth (carried from F21/F22): the GR-create PO picker should degrade to a
  permission-aware empty state on any future 403 rather than blanking the screen — flagged for
  frontend-engineer.
- Whether STOREKEEPER should additionally hold a standalone PURCHASE.ORDER.VIEW as a role-spec matter
  (vs. relying on the read-floor) is a role-composition question for system-analyst — out of scope.

## F24 — Systemic error-message hygiene: user-facing validation messages leak internal references

Found by the daily-operations simulation (Bakari/the approval flow): submitting a below-threshold PO for
approval returns a 409 whose message — surfaced verbatim to the user in `ApiResponse.errors[]` — reads
*"PO does not require approval (below threshold or gate disabled, **ADR-0027 D-6**). Place it directly via
**/place**."* It leaks an internal ADR reference **and** an internal endpoint path to the end user.

A backend scan shows this is **systemic, not a one-off**: ~168 user-facing validation/exception messages
across **78 files** embed internal references — `BR-…`, `ADR-…`, `FR-…`, `D-n` rule codes (tax, budgeting,
costing, sales, cash/bank, GL, AR/AP, …), and some additionally concatenate ULIDs/`uid`s, permission codes,
or endpoint paths into the user message. This violates the **error-message-hygiene standing rule** (owner,
2026-06-22): user-facing errors must be friendly and expose **no** system/internal info (no ULIDs, field
names, BR-/ADR- codes, exception text); technical detail belongs in **logs/comments only**. Confirmed
reaching users (the live 409 returned the ADR code in `errors[]`). Log statements (`log.*`) and code
comments are exempt and stay as-is.

| ID | Source | Severity | Issue | Status | Fix |
|----|--------|----------|-------|--------|-----|
| F24 | sim 2026-06-28 (approvals) | MEDIUM (UX + information hygiene; no auth/tenant impact) | ~168 user-facing validation/exception messages across 78 files leak internal rule codes (BR-/ADR-/FR-/D-n), and some leak ULIDs / permission codes / endpoint paths, into `ApiResponse.errors[]`. The business meaning is fine; the internal tags are not user-safe. | IN PROGRESS (technical team) | Rewrite each **user-facing** message in plain, calm language that states the rule without the internal tag/ULID/perm-code/endpoint; keep the rule code in a `//` comment if useful (comments are not user-facing); leave `log.*` untouched; update any test asserting a removed substring. |

## Production-gating (carried, still OPEN)
- **G1** (Slice 2): stable RS256 signing key from a secret store — dev key is in-memory (everyone logged out on restart; not prod-safe).
- **G2** (Slice 2): access-token denylist on logout (access token currently valid until expiry after logout).
- **F11** (Slice 6): the `audit_log` no-UPDATE/DELETE grant (above) — pre-prod gate alongside G1/G2.

These block a production deploy; they do not block dev/QA. Track to the pre-prod hardening pass.
