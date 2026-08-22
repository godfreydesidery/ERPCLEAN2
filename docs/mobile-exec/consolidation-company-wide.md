## Company-wide consolidation

> **Supersedes** the earlier "Consolidated reporting" section, which assumed organisation-level (cross-company) roll-up and concluded the feature was architecturally blocked. That conclusion was correct for the scope it assumed and is now void.

---

### 1. What "consolidated" means here

**Consolidated means every branch of one company, added up.** One legal entity, one general ledger, one base currency, one chart of accounts, one fiscal calendar — so "consolidated revenue" is simply the company's revenue with the branch filter left off, not an accounting exercise.

Because there is only ever one ledger involved, **none of the hard consolidation problems apply**: no intercompany eliminations, no ownership percentages, no minority interest, no currency translation, no consolidation journal. The numbers the exec app shows are the same numbers the company's own financial statements show — there is no derived, reconciling or adjusted figure anywhere in the app.

**Cross-company (organisation-level) roll-up is out of scope.** Nothing in this plan should be built to accommodate it.

*If the owner later asks for cross-company:* treat it as a separate project with its own ADR, not an extension of this one. It needs, at minimum, a `parent_company_id` (or a consolidation-group table), intercompany markers on transactional documents so internal sales/purchases can be eliminated, ownership percentages, a group presentation currency with translation rates and a CTA account, and a company-set dimension in `ScopeGuard` — which today is a hard single-company boundary (`backend/src/main/java/com/erp/platform/security/ScopeGuard.java:675`, `return principal.root() || companyId.equals(principal.companyId());`). The right answer at that point is a group-reporting module that reads a *published* per-company snapshot, not a widening of the exec app's live queries. Do not pre-build hooks for it now; the per-company design below is not a stepping stone to it and should not be compromised in anticipation.

---

### 2. The verdict: can we do it today?

**Yes. A company-wide, all-branch read path exists today, it works for a non-root user, and for most report endpoints it is already the default behaviour.**

The earlier blocker was misread. `ScopeGuard.canActIn` is a **company** boundary and has **no branch dimension**: it takes `(Principal, Long companyId)` and nothing else, and no branch predicate appears anywhere in its decision logic (`ScopeGuard.java:648-676`). (`ScopeGuard` does import `BranchRepository`, but only to resolve a `branch` uid to its owning company id in `companyIdOf` — `ScopeGuard.java:519` — which is a company lookup, not a branch-scope check.)

There is no Hibernate tenant filter, no `@Where`, no `@FilterDef`, no blanket branch predicate anywhere in the backend — `grep -rnE "@Where|@FilterDef|@Filter\(|enableFilter|@TenantId" backend/src/main/java` returns **0 hits** (re-run and confirmed). Branch filtering is hand-written per query, and the overwhelming majority of report queries treat branch as an *optional narrowing filter* on top of a mandatory `company_id`.

Concretely: a non-root user holding `BI.VIEW` today calls `GET /api/v1/bi/dashboard?companyId=X` with no `branchId` and receives every branch consolidated, in one request, with no header, no new code and no schema change. `GET /api/v1/bi/sales-by-branch` already returns a per-branch league table for the whole company in a single query (`SalesInvoiceRepository.java:97-102`, `AND (:branchId IS NULL OR i.branchId = :branchId) … GROUP BY i.branchId`).

**One correction to how this is usually described.** "No `X-Branch-Uid` header" does **not** mean the principal has no branch. `JwtRequestContextFilter.resolvePrincipal` (`:183-243`) takes the `branchId` **claim from the JWT** — the login default branch — whenever the header is absent (`:191-197`); it only resolves-and-validates a branch when the header *is* present. So `RequestContext.get().branchId()` is essentially always non-null. Company-wide reads work not because the principal has no branch, but because **the report queries never consult `principal.branchId()`**. That distinction is the whole design constraint for §3: an exec endpoint must never read `principal.branchId()`.

**Nothing has to change to make company-wide reads possible.** What has to change is smaller and different in kind:

1. **Three endpoints silently return one branch's number where an executive will read a company number** (§4). This is the only genuine defect, and it is a trust defect, not an architecture one.
2. **Approvals are hard-gated on `user_branch`** and will silently show a partial inbox. This is fixed by provisioning (assign the executive to every branch), not by code.
3. **The company-wide behaviour arrived by absence of a check, not by a decision.** It is undocumented, so the next engineer "fixing the missing branch predicate" would break the exec app without knowing it. It must be ratified in an ADR.

---

### 3. The read-path design

#### Mechanism: a dedicated `/api/v1/exec/**` surface whose scope is *derived*, never supplied

Three mechanisms were weighed. Two are rejected outright:

- **Rejected — "a permission that widens the tenant predicate to company scope."** There is no predicate to widen. Building this would mean first introducing a blanket branch predicate across the repository layer and then adding an escape hatch to it — a multi-month refactor of a live system with a frozen schema, which would also break the 37 endpoints that are correctly company-wide today.
- **Rejected — a `scope=COMPANY` request parameter validated against a permission.** This is exactly the confused-deputy shape the standing rule forbids: a caller-supplied value decides the breadth of data returned. It also ships a *second* way to ask a question the API already answers (omit `branchId`), with a different gate, while the older ungated path keeps working.

**Adopted — Option (c): thin executive endpoints that take no scope input of any kind.**

- **How the request expresses "all branches": it doesn't.** There is no `branchId`, no `branchUid`, no `scope`, and no `companyId` parameter on the exec surface. The absence of a branch filter *is* the company-wide read, which is precisely how the 37 working endpoints already behave. `X-Branch-Uid` is irrelevant to 38 of the 40 reporting endpoints inventoried in §4 (the two exceptions are the project-costing pair, which read `principal.branchId()` — and which the header therefore *does* move).
- **How the server authorises it:** the company id comes from `RequestContext.get().companyId()` — minted from the JWT at login — following the pattern already proven at `SalesReportController.java:57` and `:76`, `StockReportController.java:55` and `:69`, and `ProductStockReportController.java:241`; *not* the `@RequestParam Long companyId` pattern of `BiDashboardController.java:68`. `scopeGuard.assertCanActIn(...)` is still called, as defence in depth, so the ADR-0062 foreign-tenant check and the root audit stay on the path (`ScopeGuard.java:708-726`).
- **Why this satisfies the confused-deputy rule.** The rule — *scope from the loaded entity, never a caller param* — governs the **target** of an operation. An aggregate report has no single target entity to load, so its scope must come from somewhere; the only trustworthy source is the authenticated principal. Options (a) and (b) both let the query string move the boundary. Option (c) **removes the parameter rather than validating it** — the only version of this that a second developer cannot accidentally reopen by adding "one more optional filter".
- **Cross-company leak check (the 28-leak audit).** Deriving `companyId` from the principal *narrows* rather than widens: the value can only ever be the caller's own active company, and `assertCanActIn` still runs the ADR-0062 `isForeignTenant` check first (`ScopeGuard.java:669-671`) before the `root ||` short-circuit. The exec surface therefore introduces **no new cross-company reach for anyone, including root** — it is strictly safer than the 89 controllers that accept `@RequestParam Long companyId`.

