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
import java.math.BigDecimal;
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
 * HTTP-level gate regression guard for the AR controller layer (ADR-0014).
 *
 * <p>Drives requests through the REAL Spring Security filter chain (bearer token → filter →
 * @PreAuthorize SpEL). Deep AR business logic is already covered by ArReceiptServiceImplTest et al.
 * Tests here guard:
 * <ul>
 *   <li>root GET /ar/invoices → 200 with {@code @perm.has} (not hasAuthority, which breaks root).
 *   <li>non-root without AR.VIEW → 403 with the ApiResponse envelope.
 *   <li>non-root WITH AR.VIEW granted → 200 (grant flips 403 to 200 without re-issuing token).
 *   <li>root POST /ar/receipts with a structurally valid body → 4xx (not 500); missing required
 *       param → 400.
 * </ul>
 */
@AutoConfigureMockMvc
class ArHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private IamTestData testData;
    @Autowired private PermissionResolver permissionResolver;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private UserRoleService userRoleService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Organisation org;
    private Company company;
    private Branch branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String rootToken;
    private String plainToken;

    private static final String ROOT_PASS  = "ArRootH1!x";
    private static final String PLAIN_PASS = "ArPlainH1!x";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        org     = organisations.save(new Organisation("AR HTTP Test Org"));
        company = companies.save(new Company(org, "ART", "AR HTTP Co"));

        branch = new Branch(company, "AR-HQ1", "AR HQ");
        branch.setDefault(true);
        branch = branches.save(branch);

        rootUser = new AppUser("ar_root", passwordEncoder.encode(ROOT_PASS), "AR Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("ar_plain", passwordEncoder.encode(PLAIN_PASS), "AR Plain");
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
    // Invoice list
    // =========================================================================

    /** Root uses @perm.has bypass — must not receive 403 from hasAuthority-style gate. */
    @Test
    void rootListsArInvoices_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ar/invoices")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /** Non-root without AR.VIEW → 403 with ApiResponse envelope. */
    @Test
    void plainUserWithoutPerm_listArInvoices_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/ar/invoices")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    /** Grant AR.VIEW → same token now returns 200. */
    @Test
    void plainUserAfterArViewGrant_listArInvoices_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ar/invoices")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        grantRoleAsRoot(plainUser, buildRole("AR_VIEWER", "AR.VIEW"));

        mockMvc.perform(get("/api/v1/ar/invoices")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /** Missing required companyId param → 400, not 500. */
    @Test
    void rootListsArInvoices_missingCompanyId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/ar/invoices")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // =========================================================================
    // Receipt list
    // =========================================================================

    @Test
    void rootListsArReceipts_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ar/receipts")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void plainUserWithoutPerm_listArReceipts_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/ar/receipts")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Receipt POST — body structurally valid but customer unknown → 404, not 500.
    // This proves the controller reaches the service (permission gate passes for root).
    // =========================================================================

    @Test
    void rootPostsReceipt_unknownCustomer_returns4xx() throws Exception {
        Map<String, Object> body = Map.of(
                "companyUid",   company.getUid(),
                "customerUid",  "nonexistent-customer-uid",
                "amount",       BigDecimal.valueOf(100),
                "currency",     "TZS",
                "receiptDate",  LocalDate.now().toString(),
                "tenderType",   "CASH",
                "allocations",  List.of()
        );

        mockMvc.perform(post("/api/v1/ar/receipts")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                // Service throws NotFoundException (customer not found) → GlobalExceptionHandler → 404
                .andExpect(status().is4xxClientError());
    }

    /** Non-root without AR.RECEIPT.RECORD → 403 on the POST. */
    @Test
    void plainUserWithoutPerm_postReceipt_returns403() throws Exception {
        Map<String, Object> body = Map.of(
                "companyUid",   company.getUid(),
                "customerUid",  "some-uid",
                "amount",       BigDecimal.valueOf(50),
                "currency",     "TZS",
                "receiptDate",  LocalDate.now().toString(),
                "tenderType",   "CASH",
                "allocations",  List.of()
        );

        mockMvc.perform(post("/api/v1/ar/receipts")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // =========================================================================
    // Statement endpoints
    // =========================================================================

    @Test
    void rootGetsArBalance_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/ar/balance")
                        .param("companyId",  company.getId().toString())
                        .param("customerId", "999999")
                        .header("Authorization", "Bearer " + rootToken))
                // Service returns zero balance for unknown customer (COALESCE returns 0)
                .andExpect(status().isOk());
    }

    @Test
    void plainUserWithoutPerm_getArBalance_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/ar/balance")
                        .param("companyId",  company.getId().toString())
                        .param("customerId", "999999")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Private helpers (mirror PurchasesHttpIT pattern)
    // =========================================================================

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by V11__accounts_receivable.sql"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    private void grantRoleAsRoot(AppUser user, Role role) {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                company.getId(), branch.getId(), null));
        try {
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), company.getUid(), null));
            permissionResolver.invalidate();
        } finally {
            RequestContext.clear();
        }
    }
}
