package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.cashbank.domain.dto.CreatePettyCashFundRequest;
import com.erp.modules.cashbank.domain.dto.PettyCashFundDto;
import com.erp.modules.cashbank.domain.dto.PettyCashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordPettyCashTxnRequest;
import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import com.erp.modules.cashbank.repository.PettyCashFundRepository;
import com.erp.platform.bootstrap.CompanyProvisioningService;
import com.erp.modules.iam.domain.dto.BranchDto;
import com.erp.modules.iam.domain.dto.CreateBranchRequest;
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
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
 * Integration tests for {@link PettyCashServiceImpl} (ADR-0050 D-7 PR-B) against a real Postgres
 * schema: create a fund -&gt; record disbursement/replenishment -&gt; balances + balanceAfter
 * correct; the provisioning seed creates exactly one default fund (and re-provision is idempotent);
 * cross-company denial; and the HTTP-level permission gate (non-root without permission is 403).
 */
@AutoConfigureMockMvc
class PettyCashIT extends PostgresIntegrationTest {

    @Autowired private PettyCashService          pettyCashService;
    @Autowired private PettyCashFundRepository   fundRepo;
    @Autowired private PettyCashFundSeeder       seeder;
    @Autowired private CompanyProvisioningService provisioningService;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private RoleRepository            roles;
    @Autowired private PermissionRepository      permissions;
    @Autowired private UserBranchRepository      userBranches;
    @Autowired private BranchService             branchService;
    @Autowired private UserRoleService           userRoleService;
    @Autowired private JwtService                jwtService;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private PermissionResolver        permissionResolver;
    @Autowired private IamTestData               testData;
    @Autowired private MockMvc                   mockMvc;
    @Autowired private ObjectMapper              objectMapper;

    private Company company;
    private Branch  branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String  plainToken;
    private String  companyUid;