#### What happens for a user assigned to only some branches

**Ratified policy: an aggregate read returns the whole company, and the response says so.** The permission decides *what* you may read; `user_branch` decides *where you may act*.

This is what the code already does, and it is the right answer. The alternative — silently narrowing the roll-up to the executive's two assigned branches — produces a **wrong number with no indication**, which is strictly worse than a 403 and is the single fastest way to destroy trust in the app. The `ALL_BRANCHES_LABEL` / `BranchScope` shape at `DashboardServiceImpl.java:97`, `:578-588` already exists to state scope on the face of the response; **every exec DTO must carry that label**, and the mobile UI must render it in the report header. A number in this app always states its own scope.

Branch-level **drill-down** into a branch the executive is not assigned to is a separate, genuinely new capability, gated separately (§5).

#### How it is audited

Three layers, all existing:
- The audit aspect writes `audit_log` without the calling code participating (invariant 7). Append-only.
- `scopeGuard.assertCanActIn` keeps the root-bypass audit firing even though the company id came from the principal (`ScopeGuard.java:718-726`, `recordIndependent` / REQUIRES_NEW so it survives a `readOnly` query transaction) — a reason to call it rather than skip it as redundant.
- The response itself is self-describing: `scopeLabel = "All branches"` is part of the payload, so an exported PDF carries its own scope statement.

#### Java sketch

*(Illustrative. `ExecReportService`, `ExecDashboardDto`, `ExecScope` and the collaborator method names below do not exist yet; the annotations, bean names and `RequestContext` / `ScopeGuard` APIs are the real ones.)*

```java
// api/ExecDashboardController.java  — flat under com.erp.api, one per resource
@RestController
@RequestMapping("/api/v1/exec")
public class ExecDashboardController {

    private final ExecReportService exec;

    /**
     * Company-wide executive dashboard: every branch of the caller's company.
     *
     * NOTE — deliberately NO branchId/branchUid/companyId/scope parameter. The scope of this
     * endpoint is a property of the session, not of the request. Adding an optional filter here
     * would reintroduce the confused-deputy shape this surface exists to avoid.
     */
    @GetMapping("/dashboard")
    @PreAuthorize("@perm.has('EXEC.CONSOLIDATED.VIEW')")
    public ExecDashboardDto dashboard(@RequestParam LocalDate from,
                                      @RequestParam LocalDate to) {
        return exec.dashboard(from, to);
    }
}
```

```java
// modules/bi/service/ExecReportServiceImpl.java
@Override
@Transactional(readOnly = true)
public ExecDashboardDto dashboard(LocalDate from, LocalDate to) {
    final var principal = RequestContext.get();

    // Scope is DERIVED from the authenticated principal, never accepted from the caller.
    // NOTE: principal.branchId() is NOT null here (JwtRequestContextFilter:191-197 seeds it from
    // the JWT default-branch claim). It is deliberately never read on this path.
    final Long companyId = principal.companyId();
    if (companyId == null) {
        // No usable company on the session — a broken session, not "all companies".
        throw ForbiddenException.notPermitted();
    }

    // Defence in depth: keeps the ADR-0062 foreign-tenant check and the root audit on the path,
    // even though companyId did not come from the request.
    scopeGuard.assertCanActIn(principal, companyId);

    // branchId == null IS the company-wide read. No predicate is added; nothing is "widened".
    return new ExecDashboardDto(
            ExecScope.ALL_BRANCHES_LABEL,                       // the answer states its own scope
            dashboards.dashboard(companyId, null, from, to),
            incomeStatement.build(companyId, from, to),
            salesByBranch.sumByBranch(companyId, fromInstant, toInstant, null),
            arAgeing.forCompany(companyId),
            apAgeing.forCompany(companyId));
}
```

The only *predicate* changes anywhere are the two landmine fixes in §4 — this one, which turns a silently branch-scoped report into a company-wide one for holders of the exec code:

```java
// modules/projects/service/ProjectCostingQueryImpl.java:93-98
@Override
public List<ProjectWipRowDto> wipReport(Long companyId, RequestContext.Principal principal) {
    scopeGuard.assertCanActIn(principal, companyId);
    // ADR-0033 D-2 keeps projects branch-isolated for operators. An executive holding the
    // company-wide disclosure code reads the whole company; the repository already handles null
    // (ProjectCostingQueryRepository.java:103-104 appends `AND p.branch_id = ?` only if non-null).
    // `perm` is the real bean: PermissionChecks, @Component("perm"), com.erp.platform.security.
    final Long branchId = perm.has("EXEC.CONSOLIDATED.VIEW") ? null : principal.branchId();
    return costRepo.wipReport(companyId, branchId);   // + branch label on the DTO either way
}
```

and this one, a pure null-guard bug fix with no policy content:

```java
// modules/crm/repository/OpportunityRepository.java:34, :47 (JPQL) and :63 (native SQL)
- AND o.branchId = :branchId          //  :34, :47
+ AND (:branchId IS NULL OR o.branchId = :branchId)

- AND o.branch_id = :branchId         //  :63  — native query, snake_case column
+ AND (:branchId IS NULL OR o.branch_id = :branchId)
```

---

### 4. Endpoint inventory

#### 4a. The landmines — fix these before anything else ships

An executive reading a branch number as a company number is permanent trust damage. There are exactly **three**, and all three are silent — no error, no label, no parameter the caller could have set differently.

