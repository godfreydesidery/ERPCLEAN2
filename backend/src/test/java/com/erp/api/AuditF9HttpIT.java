package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.service.UserService;
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
 * HTTP-level integration test for F9 (ADR-0004 D-8): a user disabled mid-session is rejected on
 * their NEXT request, not only after the access-token TTL expires. {@code JwtRequestContextFilter}
 * re-checks the user is ACTIVE per authenticated request (AppUserRepository.existsByIdAndStatus); a
 * disabled user's still-valid token no longer authorises anything.
 */
@AutoConfigureMockMvc
class AuditF9HttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private IamTestData testData;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private UserService userService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private AppUser rootSentinel;
    private AppUser userU;
    private String tokenForU;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("F9 Test Org"));
        Company company = companies.save(new Company(org, "F9C", "F9 Company"));
        Branch branch = new Branch(company, "F9-B", "F9 Branch");
        branch.setDefault(true);
        branch = branches.save(branch);

        rootSentinel = new AppUser("f9_root", passwordEncoder.encode("RootF9Pass1!"), "F9 Root");
        rootSentinel.setRoot(true);
        rootSentinel.setOrganisationId(org.getId());
        rootSentinel = users.save(rootSentinel);

        userU = new AppUser("f9_user", passwordEncoder.encode("F9UserPass1!"), "F9 User");
        userU.setOrganisationId(org.getId());
        userU = users.save(userU);

        // Token scoped to the company/branch — enough to authenticate; /auth/me needs only auth.
        tokenForU = jwtService.issueAccessToken(userU, company.getId(), branch.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void activeUser_getMe_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokenForU))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("f9_user"));
    }

    @Test
    void userDisabledMidSession_sameToken_isRejectedOnNextRequest() throws Exception {
        // Disable U (admin action; UserService reads RequestContext for the actor/audit).
        RequestContext.set(new RequestContext.Principal(
                rootSentinel.getId(), rootSentinel.getUsername(), true, null, null, null));
        try {
            userService.disableByUid(userU.getUid());
        } finally {
            RequestContext.clear();
        }

        // The SAME still-valid token must now be refused (the filter re-checks isActive). The exact
        // code is 401 or 403 depending on how the filter signals it — assert it is NOT 200 and is a
        // 4xx auth refusal carrying the ApiResponse error envelope.
        int statusCode = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokenForU))
                .andExpect(jsonPath("$.errors").isArray())
                .andReturn().getResponse().getStatus();

        org.assertj.core.api.Assertions.assertThat(statusCode)
                .as("a disabled user's still-valid token must be refused (401 or 403)")
                .isIn(401, 403);
    }
}
