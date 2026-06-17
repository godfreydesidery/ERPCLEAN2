package com.erp.modules.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.service.CashBankAccountService;
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
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
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
 * Integration tests for Cash-Flow Statement (indirect method) (ADR-0018 D-7, FR-REP-03, BR-REP-04).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Self-check reconciliation ties: netChangeInCash == Δ(cash-equivalent GL accounts) (BR-REP-04).
 *   <li>Operating / investing / financing sections are present in the DTO.
 * </ol>
 *
 * <p>Setup: cash/bank account wired to GL 1000 (Cash), sales invoice finalised via dispatch so GL
 * receives the DR Cash / CR Revenue posting → cash-equivalent balance increases.
 */
class CashFlowIT extends PostgresIntegrationTest {

    @Autowired private ReportingService        reportingService;
    @Autowired private SalesInvoiceService     salesInvoiceService;
    @Autowired private TaxRateSeeder           taxRateSeeder;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private CashBankAccountService  cashBankAccountService;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private DomainEventRepository   domainEventRepository;
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private com.erp.modules.gl.repository.ChartOfAccountRepository glAccountRepo;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

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

        Organisation org = organisations.save(new Organisation("REP CF IT Org"));
        company  = companies.save(new Company(org, "RCFT", "REP CF IT Co"));
        branch   = branches.save(new Branch(company, "RCFT1", "REP CF IT Branch"));

        AppUser root = new AppUser("rcf_root", passwordEncoder.encode("RootPass1!"), "RCF Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "rcf_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        // Register the seeded GL Cash account (1000) as a cash-bank account so
        // CashEquivalentAccountResolver can resolve it for this company.
        String cashGlUid = glAccountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(a -> a.getUid())
                .orElseThrow(() -> new AssertionError("GL account 1000 not seeded"));

        cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "Petty Cash",
                CashBankAccountType.CASH, null, null, null, cashGlUid, true));

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        productUid   = productService.create(new CreateProductRequest(
                company.getUid(), null, "CF Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "CF Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        agentUid    = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "CF Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: CF self-check ties — netChangeInCash == Δ cash GL accounts (BR-REP-04)
    // =========================================================================

    @Test
    void cashFlow_selfCheckTies_netChangeEqualsGlCashDelta() {
        finaliseAndDispatch();

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        CashFlowStatementDto cf = reportingService.cashFlow(
                company.getId(), from, to, null, null);

        assertThat(cf.reconciliation().ties())
                .as("CF self-check: netChangeInCash must equal Δ cash-equivalent GL balances (BR-REP-04)")
                .isTrue();
        assertThat(cf.reconciliation().difference().current())
                .as("Reconciliation difference must be exactly zero (BigDecimal)")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 2: OPERATING / INVESTING / FINANCING sections present
    // =========================================================================

    @Test
    void cashFlow_allThreeSectionsPresent() {
        finaliseAndDispatch();

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        CashFlowStatementDto cf = reportingService.cashFlow(
                company.getId(), from, to, null, null);

        assertThat(cf.sections()).isNotNull();
        assertThat(cf.sections().stream()
                .anyMatch(s -> s.sectionKey() == StatementSection.OPERATING))
                .as("OPERATING section must be present")
                .isTrue();
        assertThat(cf.sections().stream()
                .anyMatch(s -> s.sectionKey() == StatementSection.INVESTING))
                .as("INVESTING section must be present")
                .isTrue();
        assertThat(cf.sections().stream()
                .anyMatch(s -> s.sectionKey() == StatementSection.FINANCING))
                .as("FINANCING section must be present")
                .isTrue();

        // Operating section must contain the Net income line (always present, even if zero)
        var operatingSection = cf.sections().stream()
                .filter(s -> s.sectionKey() == StatementSection.OPERATING)
                .findFirst().orElseThrow();
        assertThat(operatingSection.lines()).isNotEmpty();

        // Net change in cash should be non-zero (cash sale posted)
        assertThat(cf.netChangeInCash().current())
                .as("Net change in cash must be positive after a cash sale")
                .isGreaterThan(BigDecimal.ZERO);
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
        domainEventRepository.findAll().forEach(e -> {
            try { dispatcher.dispatchOne(e.getId()); } catch (Exception ex) { /* already dispatched */ }
        });
    }
}
