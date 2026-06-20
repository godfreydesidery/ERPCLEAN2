package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CreditStatus;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
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
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.money.MoneyDto;
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
 * Integration tests for the ADR-0040 D-5 credit-control hard-block at sales-order confirm
 * ({@link SalesOrderServiceImpl#confirm} → {@code assertCreditClearance}).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>A CREDIT_ACCOUNT customer with creditStatus ON_HOLD/STOPPED is blocked at confirm with a
 *       {@link ConflictException} when the caller lacks SALES.CREDIT.OVERRIDE (non-root user).</li>
 *   <li>A caller that holds SALES.CREDIT.OVERRIDE (root) confirms the same blocked order and the
 *       override is audited (SALES.CREDIT.OVERRIDE audit row).</li>
 *   <li>An OK (and an advisory WARNING) credit status confirms normally — WARNING never blocks.</li>
 *   <li>A CASH_WALK_IN customer is unaffected by credit control even when STOPPED.</li>
 * </ol>
 */
class SalesOrderCreditBlockIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;

    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private CustomerService        customerService;
    @Autowired private CustomerRepository     customerRepo;
    @Autowired private AgentService           agentService;
    @Autowired private TaxRateSeeder          taxRateSeeder;
    @Autowired private SalesOrderService      salesOrderService;
    @Autowired private AuditRepository        auditRepo;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    clerkId;
    private String  pcsUid;
    private String  priceListUid;
    private String  agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("SO Credit IT Org"));
        company = companies.save(new Company(org, "SOCR", "SO Credit IT Co"));
        branch  = branches.save(new Branch(company, "SOCR1", "SO Credit IT Branch"));

        AppUser root = new AppUser("socr_root", passwordEncoder.encode("R00t!Pass1"), "SOCR Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        // Non-root sales clerk in the SAME company — passes ScopeGuard but has no granted
        // permissions, so SALES.CREDIT.OVERRIDE is denied (used for the block bar).
        AppUser clerk = users.save(
                new AppUser("socr_clerk", passwordEncoder.encode("Cl3rk!Pass1"), "SOCR Clerk"));
        clerkId = clerk.getId();

        setRootCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "SO Credit Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: STOPPED credit customer blocked at confirm for a non-override caller
    // =========================================================================

    @Test
    void confirm_stoppedCreditCustomer_nonOverrideCaller_blockedWithConflict() {
        String customerUid = creditCustomer("Blocked Co", CreditStatus.STOPPED);
        SalesOrderDto so = orderFor(customerUid);

        // Act as the non-root clerk (no SALES.CREDIT.OVERRIDE) in the same company.
        setClerkCtx();
        assertThatThrownBy(() -> salesOrderService.confirm(so.uid()))
                .as("STOPPED credit customer must be hard-blocked at SO confirm (ADR-0040 D-5)")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("SALES.CREDIT.OVERRIDE");

        // The order must remain unconfirmed (TX rolled back).
        setRootCtx();
        assertThat(salesOrderService.getByUid(so.uid()).status())
                .isEqualTo(SalesOrderStatus.DRAFT.name());
    }

    // =========================================================================
    // Bar 2: ON_HOLD likewise blocked for a non-override caller
    // =========================================================================

    @Test
    void confirm_onHoldCreditCustomer_nonOverrideCaller_blockedWithConflict() {
        String customerUid = creditCustomer("Hold Co", CreditStatus.ON_HOLD);
        SalesOrderDto so = orderFor(customerUid);

        setClerkCtx();
        assertThatThrownBy(() -> salesOrderService.confirm(so.uid()))
                .as("ON_HOLD credit customer must be hard-blocked at SO confirm (ADR-0040 D-5)")
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // Bar 3: caller with SALES.CREDIT.OVERRIDE (root) confirms + override audited
    // =========================================================================

    @Test
    void confirm_stoppedCreditCustomer_overrideCaller_confirmsAndAudits() {
        String customerUid = creditCustomer("Override Co", CreditStatus.STOPPED);
        SalesOrderDto so = orderFor(customerUid);

        // Root always holds SALES.CREDIT.OVERRIDE (PermissionResolver root short-circuit).
        setRootCtx();
        SalesOrderDto confirmed = salesOrderService.confirm(so.uid());
        assertThat(confirmed.status())
                .as("override caller must be able to confirm a credit-blocked order")
                .isEqualTo(SalesOrderStatus.CONFIRMED.name());

        // The override must be audited (SALES.CREDIT.OVERRIDE on sales_orders).
        boolean audited = auditRepo.findAll().stream()
                .anyMatch(a -> AuditActions.SALES_CREDIT_OVERRIDE.equals(a.getAction())
                            && so.uid().equals(a.getTargetUid()));
        assertThat(audited)
                .as("a SALES.CREDIT.OVERRIDE audit row must be written for the bypassed order")
                .isTrue();
    }

    // =========================================================================
    // Bar 4: OK and WARNING credit statuses confirm normally (WARNING never blocks)
    // =========================================================================

    @Test
    void confirm_okCreditCustomer_confirmsNormally() {
        String customerUid = creditCustomer("OK Co", CreditStatus.OK);
        SalesOrderDto so = orderFor(customerUid);

        setClerkCtx();
        SalesOrderDto confirmed = salesOrderService.confirm(so.uid());
        assertThat(confirmed.status()).isEqualTo(SalesOrderStatus.CONFIRMED.name());
    }

    @Test
    void confirm_warningCreditCustomer_advisoryOnly_confirmsNormally() {
        String customerUid = creditCustomer("Warn Co", CreditStatus.WARNING);
        SalesOrderDto so = orderFor(customerUid);

        // Even a non-override caller proceeds — WARNING is advisory, never a block.
        setClerkCtx();
        SalesOrderDto confirmed = salesOrderService.confirm(so.uid());
        assertThat(confirmed.status())
                .as("WARNING credit status is advisory and must NOT block confirm (ADR-0040 D-5)")
                .isEqualTo(SalesOrderStatus.CONFIRMED.name());
    }

    // =========================================================================
    // Bar 5: CASH_WALK_IN customer unaffected by credit control even when STOPPED
    // =========================================================================

    @Test
    void confirm_cashWalkInCustomer_unaffectedByCreditControl() {
        // A cash/walk-in customer; force a STOPPED status to prove the gate is bypassed by kind.
        String customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Cash Co",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        Customer cust = customerRepo.findByCompanyIdAndUid(company.getId(), customerUid).orElseThrow();
        cust.setCreditStatus(CreditStatus.STOPPED);
        customerRepo.save(cust);

        SalesOrderDto so = orderFor(customerUid);

        setClerkCtx();
        SalesOrderDto confirmed = salesOrderService.confirm(so.uid());
        assertThat(confirmed.status())
                .as("cash/walk-in customers bypass credit control entirely (BR-SO-CREDIT-01)")
                .isEqualTo(SalesOrderStatus.CONFIRMED.name());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "socr_root", true, company.getId(), branch.getId(), null));
    }

    private void setClerkCtx() {
        RequestContext.set(new RequestContext.Principal(
                clerkId, "socr_clerk", false, company.getId(), branch.getId(), null));
    }

    /** Creates a CREDIT_ACCOUNT customer then stamps the given credit status. */
    private String creditCustomer(String name, CreditStatus status) {
        setRootCtx();
        String uid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, name,
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
        Customer cust = customerRepo.findByCompanyIdAndUid(company.getId(), uid).orElseThrow();
        cust.setCreditStatus(status);
        customerRepo.save(cust);
        return uid;
    }

    /** Creates a DRAFT sales order with one line (1 unit @ 1000) for the customer. */
    private SalesOrderDto orderFor(String customerUid) {
        setRootCtx();
        ProductDto product = productService.create(new CreateProductRequest(
                company.getUid(), null, "Credit Widget " + System.nanoTime(), null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        setRootCtx();
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), null, null, null, null));
        setRootCtx();
        salesOrderService.addLine(so.uid(), new AddSalesOrderLineRequest(
                product.uid(), pcsUid, new BigDecimal("1"), null, null, null));
        return so;
    }
}
