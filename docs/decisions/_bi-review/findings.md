# BI/Wave-2 adversarial-review findings

16 findings; 5 confirmed serious; 0 refuted.

## CONFIRMED SERIOUS

### 1. [HIGH] Composite /dashboard endpoint bypasses the fine-grained BI.FINANCE/OPS/CRM.VIEW permission gate â€” any BI.VIEW holder reads finance, AR/AP, inventory and CRM data

CONFIRMED â€” genuine defect on the primary supported path (P-2 fine-perm gate, with an information-disclosure / least-privilege impact).

Evidence:
- BiDashboardController.java:64-65 â€” the composite GET /api/v1/bi/dashboard is gated ONLY @perm.has('BI.VIEW'). The per-panel endpoints correctly add fine perms (lines 79/90/96/102/114/120: BI.FINANCE.VIEW, BI.OPS.VIEW, BI.CRM.VIEW), but the composite does not.
- DashboardServiceImpl.dashboard() (lines 113-141) does ONLY scopeGuard.assertCanActIn (line 116), then unconditionally builds every panel (lines 128-133: safeFinance/safeWorkingCapital/safeInventory/safeCrm/safeRevenueTrend/safeNetProfitTrend). It never consults the fine perms; the constructor (lines 85-107) injects no PermissionResolver/PermissionChecks. The only BI.FINANCE/OPS/CRM references in com.erp.modules.bi are the DTO comments.
- The intended contract is explicit and violated: DashboardDto.java:8-9 + field comments :17-22 ("a null panel means the caller lacked the fine-grained panel permission" / "gated BI.FINANCE.VIEW/OPS.VIEW/CRM.VIEW"); DashboardService.java:17 ("each nullable if the caller lacks the fine perm"); ADR-0037 docs/decisions/0037-bi-analytics-dashboards.md:165 (D-6) and :229 item d (Tranche-1 acceptance bar: "a missing fine perm yields a null panel not a 403").
- Reachable in production: the five perms are independent rows (V81__bi_permissions.sql:11-17) and PermissionResolver.hasPermission (PermissionResolver.java:77-78) is per-code with no hierarchy/wildcard, so a non-root user can hold BI.VIEW (+ e.g. only BI.CRM.VIEW) and receive the fully-populated finance, company-wide AR/AP working-capital, inventory and trend panels.
- Export leaks the same data: /dashboard/export is gated only BI.EXPORT (BiDashboardController.java:131), calls the same dashboard() (line 138), and BiExportFlattener.flatten (lines 44-130) emits every populated panel.
- Frontend is no mitigation: DashboardComponent.applyDto (dashboard.component.ts:213-269) consults canFinance()/canOps()/canCrm() only in the else-if branch reached when a panel is null; a populated panel takes the first branch and renders. SessionStore.hasPermission (session.store.ts:35) is pure client-side sessionStorage.
- Tests miss it by construction: the FE spec feeds NULL_PANELS_DTO (dashboard.component.spec.ts:125-134, 261-272), presupposing the backend already nulled panels; BiDashboardServiceIT tests only root principals scoped to the wrong company (assertCanActIn), never a BI.VIEW-only principal lacking a fine perm â€” the ADR's required Tranche-1 test (item d) was never written.

Minimal fix: inject PermissionChecks (the @perm bean, which exposes has(code) and root short-circuits) into DashboardServiceImpl; in dashboard(), null each panel when the caller lacks its fine perm â€” gate safeFinance/safeWorkingCapital/safeRevenueTrend/safeNetProfitTrend on BI.FINANCE.VIEW, safeInventory on BI.OPS.VIEW, safeCrm on BI.CRM.VIEW. This also closes the export leak (it flattens the same nulled DTO). Add the missing IT: a BI.VIEW-only non-root principal must get null finance/wc/inventory/crm/trend panels.

Severity HIGH (not BLOCKER) â€” authorization/information-disclosure of finance, company-wide AR/AP, inventory and CRM data to under-privileged users on the primary endpoint, but it requires an authenticated user who already holds BI.VIEW in the target company, and tenant isolation (assertCanActIn, P-6 company scope) still holds.

