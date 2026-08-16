package com.erp.modules.cashbank.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashCountDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.OpenCashCountRequest;
import com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashCountStatus;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.entity.JournalLine;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
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
 * Integration tests for {@link CashCountServiceImpl} (ADR-0050 D-7 PR-A) against a real Postgres
 * schema: open -&gt; record denominations -&gt; reconcile, with a real GL assertion (the balanced
 * over/short journal entry + the linked DIRECT_ENTRY cash-book row), cross-company denial, and the
 * HTTP-level permission gate.
 */
@AutoConfigureMockMvc
class CashCountIT extends PostgresIntegrationTest {

    @Autowired private CashCountService        cashCountService;
    @Autowired private CashBankAccountService   accountService;
    @Autowired private CashDirectEntryService   directEntryService;
    @Autowired private CashTransactionRepository txnRepo;
    @Autowired private JournalEntryRepository   journalEntryRepo;
    @Autowired private JournalLineRepository    journalLineRepo;
    @Autowired private ChartOfAccountRepository glAccountRepo;
    @Autowired private ChartOfAccountService    chartOfAccountService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;
    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private RoleRepository           roles;
    @Autowired private PermissionRepository     permissions;
    @Autowired private UserBranchRepository     userBranches;
    @Autowired private UserRoleService          userRoleService;
    @Autowired private JwtService               jwtService;
    @Autowired private PasswordEncoder          passwordEncoder;
    @Autowired private PermissionResolver       permissionResolver;
    @Autowired private IamTestData              testData;
    @Autowired private MockMvc                  mockMvc;
    @Autowired private ObjectMapper             objectMapper;

    private Company company;
    private Branch  branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String  rootToken;
    private String  plainToken;
    private String  companyUid;
    private CashBankAccountDto till;