| Endpoint | What an executive sees | What it actually is | Where | Fix |
|---|---|---|---|---|
| `GET /api/v1/bi/crm-summary` **and the CRM panel of `/api/v1/bi/dashboard`** | **"We have no open opportunities."** Empty pipeline, zero forecast, zero win-rate, rendered as a legitimate empty state | Branch-**mandatory** with a null hole: `AND o.branchId = :branchId` with no null guard, so the default call (no `branchId`) matches nothing and every aggregate comes back empty/zero | `OpportunityRepository.java:34, :47, :63`; `PipelineQuery.java:40-42, 63-65, 79-83`; `BiDashboardController.java:104-106` | **3 one-line null guards.** Pattern already proven at `SalesInvoiceRepository.java:101`. No schema, no permission, no DTO change. **[code-only]** |
| `GET /api/v1/project-costing/wip` | "Group work-in-progress" | One branch's WIP — the caller's default branch. `ProjectWipRowDto` carries **no branch field at all**, and the response has no scope label | `ProjectCostingQueryImpl.java:97` — `costRepo.wipReport(companyId, principal.branchId())` | **1 line + a DTO label.** Repository already supports null (`ProjectCostingQueryRepository.java:83, :103-104`, whose javadoc says so explicitly). Needs the exec permission code to decide who gets null **[code-only + owner decision]** |
| `GET /api/v1/project-costing/projects/uid/{uid}/pnl` | A 403 they can't explain | Cross-branch drill-down denied outright by the service | `ProjectCostingQueryImpl.java:50` | Same gate as the row above; do both in one PR. Fails loudly, so it is a usability blocker rather than a wrong-number risk **[code-only + owner decision]** |

Two corrections to the earlier draft of this table, both material:

- **The CRM panel is not swallowed into a `null`.** `DashboardServiceImpl.safeCrm` (`:505-512`) returns `null` only when `buildCrm` **throws**. A null `branchId` bound against `o.branchId = :branchId` does not throw — it simply matches no rows. So the panel comes back **structurally valid and entirely zero**: empty stage list, zero forecast, zero win-rate. That is *worse* than a null panel, because a null panel degrades visibly while zeros read as a fact about the business. (Whether the *native* `kpiRaw` query at `:63` errors on a null bind rather than returning zero rows has not been executed against Postgres — **(UNVERIFIED at runtime)**; either outcome is a defect and the same null guard fixes both.)
- **`ProjectCostingController` gates the P&L endpoint with `@perm.scoped(#projectUid,'project','PROJECTS.COSTING.VIEW')`** (`ProjectCostingController.java:58-62`), which enforces *company* scope. The cross-branch 403 comes from the service at `:50`, not the annotation. Note also a latent NPE there: `!project.getBranchId().equals(principal.branchId())` dereferences a nullable column — worth fixing in the same PR.

The CRM one is the worst of the three, because "zero" reads as a fact about the business rather than a fault in the app.

#### 4b. Free wins — 37 of the 40 reporting endpoints inventoried are already company-wide

Ship as-is. No code, no migration, no permission change. This is the exec app's v1 content. (The earlier draft said "33 of 40"; the table below enumerates 37, and 37 + the 3 landmines = the 40 inventoried. The arithmetic, not the inventory, was wrong.)

| Group | Endpoints | Permission | Why it's already company-wide |
|---|---|---|---|
| **GL financial statements** | income-statement, balance-sheet, cash-flow, account-ledger **+ their 4 exports** (8) | `REPORT.PL.VIEW` / `REPORT.BS.VIEW` / `REPORT.CASHFLOW.VIEW` / `REPORT.LEDGER.VIEW` (+ `REPORT.EXPORT`) | **No branch parameter exists** (`ReportingController.java:52-155`). `AccountMovementQuery.java:57, :81, :107, :139` all filter `WHERE l.companyId = :companyId`. The token "branch" appears **zero times** in `ReportingServiceImpl.java`, `AccountMovementQuery.java`, `AccountLedgerQuery.java` (grep count = 0 in all three) |
| **BI finance & ops panels** | finance-summary, working-capital, inventory, revenue-trend, net-profit-trend (5) | `BI.FINANCE.VIEW` / `BI.OPS.VIEW` | No branch parameter (`BiDashboardController.java:79-125`). `StockValuationQuery.java:24, :61` says "across all branches" |
| **Per-branch league table** | `bi/sales-by-branch` (1) | `BI.FINANCE.VIEW` | `SalesInvoiceRepository.java:97-102` — correctly null-guarded, `GROUP BY i.branchId`. **This is the exec branch-comparison report, already built.** ⚠ Branch names are batch-resolved with **unscoped `branchRepository.findAllById(...)`** (`DashboardServiceImpl.java:439-441`), not `findByIdAndCompany_Id`; unmatched ids fall back to `"Unknown"`. This is safe *today* only because the ids come from an already company-scoped aggregate — it is **not** an intrinsic tenant guard. (`findByIdAndCompany_Id` is used at `:582`, for the dashboard *header* label, a different thing.) |
| **Dashboard + export** | `bi/dashboard`, `bi/dashboard/export` (2) | `BI.VIEW` (+ `BI.EXPORT`) | `branchId` optional; omit → `BranchScope(null, null, "All branches")` (`DashboardServiceImpl.java:578-581`) |
| **AR sub-ledger** | ar/statement, ar/ageing, ar/ageing/by-customer, ar/balance (4) | `AR.STATEMENT.VIEW` (first three) / `AR.VIEW` (balance) | Zero `branch` tokens in `ArAgeingQuery.java` (grep count = 0) |
| **AP sub-ledger** | ap/statement/balance, /ageing, /reconciliation (3) | `AP.VIEW` | Zero `branch` tokens in `ApAgeingQuery.java`, `ApReconciliationQuery.java` (grep count = 0 in both) |
| **Stock & sales registers** | reports/sales, stock/report, stock/reports/product-list, stock/reports/stock-value, reports/stock-movement **+ their 5 exports** (10) | `SALES.INVOICE.VIEW` (sales) / `INVENTORY.VALUATION.VIEW` (the rest) (+ `REPORT.EXPORT`) | `branchUid` optional; company-wide when omitted, by explicit design — `StockReportController.java:45-49`: *"Left optional rather than defaulting to the caller's branch: this register is used both at head office (where company-wide is the point) and at a counter."* Exports state it: `ProductStockReportController.java:181-186` prints **`All branches (whole company)`**; `StockReportController.java:102` prints `dto.branchLabel()`, which is `All branches` when unfiltered |
| **Other** | manufacturing/wip-reconciliation, budgeting/variance, budgeting/departmental-actuals, costing/reports/sliced-trial-balance (4) | `MANUFACTURING.VIEW` / `BUDGETING.REPORT.VIEW` / `COSTING.VIEW`+`GL.VIEW` | Company + fiscal-year / dimension only; no branch term |

