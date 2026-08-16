package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.CreateApprovalPolicyRequest;
import com.erp.modules.approvals.domain.dto.PolicyStepInputDto;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.approvals.service.ApprovalPolicyService;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
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
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Real-{@code PlatformTransactionManager} coverage for the D-4 amount-threshold approval gate at
 * SO confirm (persona UAT I3, follow-ups R1/R2).
 *
 * <p>{@code SalesOrderServiceImplTest} exercises the same gate but mocks {@link SalesApprovalGate}
 * entirely — none of those tests exercise the real {@code REQUIRES_NEW} propagation on {@link
 * SalesApprovalGate#submitIndependent}. If that propagation were ever accidentally downgraded to
 * {@code REQUIRED}/{@code MANDATORY}, the mocked unit-test suite would stay green while the
 * stuck-forever bug (I3) — or its R1 corollary, a permanent no-policy poison — silently returned.
 * This class uses a real Postgres transaction manager (Testcontainers, no mocks) to prove both:
 *
 * <ol>
 *   <li>Policy configured + over-threshold: confirm throws the "submitted for approval" block, but
 *       the {@code ApprovalRequest} row IS durable (PENDING) — read back via a brand-new top-level
 *       service call, not the one that just rolled back.</li>
 *   <li>No matching policy + over-threshold: confirm throws the "no approval policy is configured"
 *       block, and NO {@code ApprovalRequest} row is left behind anywhere — proving R1's fix (the
 *       misconfiguration case rolls back its own independent transaction).</li>
 * </ol>
 *
 * <p><b>Note for review:</b> this is an {@code *IT} (failsafe/{@code verify}, Testcontainers
 * Postgres) — not run as part of the {@code mvn test} unit/ArchUnit gate.
 */
class SalesOrderApprovalGateTransactionIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private RoleRepository         roles;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private TaxRateSeeder          taxRateSeeder;

    @Autowired private ProductService         productService;
    @Autowired private PriceListService       priceListService;
    @Autowired private UnitOfMeasureService   unitService;
    @Autowired private CustomerService        customerService;
    @Autowired private AgentService           agentService;

    @Autowired private SalesOrderService      salesOrderService;
    @Autowired private SalesSettingsService   salesSettingsService;
    @Autowired private ApprovalPolicyService  approvalPolicyService;
    @Autowired private ApprovalEngine         approvalEngine;

    private static final String DOC_TYPE = "SALES_ORDER";

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  pcsUid;
    private String  priceListUid;
    private String  agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("SO Approval TX Org"));
        company = companies.save(new Company(org, "SOAT", "SO Approval TX Co"));
        branch  = branches.save(new Branch(company, "SOAT1", "SO Approval TX Branch"));

        AppUser root = new AppUser("soat_root", passwordEncoder.encode("R00t!Pass1"), "SOAT Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        setCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "SOAT Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // SO amount-threshold gate enabled, threshold 1000 — every order built below is above the
        // threshold so the D-4 gate always fires.
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), true, new BigDecimal("1000"), "TZS", false));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // 1. Policy configured: confirm blocks, but the PENDING request survives the rollback
    // =========================================================================

    @Test
    void confirm_overThreshold_policyConfigured_requestSurvivesRollback_orderStaysDraft() {
        roles.save(new Role("SALES_APPROVER", "Sales Approver"));
        approvalPolicyService.create(new CreateApprovalPolicyRequest(
                company.getId(), DOC_TYPE, "SO threshold policy",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000"), null, null,
                List.of(new PolicyStepInputDto(1, "SALES_APPROVER"))));

        SalesOrderDto so = orderOverThreshold();

        setCtx();
        assertThatThrownBy(() -> salesOrderService.confirm(so.uid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submitted for approval");

        // Read back via a brand-new top-level service call — not the transaction that just rolled
        // back the blocked confirm.
        setCtx();
        Optional<ApprovalRequestDto> state = approvalEngine.getApprovalState(
                DOC_TYPE, so.uid(), company.getId());
        assertThat(state)
                .as("R2: the PENDING request written by the REQUIRES_NEW submitIndependent call "
                        + "must survive the enclosing confirm transaction's rollback")
                .isPresent();
        assertThat(state.get().status()).isEqualTo(ApprovalRequestStatus.PENDING);

        setCtx();
        SalesOrderDto reread = salesOrderService.getByUid(so.uid());
        assertThat(reread.status()).isEqualTo(SalesOrderStatus.DRAFT.name());
    }

    // =========================================================================
    // 2. No matching policy: confirm blocks, and NO request is left behind (R1)
    // =========================================================================

    @Test
    void confirm_overThreshold_noMatchingPolicy_noDurableRequest_orderStaysDraft() {
        // Deliberately no ApprovalPolicy row for SALES_ORDER in this company.
        SalesOrderDto so = orderOverThreshold();

        setCtx();
        assertThatThrownBy(() -> salesOrderService.confirm(so.uid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no approval policy is configured");

        setCtx();
        Optional<ApprovalRequestDto> state = approvalEngine.getApprovalState(
                DOC_TYPE, so.uid(), company.getId());
        assertThat(state)
                .as("R1: the auto-approved-by-default request must roll back with its own "
                        + "independent transaction — no durable ApprovalRequest row for a "
                        + "misconfigured order")
                .isEmpty();

        setCtx();
        SalesOrderDto reread = salesOrderService.getByUid(so.uid());
        assertThat(reread.status()).isEqualTo(SalesOrderStatus.DRAFT.name());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "soat_root", true, company.getId(), branch.getId(), null));
    }

    /** Creates a DRAFT sales order with one line whose gross total exceeds the 1000 threshold. */
    private SalesOrderDto orderOverThreshold() {
        setCtx();
        ProductDto product = productService.create(new CreateProductRequest(
                company.getUid(), null, "Approval Widget " + System.nanoTime(), null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("2000", "TZS")));

        setCtx();
        String customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Approval Customer " + System.nanoTime(),
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();

        setCtx();
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), null, null, null, null));
        setCtx();
        salesOrderService.addLine(so.uid(), new AddSalesOrderLineRequest(
                product.uid(), pcsUid, new BigDecimal("2"), null, null, null));
        return so;
    }
}
