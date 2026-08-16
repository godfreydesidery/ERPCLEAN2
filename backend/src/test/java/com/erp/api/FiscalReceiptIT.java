package com.erp.api;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP-level integration coverage for the EFD/fiscal-receipt endpoints (ADR-0049). Drives requests
 * through the real Spring Security filter chain (bearer token + {@code @PreAuthorize} SpEL) — NOT
 * bare service calls — so the permission gates and the reused {@code 'invoice'} scope target are
 * exercised for real (RbacEnforcementHttpIT pattern).
 *
 * <p>Invoice fixtures are built via the sales/product/parties services directly under a root
 * {@link RequestContext} (fast path, mirrors {@code SalesInvoiceServiceImplIT}); only the
 * fiscal-receipt calls under test go through {@link MockMvc}.
 */
@AutoConfigureMockMvc
class FiscalReceiptIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private IamTestData testData;
    @Autowired private PermissionResolver permissionResolver;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private UserRoleService userRoleService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    // fixture services (build the FINALISED invoice under a root RequestContext)
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private TaxRateSeeder taxRateSeeder;
    @Autowired private SalesSettingsService salesSettingsService;
    @Autowired private CustomerService customerService;
    @Autowired private AgentService agentService;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;

    private Organisation org;
    private Company companyA;
    private Branch branchA;
    private AppUser rootUser;
    private String rootToken;

    private String customerAUid;
    private String agentAUid;
    private String pcsUid;
    private String priceListAUid;
    private String productAUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        org = organisations.save(new Organisation("Fiscal IT Org"));
        companyA = companies.save(new Company(org, "FSCA", "Fiscal Co A"));
        branchA = new Branch(companyA, "FS-A1", "Fiscal Branch A1");
        branchA.setDefault(true);
        branchA = branches.save(branchA);

        rootUser = new AppUser("fiscal_root", passwordEncoder.encode("RootPass123"), "Fiscal Root");
        rootUser.setRoot(true);
        rootUser.setOrganisationId(org.getId());
        rootUser = users.save(rootUser);
        UserBranch rootAssignment = new UserBranch(rootUser.getId(), branchA, rootUser.getId());
        rootAssignment.markDefault();
        userBranches.save(rootAssignment);

        rootToken = jwtService.issueAccessToken(rootUser, companyA.getId(), branchA.getId()).value();

        // Build the invoice fixture data via services, under a root RequestContext (fast path).
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                companyA.getId(), branchA.getId(), null));
        try {
            taxRateSeeder.seedDefaults(companyA.getId());

            // This suite is about fiscal-receipt issuance, not stock — its products are
            // never received. NegativeStockGuard now reads a missing sales_settings row as BLOCK
            // (fail-safe), so opt this company into backorder deliberately.
            salesSettingsService.update(new UpdateSalesSettingsRequest(
                    companyA.getUid(), false, null, "TZS", true));

            UnitOfMeasureDto pcs = unitService.create(
                    new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
            pcsUid = pcs.uid();

            PriceListDto pl = priceListService.create(
                    new CreatePriceListRequest(companyA.getUid(), "RETAIL", "Retail"));
            priceListAUid = pl.uid();

            ProductDto prod = productService.create(new CreateProductRequest(
                    companyA.getUid(), null, "Fiscal Widget", null,
                    ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                    null, null, null, null, null, null, null, null, null));
            productAUid = prod.uid();
            productService.setPrice(productAUid,
                    new SetProductPriceRequest(priceListAUid, new MoneyDto("1000", "TZS")));

            CustomerDto cust = customerService.create(new CreateCustomerRequest(
                    companyA.getId(), PartyType.INDIVIDUAL, "Fiscal Customer",
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    CustomerKind.CASH_WALK_IN, null, null, null));
            customerAUid = cust.uid();

            AgentDto ag = agentService.create(new CreateAgentRequest(
                    companyA.getId(), PartyType.INDIVIDUAL, "Fiscal Agent",
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    AgentKind.EXTERNAL, null));
            agentAUid = ag.uid();
        } finally {
            RequestContext.clear();
        }
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Default provider → NOT_CONFIGURED, never fabricates a number
    // -------------------------------------------------------------------------

    @Test
    void issue_defaultProvider_returnsNotConfigured() throws Exception {
        String invoiceUid = finaliseInvoiceAndGetUid();

        mockMvc.perform(post("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceUid").value(invoiceUid))
                .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.fiscalNumber").doesNotExist())
                .andExpect(jsonPath("$.data.attemptCount").value(1))
                .andExpect(jsonPath("$.data.errorDetail").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // Unique per invoice: a second issue call resolves to the SAME row (retry-in-place)
    // -------------------------------------------------------------------------

    @Test
    void issue_secondCall_retriesSameRow_attemptCountIncrements() throws Exception {
        String invoiceUid = finaliseInvoiceAndGetUid();

        MvcResult first = mockMvc.perform(post("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstData = objectMapper.readTree(first.getResponse().getContentAsString()).at("/data");

        MvcResult second = mockMvc.perform(post("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode secondData = objectMapper.readTree(second.getResponse().getContentAsString()).at("/data");

        assertThat(secondData.at("/uid").asText()).isEqualTo(firstData.at("/uid").asText());
        assertThat(secondData.at("/attemptCount").asInt()).isEqualTo(2);
        assertThat(secondData.at("/status").asText()).isEqualTo("NOT_CONFIGURED");
    }

    // -------------------------------------------------------------------------
    // GET returns the issued receipt; 404 before any issue call
    // -------------------------------------------------------------------------

    @Test
    void get_beforeIssue_returns404() throws Exception {
        String invoiceUid = finaliseInvoiceAndGetUid();

        mockMvc.perform(get("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_afterIssue_returnsSameData() throws Exception {
        String invoiceUid = finaliseInvoiceAndGetUid();

        mockMvc.perform(post("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceUid").value(invoiceUid))
                .andExpect(jsonPath("$.data.status").value("NOT_CONFIGURED"));
    }

    // -------------------------------------------------------------------------
    // Cross-company caller → 403 (reused 'invoice' scope target)
    // -------------------------------------------------------------------------

    @Test
    void issue_crossCompanyCaller_returns403() throws Exception {
        String invoiceUid = finaliseInvoiceAndGetUid();

        Company companyB = companies.save(new Company(org, "FSCB", "Fiscal Co B"));
        Branch branchB = new Branch(companyB, "FS-B1", "Fiscal Branch B1");
        branchB.setDefault(true);
        branchB = branches.save(branchB);

        AppUser userB = users.save(inOrganisation(
                new AppUser("fiscal_user_b", passwordEncoder.encode("Pass123!"), "Fiscal User B"),
                org.getId()));
        UserBranch assignmentB = new UserBranch(userB.getId(), branchB, userB.getId());
        assignmentB.markDefault();
        userBranches.save(assignmentB);

        // Grant FISCAL.MANAGE in company B so the only reason for denial is the scope mismatch,
        // not a missing permission (isolates the cross-company assertion from the phantom-perm case).
        Role manageRoleB = buildRole("FISCAL_MANAGER_B", "FISCAL.MANAGE");
        grantRoleAsRoot(userB, manageRoleB, companyB);

        String tokenB = jwtService.issueAccessToken(userB, companyB.getId(), branchB.getId()).value();

        mockMvc.perform(post("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Non-root user without FISCAL.MANAGE → 403 (phantom-permission trap: test as non-root)
    // -------------------------------------------------------------------------

    @Test
    void issue_nonRootWithoutFiscalManage_returns403() throws Exception {
        String invoiceUid = finaliseInvoiceAndGetUid();

        AppUser userNoPerm = users.save(inOrganisation(new AppUser(
                "fiscal_user_noperm", passwordEncoder.encode("Pass123!"), "No Perm User"),
                org.getId()));
        UserBranch assignment = new UserBranch(userNoPerm.getId(), branchA, userNoPerm.getId());
        assignment.markDefault();
        userBranches.save(assignment);
        testData.seedMembership(userNoPerm.getUid(), companyA.getUid());

        String token = jwtService.issueAccessToken(userNoPerm, companyA.getId(), branchA.getId()).value();

        mockMvc.perform(post("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_nonRootWithoutFiscalView_returns403() throws Exception {
        String invoiceUid = finaliseInvoiceAndGetUid();

        AppUser userNoPerm = users.save(inOrganisation(new AppUser(
                "fiscal_user_noview", passwordEncoder.encode("Pass123!"), "No View User"),
                org.getId()));
        UserBranch assignment = new UserBranch(userNoPerm.getId(), branchA, userNoPerm.getId());
        assignment.markDefault();
        userBranches.save(assignment);
        testData.seedMembership(userNoPerm.getUid(), companyA.getUid());

        String token = jwtService.issueAccessToken(userNoPerm, companyA.getId(), branchA.getId()).value();

        mockMvc.perform(get("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", invoiceUid)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Non-FINALISED invoice → 409
    // -------------------------------------------------------------------------

    @Test
    void issue_draftInvoice_returns409() throws Exception {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true, companyA.getId(), branchA.getId(), null));
        SalesInvoiceDto draft;
        try {
            draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                    companyA.getUid(), customerAUid, agentAUid, "TZS", null, null));
        } finally {
            RequestContext.clear();
        }

        mockMvc.perform(post("/api/v1/sales-invoices/uid/{uid}/fiscal-receipt", draft.uid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Builds + finalises a DRAFT invoice via the service layer under a root context; returns its uid. */
    private String finaliseInvoiceAndGetUid() {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true, companyA.getId(), branchA.getId(), null));
        try {
            SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                    companyA.getUid(), customerAUid, agentAUid, "TZS", null, null));
            salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                    productAUid, pcsUid, new BigDecimal("1"), null, null));
            salesInvoiceService.addPayment(draft.uid(), new AddPaymentRequest(
                    TenderType.CASH, new BigDecimal("1180"), "TZS", null));
            salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
            return draft.uid();
        } finally {
            RequestContext.clear();
        }
    }

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by R__seed_permissions.sql"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    private void grantRoleAsRoot(AppUser user, Role role, Company company) {
        testData.seedMembership(user.getUid(), company.getUid());
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                companyA.getId(), branchA.getId(), null));
        try {
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), company.getUid(), null));
        } finally {
            RequestContext.clear();
        }
    }
}