---

### 2. [HIGH] Dashboard COGS / gross profit / gross-margin% diverge from the income statement (COGS hardcoded to ZERO)

CONFIRMED â€” genuine P-3 defect on a supported path. Every cited fact verified on feat/bi-analytics.

EVIDENCE
- DashboardServiceImpl.buildFinance (backend/.../bi/service/DashboardServiceImpl.java): line 164 `BigDecimal cogs = BigDecimal.ZERO;` (comment "no separate AccountType for COGS ... zero in v1"); line 165 `grossProfit = revenue.subtract(cogs)` => grossProfit == revenue; lines 171-175 grossMarginPct => 100.00 whenever revenue != 0; line 167 lumps the FULL EXPENSE net into opex. These COGS/grossProfit/grossMarginPct values are returned in FinanceSummaryDto (fields cogs/grossProfit/grossMarginPct at lines 13-15) and surfaced to users via BiExportFlattener.java lines 48-52 ("COGS", "Gross Profit", "Gross Margin %" rows) â€” not dead fields.
- The income statement does the OPPOSITE: StatementClassifier.classifyExpense (reporting/service/StatementClassifier.java:77-81) routes EXPENSE 5100-5199 into COST_OF_SALES; IncomeStatementBuilder (lines 90-97) computes cogsSubtotal and grossProfit = revSubtotal - cogsSubtotal.
- Real COGS lands in 5100 on a normal GOODS sale: SaleIssueStockHandler.handle (stock/events/SaleIssueStockHandler.java:120-126) builds COGS legs and calls InventoryGlPoster.postCogsInNewTx, which posts DR COGS / CR INVENTORY (InventoryGlPoster.java:131-141), COGS resolving via GlConfigKey.COGS -> "5100" (GlConfigServiceImpl.java:40), and 5100 "Cost of Goods Sold" is a seeded EXPENSE account (ChartOfAccountServiceImpl.java:56). So for any company running perpetual inventory the dashboard shows COGS=0 / grossProfit=revenue / grossMargin=100% while the income statement shows real COGS and a realistic margin. (Revenue and net profit still agree because opex absorbs the COGS â€” only the gross-profit family diverges; the finding correctly scopes this.)

WHY GREEN TESTS MISS IT
BiDashboardServiceIT.postAndDispatchSale() (test .../BiDashboardServiceIT.java:556-573) creates an invoice + cash payment + finalise but seeds NO stock/GRN, so no costed issue and no 5100 movement is posted; the income statement would also report COGS=0 in that scenario, so both agree. The KPI==statement tests (kpiEqualsStatement_*, lines 226-264) only assert revenue() and netProfit() and explicitly note "no expenses posted" (line 262) â€” they never assert cogs()/grossProfit()/grossMarginPct().

MINIMAL FIX
Replace the hardcoded zero with the same code-band split the statement uses. Within buildFinance, call accountMovement.periodMovementByAccount(companyId, from, to) + accountMapForCompany and StatementClassifier.classify (or reuse IncomeStatementBuilder/ReportingService for the COST_OF_SALES subtotal): EXPENSE 5100-5199 -> cogs, remainder -> opex, then grossProfit = revenue - cogs. This keeps grossProfit/grossMarginPct equal to the income statement by construction (P-3) and preserves netProfit = revenue - (cogs + opex) unchanged. If reusing the per-account split is undesirable, drop the cogs/grossProfit/grossMarginPct KPIs rather than ship a guaranteed-wrong 100% margin. Add an IT that posts a GOODS sale with perpetual COGS and asserts BI cogs/grossProfit == IncomeStatement cogs/grossProfit.

Severity HIGH (not BLOCKER): scoped to the gross-profit family; revenue and net profit remain correct, no crash, no tenant leak.

---

### 3. [MEDIUM] a11y gate omits the BI dashboard â€” the wave's headline new component (and the ADR's designated pilot) has no *.a11y.spec.ts

CONFIRMED (real coverage gap; severity downgraded HIGHâ†’MEDIUM).

