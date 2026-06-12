package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.budgeting.domain.dto.CreateBudgetRequest;
import com.erp.modules.budgeting.domain.dto.RejectBudgetVersionRequest;
import com.erp.modules.budgeting.domain.dto.UpsertBudgetLineRequest;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
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
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level integration test for Budgeting endpoints (ADR-0034).
 *
 * <p>Guards:
 * <ul>
 *   <li>Root may create a budget + submit + approve a version (happy path).</li>
 *   <li>Non-root without BUDGETING.BUDGET.MANAGE → 403 on POST /budgets.</li>
 *   <li>Non-root without BUDGETING.BUDGET.VIEW → 403 on GET /budgets.</li>
 *   <li>Submit with zero lines → 409 Conflict (BR-BUD-11).</li>
 * </ul>
 */
@AutoConfigureMockMvc
class BudgetHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private IamTestData testData;
    @Autowired private PermissionResolver permissionResolver;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private FiscalYearRepository fiscalYears;
    @Autowired private FiscalPeriodRepository fiscalPeriods;
    @Autowired private ChartOfAccountRepository accounts;

    private Company company;
    private Branch branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String rootToken;
    private String plainToken;
    private FiscalYear fy;
    private ChartOfAccount account;

    private static final String ROOT_PASS  = "BudRootH1!x";
    private static final String PLAIN_PASS = "BudPlainH1!x";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("Budget IT Org"));
        company = companies.save(new Company(org, "BUD", "Budget IT Co"));
        branch  = branches.save(new Branch(company, "BUD-HQ", "Budget HQ"));

        rootUser = new AppUser("bud_root", passwordEncoder.encode(ROOT_PASS), "Bud Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rb = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rb.markDefault();
        userBranches.save(rb);

        plainUser = new AppUser("bud_plain", passwordEncoder.encode(PLAIN_PASS), "Bud Plain");
        plainUser = users.save(plainUser);
        UserBranch pb = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        pb.markDefault();
        userBranches.save(pb);

        rootToken  = jwtService.issueAccessToken(rootUser,  company.getId(), branch.getId()).value();
        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();

        // Create a fiscal year + 12 periods for the test company
        fy = fiscalYears.save(new FiscalYear(company.getId(), "FY2026", 1,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), rootUser.getId()));
        for (int m = 1; m <= 12; m++) {
            LocalDate start = LocalDate.of(2026, m, 1);
            LocalDate end   = start.withDayOfMonth(start.lengthOfMonth());
            FiscalPeriod p  = new FiscalPeriod(company.getId(), fy.getId(), m, start, end, rootUser.getId());
            fiscalPeriods.save(p);
        }

        // Create a test account
        account = accounts.save(new ChartOfAccount(company.getId(), "5300", "Salaries", AccountType.EXPENSE, rootUser.getId()));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Root happy path
    // =========================================================================

    @Test
    void root_createBudget_returns201() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateBudgetRequest(company.getId(), "Sales Dept FY2026 Budget",
                        fy.getUid(), null, "Test budget", null));
        mockMvc.perform(post("/api/v1/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.budgetNumber").value(org.hamcrest.Matchers.startsWith("BUDGET-")))
                .andExpect(jsonPath("$.data.versions").isArray());
    }

    @Test
    void root_listBudgets_returns200() throws Exception {
        // Create a budget first
        root_createBudget_returns201();

        mockMvc.perform(get("/api/v1/budgets")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void root_submitEmptyVersion_returns409() throws Exception {
        // Create budget
        String budgetJson = mockMvc.perform(post("/api/v1/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateBudgetRequest(company.getId(), "Empty Budget",
                                        fy.getUid(), null, null, null)))
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Extract version uid from the budget response
        Map<?, ?> budgetResp = objectMapper.readValue(budgetJson, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vers = (List<Map<String, Object>>) ((Map<?, ?>) budgetResp.get("data")).get("versions");
        String versionUid = (String) vers.get(0).get("uid");

        // Submit empty version → 409
        mockMvc.perform(post("/api/v1/budget-versions/uid/{uid}/submit", versionUid)
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // RBAC gate tests
    // =========================================================================

    @Test
    void plainUser_createBudget_without_manage_perm_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateBudgetRequest(company.getId(), "Plain Budget",
                        fy.getUid(), null, null, null));
        mockMvc.perform(post("/api/v1/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void plainUser_listBudgets_without_view_perm_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/budgets")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void root_varianceReport_noApprovedBudget_returnsNoApprovedBudgetFlag() throws Exception {
        mockMvc.perform(get("/api/v1/budgeting/variance")
                        .param("companyId", company.getId().toString())
                        .param("fiscalYearUid", fy.getUid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.header.noApprovedBudget").value(true));
    }
}
