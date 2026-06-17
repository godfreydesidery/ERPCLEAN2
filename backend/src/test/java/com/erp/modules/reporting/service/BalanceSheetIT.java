package com.erp.modules.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.enums.StatementSection;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for Balance Sheet (ADR-0018 D-6, FR-REP-02, BR-REP-02/05).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>ASSET == LIABILITY + EQUITY self-check ties after posting activity.
 *   <li>Current-year-earnings equity fold is present and non-zero.
 *   <li>Multi-year: BS still balances when activity spans two fiscal years (pins the
 *       inception-to-date fold — FY-to-date alone would break this).
 * </ol>
 */
class BalanceSheetIT extends PostgresIntegrationTest {

    @Autowired private ReportingService      reportingService;
    @Autowired private SalesInvoiceService   salesInvoiceService;
    @Autowired private TaxRateSeeder         taxRateSeeder;
    @Autowired private CustomerService       customerService;
    @Autowired private AgentService          agentService;
    @Autowired private ProductService        productService;
    @Autowired private PriceListService      priceListService;
    @Autowired private UnitOfMeasureService  unitService;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService       glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository     companies;
    @Autowired private BranchRepository      branches;
    @Autowired private AppUserRepository     users;
    @Autowired private PasswordEncoder       passwordEncoder;
    @Autowired private IamTestData           testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  customerUid;
    private String  agentUid;
    private String  productUid;
    private String  pcsUid;
    private String  priceListUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("REP BS IT Org"));
        company  = companies.save(new Company(org, "RBST", "REP BS IT Co"));
        branch   = branches.save(new Branch(company, "RBST1", "REP BS IT Branch"));

        AppUser root = new AppUser("rbs_root", passwordEncoder.encode("RootPass1!"), "RBS Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "rbs_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        productUid   = productService.create(new CreateProductRequest(
                company.getUid(), null, "BS Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "BS Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "BS Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: BS self-check ties — ASSET == LIABILITY + EQUITY (BR-REP-02)
    // =========================================================================

    @Test
    void balanceSheet_selfCheckTies_assetEqualsLiabilityPlusEquity() {
        finaliseAndDispatch();

        BalanceSheetDto bs = reportingService.balanceSheet(
                company.getId(), LocalDate.now(), null);

        assertThat(bs.reconciliation().ties())
                .as("BS self-check: ASSET must equal LIABILITY + EQUITY (BR-REP-02)")
                .isTrue();
        assertThat(bs.reconciliation().difference().current())
                .as("Reconciliation difference must be exactly zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 2: Current-year-earnings fold present (BR-REP-05)
    // =========================================================================

    @Test
    void balanceSheet_currentYearEarningsFold_present() {
        finaliseAndDispatch();

        BalanceSheetDto bs = reportingService.balanceSheet(
                company.getId(), LocalDate.now(), null);

        var equitySection = bs.sections().stream()
                .filter(s -> s.sectionKey() == StatementSection.EQUITY)
                .findFirst().orElseThrow();

        boolean hasCurrentYearLine = equitySection.lines().stream()
                .anyMatch(l -> l.accountName() != null
                        && l.accountName().contains("Current-year earnings"));
        assertThat(hasCurrentYearLine)
                .as("Equity section must contain the 'Current-year earnings' synthetic fold line (BR-REP-05)")
                .isTrue();

        // The current-year earnings line should be non-zero (we posted a sale)
        BigDecimal currentYearEarnings = equitySection.lines().stream()
                .filter(l -> l.accountName() != null && l.accountName().contains("Current-year earnings"))
                .findFirst().orElseThrow()
                .amounts().current();
        assertThat(currentYearEarnings)
                .as("Current-year earnings must be positive after posting a sale")
                .isGreaterThan(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 3: Multi-year test — BS balances with activity across two fiscal years
    //         (pins the inception-to-date fold; FY-to-date would break year 2+)
    // =========================================================================

    @Test
    void balanceSheet_multiYear_stillBalancesInYearTwo() {
        // Seed a second fiscal year (next calendar year) so GL accepts postings for it
        int nextYear = LocalDate.now().getYear() + 1;
        fiscalCalendarService.openFiscalYear(new com.erp.modules.gl.domain.dto.OpenFiscalYearRequest(
                company.getUid(),
                "FY" + nextYear,
                1,          // startMonth = January
                nextYear)); // calendarYear

        // Post activity in year 1 (current year)
        finaliseAndDispatch();

        // Run BS as-at today (year 1): must balance
        BalanceSheetDto bsYear1 = reportingService.balanceSheet(
                company.getId(), LocalDate.now(), null);
        assertThat(bsYear1.reconciliation().ties())
                .as("BS must balance as-at year-1 date (BR-REP-02)")
                .isTrue();

        // Run BS as-at a date in year 2 with the year-1 activity still unclosed:
        // inception-to-date fold picks up both years' P&L residual — must still balance
        LocalDate year2Date = LocalDate.of(nextYear, 6, 30);
        BalanceSheetDto bsYear2 = reportingService.balanceSheet(
                company.getId(), year2Date, null);
        assertThat(bsYear2.reconciliation().ties())
                .as("BS must balance as-at a year-2 date with unclosed year-1 earnings (D-6 correctness guarantee)")
                .isTrue();
        assertThat(bsYear2.reconciliation().difference().current())
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Also verify the prior-years retained line is now present (year-1 earnings fold into prior)
        var equitySection = bsYear2.sections().stream()
                .filter(s -> s.sectionKey() == StatementSection.EQUITY)
                .findFirst().orElseThrow();
        boolean hasPriorLine = equitySection.lines().stream()
                .anyMatch(l -> l.accountName() != null
                        && l.accountName().contains("prior years"));
        assertThat(hasPriorLine)
                .as("Year-2 BS should show 'Retained earnings — prior years (unclosed)' line")
                .isTrue();
    }

    // -------------------------------------------------------------------------

    private void finaliseAndDispatch() {
        var draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(company.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, new BigDecimal("1"), null, null));
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, new BigDecimal("1180"), "TZS", null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        domainEventRepository.findAll().forEach(e -> {
            try { dispatcher.dispatchOne(e.getId()); } catch (Exception ex) { /* already dispatched */ }
        });
    }
}
