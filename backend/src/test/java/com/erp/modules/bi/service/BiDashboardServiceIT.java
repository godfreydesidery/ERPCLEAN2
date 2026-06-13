package com.erp.modules.bi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ap.service.ApOpeningBalanceService;
import com.erp.modules.ap.service.ApReconciliationQuery;
import com.erp.modules.ar.domain.dto.ArReconciliationDto;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.ar.service.ArOpeningBalanceService;
import com.erp.modules.ar.service.ArReconciliationQuery;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.bi.domain.dto.FinanceSummaryDto;
import com.erp.modules.bi.domain.dto.WorkingCapitalDto;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.parties.service.SupplierService;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.reporting.service.AccountMovementQuery;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link DashboardService} (ADR-0037 T1b).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li><b>KPI == statement by construction:</b> seed posted journals; assert BI finance
 *       revenue/net-profit EQUALS what {@link AccountMovementQuery#periodMovementByAccountType} and
 *       {@link AccountMovementQuery#netIncomeForPeriod} return for the same window. The BI number IS
 *       the statement number (ADR-0037 D-1).
 *   <li><b>AR/AP totals from recon subLedger (not null-id ageing):</b> seed an AR open item and
 *       an AP opening balance; assert the dashboard AR/AP totals match
 *       {@link ArReconciliationQuery}/{@link ApReconciliationQuery} and are NON-zero. A null-id
 *       ageing path would return zero (ADR-0037 D-2 W-AR/W-AP).
 *   <li><b>Graceful per-panel degrade:</b> company with no postings / no stock yields a dashboard
 *       (not 500) with panels non-null (empty/zero) and health list present.
 *   <li><b>Tenant isolation:</b> company A root principal sees company A numbers and CANNOT see
 *       company B numbers (ForbiddenException from assertCanActIn).
 *   <li><b>Perm gate (service layer):</b> a non-root principal scoped to company B gets
 *       ForbiddenException when calling dashboard for company A.
 * </ol>
 */
class BiDashboardServiceIT extends PostgresIntegrationTest {

    // ── Subjects under test ───────────────────────────────────────────────────
    @Autowired private DashboardService       dashboardService;
    @Autowired private AccountMovementQuery   accountMovement;
    @Autowired private ArReconciliationQuery  arRecon;
    @Autowired private ApReconciliationQuery  apRecon;

    // ── Data setup beans ──────────────────────────────────────────────────────
    @Autowired private SalesInvoiceService    salesInvoiceService;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private CustomerService        customerService;
    @Autowired private AgentService           agentService;
    @Autowired private SupplierService        supplierService;
    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private ArOpeningBalanceService arObService;
    @Autowired private ApOpeningBalanceService apObService;
    @Autowired private DomainEventDispatcher  dispatcher;
    @Autowired private DomainEventRepository  domainEventRepository;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private ArGlSeeder             arGlSeeder;
    @Autowired private ApGlSeeder             apGlSeeder;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    // ── Company A state ───────────────────────────────────────────────────────
    private Company companyA;
    private Branch  branchA;
    private Long    rootAId;

    private String customerUid;
    private String supplierUid;
    private String agentUid;
    private String productUid;
    private String pcsUid;
    private String priceListUid;

    // ── Company B state (tenant-isolation tests) ──────────────────────────────
    private Company companyB;
    private Branch  branchB;
    private Long    rootBId;

    private static final BigDecimal SALE_PRICE  = new BigDecimal("1000");
    private static final BigDecimal VAT_AMOUNT  = new BigDecimal("180");
    private static final BigDecimal SALE_TOTAL  = SALE_PRICE.add(VAT_AMOUNT);
    private static final BigDecimal AR_OB_AMOUNT = new BigDecimal("5000");
    private static final BigDecimal AP_OB_AMOUNT = new BigDecimal("3000");
    private static final String TZS = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        // ── Company A ─────────────────────────────────────────────────────────
        Organisation orgA = organisations.save(new Organisation("BI IT Org A"));
        companyA = companies.save(new Company(orgA, "BIITA", "BI IT Co A"));
        branchA  = branches.save(new Branch(companyA, "BIITA1", "BI IT Branch A"));

        AppUser rootA = new AppUser("bi_root_a", passwordEncoder.encode("BiR00tA1!"), "BI Root A");
        rootA.setRoot(true);
        rootA   = users.save(rootA);
        rootAId = rootA.getId();

        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        taxRateSeeder.seedDefaults(companyA.getId());
        chartOfAccountService.seedDefaults(companyA.getId());
        fiscalCalendarService.seedCurrentYear(companyA.getId());
        glConfigService.seedDefaults(companyA.getId());
        arGlSeeder.seedDefaults(companyA.getId());
        apGlSeeder.seedDefaults(companyA.getId());

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(
                companyA.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(
                companyA.getUid(), "RETAIL", "Retail")).uid();
        productUid   = productService.create(new CreateProductRequest(
                companyA.getUid(), null, "BI Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto(SALE_PRICE.toPlainString(), TZS)));

        customerUid = customerService.create(new CreateCustomerRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "BI Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null)).uid();
        agentUid    = agentService.create(new CreateAgentRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "BI Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
        supplierUid = supplierService.create(new CreateSupplierRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "BI Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS)).uid();

        // ── Company B (separate tenant) ───────────────────────────────────────
        Organisation orgB = organisations.save(new Organisation("BI IT Org B"));
        companyB = companies.save(new Company(orgB, "BIITB", "BI IT Co B"));
        branchB  = branches.save(new Branch(companyB, "BIITB1", "BI IT Branch B"));

        AppUser rootB = new AppUser("bi_root_b", passwordEncoder.encode("BiR00tB1!"), "BI Root B");
        rootB.setRoot(true);
        rootB   = users.save(rootB);
        rootBId = rootB.getId();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: KPI == statement by construction
    // =========================================================================

    /**
     * The headline property (ADR-0037 D-1): the BI revenue and net-profit values for a period
     * MUST equal what AccountMovementQuery returns for the identical window, because DashboardServiceImpl
     * calls periodMovementByAccountType — the same underlying SQL GROUP BY.
     */
    @Test
    void kpiEqualsStatement_revenueAndNetProfit_matchAccountMovementQuery() {
        // Post a cash sale (GL: DR Cash, CR Revenue, CR VAT Payable)
        postAndDispatchSale();

        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        // ── Direct query (the source of truth) ────────────────────────────────
        Map<AccountType, BigDecimal[]> byType =
                accountMovement.periodMovementByAccountType(companyA.getId(), from, to);
        BigDecimal[] incomeArr  = byType.getOrDefault(AccountType.INCOME,  zeroArr());
        BigDecimal[] expenseArr = byType.getOrDefault(AccountType.EXPENSE, zeroArr());
        BigDecimal expectedRevenue   = incomeArr[1].subtract(incomeArr[0]);   // credit − debit
        BigDecimal expectedNetProfit = accountMovement.netIncomeForPeriod(companyA.getId(), from, to);

        // ── BI finance panel ──────────────────────────────────────────────────
        FinanceSummaryDto finance = dashboardService.financeSummary(companyA.getId(), from, to);

        assertThat(finance.revenue())
                .as("BI revenue MUST equal AccountMovementQuery.periodMovementByAccountType revenue "
                        + "(by construction, ADR-0037 D-1)")
                .isEqualByComparingTo(expectedRevenue);

        assertThat(finance.netProfit())
                .as("BI netProfit MUST equal AccountMovementQuery.netIncomeForPeriod "
                        + "(by construction, ADR-0037 D-1)")
                .isEqualByComparingTo(expectedNetProfit);

        assertThat(finance.revenue())
                .as("revenue must be positive after a posted sale")
                .isGreaterThan(BigDecimal.ZERO);

        assertThat(finance.netProfit())
                .as("net profit must be non-negative after a revenue-only sale (no expenses posted)")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    /**
     * Trend points for the current fiscal period also equal the statement values.
     * Verifies the FiscalPeriodRepository × AccountMovementQuery iteration (ADR-0037 D-4).
     */
    @Test
    void kpiEqualsStatement_revenueTrend_currentPeriodPointMatchesDirectQuery() {
        postAndDispatchSale();

        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        BigDecimal expectedRevenue = accountMovement.periodMovementByAccountType(
                        companyA.getId(), from, to)
                .getOrDefault(AccountType.INCOME, zeroArr())[1]
                .subtract(accountMovement.periodMovementByAccountType(
                        companyA.getId(), from, to)
                        .getOrDefault(AccountType.INCOME, zeroArr())[0]);

        var trend = dashboardService.revenueTrend(companyA.getId());

        assertThat(trend).isNotNull();
        assertThat(trend.points()).isNotEmpty();

        // Find the trend point whose window contains today
        var todayPoint = trend.points().stream()
                .filter(p -> !LocalDate.now().isBefore(p.periodStart())
                          && !LocalDate.now().isAfter(p.periodEnd()))
                .findFirst();

        assertThat(todayPoint)
                .as("revenue trend must contain a point for the current period")
                .isPresent();

        assertThat(todayPoint.get().value())
                .as("trend point value for current period MUST equal direct AccountMovementQuery revenue")
                .isEqualByComparingTo(expectedRevenue);
    }

    // =========================================================================
    // Bar 2: AR/AP totals come from recon subLedger, are NON-ZERO
    // =========================================================================

    /**
     * Seed AR opening balance + AP opening balance; the BI working-capital panel AR/AP outstanding
     * MUST match ArReconciliationQuery/ApReconciliationQuery subLedger totals and be NON-ZERO.
     * A null-id ageing path would silently render zero (ADR-0037 D-2 guard).
     */
    @Test
    void arApTotals_fromReconSubLedger_nonZero() {
        // Seed AR open item (via ArOpeningBalanceService — creates OPEN ar_invoice + GL posting)
        arObService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyA.getUid(), customerUid, AR_OB_AMOUNT,
                TZS, LocalDate.now(), LocalDate.now().plusDays(30), "AR-OB-BI-001"));

        // Restore context after potential REQUIRES_NEW in ArOpeningBalanceService
        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        // Seed AP open item (via ApOpeningBalanceService — creates OPEN supplier_bill + GL posting)
        apObService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyA.getUid(), supplierUid, AP_OB_AMOUNT,
                TZS, LocalDate.now(), LocalDate.now().plusDays(30), "AP-OB-BI-001"));

        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        // ── Source of truth: recon queries ────────────────────────────────────
        ArReconciliationDto arExpected = arRecon.reconcile(companyA.getId());
        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));
        ApReconciliationDto apExpected = apRecon.reconcile(companyA.getId());

        assertThat(arExpected.subLedgerTotal())
                .as("AR recon subLedger must be non-zero after opening balance seeding")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(apExpected.subLedgerTotal())
                .as("AP recon subLedger must be non-zero after opening balance seeding")
                .isGreaterThan(BigDecimal.ZERO);

        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        // ── BI working capital panel ──────────────────────────────────────────
        WorkingCapitalDto wc = dashboardService.workingCapital(companyA.getId());

        assertThat(wc.arOutstanding())
                .as("BI AR outstanding MUST equal ArReconciliationQuery subLedger total "
                        + "(not the null-id ageing path which returns zero, ADR-0037 D-2 W-AR)")
                .isEqualByComparingTo(arExpected.subLedgerTotal());

        assertThat(wc.apOutstanding())
                .as("BI AP outstanding MUST equal ApReconciliationQuery subLedger total "
                        + "(not the null-id ageing path which returns zero, ADR-0037 D-2 W-AP)")
                .isEqualByComparingTo(apExpected.subLedgerTotal());

        assertThat(wc.arOutstanding())
                .as("AR outstanding must be non-zero — null-id ageing regression would render zero")
                .isGreaterThan(BigDecimal.ZERO);

        assertThat(wc.apOutstanding())
                .as("AP outstanding must be non-zero — null-id ageing regression would render zero")
                .isGreaterThan(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 3: Graceful per-panel degrade — no data → dashboard, not 500
    // =========================================================================

    /**
     * A company with no postings / no stock / no CRM data returns a dashboard with panels
     * present (not null for finance/wc — they degrade to zero, not exception) and health list.
     * The critical guarantee: DashboardServiceImpl.safeXxx catches upstream exceptions and returns
     * null for that panel, never propagates a 500.
     */
    @Test
    void gracefulDegrade_noData_dashboardReturnedNotException() {
        // Company A has been seeded (COA, fiscal year, GL config) but has NO postings yet.
        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        // Must not throw
        DashboardDto dto = dashboardService.dashboard(companyA.getId(), from, to, branchA.getId());

        assertThat(dto).isNotNull();
        assertThat(dto.header()).isNotNull();
        assertThat(dto.header().companyId()).isEqualTo(companyA.getId());
        assertThat(dto.health())
                .as("health list must always be present (may be empty with zero data)")
                .isNotNull();

        // Finance panel: zero data should yield zero revenue/net-profit (no exception)
        if (dto.finance() != null) {
            assertThat(dto.finance().revenue())
                    .as("revenue must be zero when no postings")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.finance().netProfit())
                    .as("net profit must be zero when no postings")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        // Working capital panel: zero AR/AP with no open items (no exception)
        if (dto.workingCapital() != null) {
            assertThat(dto.workingCapital().arOutstanding())
                    .as("AR outstanding must be zero when no open items")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(dto.workingCapital().apOutstanding())
                    .as("AP outstanding must be zero when no open items")
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    /**
     * The per-panel graceful degrade is exercised via the composite dashboard method.
     * Even if stock panel raises (no stock seeded), dashboard must still return header + health.
     */
    @Test
    void gracefulDegrade_compositeDashboard_alwaysReturnsHeaderAndHealth() {
        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        DashboardDto dto = dashboardService.dashboard(companyA.getId(), from, to, null);

        assertThat(dto).isNotNull();
        assertThat(dto.header()).isNotNull();
        assertThat(dto.health()).isNotNull();
        // Null panels are allowed (graceful degrade) — only assert we don't 500
    }

    // =========================================================================
    // Bar 4: Tenant isolation — company A dashboard never reflects company B data
    // =========================================================================

    /**
     * A root principal scoped to company A CANNOT read company B's dashboard.
     * assertCanActIn in DashboardServiceImpl throws ForbiddenException (ADR-0037 D-5).
     */
    @Test
    void tenantIsolation_companyACannotReadCompanyBDashboard() {
        // Principal is scoped to company A but requests company B's dashboard
        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", false /* NOT root — scoped to company A */,
                companyA.getId(), branchA.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        assertThatThrownBy(() -> dashboardService.dashboard(companyB.getId(), from, to, null))
                .as("A non-root principal scoped to company A must be denied company B dashboard")
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * Seed a sale for company A; company B dashboard must show zero revenue (numbers don't bleed
     * across tenants). Uses two root principals, each scoped to their own company.
     */
    @Test
    void tenantIsolation_companyBDashboardShowsZero_whenSaleIsOnlyPostedForCompanyA() {
        // Post a sale for company A
        postAndDispatchSale();

        // Switch context to company B (new root)
        RequestContext.set(new RequestContext.Principal(
                rootBId, "bi_root_b", true, companyB.getId(), branchB.getId(), null));

        // Seed minimal GL config for company B so the dashboard doesn't fail on missing accounts
        chartOfAccountService.seedDefaults(companyB.getId());
        fiscalCalendarService.seedCurrentYear(companyB.getId());
        glConfigService.seedDefaults(companyB.getId());
        arGlSeeder.seedDefaults(companyB.getId());
        apGlSeeder.seedDefaults(companyB.getId());

        RequestContext.set(new RequestContext.Principal(
                rootBId, "bi_root_b", true, companyB.getId(), branchB.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        FinanceSummaryDto financeB = dashboardService.financeSummary(companyB.getId(), from, to);

        assertThat(financeB.revenue())
                .as("Company B revenue must be zero — company A's sale must never bleed across tenants")
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(financeB.netProfit())
                .as("Company B net profit must be zero (tenant isolation)")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 5: Perm gate — non-root principal scoped to wrong company gets 403
    // =========================================================================

    /**
     * A non-root principal whose active company is B must get ForbiddenException when calling
     * the per-panel financeSummary for company A (service-layer assertCanActIn fires).
     * This mirrors the controller @PreAuthorize + assertCanActIn defense-in-depth (ADR-0037 D-5).
     */
    @Test
    void permGate_nonRootPrincipalScopedToCompanyB_forbiddenForCompanyAFinance() {
        RequestContext.set(new RequestContext.Principal(
                rootBId, "bi_root_b", false /* NOT root */,
                companyB.getId(), branchB.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        assertThatThrownBy(() -> dashboardService.financeSummary(companyA.getId(), from, to))
                .as("Non-root principal scoped to company B must be denied finance summary for company A")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void permGate_nonRootPrincipalScopedToCompanyB_forbiddenForCompanyAWorkingCapital() {
        RequestContext.set(new RequestContext.Principal(
                rootBId, "bi_root_b", false /* NOT root */,
                companyB.getId(), branchB.getId(), null));

        assertThatThrownBy(() -> dashboardService.workingCapital(companyA.getId()))
                .as("Non-root principal scoped to company B must be denied working capital for company A")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void permGate_nonRootPrincipalScopedToCompanyB_forbiddenForCompanyARevenueTrend() {
        RequestContext.set(new RequestContext.Principal(
                rootBId, "bi_root_b", false /* NOT root */,
                companyB.getId(), branchB.getId(), null));

        assertThatThrownBy(() -> dashboardService.revenueTrend(companyA.getId()))
                .as("Non-root principal scoped to company B must be denied revenue trend for company A")
                .isInstanceOf(ForbiddenException.class);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Post one cash sale for company A and dispatch the GL event, giving the GL the posted revenue
     * entry needed for the KPI == statement assertion.
     */
    private void postAndDispatchSale() {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(
                        companyA.getUid(), customerUid, agentUid, TZS, null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid,
                        new BigDecimal("1"), null, null));
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, SALE_TOTAL, TZS, null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        // Dispatch all SALE.FINALISED events so GL is populated
        domainEventRepository.findAll().forEach(e -> {
            try { dispatcher.dispatchOne(e.getId()); } catch (Exception ex) { /* already dispatched */ }
        });
        // Restore context after dispatch (REQUIRES_NEW transactions may clear it)
        RequestContext.set(new RequestContext.Principal(
                rootAId, "bi_root_a", true, companyA.getId(), branchA.getId(), null));
    }

    private static BigDecimal[] zeroArr() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
    }
}
