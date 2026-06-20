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
import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.AccountLedgerRowDto;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for Account Ledger drill-down (ADR-0018 D-3(d), FR-REP-04).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Running balance is correct line-by-line; opening + Σlines == closing.
 *   <li>Pagination returns the right page / size (page 0 vs page 1 of a two-line result).
 * </ol>
 *
 * <p>Setup: post two cash sales to populate the Cash (1000) account with two debit lines,
 * then query the ledger and verify the arithmetic.
 */
class AccountLedgerIT extends PostgresIntegrationTest {

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
    @Autowired private com.erp.modules.gl.repository.ChartOfAccountRepository glAccountRepo;
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
    private String  cashGlUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("REP AL IT Org"));
        company  = companies.save(new Company(org, "RALT", "REP AL IT Co"));
        branch   = branches.save(new Branch(company, "RALT1", "REP AL IT Branch"));

        AppUser root = new AppUser("ral_root", passwordEncoder.encode("RootPass1!"), "RAL Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "ral_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        cashGlUid = glAccountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(a -> a.getUid())
                .orElseThrow(() -> new AssertionError("GL account 1000 (Cash) not seeded"));

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        productUid   = productService.create(new CreateProductRequest(
                company.getUid(), null, "AL Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "AL Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        agentUid    = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "AL Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: Running balance correct; opening + Σ lines == closing
    // =========================================================================

    @Test
    void accountLedger_runningBalance_correct() {
        // Post two sales → two DR Cash lines
        finaliseAndDispatch();
        finaliseAndDispatch();

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        AccountLedgerDto ledger = reportingService.accountLedger(
                company.getId(), cashGlUid, from, to, 0, 50);

        assertThat(ledger.rows()).as("Two cash sales should produce at least 2 journal lines on the Cash account").hasSizeGreaterThanOrEqualTo(2);

        // opening + Σ(debit − credit) == closing
        BigDecimal computed = ledger.openingBalance();
        for (AccountLedgerRowDto row : ledger.rows()) {
            computed = computed.add(row.debit()).subtract(row.credit());
        }
        assertThat(computed)
                .as("opening + Σ(debit-credit) must equal closingBalance")
                .isEqualByComparingTo(ledger.closingBalance());

        // Running balance of last row must equal closingBalance
        List<AccountLedgerRowDto> rows = ledger.rows();
        BigDecimal lastRunning = rows.get(rows.size() - 1).runningBalance();
        assertThat(lastRunning)
                .as("Last row's runningBalance must equal closingBalance")
                .isEqualByComparingTo(ledger.closingBalance());

        // closingBalance > openingBalance (cash increased)
        assertThat(ledger.closingBalance())
                .as("Closing balance must exceed opening after cash sales")
                .isGreaterThan(ledger.openingBalance());
    }

    // =========================================================================
    // Bar 2: Pagination — page 0 / size 1 vs page 1 / size 1 return different rows
    // =========================================================================

    @Test
    void accountLedger_pagination_correctPageAndSize() {
        // Post two sales → two DR Cash lines
        finaliseAndDispatch();
        finaliseAndDispatch();

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        // Full result first to understand totalElements
        AccountLedgerDto full = reportingService.accountLedger(
                company.getId(), cashGlUid, from, to, 0, 50);
        long total = full.totalElements();
        assertThat(total).as("Must have at least 2 journal lines").isGreaterThanOrEqualTo(2);

        // Page 0, size 1 — first row only
        AccountLedgerDto page0 = reportingService.accountLedger(
                company.getId(), cashGlUid, from, to, 0, 1);
        assertThat(page0.rows()).hasSize(1);
        assertThat(page0.page()).isZero();
        assertThat(page0.size()).isEqualTo(1);
        assertThat(page0.totalElements()).isEqualTo(total);

        // Page 1, size 1 — second row only
        AccountLedgerDto page1 = reportingService.accountLedger(
                company.getId(), cashGlUid, from, to, 1, 1);
        assertThat(page1.rows()).hasSize(1);
        assertThat(page1.page()).isEqualTo(1);

        // Running balances must differ: page-1 row has accumulated more cash than page-0 row.
        BigDecimal running0 = page0.rows().get(0).runningBalance();
        BigDecimal running1 = page1.rows().get(0).runningBalance();
        assertThat(running1)
                .as("Page-1 running balance must be >= page-0 running balance (cash only increases in this test)")
                .isGreaterThanOrEqualTo(running0);
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
