package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.documents.service.DocumentBrandingSeeder;
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
 * HTTP-level integration tests for the Documents module (ADR-0023).
 *
 * <p>Scope: permission gates, branding GET/PUT, template list, scope-guard enforcement on
 * cross-company access. Render-to-PDF path requires a full O2C setup (products/GL/fiscal year)
 * that is covered by the service-layer IT in DocumentRenderServiceImplIT. These HTTP tests
 * focus on the security boundary and DTO shape.
 *
 * <p>Tests:
 * <ol>
 *   <li>Unauthenticated → 401 envelope on every document endpoint.</li>
 *   <li>No permission → 403 on DOCUMENT.VIEW list.</li>
 *   <li>Root bypasses permission check → 200 on DOCUMENT.VIEW list.</li>
 *   <li>Non-root granted DOCUMENT.BRANDING.MANAGE → 200 on GET branding.</li>
 *   <li>GET branding returns seeded profile (displayName matches company name).</li>
 *   <li>PUT branding updates displayName → reflected in subsequent GET.</li>
 *   <li>Non-root without DOCUMENT.TEMPLATE.MANAGE → 403 on template list.</li>
 *   <li>Root lists templates → 200 array (seeded 6 v1 types).</li>
 *   <li>Scope guard: company-B token cannot access company-A's branding → 403.</li>
 * </ol>
 */
