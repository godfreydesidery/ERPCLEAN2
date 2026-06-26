package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.BankReconciliationDto;
import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.OpenReconciliationRequest;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression tests for the confused-deputy vulnerability in cash-bank listing endpoints.
 *
 * <p>Root cause: {@code CashDirectEntryServiceImpl.listByAccount} and
 * {@code BankReconciliationServiceImpl.listByAccount} previously asserted
 * {@code scopeGuard.assertCanActIn(ctx, companyId)} only on the <em>caller-supplied</em>
 * {@code companyId}, then queried by the caller-supplied {@code accountId} with no ownership check.
 * A Company-A admin could pass {@code companyId=A} (satisfying the guard) and
 * {@code accountId=B_account} (belonging to Company B) to read Company B's cash ledger or
 * reconciliations.
 *
 * <p>Fix: both methods now resolve the account via {@code accounts.findById(accountId)} and
 * assert {@code account.getCompanyId().equals(companyId)} — throwing 404 (no existence leak)
 * when the account does not belong to the asserted company.
 *
 * <p><b>Manual repro (pre-fix):</b>
 * <pre>
 *   # As B_ADMIN, create a BANK account → note its numeric id (e.g. 11) and companyId (e.g. 5)
 *   # As A_ADMIN (companyId=4):
 *   GET /api/v1/cash/entries?companyId=4&accountId=11          → was 200 with B's ledger; now 404
 *   GET /api/v1/cash/reconciliations?companyId=4&accountId=11  → was 200 with B's recons; now 404
 * </pre>
 */
class CashbankCrossCompanyIsolationIT extends PostgresIntegrationTest {

    @Autowired private CashDirectEntryService    directEntryService;
    @Autowired private BankReconciliationService reconService;
    @Autowired private CashBankAccountService    accountService;
    @Autowired private ChartOfAccountService     chartOfAccountService;
    @Autowired private ChartOfAccountRepository  glAccountRepo;
    @Autowired private FiscalCalendarService     fiscalCalendarService;
    @Autowired private GlConfigService           glConfigService;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private IamTestData               testData;

    // Company A fixtures
    private Company companyA;
    private Branch  branchA;
    private Long    rootA;
    private CashBankAccountDto cashAccountA;
    private CashBankAccountDto bankAccountA;
    private String  incomeGlUidA;

    // Company B fixtures
    private Company companyB;
    private Branch  branchB;
    private Long    rootB;
    private CashBankAccountDto cashAccountB;
    private CashBankAccountDto bankAccountB;
    private String  incomeGlUidB;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Isolation IT Org"));

        // --- Company A ---
        companyA = companies.save(new Company(org, "ISOA", "Isolation Co A"));
        branchA  = branches.save(new Branch(companyA, "ISOA1", "Isolation Branch A"));

        AppUser userA = new AppUser("iso_root_a", passwordEncoder.encode("RootPass1!"), "ISO Root A");
        userA.setRoot(true);
        userA  = users.save(userA);
        rootA  = userA.getId();

        actAs(rootA, "iso_root_a", companyA, branchA);
        chartOfAccountService.seedDefaults(companyA.getId());
        fiscalCalendarService.seedCurrentYear(companyA.getId());
        glConfigService.seedDefaults(companyA.getId());

        String cashGlA = glUid(companyA.getId(), "1000");
        String bankGlA = glUid(companyA.getId(), "1100");
        incomeGlUidA   = glUid(companyA.getId(), "4100");

        cashAccountA = accountService.create(new CreateCashBankAccountRequest(
                companyA.getUid(), null, "A Petty Cash", CashBankAccountType.CASH,
                null, null, null, cashGlA, false));
        bankAccountA = accountService.create(new CreateCashBankAccountRequest(
                companyA.getUid(), null, "A Bank", CashBankAccountType.BANK,
                "CRDB", "111", "Dar", bankGlA, false));

        // --- Company B ---
        companyB = companies.save(new Company(org, "ISOB", "Isolation Co B"));
        branchB  = branches.save(new Branch(companyB, "ISOB1", "Isolation Branch B"));

        AppUser userB = new AppUser("iso_root_b", passwordEncoder.encode("RootPass1!"), "ISO Root B");
        userB.setRoot(true);
        userB  = users.save(userB);
        rootB  = userB.getId();

        actAs(rootB, "iso_root_b", companyB, branchB);
        chartOfAccountService.seedDefaults(companyB.getId());
        fiscalCalendarService.seedCurrentYear(companyB.getId());
        glConfigService.seedDefaults(companyB.getId());

        String cashGlB = glUid(companyB.getId(), "1000");
        String bankGlB = glUid(companyB.getId(), "1100");
        incomeGlUidB   = glUid(companyB.getId(), "4100");

