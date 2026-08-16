package com.erp.api;

import static com.erp.support.TenantFixtures.inOrganisation;
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
import com.erp.modules.iam.service.BranchService;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level integration tests for Slice 5 — runtime branch switching via the
 * {@code X-Branch-Uid} header (ADR-0003). Every request drives the REAL Spring Security filter
 * chain — bearer token validation, JwtRequestContextFilter branch-switch logic, AccessDenied /
 * ExceptionTranslationFilter path, @PreAuthorize SpEL evaluation.
 *
 * <p>Service-level tests would not cover the filter override because the filter populates
 * RequestContext directly; this test class is the primary verification that the boundary rules
 * hold end-to-end.
 *
 * <p>Tests are numbered to match the task specification:
 * <ol>
 *   <li>No header -> request uses JWT default scope (A1 / C-A).
 *   <li>Switch to an ASSIGNED branch (B1 / C-B) -> 200, scope follows.
 *   <li>Switch to an UNASSIGNED branch (A2) -> 403 envelope.
 *   <li>Non-existent branch uid in header -> 403 envelope.
 *   <li>Money test: branch-scoped permission only at B1; denied at A1, granted after switch.
 *   <li>Archived branch uid in header -> 403 envelope.
 *   <li>ROOT override unchecked (not assigned to B1 -> 200); root + bad uid -> 403.
 *   <li>/auth/my-branches self-scoped; unauthenticated -> 401 envelope.
 * </ol>
 *
 * <p>Seed layout:
 * <ul>
 *   <li>Org -> Company C-A: branch A1 (default for C-A), branch A2 (non-default, non-assigned).
 *   <li>Org -> Company C-B: branch B1Default (company default, NOT user-assigned),
 *       branch B1 (non-default, assigned to the test user).
 *   <li>Non-root user: default assignment = A1; secondary assignment = B1.
 *   <li>Root user: default assignment = A1 only (not assigned to B1).
 * </ul>
 */
@AutoConfigureMockMvc
class Slice5HttpIT extends PostgresIntegrationTest {

    static final String BRANCH_OVERRIDE_HEADER = "X-Branch-Uid";

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
    @Autowired private BranchService branchService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    // --- shared state set up in @BeforeEach ---

    private Organisation org;

    private Company cA;
    private Branch a1;   // default branch of C-A; user's default scope
    private Branch a2;   // non-default branch of C-A; user is NOT assigned here

    private Company cB;
    private Branch b1Default;  // company-default branch of C-B; NOT assigned to user (archive guard)
    private Branch b1;         // non-default branch of C-B; assigned to the test user

    private AppUser rootUser;
    private AppUser testUser;  // non-root; assigned to a1 (default) and b1

    private String rootToken;  // minted at a1 / cA
    private String userToken;  // minted at a1 / cA (default scope)

    private static final String ROOT_PASS = "RootS5Pass1";
    private static final String USER_PASS = "UserS5Pass1";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        // --- Organisation ---
        org = organisations.save(new Organisation("Slice5 Test Org"));

        // --- Company A: two branches ---
        cA = companies.save(new Company(org, "S5A", "Slice5 Alpha"));

        a1 = new Branch(cA, "S5-A1", "Alpha HQ");
        a1.setDefault(true);
        a1 = branches.save(a1);

        a2 = new Branch(cA, "S5-A2", "Alpha Secondary");
        // NOT default; user will NOT be assigned here
        a2 = branches.save(a2);

        // --- Company B: two branches ---
        // b1Default is the company default (so it is protected from archival).
        // b1 is non-default and is the branch assigned to the user and used for archival test.
        cB = companies.save(new Company(org, "S5B", "Slice5 Beta"));

        b1Default = new Branch(cB, "S5-B1D", "Beta Default HQ");
        b1Default.setDefault(true);
        b1Default = branches.save(b1Default);

        b1 = new Branch(cB, "S5-B1", "Beta Secondary");
        // isDefault stays false — this is the non-default switchable branch
        b1 = branches.save(b1);

        // --- ROOT user: assigned to a1 (default). NOT assigned to b1. ---
        rootUser = new AppUser("s5_root", passwordEncoder.encode(ROOT_PASS), "S5 Root");
        rootUser.setRoot(true);
        rootUser = users.save(inOrganisation(rootUser, org.getId()));
        UserBranch rootAssign = new UserBranch(rootUser.getId(), a1, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        // --- Non-root test user: default = a1; secondary = b1. ---
        testUser = new AppUser("s5_user", passwordEncoder.encode(USER_PASS), "S5 User");
        testUser = users.save(inOrganisation(testUser, org.getId()));

        UserBranch userDefaultAssign = new UserBranch(testUser.getId(), a1, rootUser.getId());
        userDefaultAssign.markDefault();
        userBranches.save(userDefaultAssign);

        UserBranch userB1Assign = new UserBranch(testUser.getId(), b1, rootUser.getId());
        // NOT marked default — a1 remains the default
        userBranches.save(userB1Assign);

        // Mint tokens: both at default scope (cA / a1)
        rootToken = jwtService.issueAccessToken(rootUser, cA.getId(), a1.getId()).value();
        userToken = jwtService.issueAccessToken(testUser, cA.getId(), a1.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ===================================================================
    // Test 1 — No header -> default scope from JWT (a1 / cA)
    //          GET /auth/me -> 200, activeBranchUid == a1.uid, activeCompanyUid == cA.uid.
    // ===================================================================

    @Test
    void noHeader_getMe_returnsDefaultScope() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeBranchUid").value(a1.getUid()))
                .andExpect(jsonPath("$.data.activeCompanyUid").value(cA.getUid()));
    }

    // ===================================================================
    // Test 2 — Switch to an ASSIGNED branch (b1 in cB)
    //          GET /auth/me with X-Branch-Uid=b1.uid -> 200,
    //          activeBranchUid == b1.uid, activeCompanyUid == cB.uid.
    //          The active company follows the override branch — D-A.
    // ===================================================================

    @Test
    void headerAssignedBranch_getMe_returnsOverrideScope() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken)
                        .header(BRANCH_OVERRIDE_HEADER, b1.getUid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeBranchUid").value(b1.getUid()))
                .andExpect(jsonPath("$.data.activeCompanyUid").value(cB.getUid()));
    }

