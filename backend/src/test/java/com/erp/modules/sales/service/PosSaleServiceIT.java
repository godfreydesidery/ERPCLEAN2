package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.service.CashBankAccountService;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
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
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Basic end-to-end integration test for the core POS sale path
 * ({@link PosSaleService#processSale}) against a real Postgres.
 *
 * <p><b>Why this exists:</b> until ADR-0044 Wave 1, NO integration test rang a real POS sale through
 * to a persisted {@code origin='POS'} invoice — which is exactly why the
 * {@code chk_sales_invoice_origin_refs} gap (a POS insert violating the V17 constraint, fixed in V72)
 * went unnoticed. This IT is the permanent guard for that class of bug: it proves a plain POS sale
 * finalises and persists with {@code origin=POS}, and that an idempotent replay returns the original
 * invoice without creating a second.
 *
 * <p>Uses the singleton Testcontainers container (Ryuk disabled per the Windows convention,
 * {@link PostgresIntegrationTest}).
 */
class PosSaleServiceIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private UserBranchRepository    userBranches;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private ChartOfAccountRepository chartOfAccountRepo;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    @Autowired private CashBankAccountService  cashBankAccountService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private PriceListService        priceListService;
    @Autowired private ProductService          productService;
    @Autowired private AgentService            agentService;
    @Autowired private CustomerService         customerService;
    @Autowired private PosTillService          tillService;
    @Autowired private PosSessionService       sessionService;
    @Autowired private PosSaleService          saleService;
    @Autowired private SalesInvoiceRepository  invoiceRepo;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    cashierId;
    private Long    customerId;
    private String  pcsUid;
    private String  priceListUid;
    private String  sessionUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("POS Sale IT Org"));
        company = companies.save(new Company(org, "POSSALE", "POS Sale Co"));
        branch  = branches.save(new Branch(company, "PS1", "POS Sale Branch"));

        AppUser root = new AppUser("possale_root", passwordEncoder.encode("R00t!Pass1"), "POS Sale Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setRootCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        String cashGlUid = chartOfAccountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(a -> a.getUid())
                .orElseGet(() ->
                        chartOfAccountRepo
                                .findByCompanyIdAndAccountCode(company.getId(), "1100")
                                .map(a -> a.getUid())
                                .orElseThrow(() -> new IllegalStateException(
                                        "No cash GL account seeded for the test company")));
        cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "POS Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, true));

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        AppUser cashier = new AppUser("possale_cashier", passwordEncoder.encode("C@sh1Pass"), "POS Cashier");
        cashier = users.save(cashier);
        cashierId = cashier.getId();
        userBranches.save(new UserBranch(cashierId, branch, rootId));

        agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "POS Cashier Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, cashierId));

        customerId = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-In Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).id();

        PosTillDto till = tillService.createTill(new CreatePosTillRequest(
                company.getUid(), branch.getId(), "Till 1", null));
        setCashierCtx();
        PosSessionDto session = sessionService.openSession(
                new OpenSessionRequest(till.uid(), BigDecimal.ZERO));
        sessionUid = session.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Core guard: a plain POS cash sale finalises and persists origin=POS.
    // (This is the regression guard for the V17/V37/V72 chk_sales_invoice_origin_refs gap.)
    // =========================================================================

    @Test
    void processSale_cashSale_finalisesAndPersistsPosOrigin() {
        setRootCtx();
        Long productId = productService.getByUid(pricedProduct("Bread", "1000")).id();

        setCashierCtx();
        SalesInvoiceDto result = saleService.processSale(null, cashSaleRequest(productId));

        assertThat(result.status())
                .as("POS sale must finalise")
                .isEqualTo(InvoiceStatus.FINALISED);
        assertThat(result.grossTotalAmount()).isGreaterThan(BigDecimal.ZERO);

        SalesInvoice persisted = invoiceRepo.findByUid(result.uid()).orElseThrow();
        assertThat(persisted.getOrigin())
                .as("POS sale must persist origin=POS — guards chk_sales_invoice_origin_refs (V72)")
                .isEqualTo(DocumentOrigin.POS);
        assertThat(persisted.getPosSessionId())
                .as("POS sale must be tagged to its session")
                .isNotNull();
    }

    // =========================================================================
    // Idempotency replay: same key returns the original invoice, no second sale.
    // =========================================================================

    @Test
    void processSale_idempotencyReplay_returnsOriginalAndDoesNotDoublePost() {
        setRootCtx();
        Long productId = productService.getByUid(pricedProduct("Sugar 1kg", "2000")).id();

        setCashierCtx();
        PosSaleRequest req = cashSaleRequest(productId);
        String key = "pos-sale-it-key-001";

        SalesInvoiceDto first  = saleService.processSale(key, req);
        SalesInvoiceDto replay = saleService.processSale(key, req);

        assertThat(replay.uid())
                .as("a replay with the same Idempotency-Key returns the original invoice")
                .isEqualTo(first.uid());

        long posInvoices = invoiceRepo.findAll().stream()
                .filter(i -> i.getOrigin() == DocumentOrigin.POS)
                .count();
        assertThat(posInvoices)
                .as("a replay must not create a second POS invoice")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------------- helpers

    /** Creates a non-restricted GOODS product (restrictedKind defaults to NONE) with a retail price. */
    private String pricedProduct(String name, String price) {
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(dto.uid(), new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        return dto.uid();
    }

    /** One-line exact-cash sale (no tenders => server settles exact gross in CASH; no ageVerified). */
    private PosSaleRequest cashSaleRequest(Long productId) {
        return new PosSaleRequest(
                sessionUid, customerId, null, "TZS",
                List.of(new PosSaleRequest.LineItem(productId, unitId(), BigDecimal.ONE, null, null)),
                null, BigDecimal.TEN, null, null);
    }

    private Long unitId() {
        return unitService.getByUid(pcsUid).id();
    }

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "possale_root", true, company.getId(), branch.getId(), null));
    }

    private void setCashierCtx() {
        RequestContext.set(new RequestContext.Principal(
                cashierId, "possale_cashier", false, company.getId(), branch.getId(), null));
    }
}