        cashAccountB = accountService.create(new CreateCashBankAccountRequest(
                companyB.getUid(), null, "B Petty Cash", CashBankAccountType.CASH,
                null, null, null, cashGlB, false));
        bankAccountB = accountService.create(new CreateCashBankAccountRequest(
                companyB.getUid(), null, "B Bank", CashBankAccountType.BANK,
                "NMB", "222", "Arusha", bankGlB, false));

        // Seed Company B's cash ledger and a reconciliation so there is data to leak
        directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyB.getUid(), bankAccountB.uid(), CashTxnDirection.IN,
                new BigDecimal("1000"), LocalDate.now(), incomeGlUidB, "B interest"));

        reconService.open(new OpenReconciliationRequest(
                companyB.getUid(), bankAccountB.uid(), LocalDate.now(), null, new BigDecimal("1000")));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // CashDirectEntryServiceImpl.listByAccount
    // -------------------------------------------------------------------------

    /**
     * Company-A caller, A's own companyId, A's own CASH account → succeeds (zero rows, correct).
     */
    @Test
    void listEntries_ownAccount_succeeds() {
        actAs(rootA, "iso_root_a", companyA, branchA);

        List<CashTransactionDto> result = directEntryService.listByAccount(
                companyA.getId(), cashAccountA.id());

        assertThat(result).isEmpty(); // no entries posted on A's cash account
    }

    /**
     * Company-A caller supplies A's own companyId but B's accountId → 404 (no existence leak).
     * Pre-fix this returned 200 with Company B's transactions.
     */
    @Test
    void listEntries_crossCompanyAccountId_throwsNotFound() {
        actAs(rootA, "iso_root_a", companyA, branchA);

        Long bAccountId = bankAccountB.id();
        Long aCompanyId = companyA.getId();

        assertThatThrownBy(() -> directEntryService.listByAccount(aCompanyId, bAccountId))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Verify no Company-B data leaks: the result for Company A contains no Company-B transactions.
     */
    @Test
    void listEntries_ownAccount_doesNotLeakForeignData() {
        actAs(rootA, "iso_root_a", companyA, branchA);

        // Record a transaction on A's bank account first
        directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyA.getUid(), bankAccountA.uid(), CashTxnDirection.IN,
                new BigDecimal("500"), LocalDate.now(), incomeGlUidA, "A interest"));

        List<CashTransactionDto> result = directEntryService.listByAccount(
                companyA.getId(), bankAccountA.id());

        assertThat(result).hasSize(1);
        assertThat(result).allMatch(t -> companyA.getId().equals(t.companyId()),
                "all returned transactions must belong to Company A");
    }

    // -------------------------------------------------------------------------
    // BankReconciliationServiceImpl.listByAccount
    // -------------------------------------------------------------------------

    /**
     * Company-A caller, A's own companyId, A's own BANK account → succeeds (zero rows, correct).
     */
    @Test
    void listReconciliations_ownAccount_succeeds() {
        actAs(rootA, "iso_root_a", companyA, branchA);

        List<BankReconciliationDto> result = reconService.listByAccount(
                companyA.getId(), bankAccountA.id());

        assertThat(result).isEmpty(); // no reconciliations opened on A's bank account
    }

    /**
     * Company-A caller supplies A's own companyId but B's BANK accountId → 404 (no existence leak).
     * Pre-fix this returned 200 with Company B's reconciliations.
     */
    @Test
    void listReconciliations_crossCompanyAccountId_throwsNotFound() {
        actAs(rootA, "iso_root_a", companyA, branchA);

        Long bAccountId = bankAccountB.id();
        Long aCompanyId = companyA.getId();

        assertThatThrownBy(() -> reconService.listByAccount(aCompanyId, bAccountId))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Verify no Company-B reconciliation data leaks: the result for Company A's own account
     * contains no Company-B rows even though Company B has seeded reconciliations.
     */
    @Test
    void listReconciliations_ownAccount_doesNotLeakForeignData() {
        // Open a reconciliation on A's bank account
        actAs(rootA, "iso_root_a", companyA, branchA);
        reconService.open(new OpenReconciliationRequest(
                companyA.getUid(), bankAccountA.uid(), LocalDate.now(), null, BigDecimal.ZERO));

        List<BankReconciliationDto> result = reconService.listByAccount(
                companyA.getId(), bankAccountA.id());

        assertThat(result).hasSize(1);
        assertThat(result).allMatch(r -> companyA.getId().equals(r.companyId()),
                "all returned reconciliations must belong to Company A");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void actAs(Long userId, String username, Company company, Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                userId, username, true, company.getId(), branch.getId(), null));
    }

    private String glUid(Long companyId, String code) {
        return glAccountRepo.findByCompanyIdAndAccountCode(companyId, code)
                .map(a -> a.getUid())
                .orElseThrow(() -> new AssertionError("GL account " + code + " not seeded for company " + companyId));
    }
}
