package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.documents.repository.DocumentTemplateRepository;
import com.erp.modules.documents.service.DocumentBrandingSeeder;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression IT for the confused-deputy fix on
 * PUT /api/v1/documents/templates/{uid} (DocumentTemplateServiceImpl.update).
 *
 * <p>Verified scenario (empirically confirmed pre-fix):
 * A_ADMIN holds a valid A-company template uid. Caller supplies Company-B's branding id
 * (obtained from a separate B-company session) in the request body. Before the fix this
 * persisted a cross-tenant FK; after the fix it returns 404.
 *
 * <p>Manual repro (pre-fix, against a running stack):
 * <ol>
 *   <li>Log in as B_ADMIN → GET /api/v1/documents/branding → note response.id (e.g. 4,
 *       companyId = companyB).</li>
 *   <li>Log in as A_ADMIN → GET /api/v1/documents/templates → note an A-template uid.</li>
 *   <li>PUT /api/v1/documents/templates/{A-uid} body {"brandingId": 4} → was 200 OK with
 *       brandingId=4; after fix → 404.</li>
 *   <li>Verify no cross-tenant brandingId was persisted on the A template row.</li>
 * </ol>
 */
@AutoConfigureMockMvc
class DocumentTemplateBrandingGuardIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private IamTestData             testData;
    @Autowired private PermissionResolver      permissionResolver;
    @Autowired private DocumentBrandingSeeder  brandingSeeder;

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private UserBranchRepository    userBranches;

    @Autowired private DocumentBrandingRepository  brandingRepo;
    @Autowired private DocumentTemplateRepository  templateRepo;

    @Autowired private JwtService      jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Company companyA;
    private Branch  branchA;
    private Company companyB;
    private Branch  branchB;

    private AppUser rootUser;

    /** Root token scoped to company A. */
    private String tokenA;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("BrandGuard Org"));

        companyA = companies.save(new Company(org, "BGDA", "Brand Guard Co A"));
        branchA  = new Branch(companyA, "BGA1", "Brand Guard A HQ");
        branchA.setDefault(true);
        branchA  = branches.save(branchA);

        companyB = companies.save(new Company(org, "BGDB", "Brand Guard Co B"));
        branchB  = new Branch(companyB, "BGB1", "Brand Guard B HQ");
        branchB.setDefault(true);
        branchB  = branches.save(branchB);

        // Seed branding + templates for both companies (mirrors DocumentHttpIT bootstrap).
        brandingSeeder.seedDefaults(companyA.getId());
        brandingSeeder.seedDefaults(companyB.getId());

        rootUser = new AppUser("bg_root", passwordEncoder.encode("BgRoot1!"), "BG Root");
        rootUser.setRoot(true);
        rootUser.setOrganisationId(org.getId());
        rootUser = users.save(rootUser);
        UserBranch assign = new UserBranch(rootUser.getId(), branchA, rootUser.getId());
        assign.markDefault();
        userBranches.save(assign);

        tokenA = jwtService.issueAccessToken(rootUser, companyA.getId(), branchA.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Fix verification: supplying Company-B's brandingId on a Company-A
    // template PUT must return 404 and must NOT persist the cross-tenant FK.
    // -----------------------------------------------------------------------

    @Test
    void updateTemplate_withForeignBrandingId_returns404_andDoesNotPersist() throws Exception {
        // Locate an A-company template and B-company branding seeded above.
        DocumentTemplate aTemplate = templateRepo.findByCompanyId(companyA.getId())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No templates seeded for companyA"));

        DocumentBranding bBranding = brandingRepo.findByCompanyId(companyB.getId())
                .orElseThrow(() -> new IllegalStateException("No branding seeded for companyB"));

        Long originalBrandingId = aTemplate.getBrandingId();

        // Attempt to point Company-A template at Company-B branding.
        String body = """
                {"brandingId": %d}
                """.formatted(bBranding.getId());

        mockMvc.perform(put("/api/v1/documents/templates/{uid}", aTemplate.getUid())
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors").isArray());

        // Confirm no cross-tenant write persisted.
        DocumentTemplate reloaded = templateRepo.findByUid(aTemplate.getUid())
                .orElseThrow();
        assertThat(reloaded.getBrandingId())
                .as("brandingId must not have been changed to Company-B's value")
                .isEqualTo(originalBrandingId);
    }

    // -----------------------------------------------------------------------
    // Happy-path: supplying Company-A's own brandingId must still succeed.
    // -----------------------------------------------------------------------

    @Test
    void updateTemplate_withOwnBrandingId_returns200_andPersists() throws Exception {
        DocumentTemplate aTemplate = templateRepo.findByCompanyId(companyA.getId())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No templates seeded for companyA"));

        DocumentBranding aBranding = brandingRepo.findByCompanyId(companyA.getId())
                .orElseThrow(() -> new IllegalStateException("No branding seeded for companyA"));

        String body = """
                {"brandingId": %d}
                """.formatted(aBranding.getId());

        mockMvc.perform(put("/api/v1/documents/templates/{uid}", aTemplate.getUid())
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandingId").value(aBranding.getId()));

        DocumentTemplate reloaded = templateRepo.findByUid(aTemplate.getUid()).orElseThrow();
        assertThat(reloaded.getBrandingId()).isEqualTo(aBranding.getId());
    }
}
