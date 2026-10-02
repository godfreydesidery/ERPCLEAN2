package com.erp.modules.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.gl.service.GlConfigService;
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
import com.erp.modules.reporting.domain.dto.AmountPairDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.domain.dto.StatementLineDto;
import com.erp.modules.reporting.domain.dto.StatementSectionDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Branch-level Income Statement, Balance Sheet and Cash-Flow on real Postgres.
 *
 * <p>Bars:
 * <ol>
 *   <li>Branch A + Branch B + company-level entries == the company statement, line for line
 *       (P&amp;L), section for section (BS) and in net change (CF).
 *   <li>Every slice balances / ties on its own.
 *   <li>The header names the slice ("All branches" when none).
 *   <li>A NON-root caller assigned to Branch A reads A and the company, and is refused Branch C;
 *       a branch of another company is "not found", never a silent company-wide read.
 * </ol>
 */
class BranchStatementsIT extends PostgresIntegrationTest {

    private static final String TZS = "TZS";
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 6, 30);

    @Autowired private ReportingService       reporting;
    @Autowired private GLPostingService       glPosting;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private UserBranchRepository   userBranches;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Organisation org;
    private Company company;
    private Branch  branchA;
    private Branch  branchB;
    private Branch  branchC;
    private Branch  foreignBranch;
    private Long    rootId;
    private final Map<String, Long> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org     = organisations.save(new Organisation("BRST IT Org"));
        company = companies.save(new Company(org, "BRST", "Branch Statements IT Co"));
        branchA = branches.save(new Branch(company, "BRSTA", "Arusha"));
        branchB = branches.save(new Branch(company, "BRSTB", "Dodoma"));
        branchC = branches.save(new Branch(company, "BRSTC", "Mwanza"));
        Company other = companies.save(new Company(org, "BRSTX", "Other IT Co"));
        foreignBranch = branches.save(new Branch(other, "BRSTX1", "Foreign"));

        AppUser root = new AppUser("brst_root", passwordEncoder.encode("BrstRoot1!"), "BRST Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        asRoot();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        for (String code : List.of("1000", "1100", "1200", "1300", "2100", "3000", "4100", "5100", "5200")) {
            acct.put(code, accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                    .orElseThrow(() -> new AssertionError("Account " + code + " not seeded")).getId());
        }

        // Branch A: a cash sale and its cost
        post(branchA.getId(), JournalSourceType.SALES, LocalDate.of(2026, 3, 10), "1000", "4100", "1000");
        post(branchA.getId(), JournalSourceType.COGS,  LocalDate.of(2026, 3, 10), "5100", "1300", "400");
        // Branch B: a credit sale and a stock receipt on credit
        post(branchB.getId(), JournalSourceType.SALES,         LocalDate.of(2026, 4, 2), "1200", "4100", "600");
        post(branchB.getId(), JournalSourceType.STOCK_RECEIPT, LocalDate.of(2026, 4, 1), "1300", "2100", "500");
        // Company-level (no branch): opening capital and a rent journal
        post(null, JournalSourceType.OPENING_BALANCE, LocalDate.of(2026, 1, 5), "1100", "3000", "2000");
        post(null, JournalSourceType.MANUAL,          LocalDate.of(2026, 5, 1), "5200", "1100", "150");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 + 2 — slices add up to the company, and each balances
    // =========================================================================

    @Test
    void incomeStatement_branchesPlusCompanyLevel_equalCompany_lineForLine() {
        IncomeStatementDto company = reporting.incomeStatement(this.company.getId(), FROM, TO, null, null);
        IncomeStatementDto a  = reporting.incomeStatement(this.company.getId(), FROM, TO, null, null, branchA.getUid(), false);
        IncomeStatementDto b  = reporting.incomeStatement(this.company.getId(), FROM, TO, null, null, branchB.getUid(), false);
        IncomeStatementDto c  = reporting.incomeStatement(this.company.getId(), FROM, TO, null, null, branchC.getUid(), false);
        IncomeStatementDto un = reporting.incomeStatement(this.company.getId(), FROM, TO, null, null, null, true);

        // The figures this data set must produce
        assertThat(a.netProfit().current()).isEqualByComparingTo("600");   // 1000 − 400
        assertThat(b.netProfit().current()).isEqualByComparingTo("600");
        assertThat(c.netProfit().current()).isEqualByComparingTo("0");
        assertThat(un.netProfit().current()).isEqualByComparingTo("-150");
        assertThat(company.netProfit().current()).isEqualByComparingTo("1050");

        assertThat(sum(a.netProfit(), b.netProfit(), c.netProfit(), un.netProfit()))
                .isEqualByComparingTo(company.netProfit().current());
        assertThat(sum(a.grossProfit(), b.grossProfit(), c.grossProfit(), un.grossProfit()))
                .isEqualByComparingTo(company.grossProfit().current());

        // Line for line: every account's company figure is the sum of the slices'
        Map<String, BigDecimal> companyLines = lines(company.sections());
        Map<String, BigDecimal> sliceTotals = new HashMap<>();
        for (IncomeStatementDto s : List.of(a, b, c, un)) {
            lines(s.sections()).forEach((k, v) -> sliceTotals.merge(k, v, BigDecimal::add));
        }
        assertThat(sliceTotals.keySet()).isEqualTo(companyLines.keySet());
        companyLines.forEach((k, v) -> assertThat(sliceTotals.get(k)).as("line " + k).isEqualByComparingTo(v));

        for (IncomeStatementDto s : List.of(company, a, b, c, un)) {
            assertThat(s.reconciliation().ties()).as("P&L self-check for " + s.header().branchLabel()).isTrue();
        }
    }

    @Test
    void balanceSheet_eachSliceBalances_andSlicesSumToCompany() {
        BalanceSheetDto company = reporting.balanceSheet(this.company.getId(), TO, null);
        BalanceSheetDto a  = reporting.balanceSheet(this.company.getId(), TO, null, branchA.getUid(), false);
        BalanceSheetDto b  = reporting.balanceSheet(this.company.getId(), TO, null, branchB.getUid(), false);
        BalanceSheetDto un = reporting.balanceSheet(this.company.getId(), TO, null, null, true);

        for (BalanceSheetDto s : List.of(company, a, b, un)) {
            assertThat(s.reconciliation().ties())
                    .as("ASSET == LIABILITY + EQUITY for " + s.header().branchLabel())
                    .isTrue();
            // The posting engine stamps one branch per entry, so no slice needs an inter-branch line
            assertThat(allLines(s.sections()))
                    .noneMatch(l -> BalanceSheetBuilder.INTER_BRANCH_LINE.equals(l.accountName()));
        }
        assertThat(sum(a.totalAssets(), b.totalAssets(), un.totalAssets()))
                .isEqualByComparingTo(company.totalAssets().current());
        assertThat(sum(a.totalLiabilities(), b.totalLiabilities(), un.totalLiabilities()))
                .isEqualByComparingTo(company.totalLiabilities().current());
        assertThat(sum(a.totalEquity(), b.totalEquity(), un.totalEquity()))
                .isEqualByComparingTo(company.totalEquity().current());

        // Branch A: cash 1000, inventory −400 (it sold stock it never received — the transfer caveat)
        Map<String, BigDecimal> aLines = lines(a.sections());
        assertThat(aLines.get("1000")).isEqualByComparingTo("1000");
        assertThat(aLines.get("1300")).isEqualByComparingTo("-400");
        assertThat(a.totalEquity().current()).isEqualByComparingTo("600"); // its own earnings
    }

    @Test
    void cashFlow_eachSliceTies_andNetChangesSumToCompany() {
        CashFlowStatementDto company = reporting.cashFlow(this.company.getId(), FROM, TO, null, null);
        CashFlowStatementDto a  = reporting.cashFlow(this.company.getId(), FROM, TO, null, null, branchA.getUid(), false);
        CashFlowStatementDto b  = reporting.cashFlow(this.company.getId(), FROM, TO, null, null, branchB.getUid(), false);
        CashFlowStatementDto un = reporting.cashFlow(this.company.getId(), FROM, TO, null, null, null, true);

        for (CashFlowStatementDto s : List.of(company, a, b, un)) {
            assertThat(s.reconciliation().ties()).as("CF tie-out for " + s.header().branchLabel()).isTrue();
        }
        assertThat(sum(a.netChangeInCash(), b.netChangeInCash(), un.netChangeInCash()))
                .isEqualByComparingTo(company.netChangeInCash().current());
    }

    // =========================================================================
    // Bar 3 — header names the slice
    // =========================================================================

    @Test
    void header_namesTheBranch_orAllBranches_orCompanyLevel() {
        assertThat(reporting.balanceSheet(company.getId(), TO, null).header().branchLabel())
                .isEqualTo("All branches");
        BalanceSheetDto a = reporting.balanceSheet(company.getId(), TO, null, branchA.getUid(), false);
        assertThat(a.header().branchLabel()).isEqualTo("Arusha");
        assertThat(a.header().branchUid()).isEqualTo(branchA.getUid());
        assertThat(reporting.incomeStatement(company.getId(), FROM, TO, null, null, null, true)
                .header().branchLabel()).isEqualTo(StatementScope.COMPANY_LEVEL);
    }

    // =========================================================================
    // Bar 4 — tenancy + branch assignment, as a NON-root caller
    // =========================================================================

    @Test
    void nonRootCaller_unassignedBranch_isRefused_onEveryStatement() {
        AppUser u = new AppUser("brst_clerk", passwordEncoder.encode("BrstClerk1!"), "BRST Clerk");
        u.setOrganisationId(org.getId());
        AppUser clerk = users.save(u);
        testData.seedMembership(clerk.getUid(), company.getUid());
        userBranches.save(new UserBranch(clerk.getId(), branchA, clerk.getId()));
        RequestContext.set(new RequestContext.Principal(
                clerk.getId(), "brst_clerk", false, company.getId(), branchA.getId(), null, org.getId()));

        Long cid = company.getId();
        List<Function<String, Object>> reads = List.of(
                uid -> reporting.incomeStatement(cid, FROM, TO, null, null, uid, false),
                uid -> reporting.balanceSheet(cid, TO, null, uid, false),
                uid -> reporting.cashFlow(cid, FROM, TO, null, null, uid, false),
                uid -> reporting.financialRatios(cid, FROM, TO, uid));

        for (Function<String, Object> read : reads) {
            assertThatCode(call(read, branchA.getUid())).doesNotThrowAnyException();
            assertThatCode(call(read, null)).doesNotThrowAnyException();
            assertThatThrownBy(call(read, branchC.getUid()))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
            assertThatThrownBy(call(read, foreignBranch.getUid()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Branch not found.");
        }
        // The company-level slice is part of the company-wide read the clerk already holds
        assertThatCode(() -> reporting.balanceSheet(cid, TO, null, null, true)).doesNotThrowAnyException();
    }

    @Test
    void branchAndCompanyLevelTogether_isABadRequest() {
        assertThatThrownBy(() -> reporting.balanceSheet(company.getId(), TO, null, branchA.getUid(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------

    private void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "brst_root", true, company.getId(), branchA.getId(), null));
    }

    private void post(Long branchId, JournalSourceType source, LocalDate date,
                      String debitCode, String creditCode, String amount) {
        BigDecimal amt = new BigDecimal(amount);
        glPosting.post(new JournalEntryDraft(company.getId(), branchId, date,
                "BRST IT " + source, source, null, null, rootId,
                List.of(new LineDraft(acct.get(debitCode), amt, null, TZS, null),
                        new LineDraft(acct.get(creditCode), null, amt, TZS, null))));
    }

    private static ThrowingCallable call(Function<String, Object> read, String uid) {
        return () -> read.apply(uid);
    }

    private static BigDecimal sum(AmountPairDto... pairs) {
        BigDecimal total = BigDecimal.ZERO;
        for (AmountPairDto p : pairs) total = total.add(p.current());
        return total;
    }

    /** accountCode → current amount, account lines only. */
    private static Map<String, BigDecimal> lines(List<StatementSectionDto> sections) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (StatementLineDto l : allLines(sections)) {
            if (l.accountCode() != null) out.merge(l.accountCode(), l.amounts().current(), BigDecimal::add);
        }
        return out;
    }

    private static List<StatementLineDto> allLines(List<StatementSectionDto> sections) {
        return sections.stream().flatMap(s -> s.lines().stream()).toList();
    }
}