    // ===================================================================
    // Test 3 — Switch to an UNASSIGNED branch (a2) -> 403 envelope
    //          a2 is ACTIVE but the user has no user_branch row for it.
    //          JwtRequestContextFilter must throw AccessDeniedException -> 403.
    // ===================================================================

    @Test
    void headerUnassignedBranch_returns403Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken)
                        .header(BRANCH_OVERRIDE_HEADER, a2.getUid()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 4 — Non-existent branch uid in header -> 403 envelope
    //          branches.findByUid("no-such-uid") is empty -> the filter throws
    //          AccessDeniedException (fail-closed D-3) -> 403.
    // ===================================================================

    @Test
    void headerNonExistentBranchUid_returns403Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken)
                        .header(BRANCH_OVERRIDE_HEADER, "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    /**
     * Test 5 — THE MONEY TEST: branch-scoped permission flips on switch (D-E + D-F).
     *
     * <p>Grant USER.VIEW to testUser scoped ONLY to branch b1 (in company cB).
     * Without the header (scope = a1 / cA) the grant is not effective: 403.
     * With X-Branch-Uid=b1 the scope overrides to b1 / cB and the resolver picks up the
     * branch-scoped grant: 200. Proves PermissionResolver re-resolves for the overridden
     * scope without token re-mint.
     */
    @Test
    void branchScopedPermission_deniedAtDefaultScope_grantedAfterSwitch() throws Exception {
        // Seed: create a role with USER.VIEW and grant it branch-scoped to b1 in cB.
        Role userViewRole = buildRole("S5_USER_VIEWER", "USER.VIEW");
        grantRoleAsRootInCompany(testUser, userViewRole, cB, b1);

        // Without override (default scope = a1 / cA): USER.VIEW not in scope -> 403
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());

        // With override to b1: scope = b1 / cB; USER.VIEW is in scope -> 200
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + userToken)
                        .header(BRANCH_OVERRIDE_HEADER, b1.getUid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 6 — Archived override branch -> 403 envelope
    //          Archive b1 (non-default, so archiveByUid succeeds).
    //          Then header=b1.uid -> filter sees status != ACTIVE -> 403 (D-3).
    //          Also verifies fail-closed: even a previously-assigned branch is denied when archived.
    // ===================================================================

    @Test
    void headerArchivedBranch_returns403Envelope() throws Exception {
        // Archive b1 — it is non-default so archiveByUid will not throw ConflictException.
        branchService.archiveByUid(b1.getUid());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken)
                        .header(BRANCH_OVERRIDE_HEADER, b1.getUid()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 7 — ROOT override unchecked (D-4)
    //          Root is NOT assigned to b1; header=b1.uid -> 200, scope = cB / b1.
    //          Also: root + non-existent uid -> 403 (fail-closed even for root, D-3).
    // ===================================================================

    @Test
    void rootOverride_notAssignedBranch_succeeds() throws Exception {
        // Root is not in userBranches for b1 — no assignment check for root.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + rootToken)
                        .header(BRANCH_OVERRIDE_HEADER, b1.getUid()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeBranchUid").value(b1.getUid()))
                .andExpect(jsonPath("$.data.activeCompanyUid").value(cB.getUid()));
    }

    @Test
    void rootOverride_nonExistentUid_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + rootToken)
                        .header(BRANCH_OVERRIDE_HEADER, "00000000-0000-0000-0000-deadbeef0000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 8 — /auth/my-branches self-scoped
    //          As testUser (token): GET /auth/my-branches -> 200, returns BOTH a1 and b1
    //          (their ACTIVE assignments), does NOT require USER.VIEW (just isAuthenticated).
    //          Unauthenticated (no token): GET /auth/my-branches -> 401 envelope.
    // ===================================================================

    @Test
    void myBranches_returnsBothActiveAssignments() throws Exception {
        mockMvc.perform(get("/api/v1/auth/my-branches")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                // Both a1 and b1 must appear
                .andExpect(jsonPath("$.data[?(@.branchUid == '" + a1.getUid() + "')]").exists())
                .andExpect(jsonPath("$.data[?(@.branchUid == '" + b1.getUid() + "')]").exists());
    }

    @Test
    void myBranches_unauthenticated_returns401Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/auth/my-branches"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Private seeding helpers
    // ===================================================================

    /** Creates and saves a role containing a single seeded permission code. */
    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by V1 migration"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    /**
     * Grants {@code role} to {@code user} scoped to the given {@code company} AND restricted
     * to one specific {@code branch} (branch-scoped grant). The root context is set to the
     * target company so ScopeGuard.assertCanActIn passes. Clears RequestContext after — the
     * subsequent HTTP requests build their own context from the JWT via JwtRequestContextFilter.
     */
    private void grantRoleAsRootInCompany(AppUser user, Role role, Company company, Branch branch) {
        testData.seedMembership(user.getUid(), company.getUid());
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                company.getId(), branch.getId(), null));
        try {
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), company.getUid(), branch.getUid()));
        } finally {
            RequestContext.clear();
        }
    }
}
