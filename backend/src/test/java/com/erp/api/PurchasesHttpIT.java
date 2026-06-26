package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level regression guard for PurchaseOrderController and GoodsReceiptController list gates.
 *
 * <p><b>Bug guarded:</b> both list endpoints previously used Spring's built-in
 * {@code @PreAuthorize("hasAuthority('...')")} instead of the application's
 * {@code @perm.has('...')} bean. Spring's {@code hasAuthority} checks the JWT-embedded authorities,
 * which root users do NOT carry (root bypass happens in {@code PermissionChecks} / {@code PermissionResolver}
 * at evaluation time, not via embedded authorities). Root admin therefore received HTTP 403 when
 * listing purchase orders or goods receipts. The fix switches both gates to {@code @perm.has(...)}.
 *
 * <p>Tests 1 and 2 are the mandatory regression guards — they fail with the old {@code hasAuthority}
 * gate and pass with the fixed {@code @perm.has} gate. Tests 3 and 4 confirm the gate still enforces
 * for non-root users (the fix must not inadvertently open the endpoints to everyone).
 */
@AutoConfigureMockMvc
class PurchasesHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;

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

    private static final String ROOT_PASS  = "PurchRootH1";
    private static final String PLAIN_PASS = "PurchPlainH1";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        org     = organisations.save(new Organisation("Purchases HTTP Org"));
        company = companies.save(new Company(org, "PHPO", "Purchases HTTP Co"));

        branch = new Branch(company, "PH-PO1", "Purchases HQ");
        branch.setDefault(true);
        branch = branches.save(branch);

        rootUser = new AppUser("purch_root", passwordEncoder.encode(ROOT_PASS), "Purch Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("purch_plain", passwordEncoder.encode(PLAIN_PASS), "Purch Plain");
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

    // ===================================================================
    // Test 1 — BUG REGRESSION: root lists purchase orders → 200 (NOT 403).
    //
    // Before the fix: @PreAuthorize("hasAuthority('PURCHASE.ORDER.VIEW')") — Spring's built-in
    // hasAuthority checks JWT-embedded authorities; root carries none → 403.
    // After the fix:  @PreAuthorize("@perm.has('PURCHASE.ORDER.VIEW')") — PermissionResolver
    // bypasses the check for is_root=true → 200.
    // ===================================================================

    @Test
    void rootListsPurchaseOrders_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 2 — BUG REGRESSION: root lists goods receipts → 200 (NOT 403).
    //
    // Same root-bypass failure as test 1 but on GoodsReceiptController.list.
    // ===================================================================

    @Test
    void rootListsGoodsReceipts_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/goods-receipts")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 3 — Gate still enforces: non-root without PURCHASE.ORDER.VIEW → 403.
    //          Proves @perm.has does NOT open the endpoint to everyone.
    // ===================================================================

    @Test
    void nonRootWithoutPermission_listPurchaseOrders_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 4 — Gate lifts: non-root granted PURCHASE.ORDER.VIEW → 200.
    //          Mirrors the grant-flips-403-to-200 pattern from RbacEnforcementHttpIT (test 5).
    // ===================================================================

    @Test
    void nonRootWithPermission_listPurchaseOrders_returns200() throws Exception {
        // Confirm baseline 403
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        grantRoleAsRoot(plainUser, buildRole("PH_PO_VIEWER", "PURCHASE.ORDER.VIEW"));

        // Same token; permission now resolved from DB (cache busted by grant)
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Private helpers — identical pattern to PartiesHttpIT
    // ===================================================================

    private Role buildRole(String code, String... permissionCodes) {
        Role role = new Role(code, code);
        Set<Permission> perms = new HashSet<>();
        for (String pc : permissionCodes) {
            perms.add(permissions.findByCode(pc)
                    .orElseThrow(() -> new IllegalStateException(
                            pc + " must be seeded by Flyway migration (V8__purchases.sql)")));
        }
        role.setPermissions(perms);
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
