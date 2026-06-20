package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.erp.modules.products.domain.enums.RestrictedKind;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.platform.common.api.ConflictException;
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
 * Integration tests for the ADR-0044 D-3a age-restriction gate in POS sales.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>A product created with {@code restrictedKind=AGE_18} persists and is returned correctly.</li>
 *   <li>A POS sale containing an AGE_18 product is blocked (409) when {@code ageVerified} is absent.</li>
 *   <li>The same sale succeeds when re-submitted with {@code ageVerified=true}.</li>
 *   <li>A sale containing only NONE products is completely unaffected by the gate.</li>
 * </ol>
 *
 * <p>Uses the singleton Testcontainers container (Ryuk disabled via testcontainers.properties)
 * per the Windows convention ({@link PostgresIntegrationTest}).
 */
class PosAgeRestrictionIT extends PostgresIntegrationTest {

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

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    cashierId;   // non-root user with internal agent — used for POS sale context
    private Long    customerId;
    private String  pcsUid;
    private String  priceListUid;
    private String  sessionUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("POS Age IT Org"));
        company = companies.save(new Company(org, "POSAGE", "POS Age Co"));
        branch  = branches.save(new Branch(company, "PA1", "POS Age Branch"));

        AppUser root = new AppUser("posage_root", passwordEncoder.encode("R00t!Pass1"), "POS Age Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setRootCtx();

        // GL + tax infrastructure (required for POS finalise to post)
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        // Cash bank account on the seeded CoA account 1000 (CASH)
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

        // Non-root cashier user — BR-PARTY-10 forbids linking root as an internal agent.
        // Must belong to the company (via user_branch) so isActiveUserInCompany passes.
        AppUser cashier = new AppUser("posage_cashier", passwordEncoder.encode("C@sh1Pass"), "POS Cashier");
        cashier = users.save(cashier);
        cashierId = cashier.getId();
        userBranches.save(new UserBranch(cashierId, branch, rootId));

        // Seed an INTERNAL agent linked to the cashier — SalesInvoiceServiceImpl.resolveAgentId
        // (BR-SALES-06) looks up an internal agent by app_user_id when no agentUid is supplied.
        agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "POS Cashier Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, cashierId));

        customerId = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-In Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).id();

        // Till + OPEN session (root creates the till; session opened as cashier)
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
    // Bar 1: restricted_kind persists and is returned in ProductDto
    // =========================================================================

    @Test
    void create_product_age18_restrictedKindPersistsInDto() {
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, "Beer 500ml", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, RestrictedKind.AGE_18));

        assertThat(dto.restrictedKind())
                .as("AGE_18 must round-trip through ProductDto.from()")
                .isEqualTo(RestrictedKind.AGE_18);
    }

    @Test
    void create_product_noRestrictedKind_defaultsToNone() {
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, "Water Bottle", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));

        assertThat(dto.restrictedKind())
                .as("null restrictedKind in request must default to NONE")
                .isEqualTo(RestrictedKind.NONE);
    }

    // =========================================================================
    // Bar 2: POS sale with restricted product blocked when ageVerified absent
    // =========================================================================

    @Test
    void processSale_restrictedProduct_noAgeVerified_blockedWith409() {
        setRootCtx();
        String productUid = ageRestrictedProduct("Spirits 750ml", RestrictedKind.AGE_18);
        ProductDto dto = productService.getByUid(productUid);

        setCashierCtx();
        PosSaleRequest req = saleRequest(dto.id(), false /* no ageVerified */);

        assertThatThrownBy(() -> saleService.processSale(null, req))
                .as("AGE_18 product sale without ageVerified must be blocked (409)")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Age-restricted");
    }

    // =========================================================================
    // Bar 3: Same sale succeeds with ageVerified=true
    // =========================================================================

    @Test
    void processSale_restrictedProduct_ageVerifiedTrue_succeeds() {
        setRootCtx();
        String productUid = ageRestrictedProduct("Wine 750ml", RestrictedKind.AGE_18);
        ProductDto dto = productService.getByUid(productUid);

        setCashierCtx();
        PosSaleRequest req = saleRequest(dto.id(), true /* ageVerified */);

        assertThatCode(() -> saleService.processSale(null, req))
                .as("AGE_18 product sale with ageVerified=true must succeed")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Bar 4: NONE product is completely unaffected
    // =========================================================================

    @Test
    void processSale_noneProduct_noAgeVerified_proceeds() {
        setRootCtx();
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, "Mineral Water", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(dto.uid(), new SetProductPriceRequest(priceListUid, new MoneyDto("500", "TZS")));

        setCashierCtx();
        PosSaleRequest req = saleRequest(dto.id(), false);

        assertThatCode(() -> saleService.processSale(null, req))
                .as("NONE product must never be gated — sale proceeds without ageVerified")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Creates and prices a restricted product as root; caller must manage context switch. */
    private String ageRestrictedProduct(String name, RestrictedKind kind) {
        // Must be called under root context (PRODUCT.MANAGE).
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, kind));
        productService.setPrice(dto.uid(), new SetProductPriceRequest(priceListUid, new MoneyDto("3000", "TZS")));
        return dto.uid();
    }

    private PosSaleRequest saleRequest(Long productId, boolean ageVerified) {
        return new PosSaleRequest(
                sessionUid, customerId, null, "TZS",
                List.of(new PosSaleRequest.LineItem(productId, unitId(), BigDecimal.ONE, null, null)),
                null, BigDecimal.TEN, null,
                ageVerified ? Boolean.TRUE : null);
    }

    private Long unitId() {
        // Resolve the PCS unit id from its uid
        return unitService.getByUid(pcsUid).id();
    }

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "posage_root", true, company.getId(), branch.getId(), null));
    }

    private void setCashierCtx() {
        RequestContext.set(new RequestContext.Principal(
                cashierId, "posage_cashier", false, company.getId(), branch.getId(), null));
    }
}
