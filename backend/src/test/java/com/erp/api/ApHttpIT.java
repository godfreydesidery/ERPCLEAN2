package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
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
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level gate regression guard for the AP controller layer (ADR-0015).
 *
 * <p>Drives requests through the REAL Spring Security filter chain (bearer token → filter →
 * {@code @PreAuthorize} SpEL). Deep AP logic is covered by the service ITs.
 * Guards tested here:
 * <ul>
 *   <li>root GET /ap/supplier-bills → 200 with {@code @perm.has} bypass.
 *   <li>non-root without AP.VIEW → 403 with ApiResponse envelope.
 *   <li>non-root WITH AP.VIEW granted → 200 (grant flips 403 without re-issuing token).
 *   <li>root POST /ap/supplier-bills with structurally valid body → 4xx (not 500).
 *   <li>AP.BILL.ENTER protected: plain user without perm → 403.
 *   <li>Statement / reconciliation / ageing endpoints return 200 for root.
 * </ul>
 */
@AutoConfigureMockMvc
class ApHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc           mockMvc;
    @Autowired private ObjectMapper      objectMapper;
    @Autowired private IamTestData       testData;
    @Autowired private PermissionResolver permissionResolver;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private RoleRepository         roles;
    @Autowired private PermissionRepository   permissions;
    @Autowired private UserBranchRepository   userBranches;
    @Autowired private UserRoleService        userRoleService;
    @Autowired private JwtService             jwtService;
    @Autowired private PasswordEncoder        passwordEncoder;

    private Organisation org;
    private Company      company;
    private Branch       branch;
    private AppUser      rootUser;
    private AppUser      plainUser;
    private String       rootToken;
    private String       plainToken;

    private static final String ROOT_PASS  = "ApRootH1!z";
    private static final String PLAIN_PASS = "ApPlainH1!z";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        org     = organisations.save(new Organisation("AP HTTP Test Org"));
        company = companies.save(new Company(org, "APHTTP", "AP HTTP Co"));

        branch = new Branch(company, "APHTTP1", "AP HTTP HQ");
        branch.setDefault(true);
        branch = branches.save(branch);

        rootUser = new AppUser("ap_http_root", passwordEncoder.encode(ROOT_PASS), "AP HTTP Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("ap_http_plain", passwordEncoder.encode(PLAIN_PASS), "AP HTTP Plain");
        plainUser = users.save(plainUser);
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        rootToken  = jwtService.issueAccessToken(rootUser,  company.getId(), branch.getId()).value();
        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Supplier bills — list
    // =========================================================================

    @Test
    void rootListsSupplierBills_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ap/supplier-bills")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void plainUserWithoutPerm_listSupplierBills_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/ap/supplier-bills")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void plainUserAfterApViewGrant_listSupplierBills_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ap/supplier-bills")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        grantRoleAsRoot(plainUser, buildRole("AP_VIEWER", "AP.VIEW"));

        mockMvc.perform(get("/api/v1/ap/supplier-bills")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void rootListsSupplierBills_missingCompanyId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/ap/supplier-bills")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Supplier bills — POST enter bill
    // =========================================================================

    /** Root posts a bill with structurally valid body but unknown supplier → 4xx (not 500). */
    @Test
    void rootPostsBill_unknownSupplier_returns4xx() throws Exception {
        Map<String, Object> body = Map.of(
                "companyUid",       company.getUid(),
                "supplierUid",      "nonexistent-supplier-uid",
                "supplierInvoiceNo", "INV-HTTP-001",
                "billDate",         LocalDate.now().toString(),
                "dueDate",          LocalDate.now().plusDays(30).toString(),
                "currency",         "TZS",
                "lines",            List.of(Map.of(
                        "description",   "Widget",
                        "billedQty",     1,
                        "unitCostAmount", 500)));

        mockMvc.perform(post("/api/v1/ap/supplier-bills")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is4xxClientError());
    }

    /** Plain user without AP.BILL.ENTER → 403. */
    @Test
    void plainUserWithoutPerm_postBill_returns403() throws Exception {
        // Provide a structurally valid body (non-empty lines) so Spring Bean Validation passes
        // and the request reaches @PreAuthorize, which must return 403 for a permissionless user.
        Map<String, Object> body = Map.of(
                "companyUid",       company.getUid(),
                "supplierUid",      "some-uid",
                "supplierInvoiceNo", "INV-HTTP-002",
                "billDate",         LocalDate.now().toString(),
                "dueDate",          LocalDate.now().plusDays(30).toString(),
                "currency",         "TZS",
                "lines",            List.of(Map.of(
                        "description",    "Widget",
                        "billedQty",      1,
                        "unitCostAmount", 100)));

        mockMvc.perform(post("/api/v1/ap/supplier-bills")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // =========================================================================
    // Payments — list
    // =========================================================================

    @Test
    void rootListsApPayments_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ap/payments")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void plainUserWithoutPerm_listApPayments_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/ap/payments")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Statement — balance, ageing, reconciliation
    // =========================================================================

    @Test
    void rootGetsApBalance_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ap/statement/balance")
                        .param("companyId",  company.getId().toString())
                        .param("supplierId", "999999")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());
    }

    @Test
    void rootGetsApAgeing_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ap/statement/ageing")
                        .param("companyId",  company.getId().toString())
                        .param("supplierId", "999999")
                        .param("asAt",       LocalDate.now().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());
    }

    @Test
    void plainUserWithoutPerm_getApBalance_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/ap/statement/balance")
                        .param("companyId",  company.getId().toString())
                        .param("supplierId", "999999")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by V12__accounts_payable.sql"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    private void grantRoleAsRoot(AppUser user, Role role) {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                company.getId(), branch.getId(), null));
        try {
            testData.seedMembership(user.getUid(), company.getUid());
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), company.getUid(), null));
            permissionResolver.invalidate();
        } finally {
            RequestContext.clear();
        }
    }
}