All 20 distinct permission codes across §4a + §4b were grepped individually against `R__seed_permissions.sql` — **every one is seeded. No phantom codes in the reporting surface.**

#### 4c. Known inconsistency to hand the mobile team as a constraint

Two conventions coexist and the Flutter client must wrap both once: **BI / reports / AR / AP / budgeting / costing take `companyId` as an explicit `@RequestParam`** (and `BiDashboardController.java:40-42` states that as a deliberate defence-in-depth choice); the **stock and sales registers read it from `RequestContext`** (`SalesReportController.java:57, :76`; `StockReportController.java:55, :69`; `ProductStockReportController.java:241`). The new `/exec/**` surface uses the second convention exclusively.

There is also a live asymmetry worth naming to the owner: a branch manager **cannot** filter Stock Value to a sister branch (`ProductStockReportQuery.java:390-415` enforces a live `user_branch` row), but **can** read that sister branch's total sales off `/bi/sales-by-branch`, and can read company-wide revenue, margin, AR/AP and cash off `/bi/dashboard` — because `R__seed_permissions.sql:700-702` grants `BRANCH_MANAGER` exactly `BI.VIEW`, `BI.OPS.VIEW`, `BI.FINANCE.VIEW` (verified line-for-line). **This is a pre-existing exposure, not something the exec app introduces**, and it is a separate owner decision (§6, W6). Read the other way, it is the proof that the exec app's read requirement is already satisfied.

---

### 5. Permissions

#### 5a. Already seeded — everything the exec app reads is reachable today

Each of the following was grepped individually against `R__seed_permissions.sql`; **all are present** (the catalogue holds 250 distinct codes in total):

`BI.VIEW` · `BI.FINANCE.VIEW` · `BI.OPS.VIEW` · `BI.CRM.VIEW` · `BI.EXPORT` · `REPORT.VIEW` · `REPORT.PL.VIEW` · `REPORT.BS.VIEW` · `REPORT.CASHFLOW.VIEW` · `REPORT.LEDGER.VIEW` · `REPORT.EXPORT` · `GL.VIEW` · `AR.VIEW` · `AR.STATEMENT.VIEW` · `AP.VIEW` · `CASH.VIEW` · `VAT.VIEW` · `FA.VIEW` · `FX.EXPOSURE.VIEW` · `COSTING.VIEW` · `BUDGETING.REPORT.VIEW` · `STOCK.VIEW` · `INVENTORY.VALUATION.VIEW` · `INVENTORY.EXPIRY.VIEW` · `SALES.INVOICE.VIEW` · `SALES.ORDER.VIEW` · `POS.SESSION.VIEW` · `POS.EXPENSE.VIEW` · `PURCHASE.ORDER.VIEW` · `PURCHASE.REQUISITION.VIEW` · `CRM.PIPELINE.VIEW` · `CRM.OPPORTUNITY.VIEW` · `MANUFACTURING.VIEW` · `PROJECTS.COSTING.VIEW` · `HR.PAYROLL.VIEW` · `APPROVALS.DECIDE` · `APPROVALS.REQUEST.VIEW` · `APPROVALS.POLICY.VIEW` · `BRANCH.VIEW` · `COMPANY.VIEW` · `DOCUMENT.VIEW` · `DOCUMENT.RENDER` · `NOTIFICATION.VIEW` · `NOTIFICATION.PREFERENCE.MANAGE` · plus the master-data reads (`CUSTOMER.VIEW`, `SUPPLIER.VIEW`, `PRODUCT.VIEW`, `UOM.VIEW`, `CURRENCY.VIEW`).

**Export needs no new code** — `REPORT.EXPORT` + `BI.EXPORT` cover it, and `BiDashboardController.java:147` requires the conjunction (`@perm.has('BI.VIEW') and @perm.has('BI.EXPORT')`) so an export can never outrun the read. Every export endpoint in `ReportingController`, `SalesReportController`, `StockReportController` and `ProductStockReportController` follows the same conjunction pattern.

#### 5b. Must add — two codes, both deliberate

```sql
    ('EXEC.CONSOLIDATED.VIEW', 'bi',
     'Open the executive consolidated view: company-wide roll-up across every branch, including branches the user is not assigned to'),
    ('EXEC.BRANCH.DRILL', 'bi',
     'Drill a company-wide executive report into any single branch of the company, including branches the user is not assigned to'),
```

Neither code exists today (`grep "EXEC\." R__seed_permissions.sql` → 0 hits), so both are genuinely new.

- **`EXEC.CONSOLIDATED.VIEW`** is the single company-wide-disclosure lever. It gates the whole `/api/v1/exec/**` surface and is the flag that flips project-costing WIP/P&L to company scope. **Do not reuse `BI.VIEW`**: it is already in operational bundles including `BRANCH_MANAGER` (`:700`), so reusing it silently grants company-wide disclosure to people the owner never decided to grant it to — and it forecloses ever narrowing `BI.VIEW` later without breaking the exec app.
- **`EXEC.BRANCH.DRILL`** is the one code that genuinely *increases reach*. It is what lets an executive filter a report to a branch they have no live `user_branch` row for, defeating `ProductStockReportQuery.assertMayReadBranch` (`:390-415`; note it already exempts root at `:394-396`, which is exactly why root testing proves nothing here). **This is an explicit owner decision** — it is not privilege escalation under ADR-0059, but it is a real horizontal widening and must be presented as such. If the owner says no, the app simply offers no branch drill-down and everything else still works. Ship v1 without it if the answer isn't immediate.

**Do not put either code in `module = 'platform'`** — non-platform codes auto-flow to `ORG_ADMIN` via the `CROSS JOIN … WHERE r.code = 'ORG_ADMIN' AND p.module <> 'platform'` at `R__seed_permissions.sql:290-296`, which is correct and intended here (ORG_ADMIN already holds every read); `platform` is the partition ADR-0062 R-2 fenced. Note that CROSS JOIN is `ON CONFLICT DO NOTHING`, so the flow is one-way and permanent.

