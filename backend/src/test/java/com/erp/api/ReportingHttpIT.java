package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level gate + export regression guard for the Reporting controller layer (ADR-0018).
 *
 * <p>Drives requests through the REAL Spring Security filter chain (bearer → {@code @PreAuthorize}).
 * Deep statement logic + the reconciliation bars are covered by the service ITs
 * ({@code IncomeStatementIT}/{@code BalanceSheetIT}/{@code CashFlowIT}/{@code AccountLedgerIT}).
 * Guards here:
 * <ul>
 *   <li>root GET /reports/balance-sheet → 200 ApiResponse envelope (root @perm bypass).
 *   <li>non-root without REPORT.BS.VIEW → 403.
 *   <li>non-root WITH REPORT.BS.VIEW granted → 200.
 *   <li>export (CSV) → 200 with text/csv + Content-Disposition attachment, and the body is RAW
 *       bytes (NOT a JSON ApiResponse envelope — the ApiResponseAdvice binary pass-through, D-9).
 *   <li>export gated by REPORT.EXPORT (BS.VIEW alone is not enough).
 * </ul>
 */
@AutoConfigureMockMvc
class ReportingHttpIT extends PostgresIntegrationTest {

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

    private Company company;
    private Branch  branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String  rootToken;
    private String  plainToken;

    private static final String ROOT_PASS  = "RepRootH1!z";
    private static final String PLAIN_PASS = "RepPlainH1!z";
    private static final String AS_AT      = LocalDate.now().toString();

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("Reporting HTTP Org"));
        company = companies.save(new Company(org, "REPHTTP", "Reporting HTTP Co"));
        branch  = new Branch(company, "REPHTTP1", "Reporting HTTP HQ");
        branch.setDefault(true);
        branch  = branches.save(branch);

        rootUser = new AppUser("rep_http_root", passwordEncoder.encode(ROOT_PASS), "Rep HTTP Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("rep_http_plain", passwordEncoder.encode(PLAIN_PASS), "Rep HTTP Plain");
        plainUser = users.save(plainUser);
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        // A statement needs the CoA + fiscal calendar seeded (an empty company still balances at 0).
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        rootToken  = jwtService.issueAccessToken(rootUser,  company.getId(), branch.getId()).value();
        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void rootBalanceSheet_returns200_envelope() throws Exception {
        mockMvc.perform(get("/api/v1/reports/balance-sheet")
                        .param("companyId", company.getId().toString())
                        .param("asAtDate", AS_AT)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    void plainUserWithoutPerm_balanceSheet_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/reports/balance-sheet")
                        .param("companyId", company.getId().toString())
                        .param("asAtDate", AS_AT)
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void plainUserAfterBsViewGrant_balanceSheet_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/reports/balance-sheet")
                        .param("companyId", company.getId().toString())
                        .param("asAtDate", AS_AT)
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        grantRoleAsRoot(plainUser, buildRole("BS_VIEWER", "REPORT.BS.VIEW"));

        mockMvc.perform(get("/api/v1/reports/balance-sheet")
                        .param("companyId", company.getId().toString())
                        .param("asAtDate", AS_AT)
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk());
    }

    @Test
    void rootExportCsv_returnsRawBytes_notJsonEnvelope() throws Exception {
        byte[] body = mockMvc.perform(get("/api/v1/reports/balance-sheet/export")
                        .param("companyId", company.getId().toString())
                        .param("asAtDate", AS_AT)
                        .param("format", "CSV")
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("text/csv")))
                .andReturn().getResponse().getContentAsByteArray();

        // The binary pass-through (ApiResponseAdvice) must NOT have JSON-wrapped the CSV.
        String csv = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain("\"data\"").doesNotStartWith("{");
        assertThat(body).isNotEmpty();
    }

    @Test
    void exportRequiresExportPerm_bsViewAloneIsNotEnough() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("BS_VIEWER2", "REPORT.BS.VIEW"));

        // Has BS.VIEW but NOT REPORT.EXPORT → export endpoint forbidden.
        mockMvc.perform(get("/api/v1/reports/balance-sheet/export")
                        .param("companyId", company.getId().toString())
                        .param("asAtDate", AS_AT)
                        .param("format", "CSV")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by V15__reporting_permissions.sql"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
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