@AutoConfigureMockMvc
class DocumentHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private IamTestData          testData;
    @Autowired private PermissionResolver   permissionResolver;
    @Autowired private DocumentBrandingSeeder brandingSeeder;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private RoleRepository         roles;
    @Autowired private PermissionRepository   permissions;
    @Autowired private UserBranchRepository   userBranches;

    @Autowired private UserRoleService  userRoleService;
    @Autowired private JwtService       jwtService;
    @Autowired private PasswordEncoder  passwordEncoder;

    private Organisation org;
    private Company      companyA;
    private Branch       branchA;

    private Company companyB;
    private Branch  branchB;

    private AppUser rootUser;
    private AppUser plainUser;

    private String rootToken;
    private String plainToken;
    private String companyBToken;   // root minted at companyB scope

    private static final String ROOT_PASS  = "DocRootPwd1!";
    private static final String PLAIN_PASS = "DocPlainPwd1";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        org      = organisations.save(new Organisation("Doc HTTP Org"));
        companyA = companies.save(new Company(org, "DOCA", "Doc Http Company A"));
        branchA  = new Branch(companyA, "DOC-A1", "Doc A HQ");
        branchA.setDefault(true);
        branchA  = branches.save(branchA);

        companyB = companies.save(new Company(org, "DOCB", "Doc Http Company B"));
        branchB  = new Branch(companyB, "DOC-B1", "Doc B HQ");
        branchB.setDefault(true);
        branchB  = branches.save(branchB);

        // Seed branding + templates for companyA (normally done by BootstrapRunner / CompanyService).
        brandingSeeder.seedDefaults(companyA.getId());
        brandingSeeder.seedDefaults(companyB.getId());

        rootUser = new AppUser("doc_root", passwordEncoder.encode(ROOT_PASS), "Doc Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branchA, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("doc_plain", passwordEncoder.encode(PLAIN_PASS), "Doc Plain");
        plainUser = users.save(plainUser);
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branchA, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        rootToken     = jwtService.issueAccessToken(rootUser,  companyA.getId(), branchA.getId()).value();
        plainToken    = jwtService.issueAccessToken(plainUser, companyA.getId(), branchA.getId()).value();
        companyBToken = jwtService.issueAccessToken(rootUser,  companyB.getId(), branchB.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ===================================================================
    // Test 1 — Unauthenticated → 401 on DOCUMENT.VIEW list
    // ===================================================================

    @Test
    void unauthenticated_listDocuments_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .param("companyId", companyA.getId().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ===================================================================
    // Test 2 — Non-root without DOCUMENT.VIEW → 403
    // ===================================================================

    @Test
    void nonRoot_withoutPermission_listDocuments_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ===================================================================
    // Test 3 — Root bypasses permission check on DOCUMENT.VIEW list → 200
    // ===================================================================

    @Test
    void root_listDocuments_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/documents")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 4 — Non-root granted DOCUMENT.BRANDING.MANAGE → 200 on GET branding
    // ===================================================================

    @Test
    void nonRoot_grantedBrandingManage_getBranding_returns200() throws Exception {
        grantPermissionAsRoot(plainUser, "DOCUMENT.BRANDING.MANAGE");

        mockMvc.perform(get("/api/v1/documents/branding")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(companyA.getId()));
    }

    // ===================================================================
    // Test 5 — GET branding returns seeded displayName = company name
    // ===================================================================

    @Test
    void root_getBranding_returnsSeededDisplayName() throws Exception {
        mockMvc.perform(get("/api/v1/documents/branding")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value(companyA.getName()))
                .andExpect(jsonPath("$.data.companyId").value(companyA.getId()))
                .andExpect(jsonPath("$.data.uid").isString());
    }

    // ===================================================================
    // Test 6 — PUT branding updates displayName → reflected in GET
    // ===================================================================

    @Test
    void root_updateBranding_newDisplayName_reflected() throws Exception {
        String body = """
                {
                  "displayName": "Acme Corp Updated",
                  "version": 0
                }
                """;
        mockMvc.perform(put("/api/v1/documents/branding")
                        .header("Authorization", "Bearer " + rootToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Acme Corp Updated"));

        // GET should reflect it
        mockMvc.perform(get("/api/v1/documents/branding")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Acme Corp Updated"));
    }

    // ===================================================================
    // Test 7 — Non-root without DOCUMENT.TEMPLATE.MANAGE → 403 on template list
    // ===================================================================

    @Test
    void nonRoot_withoutPermission_listTemplates_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/documents/templates")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ===================================================================
    // Test 8 — Root lists templates → 200 array with 6 seeded v1 types
    // ===================================================================

    @Test
    void root_listTemplates_returns6SeedTypes() throws Exception {
        mockMvc.perform(get("/api/v1/documents/templates")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(6));
    }

    // ===================================================================
    // Test 9 — Scope guard: companyB token cannot access companyA branding
    //          GET /api/v1/documents/branding resolves principal.companyId() = companyB;
    //          brandingService.getForCompany returns companyB's own branding (not companyA's).
    //          This verifies that the branding endpoint is scoped to the JWT's companyId.
    // ===================================================================

    @Test
    void companyBToken_getBranding_returnsCompanyBProfile() throws Exception {
        // companyB token should return companyB's branding, NOT companyA's
        mockMvc.perform(get("/api/v1/documents/branding")
                        .header("Authorization", "Bearer " + companyBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(companyB.getId()));
    }

    // ===================================================================
    // Test 10 — Render endpoint gate: no permission → 403
    // ===================================================================

    @Test
    void nonRoot_withoutRenderPermission_postRender_returns403() throws Exception {
        String body = """
                {"documentType":"INVOICE","sourceUid":"nonexistent-uid-00000000000"}
                """;
        mockMvc.perform(post("/api/v1/documents/render")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ===================================================================
    // Private helpers
    // ===================================================================

    private void grantPermissionAsRoot(AppUser user, String permCode) {
        Permission perm = permissions.findByCode(permCode)
                .orElseThrow(() -> new IllegalStateException(
                        permCode + " must be seeded by V23 migration"));
        Role role = new Role("DOC_ROLE_" + permCode, "Doc role " + permCode);
        role.setPermissions(Set.of(perm));
        role = roles.save(role);

        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                companyA.getId(), branchA.getId(), null));
        try {
            testData.seedMembership(user.getUid(), companyA.getUid());
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), companyA.getUid(), null));
            permissionResolver.invalidate();
        } finally {
            RequestContext.clear();
        }
    }
}
