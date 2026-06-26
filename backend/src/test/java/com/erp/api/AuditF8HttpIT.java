package com.erp.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.iam.domain.dto.AssignBranchRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.dto.TokenResponse;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.service.AuthService;
import com.erp.modules.iam.service.UserBranchService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level integration tests for F8 (ADR-0004 D-8): branch usability depends on both the
 * branch status AND its parent company status.
 *
 * <p>{@code Branch.isUsableForSession()} returns {@code true} only when {@code branch.status ==
 * ACTIVE && branch.company.status == ACTIVE}. This is enforced in two places:
 * <ol>
 *   <li>{@code AuthServiceImpl.issueSession} — the default branch is filtered with
 *       {@code isUsableForSession()}, so a login against a user whose only default branch belongs
 *       to an ARCHIVED company produces a read-only token (no active branch).
 *   <li>{@code JwtRequestContextFilter.resolvePrincipal} — the {@code X-Branch-Uid} override
 *       resolves via {@code BranchRepository.findWithCompanyByUid} and then filters with
 *       {@code isUsableForSession()}, so overriding to a branch whose company is archived yields
 *       a 403.
 * </ol>
 *
 * <p>Tests:
 * <ol>
 *   <li>Sanity: BEFORE archiving, GET /auth/me with B's token -> 200, activeBranchUid == B.uid.
 *   <li>After company C is archived: {@code AuthService.login} -> {@code hasBranch == false},
 *       {@code activeBranchUid == null} (branch B excluded because its company is not ACTIVE).
 *   <li>After company C is archived: GET /auth/me with a previously-minted token scoped to C/B
 *       + {@code X-Branch-Uid = B.uid} -> 403 envelope (override rejected by filter).
 * </ol>
 *
 * <p>A NEW company + branch is created for this test to avoid archiving the bootstrap company.
 */
@AutoConfigureMockMvc
class AuditF8HttpIT extends PostgresIntegrationTest {

    static final String BRANCH_OVERRIDE_HEADER = "X-Branch-Uid";

    @Autowired private MockMvc mockMvc;

    @Autowired private IamTestData testData;
    @Autowired private PermissionResolver permissionResolver;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private UserBranchRepository userBranches;

    @Autowired private AuthService authService;
    @Autowired private UserBranchService userBranchService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    // ---- shared state ----

    private Organisation org;
    private Company companyC;
    private Branch branchB;

    /** Non-root user whose only default is branchB (in companyC). */
    private AppUser userU;
    private String tokenForU; // minted at C/B scope — kept for test c after archival

    private static final String USER_PASS = "F8UserPass1!";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        // Org
        org = organisations.save(new Organisation("F8 Test Org"));

        // Company C + Branch B (B is the default for its company and for the user)
        companyC = companies.save(new Company(org, "F8C", "F8 Company C"));

        branchB = new Branch(companyC, "F8-B", "F8 Branch B");
        branchB.setDefault(true);
        branchB = branches.save(branchB);

        // Root sentinel (needed so UserBranch constructor has a valid assignedById)
        AppUser rootSentinel = new AppUser("f8_root", passwordEncoder.encode("RootF8Pass1!"), "F8 Root");
        rootSentinel.setRoot(true);
        rootSentinel = users.save(rootSentinel);

        // Non-root user U, assigned to B as default via UserBranchService (requires RequestContext)
        userU = new AppUser("f8_user", passwordEncoder.encode(USER_PASS), "F8 User");
        userU = users.save(userU);

        // Assign U to B as default — use direct UserBranch save (mirrors Slice5HttpIT pattern)
        // to avoid audit-service dependency; set ROOT context for the service call.
        RequestContext.set(new RequestContext.Principal(
                rootSentinel.getId(), rootSentinel.getUsername(), true,
                companyC.getId(), branchB.getId(), null));
        try {
            testData.seedMembership(userU.getUid(), companyC.getUid());
            userBranchService.assign(
                    new AssignBranchRequest(userU.getUid(), branchB.getUid(), true));
        } finally {
            RequestContext.clear();
        }

        // Mint U's token scoped to C/B — retained for use AFTER archival in test c
        tokenForU = jwtService.issueAccessToken(userU, companyC.getId(), branchB.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ===================================================================
    // Test a — Sanity: BEFORE archiving, GET /auth/me -> 200 + activeBranchUid == B.uid
    // ===================================================================

    @Test
    void beforeArchive_getMe_returns200WithActiveBranch() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokenForU))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeBranchUid").value(branchB.getUid()));
    }

    // ===================================================================
    // Test b — After archiving company C: login -> hasBranch == false, activeBranchUid == null
    //          Branch B is still ACTIVE; the company is ARCHIVED. issueSession excludes B because
    //          isUsableForSession() returns false. User lands read-only.
    // ===================================================================

    @Test
    void afterCompanyArchived_login_returnsReadOnlySessionNoBranch() {
        archiveCompanyC();

        TokenResponse response = authService.login(userU.getUsername(), USER_PASS, null);

        assertFalse(response.user().hasBranch(),
                "Expected hasBranch == false after company archived, got true");
        assertNull(response.user().activeBranchUid(),
                "Expected activeBranchUid == null after company archived, got: "
                + response.user().activeBranchUid());
        assertNull(response.user().activeCompanyUid(),
                "Expected activeCompanyUid == null after company archived, got: "
                + response.user().activeCompanyUid());
    }

    // ===================================================================
    // Test c — After archiving company C: GET /auth/me with a pre-minted token scoped to C/B
    //          + X-Branch-Uid = B.uid -> 403 envelope.
    //          The filter resolves B eagerly (findWithCompanyByUid), calls isUsableForSession()
    //          which returns false (company ARCHIVED), and throws AccessDeniedException -> 403.
    // ===================================================================

    @Test
    void afterCompanyArchived_overrideToBranchOfArchivedCompany_returns403() throws Exception {
        archiveCompanyC();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokenForU)
                        .header(BRANCH_OVERRIDE_HEADER, branchB.getUid()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Private helpers
    // ===================================================================

    /**
     * Archive company C directly via the repository (the task spec approach) — keeps branch B ACTIVE
     * so the test specifically exercises the company-archived case, not branch-archived.
     */
    private void archiveCompanyC() {
        companyC = companies.findById(companyC.getId()).orElseThrow();
        companyC.setStatus(MasterStatus.ARCHIVED);
        companies.save(companyC);
    }
}