EVIDENCE
- Exactly 4 a11y specs exist: web/src/app/features/auth/login/login.component.a11y.spec.ts, web/src/app/features/admin/approvals/approval-policy-detail.component.a11y.spec.ts, web/src/app/features/admin/parties/customer-list.component.a11y.spec.ts, web/src/app/features/admin/fixed-assets/fixed-asset-list.component.a11y.spec.ts. None renders DashboardComponent (grep `assertA11y|DashboardComponent` finds the dashboard only in dashboard.component.ts, dashboard.component.spec.ts, admin.routes.ts).
- The dashboard is shipped and route-wired: web/src/app/features/admin/admin.routes.ts:830-833 (`/admin/dashboard`, canActivate requirePermission('BI.VIEW')). Its template (web/src/app/features/admin/dashboard/dashboard.component.html, 552 lines) is exactly the a11y-rich surface a gate should scan: tables with <caption> (l.214), <dl> lists (l.398-419), role="img" CSS bars with aria-labels (l.369,447,496), form selects with <label for> (l.28-64,531-537).
- The gate IS real: web/src/testing/a11y.helper.ts:30-36 calls axe(fixture.nativeElement) and asserts toHaveNoViolations; axe-core/jest-axe are real deps (web/package.json:33-34). So a dashboard a11y spec would genuinely execute.
- AUTHORITY: ADR-0037 (docs/decisions/0037-bi-analytics-dashboards.md) FE-Polish plan explicitly scopes the dashboard as the FIRST a11y target â€” l.235 "the BI dashboard is its first beneficiary and its pilot"; l.231 "the dashboard a11y spec tracks Tranche 2's component"; l.239(c) "add ... a paired *.a11y.spec.ts. Start with the BI dashboard, then ... fixed-assets ...". The wave shipped a fixed-asset a11y spec (an ADR "later" item) but skipped the dashboard (the ADR "start with" item).
- No mitigation elsewhere: the Playwright axe scans (web/e2e/smoke.spec.ts:59-84) cover only /login and /admin home, NOT /admin/dashboard, and require a live backend + ROOT_PASS (not part of hermetic `npm test`/CI), so they are not a substitute gate.

WHY NOT HIGH: This is a missing test within an otherwise-real, correctly-wired gate, not a runtime defect on a supported path. The shipped dashboard code is unaffected and the gate's other P-8 facets (axe realness, Playwright config runnability) hold. It is a verification gap on the wave's own headline FE deliverable, contradicting the ADR's stated pilot scope â€” material, hence MEDIUM, but not HIGH since no user-facing behavior is broken.

MINIMAL FIX: Add web/src/app/features/admin/dashboard/dashboard.component.a11y.spec.ts reusing the existing dashboard.component.spec.ts scaffolding (makeBed, MOCK_DTO, NULL_PANELS_DTO; provideRouter, DashboardService/Company/Org/Branch/SessionStore stubs). Two cases following the customer-list pattern (fakeTimers â†’ runAllTimersAsync â†’ useRealTimers â†’ detectChanges â†’ assertA11y(fixture)): (1) populated MOCK_DTO with all BI perms (exercises tables/dl/CSS bars/export controls), (2) NULL_PANELS_DTO with BI.VIEW only (exercises the all-forbidden panels). The infra already exists; this is a ~40-line spec.

---

### 4. [MEDIUM] Dashboard CSS bars emit aria-label on role-less <div> â†’ real aria-prohibited-attr (serious, WCAG 2 A) violation

CONFIRMED (genuine defect; severity adjusted HIGHâ†’MEDIUM â€” accessibility quality issue, not a functional/security/data break).