#### 5c. Proposed `EXECUTIVE` role bundle

No existing bundle fits, and the two candidates fail in opposite directions.

**`FINANCE_DIRECTOR`** (83 grants, `:813-895`; the section comment at `:812` says 83 and the count is exactly 83) has nearly the right read set but carries `GL.POST`, `AR.WRITEOFF`, `AP.PAYMENT.RUN`, `GL.PERIOD.CLOSE`, `GL.YEAR.CLOSE`, `VAT.RETURN.FILE`, `FX.REVALUE`, `COMPANY.CURRENCY.CHANGE`, `APPROVALS.ADMIN` — **all nine confirmed present** — irreversible authority an owner on a phone must not hold. It also lacks `MANUFACTURING.VIEW`, `PROJECTS.COSTING.VIEW` and `HR.PAYROLL.VIEW` (**all three confirmed absent**).

**`BRANCH_MANAGER`** (`:658-718`; the section comment at `:657` says "54 perms" but the file actually contains **56** grant rows — the comment is stale) has the right breadth but is stuffed with operational overrides: `SALES.INVOICE.VOID` (`:662`), `SALES.BELOW_COST.OVERRIDE` (`:666`), `POS.SALE.VOID` (`:673`), `STOCK.ADJUST` (`:682`), `STOCK.IMPORT` (`:683`), `STOCK.COUNT.POST` (`:685`) — all confirmed. ADR-0057 D-2's own logic applies again: *operate ≠ oversee*.

```sql
  ('0000000000000000000000000D', 'EXECUTIVE', 'Executive',
   'Company-wide read-only oversight across every branch, plus the approvals inbox. No posting, no overrides, no administration.', true),
```

`0D` is confirmed the next free synthetic uid — the existing twelve bundles run `01`…`0C` (`R__seed_permissions.sql:309-321`).

Grants: the entire §5a list, **plus `EXEC.CONSOLIDATED.VIEW`** (and `EXEC.BRANCH.DRILL` if approved), strictly read + decide.

Deliberately **excluded**: `APPROVALS.ADMIN` (recall/cancel any request — a write dressed as oversight), `HR.EMPLOYEE.PAYEE.VIEW` (bank/mobile-money detail, ADR-0040 D-11), every `*.MANAGE` / `*.POST` / `*.OVERRIDE` / `*.CLOSE`, and all five reserved codes (`USER.MANAGE`, `USER.COMPANY.MANAGE`, `ROLE.MANAGE`, `ROLE.ADMIN`, `BRANCH.ASSIGN` — `AuthorityCeiling.java:49-54`, verified verbatim). **Never add `BRANCH.ASSIGN`** as a convenience for self-service branch assignment: its own inline comment is *"branch-scope weaponisation"*, and adding it would convert a read role into a scope-granting role.

Because the bundle contains no reserved code and `AuthorityCeiling.assertCanConfer` (`:85-99`) enforces the subset rule before the reserved floor, **granting `EXECUTIVE` creates no escalation path**: a holder without `ROLE.MANAGE` cannot reach the conferral boundary at all.

Three structural traps to put in the provisioning runbook:
- **ADR-0057 D-4 is an additive floor.** The bundle-grant INSERT ends `ON CONFLICT DO NOTHING` (`R__seed_permissions.sql:922`), so the bundle will never *tighten* on the client's durable DB once shipped. Get the list right before the first migrate; removing a code later needs an explicit one-off revoke.
- **The `EXECUTIVE` grant must be company-wide (`user_role.branch_id IS NULL`).** `UserRoleRepository.resolvePermissionCodes` (`:20-33`) filters `AND (ur.branchId IS NULL OR ur.branchId = :branchId)` — pin the grant to a branch and the executive's reports evaporate the moment they switch branch. This presents as "the app worked Monday and is empty Tuesday" and will cost a day.
- **`DefaultRoleBundlesSeededTest` will not notice the new bundle unless you add it.** `EXPECTED_ROLES` is a hard-coded list of the twelve existing bundles (`:48-51`) and `ANCHORS` a hard-coded map (`:54-70`). Assertions 1 (has a `roles` row), 2 (non-empty) and 4 (signature anchor) iterate `EXPECTED_ROLES`/`ANCHORS` and would **silently skip `EXECUTIVE`**. Assertions 3 (no phantom code) and 5 (no reserved code) iterate every role found in the file and *do* cover it automatically. **W2 must therefore also edit the test** — add `"EXECUTIVE"` to `EXPECTED_ROLES` and an anchor entry such as `Map.entry("EXECUTIVE", "EXEC.CONSOLIDATED.VIEW")`.

#### 5d. Phantom-code risk: mechanically covered, but verify on the estate

`EndpointAuthorizationTest.everyApiHandlerIsPermissionGatedOrExplicitlyPublic` (`:38`) fails the build on an ungated handler, and `PermissionCodesSeededTest.allPreAuthorizeCodesAreDefinedInSeed` (`:57`) fails on a `@PreAuthorize` code not in the seed. `DefaultRoleBundlesSeededTest.allDefaultRoleBundlesAreSeededConsistently` (`:73-154`) covers the bundle, subject to the `EXPECTED_ROLES` caveat above. All three are surefire — the PR gate — so a phantom code cannot ship.

**But they are static file analysis.** They prove the code is in the seed *file*, not that the seed ran on the client's populated DB. After deploy, verify on the estate:

```sql
SELECT code FROM permissions WHERE code LIKE 'EXEC%';
SELECT count(*) FROM role_permission rp JOIN roles r ON r.id = rp.role_id WHERE r.code = 'EXECUTIVE';
```

#### 5e. The non-root testing rule — non-negotiable

Every gate this design depends on short-circuits for root, verified individually:

`PermissionResolver.hasPermission` returns `true` immediately for root (`:106-122`). `isMember` returns `true` for root (`:91-97`). `AuthorityCeiling.assertCanConfer` returns immediately for root (`:85-88`). `ScopeGuard.canActOn` short-circuits for root (`:684-703`). `StepApproverResolver.canDecide` has `&& !principal.root()` on its branch check (`:66`). `JwtRequestContextFilter` skips the `user_branch` verification for root (`:229-234`). `ProductStockReportQuery.assertMayReadBranch` returns early for root (`:394-396`).

