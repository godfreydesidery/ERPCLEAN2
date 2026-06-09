package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
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
import com.erp.modules.iam.domain.dto.GrantRoleRequest;
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

/**
 * HTTP-level gate tests for Cash & Bank account endpoints (ADR-0016 D-2a/D-12).
 * Mirrors {@link ApHttpIT}:
 * <ul>
 *   <li>root GET /cash/accounts → 200.
 *   <li>non-root without CASH.VIEW → 403 with ApiResponse envelope.
 *   <li>non-root WITH CASH.VIEW granted → 200 (grant flips without re-issuing token).
 *   <li>root POST with invalid body → 400.
 *   <li>CASH.ACCOUNT.MANAGE protected: plain user without perm → 403.
 * </ul>
 */
@AutoConfigureMockMvc
class CashBankAccountHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc            mockMvc;
    @Autowired private ObjectMapper       objectMapper;
    @Autowired private IamTestData        testData;
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
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;

    private Company company;
    private Branch  branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String  rootToken;
    private String  plainToken;

    private static final String ROOT_PASS  = "CbRootH1!z";
    private static final String PLAIN_PASS = "CbPlainH1!z";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("CB HTTP Test Org"));
        company = companies.save(new Company(org, "CBHTTP", "CB HTTP Co"));

        branch = new Branch(company, "CBHTTP1", "CB HTTP HQ");
        branch.setDefault(true);
        branch = branches.save(branch);

        rootUser = new AppUser("cb_http_root", passwordEncoder.encode(ROOT_PASS), "CB HTTP Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("cb_http_plain", passwordEncoder.encode(PLAIN_PASS), "CB HTTP Plain");
        plainUser = users.save(plainUser);
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        rootToken  = jwtService.issueAccessToken(rootUser,  company.getId(), branch.getId()).value();
        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();

        // Seed GL prerequisites so root can make real requests without a 500
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // GET /cash/accounts — list
    // =========================================================================

    @Test
    void rootListsAccounts_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/cash/accounts")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());
    }

    @Test
    void plainUserWithoutPerm_listAccounts_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/cash/accounts")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void plainUserAfterCashViewGrant_listAccounts_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/cash/accounts")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        grantRoleAsRoot(plainUser, buildRole("CASH_VIEWER", "CASH.VIEW"));

        mockMvc.perform(get("/api/v1/cash/accounts")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk());
    }

    @Test
    void listAccounts_missingCompanyId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/cash/accounts")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // POST /cash/accounts — create
    // =========================================================================

    @Test
    void plainUserWithoutManagePerm_createAccount_returns403() throws Exception {
        Map<String, Object> body = Map.of(
                "companyUid",   company.getUid(),
                "name",         "Petty Cash",
                "accountType",  "CASH",
                "glAccountUid", "some-uid");

        mockMvc.perform(post("/api/v1/cash/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void rootCreatesAccount_invalidBody_returns400() throws Exception {
        // Missing required glAccountUid → @NotBlank validation fails
        Map<String, Object> body = Map.of(
                "companyUid",  company.getUid(),
                "name",        "Test Cash",
                "accountType", "CASH");

        mockMvc.perform(post("/api/v1/cash/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by V13__cash_and_bank.sql"));
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