Evidence (code, feat/bi-analytics):
- dashboard.component.html:378-381 (pipeline), 459-462 (revenue trend), 508-512 (net profit trend): each inner bar-fill is a bare generic <div class="bi-bar-fill" ...> with NO role attribute carrying [attr.aria-label]="...". A generic div (implicit role=generic) prohibits aria-label per ARIA-in-HTML, so axe flags aria-prohibited-attr.
- I reproduced this empirically with the repo's own toolchain (web/node_modules axe-core 4.10.3 + jsdom) using the gate's exact rule config from a11y.helper.ts:19-24 (color-contrast + scrollable-region-focusable disabled). The exact rendered markup for all three bars produces: aria-prohibited-attr (serious): Elements must only use permitted ARIA attributes [1 node] on the bi-bar-fill div. When wrapped in a <main> landmark (as in the real app), the only remaining violation is aria-prohibited-attr â€” confirming it is intrinsic to the element, not a harness artifact (the transient "region" violation in my landmark-less first run is harness noise and not part of the finding).

Why the green tests missed it (also confirmed): the a11y gate is REAL â€” testing/a11y.helper.ts wires jest-axe v9 toHaveNoViolations into vitest and is exercised by 4 specs (customer-list, login, fixed-asset-list, approval-policy-detail), all calling assertA11y(fixture). That gate would catch a 'serious' violation. But there is NO web/**/dashboard*.a11y.spec.ts (Glob returns none), and dashboard.component.spec.ts (read in full) makes zero assertA11y calls â€” it only asserts component-state signals. So the dashboard markup is never rendered through axe. This is a real P-8 gap: the dashboard does NOT follow the house a11y convention (no a11y spec), and it contains real serious-impact violations the gate should have blocked.

Minimal fix (the visible monospace amount + period label adjacent to each bar already convey the value to AT, so the aria-label on the fill is redundant):
1) Remove [attr.aria-label] from the three bi-bar-fill divs (html:378-381, 459-462, 508-512). The bar then becomes a purely decorative width-driven element with its meaning provided by the sibling visible text; optionally add aria-hidden="true" to the fill div to be explicit. (Alternatively, add role="img" to each fill div, but removal is cleaner since the text already labels it.)
2) Add web/src/app/features/admin/dashboard/dashboard.component.a11y.spec.ts that renders the populated MOCK_DTO state and calls assertA11y(fixture) to regression-lock the fix and close the convention gap.

Secondary note from the finding (outer role="img" containers wrapping live text) is real-ish but lower priority and not an axe violation here; the load-bearing defect is the prohibited aria-label, which I confirmed fires three times.

---

### 5. [MEDIUM] a11y gate is flaky: timed-out axe run leaks the axe-core singleton lock to the next sibling test ("Axe is already running")

CONFIRMED (P-8). The a11y gate is genuinely flaky on the supported `ng test --watch=false` path; the failure mechanism is real and unguarded.

EVIDENCE (cited):
- web/src/testing/a11y.helper.ts:19,30-36 â€” `assertA11y` awaits a MODULE-LEVEL global `axe` singleton (configureAxe at L19) with NO try/finally, NO serialization, NO state reset. Verified no reset/guard exists anywhere (grep of src/testing and src: only a11y.helper.ts + jest-axe.d.ts reference axe).
- axe-core/axe.js:29804-29808,29820,29829,29850 â€” `axe.run()` asserts `!axe._running`, then sets the GLOBAL `axe._running=true`; only reset to false on resolve/reject/error.
- @angular/build (21.2.14) runners/vitest/plugins.js:128 â€” builder injects `isolate:false` as a project default; Vitest 4.1.8 default `fileParallelism:true` (vitest cac chunk L926). So the axe singleton's `_running` flag is shared across the two tests in a spec file (and across files in a worker).
- @vitest/runner chunk-artifact.js:2261-2319 (withTimeout) â€” on test timeout it rejects the TEST promise but does NOT cancel the test body's in-flight `axe.run()`, so `_running` stays true.