**Root passes every single gate this design depends on** — a root smoke test would pass identically whether the `EXECUTIVE` bundle exists, is empty, or was never seeded.

The fixture, built as root and then abandoned: a non-root `AppUser` (`is_root = false`, `status = ACTIVE`, `organisation_id` stamped), a `user_company` membership row (ADR-0046 — grant/assign require prior membership), a `user_role` grant of `EXECUTIVE` with **`branch_id = NULL`**, and `user_branch` rows for **only 2 of the company's branches**, one `is_default`.

The assertions that matter:
1. `/auth/my-branches` (`AuthController.java:61-64`) returns **2** — confirms the fixture is genuinely partial.
2. `GET /api/v1/exec/dashboard` (no branch param) → `scopeLabel == "All branches"` and the branch league table has **a row per branch of the company, not 2**; total cross-checked against the sum of per-branch queries run as root. **This is the single most important test in the suite** — it is what proves per-company consolidation works for a non-root user.
3. `GET /api/v1/stock/reports/stock-value?branchUid=<an unassigned branch>` → **403** with the literal from `ProductStockReportQuery.branchNotAssigned()` (`:429-433`: *"You are not assigned to that branch. Choose a branch you work in, or clear the branch filter to see the whole company."*) without `EXEC.BRANCH.DRILL`; **200** with it.
4. Approvals inbox with pending requests in 4 branches (2 assigned, 2 not) → **2, not 4**, and silent about the missing 2 (`ApprovalRequestStepRepository.java:72-91`, `AND ub.userId IS NOT NULL`). Run the same call as root for contrast — and note root will *also* see only 2, because unlike `canDecide` the inbox query has **no root exemption**.
5. Ceiling: as the executive, granting `EXECUTIVE` to another user → **403** (no `ROLE.MANAGE`).

---

### 6. Backend work items

Latest applied migration is **V104** (`V104__multitenancy_constrain.sql`; the directory holds V1–V104 plus the single `R__seed_permissions.sql`), so **the next free version is V105.** The seed work below is an `R__seed_permissions.sql` edit, not a `V<n>` migration — it is the one file the frozen-migration rule permits editing, because it upserts and self-heals — but it still requires **explicit owner approval** under the standing rule, and it re-runs against the client's populated tables on every migrate, so it must be booted against a restored customer copy first (the 2026-08-15 trigger self-match rule).

**Sequenced:**

| # | Item | Kind | Notes |
|---|---|---|---|
| **W0** | **Provisioning: assign each executive a `user_branch` row for every branch of their company**, one flagged `is_default` | **[no code, no schema]** | Unblocks the approvals inbox *today*. Reads are unaffected (nothing on the reporting read path consults `user_branch`); writes are unaffected (an executive posts nothing). Also fixes `/auth/my-branches` (`AuthController.java:61-64`), which would otherwise show 2 entries while the reports show every branch. Rows must be `active = true`, `revoked_at IS NULL` — `assertMayReadBranch` (`:405-410`) and the filter's switch check (`:229-231`) both require a *live* assignment. Requires `BRANCH.ASSIGN` — root/ORG_ADMIN only, which is the correct control |
| **W1** | **CRM null-guard fix** — 3 edits in `OpportunityRepository.java` (`:34`, `:47` JPQL; `:63` native SQL) | **[code-only]** | Highest value/effort ratio in the whole plan. Fixes a panel that today reports zeros as fact. Do it first, independent of everything else. Add a non-root test that calls `/bi/crm-summary` with no `branchId` and asserts a non-empty pipeline |
| **W2** | **Seed: `EXEC.CONSOLIDATED.VIEW`, `EXEC.BRANCH.DRILL`, and the `EXECUTIVE` bundle** in `R__seed_permissions.sql`, **plus adding `"EXECUTIVE"` to `DefaultRoleBundlesSeededTest.EXPECTED_ROLES` (`:48-51`) and `ANCHORS` (`:54-70`)** | **[schema — needs owner approval]** *(repeatable seed, no `V<n>`)* | Present the exact code list and bundle grants for approval. Additive floor (`ON CONFLICT DO NOTHING`, `:922`): irreversible in practice. Without the test edit, three of the five bundle assertions silently skip the new role |
| **W3** | **`/api/v1/exec/**` BFF surface** — thin aggregators over existing services; scope derived from `RequestContext.companyId()`, `principal.branchId()` never read; every DTO carries `scopeLabel` | **[code-only]** | ~4-6 endpoints (dashboard, financials, branch league table, receivables/payables, stock, approvals feed). No new queries — this is composition, not computation |
| **W4** | **Project-costing landmine fix** — `ProjectCostingQueryImpl.java:97` and `:50`, gated on `EXEC.CONSOLIDATED.VIEW` via the `perm` bean (`PermissionChecks`, `@Component("perm")`); add a branch label to `ProjectWipRowDto` (which today has no branch field at all); fix the latent NPE at `:50` on a null `project.getBranchId()` | **[code-only]** | One PR for all of it. Amends ADR-0033 D-2 with an exec exemption — note it in the ADR rather than silently diverging |
| **W5** | **ADR: "Branch scope in reads"** — ratify that aggregate reads are company-wide by permission, and `user_branch` governs acting, not reading | **[docs]** | Cheap, and the thing that stops a future engineer "fixing" the missing branch predicate and breaking the exec app. Must also document: (a) the `EXECUTIVE`-must-be-`branch_id NULL` trap; (b) that `principal.branchId()` is *always* populated from the JWT, so "no header" never means "all branches" at the principal level |
| **W6** | **Owner decision: `BRANCH_MANAGER` → `BI.VIEW` / `BI.OPS.VIEW` / `BI.FINANCE.VIEW`** (`R__seed_permissions.sql:700-702`) | **[schema — needs owner approval]** | Pre-existing over-disclosure, not introduced here. A branch manager currently reads company-wide revenue, margin, AR/AP, cash, and a full branch league table. Decoupled from the exec app now that `EXEC.CONSOLIDATED.VIEW` exists. Note the additive-floor problem cuts the other way here: revoking needs an explicit `DELETE FROM role_permission`, which the repeatable seed cannot express |
| **W7** | **Deferred: harden the three sibling reports** — add `assertMayReadBranch` (copy `ProductStockReportQuery.java:390-415`) to `SalesReportQuery` (`:56, :72`), `StockReportQuery` (`:67, :73`), `StockMovementReportQuery`. Confirmed: none of the three checks `user_branch` today | **[code-only]** | **Schedule after the exec permission model is settled, never before.** It narrows drill-down and will 403 executives; it must bypass on `EXEC.BRANCH.DRILL`, and it must be tested non-root or the change is invisible |
| **W8** | **Optional / later: P&L by branch** | **[code-only]** — **no migration needed** | `journal_lines.branch_id` exists, is nullable (`V10__general_ledger.sql:225`), is mapped (`JournalLine.java:26-27`) and is set on every debit/credit factory (`:154`, `:206`), and **no reporting query selects or groups by it**. ⚠ **Correction to the earlier draft: the composite index already exists** — `CREATE INDEX ix_journal_lines_company_branch ON journal_lines (company_id, branch_id);` at `V10__general_ledger.sql:330-331`. **No V105 index migration is required.** Before building, still run a data check on the live estate for how many posted lines actually carry a non-null `branch_id` |

