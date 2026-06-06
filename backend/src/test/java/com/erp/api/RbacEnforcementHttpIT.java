package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.UpdateCompanyRequest;
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
 * HTTP-level RBAC regression guard (ADR-0002). Drives every request through the REAL Spring Security
 * filter chain — bearer token validation, JwtRequestContextFilter, @PreAuthorize SpEL evaluation —
 * NOT service calls with a manually-set RequestContext. Any regression in the two fixed bugs will
 * make a specific test here go red:
 *
 * <ul>
 *   <li>Bug-1 regression guard: @perm.has SpEL reaches the controller without a 400/500 (tests 3).
 *   <li>Bug-2 regression guard: RequestContext is populated via the filter chain so /me returns 200
 *       with the correct principal (test 2); if the filter runs before BearerTokenAuthenticationFilter
 *       again, the context will be empty and /me will throw 401.
 * </ul>
 *
 * <p>Tokens are minted directly via JwtService (same path as login) to avoid bcrypt latency per test.
 * Seeding calls that go through UserRoleService (which reads RequestContext) set a root principal on
 * the ThreadLocal for the duration of the seeding call, then clear it — the subsequent HTTP requests
 * populate their own context via the filter.
 */
@AutoConfigureMockMvc
class RbacEnforcementHttpIT extends PostgresIntegrationTest {

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

    // --- shared state set up in @BeforeEach ---

    private Organisation org;
    private Company companyA;
    private Branch branchA;
    private Company companyB;
    private Branch branchB;

    private AppUser rootUser;
    private AppUser nonRootUser;

    private String rootToken;
    private String nonRootToken;

    private static final String ROOT_PASS = "RootPass123";
    private static final String USER_PASS = "UserPass123";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        // ---- build the data tree ----

        org = organisations.save(new Organisation("Rbac Test Org"));

        companyA = companies.save(new Company(org, "CA", "Company Alpha"));
        branchA = new Branch(companyA, "BR-A1", "Alpha HQ");
        branchA.setDefault(true);
        branchA = branches.save(branchA);

        companyB = companies.save(new Company(org, "CB", "Company Beta"));
        branchB = new Branch(companyB, "BR-B1", "Beta HQ");
        branchB.setDefault(true);
        branchB = branches.save(branchB);

        // ROOT user — is_root=true, active scope = company A / branch A
        rootUser = new AppUser("rbac_root", passwordEncoder.encode(ROOT_PASS), "Rbac Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssignment = new UserBranch(rootUser.getId(), branchA, rootUser.getId());
        rootAssignment.markDefault();
        userBranches.save(rootAssignment);

        // NON-ROOT user — no roles yet, active scope = company A / branch A
        nonRootUser = new AppUser("rbac_user", passwordEncoder.encode(USER_PASS), "Rbac User");
        nonRootUser = users.save(nonRootUser);
        UserBranch nonRootAssignment = new UserBranch(nonRootUser.getId(), branchA, nonRootUser.getId());
        nonRootAssignment.markDefault();
        userBranches.save(nonRootAssignment);

        // Mint tokens the same way login does (companyId + branchId from the default branch)
        rootToken = jwtService
                .issueAccessToken(rootUser, companyA.getId(), branchA.getId())
                .value();
        nonRootToken = jwtService
                .issueAccessToken(nonRootUser, companyA.getId(), branchA.getId())
                .value();
    }

    @AfterEach
    void tearDown() {
        // Ensure the seeding ThreadLocal is never leaked between tests.
        RequestContext.clear();
    }

    // ===================================================================
    // Test 1 — No token → 401, body is the ApiResponse envelope.
    //          Proves SecurityErrorResponder renders the envelope on auth failure.
    //          (Bug-2-adjacent: would also catch a filter ordering that collapses all failures to 500)
    // ===================================================================

    @Test
    void noToken_getMe_returns401WithEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 2 — ROOT token → GET /auth/me → 200, isRoot=true, permissions=[].
    //          THE Bug-2 regression guard: if JwtRequestContextFilter runs before
    //          BearerTokenAuthenticationFilter the context is empty and me() throws 401.
    // ===================================================================

