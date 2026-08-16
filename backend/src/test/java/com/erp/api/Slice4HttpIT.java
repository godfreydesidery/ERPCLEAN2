package com.erp.api;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP-level integration tests for Slice 4 (user admin + branch assignment). Every request drives
 * the REAL Spring Security filter chain — bearer token validation, JwtRequestContextFilter,
 * {@code @PreAuthorize} SpEL evaluation — NOT service calls with a manually-set RequestContext.
 *
 * <p>Mirrors {@link RbacEnforcementHttpIT} exactly: extends {@link PostgresIntegrationTest},
 * seeds org/company/branch/users in {@code @BeforeEach}, mints bearer tokens via
 * {@link JwtService#issueAccessToken}, and asserts status + the {@code ApiResponse} envelope
 * ({@code $.data} / {@code $.errors}). Seeds that call services (grant, assign) set a root
 * {@link RequestContext.Principal} for the duration of the seeding call, then clear it — the
 * subsequent HTTP requests build their own context via the filter.
 *
 * <p>Tests are numbered to match the task specification:
 * <ol>
 *   <li>USER.MANAGE gate flip: 403 without, 201 after grant.
 *   <li>is_root field is not settable via POST /users.
 *   <li>Weak password → 400 envelope.
 *   <li>Disable blocks login end-to-end.
 *   <li>Disable a root user → 409.
 *   <li>BRANCH.ASSIGN gate flip: 403 without, 201 after grant.
 *   <li>Cross-company scope: BRANCH.ASSIGN in company A cannot assign company-B branch; root can.
 *   <li>Branch-present-but-no-role → 403 on permission-gated endpoint.
 * </ol>
 */
@AutoConfigureMockMvc
class Slice4HttpIT extends PostgresIntegrationTest {

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
    private AppUser adminUser;   // non-root, will receive roles per-test

    private String rootToken;
    private String adminToken;   // active scope: company A / branch A

    private static final String ROOT_PASS   = "RootS4Pass1";
    private static final String ADMIN_PASS  = "AdminS4Pass1";
    private static final String TARGET_PASS = "TargetS4Pass1";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        // Organisation + two companies each with one branch
        org = organisations.save(new Organisation("Slice4 Test Org"));

        companyA = companies.save(new Company(org, "S4A", "Slice4 Alpha"));
        branchA = new Branch(companyA, "S4-A1", "Alpha HQ");
        branchA.setDefault(true);
        branchA = branches.save(branchA);

        companyB = companies.save(new Company(org, "S4B", "Slice4 Beta"));
        branchB = new Branch(companyB, "S4-B1", "Beta HQ");
        branchB.setDefault(true);
        branchB = branches.save(branchB);

        // ROOT user — is_root=true, scoped to company A / branch A
        rootUser = new AppUser("s4_root", passwordEncoder.encode(ROOT_PASS), "S4 Root");
        rootUser.setRoot(true);
        rootUser = users.save(inOrganisation(rootUser, org.getId()));
        UserBranch rootAssignment = new UserBranch(rootUser.getId(), branchA, rootUser.getId());
        rootAssignment.markDefault();
        userBranches.save(rootAssignment);

        // NON-ROOT admin — no roles yet, scoped to company A / branch A
        adminUser = new AppUser("s4_admin", passwordEncoder.encode(ADMIN_PASS), "S4 Admin");
        adminUser = users.save(inOrganisation(adminUser, org.getId()));
        UserBranch adminAssignment = new UserBranch(adminUser.getId(), branchA, rootUser.getId());
        adminAssignment.markDefault();
        userBranches.save(adminAssignment);

        // Mint tokens the same way login does
        rootToken  = jwtService.issueAccessToken(rootUser,  companyA.getId(), branchA.getId()).value();
        adminToken = jwtService.issueAccessToken(adminUser, companyA.getId(), branchA.getId()).value();
    }

    @AfterEach
    void tearDown() {
        // Ensure the seeding ThreadLocal is never leaked between tests.
        RequestContext.clear();
    }

    // ===================================================================
    // Test 1 — USER.MANAGE gate flip
    //          Non-root WITHOUT USER.MANAGE -> POST /users -> 403 (envelope).
    //          Grant USER.MANAGE (org-wide, so null branch) -> repeat -> 201.
    // ===================================================================

    @Test
    void userManageGate_flip_403_then_201() throws Exception {
        String body = createUserJson("gatetest1", "Gate Test One", TARGET_PASS);

        // Baseline: no permission → 403 with envelope
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());

        // Grant USER.MANAGE in company A; same token, perms resolved from DB per-request
        Role manageRole = buildRole("S4_USER_MANAGER", "USER.MANAGE");
        grantRoleAsRoot(adminUser, manageRole, companyA);

        // After grant → 201
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("gatetest1"));
    }

    // ===================================================================
    // Test 2 — is_root is NOT settable via POST /users
    //          POST /users with extra "isRoot":true in the body (as ROOT or USER.MANAGE caller)
    //          -> 201, created user has is_root=false in DB.
    // ===================================================================

    @Test
    void createUser_extraIsRootField_ignored_isRootFalseInDb() throws Exception {
        // Build a JSON body that includes the unknown "isRoot" field
        String body = objectMapper.writeValueAsString(Map.of(
                "username", "noroot_user",
                "displayName", "No Root User",
                "password", TARGET_PASS,
                "isRoot", true          // extra field — must be ignored
        ));

        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.isRoot").value(false))
                .andReturn();

        // Double-check in DB: the created user must NOT be root
        String createdUid = objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/uid").asText();
        AppUser created = users.findByUid(createdUid)
                .orElseThrow(() -> new AssertionError("Created user not found in DB: " + createdUid));
        assertThat(created.isRoot())
                .as("is_root must be false in DB regardless of the extra isRoot field in the body")
                .isFalse();
    }

    // ===================================================================
    // Test 3 — Password policy enforced over HTTP
    //          POST /users with a weak password -> 400 envelope (WeakPasswordException mapped).
    // ===================================================================

    @Test
    void createUser_weakPassword_returns400Envelope() throws Exception {
        String body = createUserJson("weakpass_user", "Weak Pass User", "short");

        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 4 — Disable blocks login end-to-end
    //          Create user → PUT /users/uid/{uid}/disable as root → 204;
    //          POST /auth/login with that user's creds → 401 (disabled).
    // ===================================================================

    @Test
    void disableUser_blocksSubsequentLogin() throws Exception {
        // Step 1: create the target user via the API (using root token)
        String createBody = createUserJson("to_be_disabled", "To Be Disabled", TARGET_PASS);
        MvcResult createResult = mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andReturn();

        String uid = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .at("/data/uid").asText();

        // Step 2: disable the user
        mockMvc.perform(put("/api/v1/users/uid/{uid}/disable", uid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isNoContent());

        // Step 3: attempt login with the disabled user's credentials → 401
        String loginBody = objectMapper.writeValueAsString(
                Map.of("username", "to_be_disabled", "password", TARGET_PASS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 5 — Disable a ROOT user → 409 (ConflictException).
    // ===================================================================

    @Test
    void disableRootUser_returns409() throws Exception {
        mockMvc.perform(put("/api/v1/users/uid/{uid}/disable", rootUser.getUid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 6 — BRANCH.ASSIGN gate flip
    //          Non-root WITHOUT BRANCH.ASSIGN -> POST /user-branches -> 403.
    //          Grant BRANCH.ASSIGN (scoped to company A) -> POST with branchA -> 201.
    // ===================================================================

    @Test
    void branchAssignGate_flip_403_then_201() throws Exception {
        // Create a target user to assign (must exist in DB)
        AppUser targetUser = createPersistedUser("branchassign_target", TARGET_PASS);
        String assignBody = assignBranchJson(targetUser.getUid(), branchA.getUid(), true);

        // Baseline: no permission → 403
        mockMvc.perform(post("/api/v1/user-branches")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());

        // Grant BRANCH.ASSIGN in company A
        Role assignRole = buildRole("S4_BRANCH_ASSIGNOR", "BRANCH.ASSIGN");
        grantRoleAsRoot(adminUser, assignRole, companyA);

        // ADR-0046: the target user must be a member of the company before a branch there is assigned.
        testData.seedMembership(targetUser.getUid(), companyA.getUid());

        // After grant → 201 (branchA belongs to companyA, same scope as the caller)
        mockMvc.perform(post("/api/v1/user-branches")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.branchUid").value(branchA.getUid()));
    }

    // ===================================================================
    // Test 7 — Cross-company scope for BRANCH.ASSIGN
    //          Non-root with BRANCH.ASSIGN in company A cannot assign a branch belonging to
    //          company B (@perm.scoped resolves branchB → companyB ≠ active companyA → deny).
    //          Root assigning the same branch → 201.
    // ===================================================================

    @Test
    void branchAssign_crossCompanyScope_403forNonRoot_201forRoot() throws Exception {
        AppUser targetUser = createPersistedUser("crossco_target", TARGET_PASS);
        String assignBodyB = assignBranchJson(targetUser.getUid(), branchB.getUid(), true);

        // Grant BRANCH.ASSIGN in company A (caller is in company A)
        Role assignRole = buildRole("S4_CROSS_CO_ASSIGNOR", "BRANCH.ASSIGN");
        grantRoleAsRoot(adminUser, assignRole, companyA);

        // Non-root with BRANCH.ASSIGN in A tries to assign a branch in B → 403 (scope mismatch)
        mockMvc.perform(post("/api/v1/user-branches")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBodyB))
                .andExpect(status().isForbidden());

        // ADR-0046: root bypasses tenant SCOPE but the membership gate still applies — seed first.
        testData.seedMembership(targetUser.getUid(), companyB.getUid());

        // Root assigning the same user to branch B → 201 (root bypasses scope)
        mockMvc.perform(post("/api/v1/user-branches")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(assignBodyB))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.branchUid").value(branchB.getUid()));
    }

    // ===================================================================
    // Test 8 — Branch-present-but-no-role → 403 (FR-IAM-24 / FR-IAM-20)
    //          Create a non-root user U; assign U a default branch in company A with NO role;
    //          mint U's token (company A / branch A); call a permission-gated endpoint → 403.
    //          Proves "present in a branch but can't act without a role."
    // ===================================================================

    @Test
    void userInBranch_noRole_cannotCallPermissionGatedEndpoint() throws Exception {
        // Create user with no role grants
        AppUser branchUser = createPersistedUser("norole_branch_user", TARGET_PASS);

        // Assign the user to branchA in companyA as their default (seeding via repository directly,
        // no service call needed here — just need the row in the DB).
        UserBranch assignment = new UserBranch(branchUser.getId(), branchA, rootUser.getId());
        assignment.markDefault();
        userBranches.save(assignment);

        // Mint a token for this user scoped to company A / branch A
        String noRoleToken = jwtService
                .issueAccessToken(branchUser, companyA.getId(), branchA.getId())
                .value();

        // GET /api/v1/users requires USER.VIEW — this user has no roles → 403 + envelope
        mockMvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + noRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createUserJson("should_not_create", "Should Not", TARGET_PASS)))
                .andExpect(status().isForbidden())
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
     * Grants {@code role} to {@code user} in {@code company} via UserRoleService (which calls
     * permissionResolver.invalidate() on completion, busting the cache immediately).
     *
     * <p>UserRoleServiceImpl.grant() reads RequestContext for granted_by + scope guard. We set a
     * root context just for this seeding call, then clear it — the subsequent HTTP requests
     * populate their own context from the JWT via JwtRequestContextFilter.
     */
    private void grantRoleAsRoot(AppUser user, Role role, Company company) {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                companyA.getId(), branchA.getId(), null));
        try {
            testData.seedMembership(user.getUid(), company.getUid());
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), company.getUid(), null));
        } finally {
            RequestContext.clear();
        }
    }

    /**
     * Persists a non-root user with the given username and password. Used to create target users
     * for assignment/disable tests without going through the HTTP layer (avoids needing USER.MANAGE
     * set up before the test subject needs it).
     */
    private AppUser createPersistedUser(String username, String password) {
        AppUser user = new AppUser(username, passwordEncoder.encode(password), username + " Display");
        return users.save(inOrganisation(user, org.getId()));
    }

    /** Builds the JSON body for POST /users. */
    private String createUserJson(String username, String displayName, String password) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "username", username,
                    "displayName", displayName,
                    "password", password));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Builds the JSON body for POST /user-branches. */
    private String assignBranchJson(String userUid, String branchUid, boolean makeDefault) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "userUid", userUid,
                    "branchUid", branchUid,
                    "makeDefault", makeDefault));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