**Approvals — the one remaining structural gap.** `approval_requests.branch_id` is `BIGINT NOT NULL` (`V18__approvals_engine.sql:73`), and both the inbox query (`ApprovalRequestStepRepository.java:72-91`, `AND ub.userId IS NOT NULL`) and `canDecide` (`StepApproverResolver.java:62-68`) hard-gate on `user_branch`. **W0 solves this with data and zero code, and that is the recommended answer.** If the owner later wants assignment-free company-wide approvals, add a read-only `/api/v1/exec/approvals` *visibility* feed gated on `APPROVALS.REQUEST.VIEW` (company-scoped) while leaving the *decide* path's branch gate untouched — cleanest separation of "see" from "act", and it leaves ADR-0022 D-8 intact.

Two asymmetries in that pair worth their own issue: the inbox query does **not** exempt root while `canDecide` does (`:66`), so **root can decide a request it cannot see**; and `canDecide` looks the assignment up with `findByUserIdAndBranchId` (`:63-65`), which filters neither `revokedAt` nor `active`, whereas the same check elsewhere does — a revoked assignment still permits a decision.

**Delivery-checklist item that is not code:** the inbox is also gated on `s.approverRoleCode IN :roleCodes`, resolved from the caller's `user_role` grants (`StepApproverResolver.resolveRoleCodes:87-94`). Approval **policy steps name role codes**, so shipping the `EXECUTIVE` role changes nothing in the inbox until the client's approval policies actually name `EXECUTIVE` on the steps executives should see. `APPROVALS.DECIDE` is necessary but not sufficient. If this is missed, the approvals half of the app ships empty and looks broken.

---

### 7. What this changes about the plan

**Assumptions now void — delete them from the plan:**
- ~~"Consolidation is architecturally blocked by `ScopeGuard.canActIn`."~~ That line (`ScopeGuard.java:675`) is a **company** boundary. It was never in the way of per-company consolidation.
- ~~"We need intercompany markers, `parent_company_id`, ownership percentages, elimination entries."~~ Out of scope. Zero work.
- ~~"We need currency translation and a group presentation currency."~~ One company, one base currency. Zero work.
- ~~"We need a new blanket branch predicate with an escape hatch."~~ There is no blanket branch predicate to escape (0 hits for `@Where`/`@FilterDef`/`@Filter(`/`enableFilter`/`@TenantId`). Building one would be a multi-month refactor that *breaks* the 37 endpoints that are correct today.
- ~~"A schema change is required."~~ No `V<n>` migration is needed for the feature at all — including for W8, whose index already exists. The only schema-class change is the `R__seed_permissions.sql` edit.
- ~~"Executives need to be root."~~ The opposite: root proves nothing (seven separate short-circuits, §5e), and all acceptance testing must be non-root.

**Reports that get simpler:**
- The **entire financial statement pack** (P&L, Balance Sheet, Cash Flow, Account Ledger) is a straight pass-through. It has no branch dimension at all — there is nothing to consolidate, sum, or reconcile.
- **AR and AP ageing** are pass-throughs for the same reason.
- **Branch comparison** — the report an owner actually wants — already exists as `/bi/sales-by-branch` and is correct and deliberate. (Its branch-name lookup is unscoped, which is safe today but should be tightened to `findByIdAndCompany_Id` opportunistically.)
- **Stock and sales registers** already default to whole-company; the exec app just doesn't send a branch.
- The mobile client needs **no consolidation logic whatsoever** — no summing across responses, no FX maths, no reconciliation. It renders what the API returns.

**What the owner no longer has to worry about:**
- Group accounting policy, elimination rules, ownership structures, or a group chart of accounts.
- Whether the mobile numbers tie to the statutory accounts — they *are* the statutory accounts, from the same queries.
- Any database schema change to the live system for the read path.

**What the owner does still have to decide — three items, all small:**
1. **Approve the `EXECUTIVE` bundle and `EXEC.CONSOLIDATED.VIEW`** (W2) — this is the only approval blocking the build.
2. **May executives drill into branches they are not assigned to?** (`EXEC.BRANCH.DRILL`, W7). A "no" costs nothing; v1 ships without drill-down.
3. **Should `BRANCH_MANAGER` keep company-wide `BI.*`?** (W6) — pre-existing, now decoupled from this work.

Total backend cost to a working v1: **one seed edit (plus its test-fixture edit), one three-line bug fix, one thin BFF surface, one branch-assignment provisioning step, and one approval-policy configuration task at the client.** No `ScopeGuard` surgery, no schema migration, no consolidation engine.

---

### Verification notes

**Corrected — factual errors found against the repo:**

