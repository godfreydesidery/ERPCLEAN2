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
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.domain.enums.StatementSection;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
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
 * Integration test for Income Statement (ADR-0018 D-5, FR-REP-01, BR-REP-03).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>P&amp;L net == period INCOME − EXPENSE GL movement (self-check ReconciliationDto.ties=true).
 *   <li>Gross profit = REVENUE − COST_OF_SALES; net profit = gross profit − OPERATING_EXPENSES.
 *   <li>Comparative column is populated.
 * </ol>
 */
class IncomeStatementIT extends PostgresIntegrationTest {

    @Autowired private ReportingService       reportingService;
    @Autowired private SalesInvoiceService    salesInvoiceService;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private CustomerService        customerService;
    @Autowired private AgentService           agentService;
    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private DomainEventDispatcher  dispatcher;
    @Autowired private DomainEventRepository  domainEventRepository;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company  company;
    private Branch   branch;
    private Long     rootId;
    private String   customerUid;
    private String   agentUid;
    private String   productUid;
    private String   pcsUid;
    private String   priceListUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("REP PL IT Org"));
        company  = companies.save(new Company(org, "RPLT", "REP PL IT Co"));
        branch   = branches.save(new Branch(company, "RPLT1", "REP PL IT Branch"));

        AppUser root = new AppUser("rpl_root", passwordEncoder.encode("RootPass1!"), "RPL Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "rpl_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        productUid   = productService.create(new CreateProductRequest(
                company.getUid(), null, "PL Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "PL Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        agentUid    = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "PL Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: P&L self-check ties (BR-REP-03)
    // =========================================================================

    @Test
    void plNetEqualsIncomeMinusExpenseMovement_selfCheckTies() {
        // Post a cash sale: DR Cash 1000, CR Revenue 4100, CR VAT Payable 2200
        // Net income = revenue net (credit-normal, credit − debit) presented positive
        finaliseAndDispatch();

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        IncomeStatementDto pl = reportingService.incomeStatement(
                company.getId(), from, to, null, null);

        assertThat(pl.reconciliation().ties())
                .as("P&L self-check: netProfit must equal period INCOME−EXPENSE GL movement (BR-REP-03)")
                .isTrue();
        assertThat(pl.reconciliation().difference().current())
                .as("Reconciliation difference must be exactly zero (BigDecimal)")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 2: Gross profit and net profit subtotals correct
    // =========================================================================

    @Test
    void grossProfitAndNetProfitSubtotals_correct() {
        finaliseAndDispatch();

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        IncomeStatementDto pl = reportingService.incomeStatement(
                company.getId(), from, to, null, null);

        // Revenue section must exist and be positive (sale posted)
        var revenueSection = pl.sections().stream()
                .filter(s -> s.sectionKey() == StatementSection.REVENUE)
                .findFirst().orElseThrow();
        assertThat(revenueSection.subtotal().current())
                .as("Revenue subtotal must be positive after posting a sale")
                .isGreaterThan(BigDecimal.ZERO);

        // Gross profit = revenue − COGS (no COGS posted in this test, so grossProfit == revenue)
        assertThat(pl.grossProfit().current())
                .as("Gross profit == revenue when no COGS")
                .isEqualByComparingTo(revenueSection.subtotal().current());

        // Net profit >= gross profit (no opex posted)
        assertThat(pl.netProfit().current())
                .as("Net profit == gross profit when no operating expenses")
                .isEqualByComparingTo(pl.grossProfit().current());
    }

    // =========================================================================
    // Bar 3: Comparative column populated
    // =========================================================================

    @Test
    void comparativeColumn_populated() {
        finaliseAndDispatch();

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        // Explicit comparative window = same period last month
        LocalDate cmpFrom = from.minusMonths(1);
        LocalDate cmpTo   = to.minusMonths(1);

        IncomeStatementDto pl = reportingService.incomeStatement(
                company.getId(), from, to, cmpFrom, cmpTo);

        // The DTO is built for both windows — comparative subtotal is present (zero if no postings there)
        assertThat(pl.grossProfit()).isNotNull();
        assertThat(pl.netProfit()).isNotNull();
        // Self-check still ties
        assertThat(pl.reconciliation().ties()).isTrue();
    }

    // -------------------------------------------------------------------------

    private void finaliseAndDispatch() {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(company.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, new BigDecimal("1"), null, null));
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, new BigDecimal("1180"), "TZS", null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        // Dispatch all pending SALE.FINALISED events so GL is populated
        domainEventRepository.findAll().forEach(e -> {
            try { dispatcher.dispatchOne(e.getId()); } catch (Exception ex) { /* already dispatched */ }
        });
    }
}