    private static final String ROOT_PASS  = "CcRootH1!z";
    private static final String PLAIN_PASS = "CcPlainH1!z";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("CC IT Org"));
        company = companies.save(new Company(org, "CCIT", "CC IT Co"));
        branch  = new Branch(company, "CCIT1", "CC IT Branch");
        branch.setDefault(true);
        branch  = branches.save(branch);
        companyUid = company.getUid();

        rootUser = new AppUser("cc_root", passwordEncoder.encode(ROOT_PASS), "CC Root");
        rootUser.setRoot(true);
        rootUser.setOrganisationId(org.getId());
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("cc_plain", passwordEncoder.encode(PLAIN_PASS), "CC Plain");
        plainUser.setOrganisationId(org.getId());
        plainUser = users.save(plainUser);
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        rootToken  = jwtService.issueAccessToken(rootUser, company.getId(), branch.getId()).value();
        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();

        actAs(rootUser.getId(), "cc_root", company, branch);

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        String cashGlUid = glUid(company.getId(), "1000");
        till = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "Main Till", CashBankAccountType.CASH,
                null, null, null, cashGlUid, false));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Full flow: open -> denominations -> reconcile, with a real GL assertion
    // -------------------------------------------------------------------------

    @Test
    void fullFlow_overVariance_postsBalancedGlEntry_andLinkedCashBookRow_bookBalanceMatchesCounted() {
        LocalDate businessDate = LocalDate.now();
        String incomeGlUid = glUid(company.getId(), "4100");

        // Seed the till's book balance to 1000 via a direct entry dated before the count.
        directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyUid, till.uid(), CashTxnDirection.IN, new BigDecimal("1000"),
                businessDate.minusDays(1), incomeGlUid, "Seed till balance"));

        CashCountDto opened = cashCountService.open(
                new OpenCashCountRequest(companyUid, till.uid(), businessDate));
        assertThat(opened.status()).isEqualTo(CashCountStatus.OPEN);
        assertThat(opened.expectedAmount()).isEqualByComparingTo("1000");
        assertThat(opened.countNumber()).startsWith("CC-");

        CashCountDto counted = cashCountService.recordDenominations(opened.uid(),
                new RecordDenominationsRequest(List.of(
                        new RecordDenominationsRequest.Line(new BigDecimal("1000"), 1),
                        new RecordDenominationsRequest.Line(new BigDecimal("200"), 1))));
        assertThat(counted.countedAmount()).isEqualByComparingTo("1200");
        assertThat(counted.varianceAmount()).isEqualByComparingTo("200");
        assertThat(counted.status()).isEqualTo(CashCountStatus.COUNTED);
        assertThat(counted.denominations()).hasSize(2);

        CashCountDto reconciled = cashCountService.reconcile(opened.uid());
        assertThat(reconciled.status()).isEqualTo(CashCountStatus.RECONCILED);
        assertThat(reconciled.journalEntryRef()).isNotBlank();

        // --- Real GL assertion: the posted entry is balanced and hits the till GL + POS_CASH_OVER ---
        JournalEntry entry = journalEntryRepo.findByUid(reconciled.journalEntryRef()).orElseThrow();
        List<JournalLine> lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());
        assertThat(lines).hasSize(2);
        BigDecimal totalDebit  = lines.stream().map(JournalLine::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = lines.stream().map(JournalLine::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isEqualByComparingTo("200");

        Long tillGlId  = till.glAccountId();
        Long overGlId  = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "4900").orElseThrow().getId();
        assertThat(lines).anyMatch(l -> l.getAccountId().equals(tillGlId) && l.getDebitAmount().compareTo(new BigDecimal("200")) == 0);
        assertThat(lines).anyMatch(l -> l.getAccountId().equals(overGlId) && l.getCreditAmount().compareTo(new BigDecimal("200")) == 0);

        // --- Linked cash-book row: a DIRECT_ENTRY txn referencing the count uid + the same journal ---
        List<CashTransaction> variantTxns = txnRepo.findByCompanyIdAndSourceRef(company.getId(), opened.uid());
        assertThat(variantTxns).hasSize(1);
        CashTransaction variance = variantTxns.get(0);
        assertThat(variance.getTxnType()).isEqualTo(CashTxnType.DIRECT_ENTRY);
        assertThat(variance.getDirection()).isEqualTo(CashTxnDirection.IN);
        assertThat(variance.getAmount()).isEqualByComparingTo("200");
        assertThat(variance.getJournalEntryRef()).isEqualTo(reconciled.journalEntryRef());

        // --- Cash-book balance now equals the counted amount (1000 seed + 200 variance) ---
        BigDecimal bookBalance = txnRepo.bookBalanceAsOf(
                accountService.getByUid(till.uid()).id(), businessDate);
        assertThat(bookBalance).isEqualByComparingTo("1200");
    }

    @Test
    void fullFlow_shortVariance_postsDrShortExpense_andWritesOutgoingCashRow() {
        LocalDate businessDate = LocalDate.now();
        String incomeGlUid = glUid(company.getId(), "4100");

        directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyUid, till.uid(), CashTxnDirection.IN, new BigDecimal("1000"),
                businessDate.minusDays(1), incomeGlUid, "Seed till balance"));

        CashCountDto opened = cashCountService.open(
                new OpenCashCountRequest(companyUid, till.uid(), businessDate));

        CashCountDto counted = cashCountService.recordDenominations(opened.uid(),
                new RecordDenominationsRequest(List.of(
                        new RecordDenominationsRequest.Line(new BigDecimal("500"), 1),
                        new RecordDenominationsRequest.Line(new BigDecimal("200"), 1))));
        assertThat(counted.countedAmount()).isEqualByComparingTo("700");
        assertThat(counted.varianceAmount()).isEqualByComparingTo("-300");

        CashCountDto reconciled = cashCountService.reconcile(opened.uid());
        assertThat(reconciled.status()).isEqualTo(CashCountStatus.RECONCILED);

        JournalEntry entry = journalEntryRepo.findByUid(reconciled.journalEntryRef()).orElseThrow();
        List<JournalLine> lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());
        Long shortGlId = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "5170").orElseThrow().getId();
        Long tillGlId  = till.glAccountId();
        assertThat(lines).anyMatch(l -> l.getAccountId().equals(shortGlId) && l.getDebitAmount().compareTo(new BigDecimal("300")) == 0);
        assertThat(lines).anyMatch(l -> l.getAccountId().equals(tillGlId) && l.getCreditAmount().compareTo(new BigDecimal("300")) == 0);

        List<CashTransaction> variantTxns = txnRepo.findByCompanyIdAndSourceRef(company.getId(), opened.uid());
        assertThat(variantTxns).hasSize(1);
        assertThat(variantTxns.get(0).getDirection()).isEqualTo(CashTxnDirection.OUT);
        assertThat(variantTxns.get(0).getAmount()).isEqualByComparingTo("300");

        BigDecimal bookBalance = txnRepo.bookBalanceAsOf(
                accountService.getByUid(till.uid()).id(), businessDate);
        assertThat(bookBalance).isEqualByComparingTo("700");
    }

    @Test
    void reconcile_zeroVariance_noGlEntry_noCashRowWritten() {
        LocalDate businessDate = LocalDate.now();
        CashCountDto opened = cashCountService.open(
                new OpenCashCountRequest(companyUid, till.uid(), businessDate));
        assertThat(opened.expectedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        cashCountService.recordDenominations(opened.uid(), new RecordDenominationsRequest(List.of()));

        CashCountDto reconciled = cashCountService.reconcile(opened.uid());
        assertThat(reconciled.status()).isEqualTo(CashCountStatus.RECONCILED);
        assertThat(reconciled.journalEntryRef()).isNull();
        assertThat(txnRepo.findByCompanyIdAndSourceRef(company.getId(), opened.uid())).isEmpty();
    }

    @Test
    void reconcile_calledTwice_isIdempotent_noDoublePost() {
        LocalDate businessDate = LocalDate.now();
        String incomeGlUid = glUid(company.getId(), "4100");
        directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyUid, till.uid(), CashTxnDirection.IN, new BigDecimal("1000"),
                businessDate.minusDays(1), incomeGlUid, "Seed till balance"));

        CashCountDto opened = cashCountService.open(
                new OpenCashCountRequest(companyUid, till.uid(), businessDate));
        cashCountService.recordDenominations(opened.uid(), new RecordDenominationsRequest(List.of(
                new RecordDenominationsRequest.Line(new BigDecimal("1000"), 1),
                new RecordDenominationsRequest.Line(new BigDecimal("200"), 1))));

        CashCountDto first  = cashCountService.reconcile(opened.uid());
        CashCountDto second = cashCountService.reconcile(opened.uid());

        assertThat(second.status()).isEqualTo(CashCountStatus.RECONCILED);
        assertThat(second.journalEntryRef()).isEqualTo(first.journalEntryRef());
        // Only ONE variance cash-book row was ever written
        assertThat(txnRepo.findByCompanyIdAndSourceRef(company.getId(), opened.uid())).hasSize(1);
    }

    @Test
    void open_bankAccount_rejected() {
        String bankGlUid = glUid(company.getId(), "1100");
        CashBankAccountDto bankAccount = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "Main Bank", CashBankAccountType.BANK,
                "NMB", "9876", "Dar", bankGlUid, false));

        assertThatThrownBy(() -> cashCountService.open(
                new OpenCashCountRequest(companyUid, bankAccount.uid(), LocalDate.now())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Cross-company denial (non-root)
    // -------------------------------------------------------------------------

    @Test
    void getByUid_crossCompany_nonRoot_forbidden() {
        CashCountDto opened = cashCountService.open(
                new OpenCashCountRequest(companyUid, till.uid(), LocalDate.now()));

        Organisation org2 = organisations.save(new Organisation("CC IT Org 2"));
        Company company2  = companies.save(new Company(org2, "CCIT2", "CC IT Co 2"));
        Branch branch2    = branches.save(new Branch(company2, "CCIT2B", "CC IT Branch 2"));
        AppUser nonRoot = users.save(inOrganisation(new AppUser(
                "cc_nonroot", passwordEncoder.encode("Pass1!"), "CC NonRoot"), org2.getId()));

        RequestContext.set(new RequestContext.Principal(
                nonRoot.getId(), "cc_nonroot", false, company2.getId(), branch2.getId(), null));

        String openedUid = opened.uid();
        assertThatThrownBy(() -> cashCountService.getByUid(openedUid))
                .as("a non-root caller from a different company must be denied (ADR-0050)")
                .isInstanceOf(ForbiddenException.class);
    }

    // -------------------------------------------------------------------------
    // HTTP-level permission gate: non-root without CASH.COUNT.* is 403; grant flips it
    // -------------------------------------------------------------------------

    @Test
    void plainUserWithoutPermission_openCashCount_returns403_thenGrantReturns201() throws Exception {
        Map<String, Object> body = Map.of(
                "companyUid", companyUid,
                "cashBankAccountUid", till.uid(),
                "businessDate", LocalDate.now().toString());

        mockMvc.perform(post("/api/v1/cash/counts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());

        grantRoleAsRoot(plainUser, buildRole("CASH_COUNT_MANAGER", "CASH.COUNT.MANAGE"));

        mockMvc.perform(post("/api/v1/cash/counts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    void plainUserWithoutPermission_getByUid_returns403_thenGrantReturns200() throws Exception {
        CashCountDto opened = cashCountService.open(
                new OpenCashCountRequest(companyUid, till.uid(), LocalDate.now()));

        mockMvc.perform(get("/api/v1/cash/counts/uid/{uid}", opened.uid())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());

        grantRoleAsRoot(plainUser, buildRole("CASH_COUNT_VIEWER", "CASH.COUNT.VIEW"));

        mockMvc.perform(get("/api/v1/cash/counts/uid/{uid}", opened.uid())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uid").value(opened.uid()));
    }

    @Test
    void plainUserWithoutPermission_recordDenominations_returns403() throws Exception {
        CashCountDto opened = cashCountService.open(
                new OpenCashCountRequest(companyUid, till.uid(), LocalDate.now()));

        Map<String, Object> body = Map.of("lines", List.of(
                Map.of("denomination", "1000", "quantity", 1)));

        mockMvc.perform(put("/api/v1/cash/counts/uid/{uid}/denominations", opened.uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void actAs(Long userId, String username, Company c, Branch b) {
        RequestContext.set(new RequestContext.Principal(userId, username, true, c.getId(), b.getId(), null));
    }

    private String glUid(Long companyId, String code) {
        return glAccountRepo.findByCompanyIdAndAccountCode(companyId, code)
                .map(a -> a.getUid())
                .orElseThrow(() -> new AssertionError("GL account " + code + " not seeded"));
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