    @Test
    void rootToken_getMe_returns200WithIsRootTrue() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isRoot").value(true))
                .andExpect(jsonPath("$.data.username").value("rbac_root"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions").isEmpty());
    }

    // ===================================================================
    // Test 3 — ROOT token → GET /companies and GET /roles → both 200.
    //          THE Bug-1 regression guard: if @perm.has SpEL is broken (1-arg form, wrong bean)
    //          Spring throws a SpEL evaluation error → 400 or 500; root bypasses the permission
    //          check but still exercises the SpEL parser path.
    // ===================================================================

    @Test
    void rootToken_listCompanies_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/companies")
                        .param("organisationUid", org.getUid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void rootToken_listRoles_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/roles")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 4 — NON-ROOT token, no roles → GET /companies → 403 and envelope.
    //          Proves the permission gate fires for real (not mocked) and the
    //          AccessDeniedHandler renders the ApiResponse envelope.
    // ===================================================================

    @Test
    void nonRootToken_noRoles_listCompanies_returns403WithEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/companies")
                        .param("organisationUid", org.getUid())
                        .header("Authorization", "Bearer " + nonRootToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 5 — Grant COMPANY.VIEW to non-root in company A; same token → GET /companies → 200.
    //          Proves the grant flips 403→200 over HTTP without re-issuing the token (perms are
    //          resolved per request, not embedded in the JWT — ADR-0001 D-E).
    //          Also validates PermissionResolver cache invalidation on grant.
    // ===================================================================

    @Test
    void nonRootToken_afterGrantCompanyView_listCompanies_returns200() throws Exception {
        // First confirm the 403 baseline (identical to test 4)
        mockMvc.perform(get("/api/v1/companies")
                        .param("organisationUid", org.getUid())
                        .header("Authorization", "Bearer " + nonRootToken))
                .andExpect(status().isForbidden());

        // Grant COMPANY.VIEW to the non-root user in company A via the service
        // (which calls permissionResolver.invalidate() on completion).
        // The grant call itself reads RequestContext (for granted_by + scope check) — supply root.
        Role viewRole = buildRole("HTTP_COMPANY_VIEWER", "COMPANY.VIEW");
        grantRoleAsRoot(nonRootUser, viewRole, companyA);

        // Same token, fresh HTTP request → permission resolved from DB, cache busted by grant.
        mockMvc.perform(get("/api/v1/companies")
                        .param("organisationUid", org.getUid())
                        .header("Authorization", "Bearer " + nonRootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 6 — Cross-company scope over HTTP.
    //          NON-ROOT user (active scope = company A) even with COMPANY.MANAGE in A cannot
    //          PUT a company-B resource → 403 (@perm.scoped denies different company).
    //          ROOT with same PUT → 200 (root bypasses scope).
    // ===================================================================

    @Test
    void nonRootToken_updateCompanyB_returns403() throws Exception {
        // Grant COMPANY.MANAGE in company A — still can't act on company B.
        Role manageRole = buildRole("HTTP_COMPANY_MANAGER", "COMPANY.MANAGE");
        grantRoleAsRoot(nonRootUser, manageRole, companyA);

        UpdateCompanyRequest body = new UpdateCompanyRequest(
                "Beta Updated", null, null, null);

        mockMvc.perform(put("/api/v1/companies/uid/{uid}", companyB.getUid())
                        .header("Authorization", "Bearer " + nonRootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rootToken_updateCompanyB_returns200() throws Exception {
        UpdateCompanyRequest body = new UpdateCompanyRequest(
                "Beta Root Updated", null, null, null);

        mockMvc.perform(put("/api/v1/companies/uid/{uid}", companyB.getUid())
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Beta Root Updated"));
    }

    // ===================================================================
    // Test 7 — GET /auth/me for the non-root AFTER the grant → 200, permissions contains
    //          'COMPANY.VIEW'. Proves the /me endpoint reflects granted permissions through the
    //          filter chain (full stack: auth → RequestContext → PermissionResolver → response).
    // ===================================================================

    @Test
    void nonRootToken_afterGrantCompanyView_getMe_permissionsContainsCompanyView() throws Exception {
        Role viewRole = buildRole("HTTP_ME_VIEWER", "COMPANY.VIEW");
        grantRoleAsRoot(nonRootUser, viewRole, companyA);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + nonRootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isRoot").value(false))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions[?(@ == 'COMPANY.VIEW')]").exists());
    }

    // ===================================================================
    // Private seeding helpers
    // ===================================================================

    /**
     * Creates and saves a role containing a single seeded permission code. Must be called before
     * the HTTP requests that need the role, and after testData.clearAll() (so the DB is clean).
     */
    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by V1 migration"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    /**
     * Grants {@code role} to {@code user} in {@code company} using the UserRoleService (which calls
     * permissionResolver.invalidate() on completion, so the cache reflects the new grant immediately).
     *
     * <p>UserRoleServiceImpl.grant() reads RequestContext for the granted_by user id and the scope
     * guard. We set a root context just for this seeding call, then clear it — the subsequent HTTP
     * requests will populate their own context from the JWT via JwtRequestContextFilter.
     */
    private void grantRoleAsRoot(AppUser user, Role role, Company company) {
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