REPRODUCED: ran real installed axe-core + jsdom; an in-flight `axe.run()` sets `_running=true` and an immediate second `axe.run()` throws verbatim "Axe is already running. Use `await axe.run()` to wait for the previous run to finish before starting a new run." â€” the exact second error in the finding. Login a11y spec in isolation passes in 1.84s (matches finding's ~2s), confirming the failure is load/contention-dependent (flaky), not deterministic. Suite is 52 spec files / ~477 tests, so cold-start parallel-worker CPU contention exceeding the explicit 15s budget (login.component.a11y.spec.ts:38,45) is plausible.

MINOR INACCURACY (non-material): finding says "the global default bites the first it" â€” actually login passes an explicit 15_000; the substance is unaffected.

SEVERITY: MEDIUM not HIGH. It is a real flaky-gate quality defect but: (a) the gate still surfaces real violations on a clean run; (b) it does not affect production code or any P-1..P-7 backend invariant; (c) impact is CI noise / a lucky-run dependency on the "436 green" claim, not a security/correctness regression on a shipped path.

MINIMAL FIX (assertA11y): serialize axe runs via a module-level promise-chain mutex and wrap the axe(...) call in try/finally that force-releases the lock on failure (axe-core has no public reset, so set (axe as any)._running=false in finally); optionally raise the a11y per-spec timeout and run a11y specs single-threaded.

---

## MEDIUM/LOW

- **[LOW] P-1 and P-7 hold structurally â€” BI is genuine pure composition and V81 is a correct additive r.code grant**
  - backend/src/main/java/com/erp/modules/bi/service/DashboardServiceImpl.java; backend/src/main/resources/db/migration/V81__bi_permissions.sql
  - fix: No fix required for the core properties. See the separate findings below for the (minor) gaps in enforcement/coverage.

- **[LOW] BI imports another module's domain entity (gl FiscalPeriod, iam Company), which ADR-0037 D-3 explicitly lists as forbidden**
  - backend/src/main/java/com/erp/modules/bi/service/DashboardServiceImpl.java:26,394,400
  - fix: Either (a) reconcile the ADR wording with reality â€” amend D-3 to say BI consumes the two allowed repos' entity return types (FiscalPeriod, Company) read-only, as the documented exception; or (b) if zero-entity-coupling is truly desired, expose the period axis and company name/currency behind thin gl/iam SERVICE methods returning DTOs (the OQ-BI-01 alternative) so bi imports no foreign entity at all. Option (a) is the cheaper, honest fix.

- **[LOW] No architectural gate enforces P-1 â€” a future regression adding a real repository/EntityManager/@Query into bi would pass the green suite**
  - backend/src/test/java/com/erp/architecture/ModuleBoundaryTest.java:25-77
  - fix: Add the should-add ArchUnit rule from D-3: noClasses().that().resideInAPackage('com.erp.modules.bi..').should().dependOnClassesThat().resideInAPackage('..repository..').orShould().dependOnClassesThat().areAssignableTo(EntityManager) â€” with an explicit allowance only for FiscalPeriodRepository + CompanyRepository (e.g. an ArchUnit ignore predicate naming those two FQNs). Optionally also forbid bi depending on '..domain.entity..'. This turns P-1 from a review invariant into an enforced gate.

- **[LOW] V81 grant + perm-code validity is not exercised end-to-end â€” the green suite proves the service guard but never that an ORG_ADMIN granted the seeded BI.* perms can reach the endpoints**
  - backend/src/test/java/com/erp/modules/bi/service/BiDashboardServiceIT.java:96-578; backend/src/test/java/com/erp/architecture/EndpointAuthorizationTest.java:33-57
  - fix: Add an HTTP-layer IT (mirroring RbacEnforcementHttpIT / ReportingHttpIT) that logs in as a user holding the ORG_ADMIN role on a company (so the V81 grant is in play) and asserts: BI.* endpoints return 200, while a user lacking BI.OPS.VIEW gets 403 on /inventory. Additionally extend EndpointAuthorizationTest (or add a perm-catalogue test) to assert every @PreAuthorize('@perm.has(X)') code X under com.erp.api exists in the seeded permissions set â€” this would mechanically guarantee 'perm codes match the controller exactly' for BI and every other module.

- **[MEDIUM] Dashboard export (/dashboard/export) leaks all panel data to a BI.EXPORT-only holder â€” same missing fine-perm gate**
  - backend/src/main/java/com/erp/api/BiDashboardController.java:130-141; backend/src/main/java/com/erp/modules/bi/service/DashboardServiceImpl.java:113-141
  - fix: Once the composite dashboard() nulls panels the caller lacks fine perms for (primary finding fix), the flattener already skips null panels (BiExportFlattener.java:45,68,78,86,108,117), so export is automatically scoped. Add an IT asserting a BI.EXPORT-only principal's export contains no Finance/Working-Capital/Inventory/CRM section rows.

- **[MEDIUM] CRM panel silently returns ZERO rows when the composite dashboard is called without a branchId (null-branch trap, same class as the killed AR/AP null-id bug)**
  - backend/src/main/java/com/erp/modules/crm/repository/OpportunityRepository.java:33-34, 46-47, 62-63; reached via DashboardServiceImpl.buildCrm (DashboardServiceImpl.java:255-263) and PipelineQuery (PipelineQuery.java:39,62,78)
  - fix: Decide the company-wide CRM contract: either make the OpportunityRepository CRM queries null-safe ('AND (:branchId IS NULL OR o.branchId = :branchId)') so a null branchId means company-wide, or have buildCrm resolve a concrete default branch when branchId is null. Add an IT: company with open opportunities and branchId=null must yield a non-empty pipeline/forecast/KPI panel.

- **[MEDIUM] Revenue-trend 'current period' point uses fiscal-period window, not the finance-card window â€” values can disagree for the same 'now'**
  - backend/src/main/java/com/erp/modules/bi/service/DashboardServiceImpl.java:294-314
  - fix: Decide and document the trend semantics explicitly. If the trend is meant to mirror the finance KPI for the live period, clamp the current period's end to LocalDate.now() (or to effectiveTo) before calling periodMovementByAccountType. If the trend is deliberately full-period, label the current bar as a full-period figure so it is not read as equal to the month-to-date KPI, and change the IT to compare over [periodStart, periodEnd] rather than [firstOfMonth, today].

- **[LOW] FinanceSummaryDto carries net profit twice (netProfitPeriod and netProfit) populated with the identical value**
  - backend/src/main/java/com/erp/modules/bi/service/DashboardServiceImpl.java:184-185
  - fix: Drop one of the two fields from FinanceSummaryDto (keep `netProfit`) or, if both are intended (period vs cumulative), actually compute the second one (e.g. cumulative netIncome via AccountMovementQuery.cumulativeByAccountTypeAsAt) instead of duplicating the period value.

- **[MEDIUM] Header resolution (resolveCompanyName / resolveBaseCurrency) runs OUTSIDE the per-panel try/catch â€” a CompanyRepository failure 500s the whole dashboard instead of degrading**
  - backend/src/main/java/com/erp/modules/bi/service/DashboardServiceImpl.java:118-119 (resolveCompanyName/resolveBaseCurrency at 392-402)
  - fix: Resolve companyName/currency inside a small try/catch (or fold them into a safeHeader helper) that falls back to companyName="Company "+companyId and currency="TZS" on any exception, so a header-source hiccup degrades to a best-effort header + still-attempted panels rather than a 500. Alternatively wrap the whole panel-assembly body so the method can never propagate, since DashboardController.dashboard() does no try/catch of its own.

- **[LOW] Per-panel trend/finance/working-capital ENDPOINTS (not the composite) have no degrade layer â€” they 500 on any upstream throw**
  - backend/src/main/java/com/erp/modules/bi/service/DashboardServiceImpl.java:147-152, 208-212, 270-280; backend/src/main/java/com/erp/api/BiDashboardController.java:78-123
  - fix: If the per-panel endpoints are meant to be independently degradable, wrap their builds in the same safeXxx pattern (or have the controller map known recoverable exceptions to an empty payload). If they are intentionally fail-fast, document that the degrade guarantee applies only to /dashboard so reviewers don't assume otherwise.

- **[LOW] a11y.helper.ts disables scrollable-region-focusable in addition to color-contrast â€” slightly broader than the documented 'color-contrast only' allowance**
  - web/src/testing/a11y.helper.ts:19-24
  - fix: Either keep it (acceptable, well-documented) or narrow to color-contrast only and instead size the jsdom viewport so scrollable-region-focusable can evaluate. No action strictly required.