1. **W8's proposed V105 index already exists.** `CREATE INDEX ix_journal_lines_company_branch ON journal_lines (company_id, branch_id);` is at `backend/src/main/resources/db/migration/V10__general_ledger.sql:330-331`. The draft called the column "unindexed" and proposed a `CREATE INDEX CONCURRENTLY` migration. Removed; W8 is now code-only. (`branch_id` nullable at `V10:225` ✓; no reporting query selects/groups by it ✓.)
2. **`StockReportController.java:238-243` and `:248` do not exist** — the file is 171 lines. The quoted "Left optional rather than defaulting to the caller's branch" comment is at `:45-49`; `RequestContext.get().companyId()` is at `:55` and `:69`. Corrected throughout.
3. **`SalesReportController.java:59` → `:57`** (and `:76` for the export). Corrected.
4. **The sales-by-branch tenant-hardening claim was attributed to the wrong method.** `buildSalesByBranch` batch-resolves branch names with **unscoped `branchRepository.findAllById(...)`** (`DashboardServiceImpl.java:439-441`), falling back to `"Unknown"`. `findByIdAndCompany_Id` is used at `:582` in `resolveBranchScope`, for the dashboard *header* label only. Not a live leak (ids come from a company-scoped aggregate), but the citation was wrong and the guard is incidental, not intrinsic. Corrected and flagged.
5. **"33 of 40 reporting endpoints" → 37 of 40.** The §4b table enumerates 8+5+1+2+4+3+10+4 = 37; 37 + the 3 landmines = 40. Arithmetic corrected; the inventory itself was right.
6. **The CRM landmine is not "swallowed into a null panel."** `safeCrm` (`:505-512`) nulls the panel only on a thrown exception; a null `branchId` bound against `o.branchId = :branchId` matches no rows and yields a structurally valid, entirely **zeroed** `CrmSnapshotDto`. Corrected — and noted that this is worse, not better.
7. **`ProjectCostingQueryRepository.java:81` → `:83`** (method signature). The null-branch handling at `:103-104` was correct.
8. **`V18__approvals_engine.sql:74` → `:73`** for `branch_id BIGINT NOT NULL`.
9. **`AuthController.java:61-62` → `:61-64`** (mapping at 61, method at 63-65).
10. **`DefaultRoleBundlesSeededTest` will NOT fully check the new bundle as claimed.** `EXPECTED_ROLES` (`:48-51`) and `ANCHORS` (`:54-70`) are hard-coded lists of the twelve existing bundles; assertions 1, 2 and 4 iterate them and would skip `EXECUTIVE` silently. Only assertions 3 (phantom) and 5 (reserved leak) cover every role automatically. W2 now includes the required test edit. This was a real gap in the plan's safety argument.
11. **`BRANCH_MANAGER` grant count**: the seed's own comment at `:657` says 54; there are **56** grant rows (`:658-718`). Noted as a stale comment.
12. **X-Branch-Uid semantics restated.** `JwtRequestContextFilter.resolvePrincipal:191-197` seeds `branchId` from the JWT claim when the header is absent — the principal always has a branch. Company-wide reads work because report queries ignore `principal.branchId()`, not because it is null. Added as a design constraint in §2, §3 and W5.
13. **Sketch corrections**: the permission bean is `PermissionChecks` / `@Component("perm")` with `has(String)` (`backend/src/main/java/com/erp/platform/security/PermissionChecks.java:13, :29`); the draft's `permChecks` was renamed and the sketch marked illustrative. The P&L endpoint's actual gate is `@perm.scoped(#projectUid,'project','PROJECTS.COSTING.VIEW')`, not a plain `has`.
14. **Added**: latent NPE at `ProjectCostingQueryImpl.java:50` (`project.getBranchId()` is nullable); `StepApproverResolver.canDecide` uses `findByUserIdAndBranchId` with no `revokedAt`/`active` filter (`:63-65`) while the filter and `assertMayReadBranch` both require a live assignment.
15. **ScopeGuard nuance added**: the class does import `BranchRepository` and resolves `case "branch"` at `:519`; the "no branch anywhere" claim is true only of `canActIn`'s decision logic.

**Verified correct as written (spot-checked, all resolved):**

`ScopeGuard.java:675` and the `canActIn` span `:648-676`; the 0-hit Hibernate-filter grep; `SalesInvoiceRepository.java:97-102`; `OpportunityRepository.java:34, :47, :63` (`:63` is native SQL — `o.branch_id`, so the fix differs in case); `PipelineQuery.java:40-42, 63-65, 79-83`; `DashboardServiceImpl.java:97, :505-508, :578-584, :588`; `BiDashboardController.java:68, :106, :147`; `AccountMovementQuery.java:57, :81, :107, :139`; grep count of "branch" = **0** in `ReportingServiceImpl`, `AccountMovementQuery`, `AccountLedgerQuery`, `ArAgeingQuery`, `ApAgeingQuery`, `ApReconciliationQuery`; `StockValuationQuery.java:24, :61`; `ProductStockReportQuery.java:390-415, :429-433`; `ProductStockReportController.java:241`; `ProjectCostingQueryImpl.java:50, :97`; `ApprovalRequestStepRepository.java:72-91`; `StepApproverResolver.java:66`; `UserRoleRepository.java:20-33`; `PermissionResolver.java:91-97, :106-122`; `AuthorityCeiling.java:49-54, :85-99`; `JwtRequestContextFilter.java:229`; `R__seed_permissions.sql:290-296, :700-702, :812-895, :922`; `PermissionCodesSeededTest:57`; ADR-0022 D-8, ADR-0033 D-2, ADR-0040 D-11, ADR-0057 D-4, ADR-0062 R-2 all exist. **Every permission code listed in §5a and §4 was grepped individually — all present. `EXEC.*` correctly returns 0 hits (both codes are new).** Latest migration confirmed **V104**, next free **V105**. Role uid `0D` confirmed next free. `EndpointAuthorizationTest` method is at `:38` (draft said `:37-61` — start line off by one, corrected inline).

**Could not confirm:**

- Whether the native `kpiRaw` query (`OpportunityRepository.java:56-70`) *errors* on a null `:branchId` bind under Postgres rather than returning zero rows — marked **(UNVERIFIED at runtime)** in §4a. No test run; either behaviour is fixed by the same null guard.
- The literal branch count ("eleven branches") for the live client — no DB access. Replaced with branch-count-agnostic wording throughout.
- The "784 seeded permission rows" figure from the task brief. The catalogue INSERT in `R__seed_permissions.sql` contains **250** distinct permission codes; 784 matches no count I could reproduce (it may refer to total `role_permission` grant rows on the estate). §5a now states 250.
- That the `EXECUTIVE` bundle grants list is complete for the app's real screens — that depends on W3's final endpoint set, which does not exist yet.
- Runtime behaviour of any endpoint: all findings are from source reading and grep, not from an executed request against a running stack.