    private static final String ROOT_PASS  = "PcRootH1!z";
    private static final String PLAIN_PASS = "PcPlainH1!z";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("PC IT Org"));
        company = companies.save(new Company(org, "PCIT", "PC IT Co"));
        branch  = new Branch(company, "PCIT1", "PC IT Branch");
        branch.setDefault(true);
        branch  = branches.save(branch);
        companyUid = company.getUid();

        rootUser = new AppUser("pc_root", passwordEncoder.encode(ROOT_PASS), "PC Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("pc_plain", passwordEncoder.encode(PLAIN_PASS), "PC Plain");
        plainUser = users.save(plainUser);
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();

        actAs(rootUser.getId(), "pc_root", company, branch);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Full flow: create fund -> disbursement -> replenishment -> balances correct
    // -------------------------------------------------------------------------

    @Test
    void fullFlow_createFund_disburse_replenish_balancesAndBalanceAfterCorrect() {
        PettyCashFundDto fund = pettyCashService.createFund(new CreatePettyCashFundRequest(
                companyUid, "PETTY-HQ", "HQ Petty Cash", null, new BigDecimal("1000"), "TZS"));
        assertThat(fund.balanceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(fund.floatAmount()).isEqualByComparingTo("1000");
        assertThat(fund.status().name()).isEqualTo("ACTIVE");

        // Replenish first so there is something to disburse from.
        PettyCashTransactionDto replenish = pettyCashService.recordTransaction(fund.uid(),
                new RecordPettyCashTxnRequest(PettyCashTxnType.REPLENISHMENT, new BigDecimal("1000"),
                        LocalDate.now(), null, "REPL-1", "Initial float"));
        assertThat(replenish.balanceAfter()).isEqualByComparingTo("1000");
        assertThat(replenish.txnNumber()).startsWith("PC-");

        PettyCashTransactionDto disburse = pettyCashService.recordTransaction(fund.uid(),
                new RecordPettyCashTxnRequest(PettyCashTxnType.DISBURSEMENT, new BigDecimal("350"),
                        LocalDate.now(), null, "DISB-1", "Office supplies"));
        assertThat(disburse.balanceAfter()).isEqualByComparingTo("650");
        assertThat(disburse.amount()).isEqualByComparingTo("350");

        PettyCashFundDto reloaded = pettyCashService.getFund(fund.uid());
        assertThat(reloaded.balanceAmount()).isEqualByComparingTo("650");

        List<PettyCashTransactionDto> history = pettyCashService.listTransactions(fund.uid());
        assertThat(history).hasSize(2);
    }

    @Test
    void disbursement_exceedingBalance_softBlocked_balanceUnchanged() {
        PettyCashFundDto fund = pettyCashService.createFund(new CreatePettyCashFundRequest(
                companyUid, "PETTY-2", "Branch Petty Cash", null, BigDecimal.ZERO, "TZS"));

        pettyCashService.recordTransaction(fund.uid(), new RecordPettyCashTxnRequest(
                PettyCashTxnType.REPLENISHMENT, new BigDecimal("100"), LocalDate.now(), null, null, null));

        assertThatThrownBy(() -> pettyCashService.recordTransaction(fund.uid(), new RecordPettyCashTxnRequest(
                PettyCashTxnType.DISBURSEMENT, new BigDecimal("500"), LocalDate.now(), null, null, null)))
                .isInstanceOf(ConflictException.class);

        PettyCashFundDto reloaded = pettyCashService.getFund(fund.uid());
        assertThat(reloaded.balanceAmount()).isEqualByComparingTo("100");
    }

    @Test
    void adjustment_negativeSigned_decreasesBalance_persistsSignedNegativeAmount() {
        PettyCashFundDto fund = pettyCashService.createFund(new CreatePettyCashFundRequest(
                companyUid, "PETTY-3", "Adj Petty Cash", null, BigDecimal.ZERO, "TZS"));
        pettyCashService.recordTransaction(fund.uid(), new RecordPettyCashTxnRequest(
                PettyCashTxnType.REPLENISHMENT, new BigDecimal("500"), LocalDate.now(), null, null, null));

        PettyCashTransactionDto adj = pettyCashService.recordTransaction(fund.uid(),
                new RecordPettyCashTxnRequest(PettyCashTxnType.ADJUSTMENT, new BigDecimal("-40"),
                        LocalDate.now(), null, null, "Cash count shortfall"));

        // the persisted amount is the SIGNED request amount (negative), not the magnitude — round
        // trips through the real Postgres CHECK constraint (chk_petty_cash_txn_amount).
        assertThat(adj.amount()).isEqualByComparingTo("-40");
        assertThat(adj.amount().signum()).isEqualTo(-1);
        assertThat(adj.balanceAfter()).isEqualByComparingTo("460");

        PettyCashTransactionDto reloaded = pettyCashService.listTransactions(fund.uid()).stream()
                .filter(t -> t.uid().equals(adj.uid()))
                .findFirst().orElseThrow();
        assertThat(reloaded.amount()).isEqualByComparingTo("-40");
    }

    @Test
    void adjustment_positiveSigned_increasesBalance_persistsSignedPositiveAmount() {
        PettyCashFundDto fund = pettyCashService.createFund(new CreatePettyCashFundRequest(
                companyUid, "PETTY-3B", "Adj Petty Cash Pos", null, BigDecimal.ZERO, "TZS"));
        pettyCashService.recordTransaction(fund.uid(), new RecordPettyCashTxnRequest(
                PettyCashTxnType.REPLENISHMENT, new BigDecimal("500"), LocalDate.now(), null, null, null));

        PettyCashTransactionDto adj = pettyCashService.recordTransaction(fund.uid(),
                new RecordPettyCashTxnRequest(PettyCashTxnType.ADJUSTMENT, new BigDecimal("40"),
                        LocalDate.now(), null, null, "Cash count surplus"));

        assertThat(adj.amount()).isEqualByComparingTo("40");
        assertThat(adj.amount().signum()).isEqualTo(1);
        assertThat(adj.balanceAfter()).isEqualByComparingTo("540");
    }

    // -------------------------------------------------------------------------
    // Provisioning: exactly one default fund; re-provision is idempotent
    // -------------------------------------------------------------------------

    @Test
    void provisioningSeed_createsExactlyOneDefaultFund_reprovisionIsIdempotent() {
        // The company was created directly via the repository in setUp (bypassing
        // CompanyServiceImpl.create), so no fund has been seeded yet for it.
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(company.getId())).isEmpty();

        seeder.seedDefaults(company.getId());
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(company.getId())).hasSize(1);
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(company.getId()).get(0).getCode())
                .isEqualTo("PETTY");

        // Re-provisioning the whole company chain must not create a second fund.
        provisioningService.provisionDefaults(company.getId(), "TZS", "TZS", List.of("TZS"));
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(company.getId())).hasSize(1);

        // Calling the seeder again directly is also a no-op.
        seeder.seedDefaults(company.getId());
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(company.getId())).hasSize(1);
    }

    /**
     * Review fix #4: a company's first branch created NON-default defers the seed (no default
     * branch yet); only once {@link BranchService#setDefault} promotes that branch is the seed
     * completed — and exactly once, even if {@code setDefault} is called again on the same branch.
     */
    @Test
    void firstBranchCreatedNonDefault_thenSetDefault_seedsExactlyOneFund_idempotent() {
        Organisation org = organisations.save(new Organisation("PC IT Org SetDefault"));
        Company freshCompany = companies.save(new Company(org, "PCITSD", "PC IT SetDefault Co"));

        BranchDto firstBranch = branchService.create(new CreateBranchRequest(
                freshCompany.getUid(), "SD1", "Non-default First Branch", null, false));
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(freshCompany.getId()))
                .as("no default branch yet -> seed must defer")
                .isEmpty();

        branchService.setDefault(firstBranch.uid());
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(freshCompany.getId())).hasSize(1);
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(freshCompany.getId()).get(0).getCode())
                .isEqualTo("PETTY");

        // Idempotent: promoting the (already-default) branch again must not create a second fund.
        branchService.setDefault(firstBranch.uid());
        assertThat(fundRepo.findByCompanyIdOrderByNameAsc(freshCompany.getId())).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Cross-company denial (non-root)
    // -------------------------------------------------------------------------

    @Test
    void getFund_crossCompany_nonRoot_forbidden() {
        PettyCashFundDto fund = pettyCashService.createFund(new CreatePettyCashFundRequest(
                companyUid, "PETTY-X", "Cross Co Test", null, BigDecimal.ZERO, "TZS"));

        Organisation org2 = organisations.save(new Organisation("PC IT Org 2"));
        Company company2  = companies.save(new Company(org2, "PCIT2", "PC IT Co 2"));
        Branch branch2    = branches.save(new Branch(company2, "PCIT2B", "PC IT Branch 2"));
        AppUser nonRoot = users.save(new AppUser(
                "pc_nonroot", passwordEncoder.encode("Pass1!"), "PC NonRoot"));

        RequestContext.set(new RequestContext.Principal(
                nonRoot.getId(), "pc_nonroot", false, company2.getId(), branch2.getId(), null));

        String fundUid = fund.uid();
        assertThatThrownBy(() -> pettyCashService.getFund(fundUid))
                .as("a non-root caller from a different company must be denied (ADR-0050)")
                .isInstanceOf(ForbiddenException.class);
    }

    // -------------------------------------------------------------------------
    // HTTP-level permission gate: non-root without PETTY_CASH.* is 403; grant flips it
    // -------------------------------------------------------------------------

    @Test
    void plainUserWithoutPermission_createFund_returns403_thenGrantReturns201() throws Exception {
        Map<String, Object> body = Map.of(
                "companyUid", companyUid,
                "code", "PETTY-HTTP",
                "name", "HTTP Petty Cash",
                "floatAmount", "0",
                "currency", "TZS");

        mockMvc.perform(post("/api/v1/petty-cash/funds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());

        grantRoleAsRoot(plainUser, buildRole("PETTY_CASH_MANAGER", "PETTY_CASH.MANAGE"));

        mockMvc.perform(post("/api/v1/petty-cash/funds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("PETTY-HTTP"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void actAs(Long userId, String username, Company c, Branch b) {
        RequestContext.set(new RequestContext.Principal(userId, username, true, c.getId(), b.getId(), null));
    }

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by R__seed_permissions.sql"));
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
