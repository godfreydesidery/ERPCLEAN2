package com.erp.api;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
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
import com.erp.modules.gl.repository.FiscalYearRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level gate guard for the Year-End Close controller (ADR-0019). Drives the real Spring Security
 * filter chain (bearer → {@code @perm.scoped}). Deep close/reopen logic is covered by
 * {@code YearEndCloseServiceIT}. Guards: a non-{@code GL.YEAR.CLOSE} principal → 403; root → 200.
 */
@AutoConfigureMockMvc
class YearEndCloseHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc            mockMvc;
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
    @Autowired private FiscalYearRepository    fiscalYears;

    private Company company;
    private Branch  branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String  rootToken;
    private String  plainToken;
    private String  fiscalYearUid;

    private static final String ROOT_PASS  = "YecRootH1!z";
    private static final String PLAIN_PASS = "YecPlainH1!z";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("YEC HTTP Org"));
        company = companies.save(new Company(org, "YECHTTP", "YEC HTTP Co"));
        branch  = new Branch(company, "YECHTTP1", "YEC HTTP HQ");
        branch.setDefault(true);
        branch  = branches.save(branch);

        rootUser = new AppUser("yec_http_root", passwordEncoder.encode(ROOT_PASS), "YEC HTTP Root");
        rootUser.setRoot(true);
        rootUser = users.save(inOrganisation(rootUser, org.getId()));
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("yec_http_plain", passwordEncoder.encode(PLAIN_PASS), "YEC HTTP Plain");
        plainUser = users.save(inOrganisation(plainUser, org.getId()));
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        // Read the seeded fiscal year's uid via the repository (unguarded) — calling the
        // scope-guarded FiscalCalendarService here would need a RequestContext set in setUp.
        fiscalYearUid = fiscalYears.findByCompanyIdOrderByStartDateDesc(company.getId()).get(0).getUid();

        rootToken  = jwtService.issueAccessToken(rootUser,  company.getId(), branch.getId()).value();
        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void plainUserWithoutPerm_close_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/gl/periods/fiscal-years/uid/" + fiscalYearUid + "/close")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void plainUserWithoutPerm_reopen_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/gl/periods/fiscal-years/uid/" + fiscalYearUid + "/reopen")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void plainUserAfterGrant_close_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/gl/periods/fiscal-years/uid/" + fiscalYearUid + "/close")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        grantRoleAsRoot(plainUser, buildRole("YEAR_CLOSER", "GL.YEAR.CLOSE"));

        // No prior year, no P&L activity → close succeeds (no journal needed); the gate now passes.
        mockMvc.perform(post("/api/v1/gl/periods/fiscal-years/uid/" + fiscalYearUid + "/close")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk());
    }

    @Test
    void root_close_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/gl/periods/fiscal-years/uid/" + fiscalYearUid + "/close")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by V16__year_end_close.sql"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    private void grantRoleAsRoot(AppUser user, Role role) {
        testData.seedMembership(user.getUid(), company.getUid());
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
