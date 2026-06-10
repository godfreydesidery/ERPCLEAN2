package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.service.ReportingService;
import com.erp.platform.common.api.ConflictException;
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
 * Integration tests for {@link YearEndCloseService} (ADR-0019, FR-CLOSE-01..08, BR-CLOSE-01..14).
 *
 * <p>Every test runs against a real Postgres schema (Testcontainers, singleton container).
 * No mocked DB — closes exercise the real Flyway V16 schema, GLPostingService engine,
 * and FiscalCalendarService period-gate ordering (D-5).
 *
 * <p>Test bars covered:
 * <ol>
 *   <li>Close zeroes P&amp;L and rolls net profit to 3900 (BR-CLOSE-01/02/03).
 *   <li>Reopen restores P&amp;L balances; year is re-closeable (BR-CLOSE-07/08, FR-CLOSE-04/05).
 *   <li>Net loss case — closing entry DEBITs 3900, 3900 decreases (BR-CLOSE-02).
 *   <li>Guards — prior year open, double close, reopen non-latest (BR-CLOSE-04/05/10).
 *   <li>Close&#8596;Reporting consistency — Balance Sheet ties after close (BR-CLOSE-12).
 * </ol>
 */
class YearEndCloseServiceIT extends PostgresIntegrationTest {

    @Autowired private YearEndCloseService     yearEndCloseService;
    @Autowired private GLPostingService        glPostingService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private ReportingService        reportingService;

    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private JournalEntryRepository   entryRepo;
    @Autowired private JournalLineRepository    lineRepo;

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;

    private Long cashAccountId;   // 1000 Cash (ASSET)
    private Long salesAccountId;  // 4100 Sales Revenue (INCOME)
    private Long expenseAccountId; // 5200 Operating Expense (EXPENSE)
    private Long retainedEarningsId; // 3900 Retained Earnings (EQUITY)

    private static final String TZS = "TZS";

    // seedCurrentYear seeds FY2026 (calendar year 2026, January start)
    private static final LocalDate FY2026_END    = LocalDate.of(2026, 12, 31);
    private static final LocalDate FY2026_START  = LocalDate.of(2026, 1, 1);

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("YEC IT Org"));
        company = companies.save(new Company(org, "YECIT", "Year-End Close IT Co"));
        branch  = branches.save(new Branch(company, "YECB1", "YEC IT Branch"));

        AppUser root = new AppUser("yec_root", passwordEncoder.encode("YecRoot1!"), "YEC Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "yec_root", true, company.getId(), branch.getId(), null));

        // Seed GL foundations — mirrors GLPostingServiceIT.setUp exactly
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId()); // seeds FY2026 + 12 periods
        glConfigService.seedDefaults(company.getId());          // seeds RETAINED_EARNINGS → 3900

        // Resolve account IDs
        cashAccountId = requireAccount("1000").getId();
        salesAccountId = requireAccount("4100").getId();
        // 5200 Operating Expense — seeded by the default CoA
        expenseAccountId = requireAccount("5200").getId();
        retainedEarningsId = requireAccount("3900").getId();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 — close zeroes P&L + rolls net profit to 3900
    // =========================================================================

    @Test
    void closeFiscalYear_zeroesPlAccounts_rollsNetProfitToRetainedEarnings() {
        // Arrange: post P&L activity in FY2026
        // Journal A: DR 1000 Cash 1000 / CR 4100 Sales 1000 (revenue)
        post2026(cashAccountId, salesAccountId, new BigDecimal("1000"));
        // Journal B: DR 5200 Expense 300 / CR 1000 Cash 300
        post2026(expenseAccountId, cashAccountId, new BigDecimal("300"));

        // Capture pre-close P&L balances
        BigDecimal salesPreClose   = netBalance(salesAccountId);
        BigDecimal expensePreClose = netBalance(expenseAccountId);
        // Sales = net-credit = CR 1000, so netDebit = −1000  → balance = −1000
        // Expense = net-debit = DR 300, so netDebit = +300 → balance = +300
        assertThat(salesPreClose).as("Sales pre-close net-debit").isEqualByComparingTo("-1000");
        assertThat(expensePreClose).as("Expense pre-close net-debit").isEqualByComparingTo("300");

        BigDecimal re3900PreClose = netBalance(retainedEarningsId);

        // Act
        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId()).get(0);
        FiscalYearDto closed = yearEndCloseService.closeFiscalYear(fy2026.uid());

        // Assert — FiscalYearDto state
        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.closedBy()).isEqualTo(rootId);
        assertThat(closed.closingJournalUid()).isNotNull();

        // All periods in the year must be CLOSED (BR-CLOSE-06)
        List<FiscalPeriodDto> periods = fiscalCalendarService.listPeriodsForYear(fy2026.uid());
        assertThat(periods).isNotEmpty()
                .allMatch(p -> p.status() == PeriodStatus.CLOSED,
                        "every period must be CLOSED after year-end close");

        // P&L accounts net to zero (BR-CLOSE-01)
        BigDecimal salesAfter   = netBalance(salesAccountId);
        BigDecimal expenseAfter = netBalance(expenseAccountId);
        assertThat(salesAfter).as("Sales net-balance after close must be zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(expenseAfter).as("Expense net-balance after close must be zero")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // 3900 Retained Earnings credit balance increased by exactly net profit = 700 (BR-CLOSE-02)
        // Net profit = INCOME(1000) − EXPENSE(300) = 700 → CR 3900 by 700
        // After: 3900 netDebit should decrease by 700 (i.e. more credit-heavy)
        BigDecimal re3900After = netBalance(retainedEarningsId);
        // netDebit = DR - CR; a CR of 700 decreases netDebit by 700
        BigDecimal expectedReAfter = re3900PreClose.subtract(new BigDecimal("700"));
        assertThat(re3900After).as("3900 netDebit after close = pre-close minus 700")
                .isEqualByComparingTo(expectedReAfter);

        // Closing journal exists with sourceType=YEAR_END_CLOSE, sourceRef=fy.uid (BR-CLOSE-03)
        var closingEntry = entryRepo.findByCompanyIdAndSourceTypeAndSourceRef(
                company.getId(), JournalSourceType.YEAR_END_CLOSE, fy2026.uid());
        assertThat(closingEntry).isPresent();

        // Closing journal is balanced (Σdebit == Σcredit, BR-CLOSE-03)
        var closingLines = lineRepo.findByEntryIdOrderByLineNo(closingEntry.get().getId());
        BigDecimal totalDebit  = closingLines.stream()
                .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = closingLines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).as("closing journal Σdebit must equal Σcredit")
                .isEqualByComparingTo(totalCredit);

        // The closing journal uid matches FiscalYearDto.closingJournalUid
        assertThat(closed.closingJournalUid()).isEqualTo(closingEntry.get().getUid());
    }

    // =========================================================================
    // Bar 2 — reopen restores P&L + re-closeable
    // =========================================================================

    @Test
    void reopenFiscalYear_restoresPlBalances_yearIsRecloseable() {
        // Setup: post activity, close FY2026
        post2026(cashAccountId, salesAccountId, new BigDecimal("1000"));
        post2026(expenseAccountId, cashAccountId, new BigDecimal("300"));

        BigDecimal salesPreClose   = netBalance(salesAccountId);
        BigDecimal expensePreClose = netBalance(expenseAccountId);
        BigDecimal re3900PreClose  = netBalance(retainedEarningsId);

        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId()).get(0);
        FiscalYearDto closed = yearEndCloseService.closeFiscalYear(fy2026.uid());
        String closingJournalUid = closed.closingJournalUid();

        // Act: reopen
        FiscalYearDto reopened = yearEndCloseService.reopenFiscalYear(fy2026.uid());

        // Assert — year back to OPEN, close stamps cleared (D-6 step 5)
        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(reopened.closedAt()).isNull();
        assertThat(reopened.closedBy()).isNull();
        assertThat(reopened.closingJournalUid()).isNull();

        // All periods back to OPEN (BR-CLOSE-06)
        List<FiscalPeriodDto> periods = fiscalCalendarService.listPeriodsForYear(fy2026.uid());
        assertThat(periods).allMatch(p -> p.status() == PeriodStatus.OPEN,
                "every period must be OPEN after reopen");

        // Reversal journal exists (reversalOfId == closing entry id, D-6)
        var closingEntry = entryRepo.findByUid(closingJournalUid).orElseThrow();
        assertThat(entryRepo.existsByReversalOfId(closingEntry.getId()))
                .as("a reversal of the closing journal must exist after reopen").isTrue();

        // P&L balances restored to exact pre-close values (BR-CLOSE-07)
        assertThat(netBalance(salesAccountId))
                .as("Sales balance restored to pre-close value")
                .isEqualByComparingTo(salesPreClose);
        assertThat(netBalance(expenseAccountId))
                .as("Expense balance restored to pre-close value")
                .isEqualByComparingTo(expensePreClose);
        assertThat(netBalance(retainedEarningsId))
                .as("3900 balance restored to pre-close value")
                .isEqualByComparingTo(re3900PreClose);

        // Re-closeable: closing a second time succeeds (FR-CLOSE-05)
        FiscalYearDto reclosed = yearEndCloseService.closeFiscalYear(fy2026.uid());
        assertThat(reclosed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(reclosed.closingJournalUid()).isNotNull()
                .as("re-close must produce a new closing journal uid")
                .isNotEqualTo(closingJournalUid); // new uid from fresh close
    }

    // =========================================================================
    // Bar 3 — net loss case (expenses > revenue → DR 3900, 3900 decreases)
    // =========================================================================

    @Test
    void closeFiscalYear_netLoss_debits3900() {
        // Revenue 200, Expense 500 → net loss 300 → DR 3900 by 300
        post2026(cashAccountId, salesAccountId, new BigDecimal("200"));  // CR Sales 200
        post2026(expenseAccountId, cashAccountId, new BigDecimal("500")); // DR Expense 500

        BigDecimal re3900PreClose = netBalance(retainedEarningsId);

        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId()).get(0);
        yearEndCloseService.closeFiscalYear(fy2026.uid());

        // P&L accounts zeroed (BR-CLOSE-01)
        assertThat(netBalance(salesAccountId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(netBalance(expenseAccountId)).isEqualByComparingTo(BigDecimal.ZERO);

        // 3900 net loss → DR 3900 by 300 → netDebit increases by 300 (BR-CLOSE-02)
        BigDecimal re3900After = netBalance(retainedEarningsId);
        BigDecimal expectedRe  = re3900PreClose.add(new BigDecimal("300"));
        assertThat(re3900After).as("3900 netDebit after loss close = pre-close plus 300")
                .isEqualByComparingTo(expectedRe);
    }

    // =========================================================================
    // Bar 4a — guard: prior year open blocks close (BR-CLOSE-04)
    // =========================================================================

    @Test
    void closeFiscalYear_priorYearOpen_rejected() {
        // Open a FY2025 (calendarYear 2025, startMonth 1) — leaves it OPEN
        FiscalYearDto fy2025 = fiscalCalendarService.openFiscalYear(
                new OpenFiscalYearRequest(company.getUid(), "FY2025", 1, 2025));
        // FY2026 is already seeded by setUp (seedCurrentYear)
        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId())
                .stream().filter(y -> y.yearCode().equals("FY2026")).findFirst().orElseThrow();

        // FY2025 is OPEN → closing FY2026 must be rejected (BR-CLOSE-04)
        assertThatThrownBy(() -> yearEndCloseService.closeFiscalYear(fy2026.uid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("prior fiscal year");
    }

    // =========================================================================
    // Bar 4b — guard: double-close rejected (BR-CLOSE-05)
    // =========================================================================

    @Test
    void closeFiscalYear_alreadyClosed_rejected() {
        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId()).get(0);
        yearEndCloseService.closeFiscalYear(fy2026.uid());

        assertThatThrownBy(() -> yearEndCloseService.closeFiscalYear(fy2026.uid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already CLOSED");
    }

    // =========================================================================
    // Bar 4c — guard: reopen non-latest rejected (BR-CLOSE-10)
    // =========================================================================

    @Test
    void reopenFiscalYear_notMostRecentlyClosed_rejected() {
        // Need FY2025 CLOSED before FY2026 can be closed (BR-CLOSE-04)
        // Step 1: open and close FY2025
        FiscalYearDto fy2025 = fiscalCalendarService.openFiscalYear(
                new OpenFiscalYearRequest(company.getUid(), "FY2025", 1, 2025));
        // Close FY2025 first (no prior year → guard satisfied vacuously)
        yearEndCloseService.closeFiscalYear(fy2025.uid());

        // Step 2: FY2026 is already seeded; now close it (FY2025 is CLOSED — guard passes)
        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId())
                .stream().filter(y -> y.yearCode().equals("FY2026")).findFirst().orElseThrow();
        yearEndCloseService.closeFiscalYear(fy2026.uid());

        // Now FY2026 is the latest-closed; reopening FY2025 must be rejected (BR-CLOSE-10)
        assertThatThrownBy(() -> yearEndCloseService.reopenFiscalYear(fy2025.uid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("most-recently-closed");

        // But reopening FY2026 (the latest closed) succeeds
        FiscalYearDto reopened2026 = yearEndCloseService.reopenFiscalYear(fy2026.uid());
        assertThat(reopened2026.status()).isEqualTo(PeriodStatus.OPEN);
    }

    // =========================================================================
    // Bar 5 — close ↔ Reporting consistency (BR-CLOSE-12)
    // =========================================================================

    @Test
    void closeFiscalYear_balanceSheetStillTies_afterClose() {
        // Post P&L activity in FY2026
        post2026(cashAccountId, salesAccountId, new BigDecimal("1000"));
        post2026(expenseAccountId, cashAccountId, new BigDecimal("300"));

        // Close FY2026
        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId()).get(0);
        yearEndCloseService.closeFiscalYear(fy2026.uid());

        // Balance Sheet as-at a date AFTER FY2026 end (D-12) must still balance
        LocalDate asAt = FY2026_END.plusDays(1);
        BalanceSheetDto bs = reportingService.balanceSheet(company.getId(), asAt, null);

        assertThat(bs.reconciliation().ties())
                .as("Balance Sheet must balance after year-end close (BR-CLOSE-12 / D-12)")
                .isTrue();

        // Income Statement for the NEXT (open) year window reads zero revenue/expense
        // (the closed year's P&L was zeroed; if we run IS for a period entirely after FY2026
        //  there should be no P&L lines from the closed year's original activity)
        LocalDate nextYearStart = FY2026_END.plusDays(1);
        LocalDate nextYearEnd   = nextYearStart.plusMonths(1);
        IncomeStatementDto is = reportingService.incomeStatement(
                company.getId(), nextYearStart, nextYearEnd, null, null);

        // The IS for the next year should show zero net profit (no activity in that window)
        assertThat(is.netProfit().current()).as("IS for post-close period must show zero net profit")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 6 — close still zeroes a P&L account that was DEACTIVATED with a live balance
    //         (adversarial-review #14: BR-GL-07 permits deactivating an account that has
    //          postings, so YEAR_END_CLOSE must still be able to clear it — BR-GL-04 exception)
    // =========================================================================

    @Test
    void closeFiscalYear_zeroesInactivePlAccount_afterDeactivation() {
        // FY2026 P&L activity: CR 4100 Sales 1000, DR 5200 Expense 300 → net profit 700
        post2026(cashAccountId, salesAccountId, new BigDecimal("1000"));
        post2026(expenseAccountId, cashAccountId, new BigDecimal("300"));

        // Deactivate Sales (4100) while it still carries a live -1000 net-debit balance (allowed by BR-GL-07).
        chartOfAccountService.deactivate(requireAccount("4100").getUid());
        assertThat(requireAccount("4100").isActive()).as("4100 is now inactive").isFalse();

        // Pre-fix this threw BR-GL-04 ("inactive; cannot post to it") and the whole close failed.
        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId()).get(0);
        FiscalYearDto closed = yearEndCloseService.closeFiscalYear(fy2026.uid());

        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(netBalance(salesAccountId)).as("inactive Sales account is still zeroed by the close")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(netBalance(expenseAccountId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Posts a simple balanced 2-line journal in FY2026 (dated FY2026_START + 10 days,
     * always within period 1 which is OPEN at setup time).
     * DR debitAccountId / CR creditAccountId, same amount.
     */
    private void post2026(Long debitAccountId, Long creditAccountId, BigDecimal amount) {
        LocalDate postingDate = FY2026_START.plusDays(10);
        glPostingService.post(new JournalEntryDraft(
                company.getId(), branch.getId(), postingDate,
                "YEC IT test entry", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new LineDraft(debitAccountId,  amount, null,   TZS, null),
                        new LineDraft(creditAccountId, null,   amount, TZS, null)
                )
        ));
    }

    /**
     * Returns the net-debit balance (SUM(debit) - SUM(credit)) for an account.
     * Returns ZERO when no lines exist.
     */
    private BigDecimal netBalance(Long accountId) {
        BigDecimal bal = lineRepo.accountBalance(company.getId(), accountId);
        return bal != null ? bal : BigDecimal.ZERO;
    }

    private ChartOfAccount requireAccount(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .orElseThrow(() -> new AssertionError("Account " + code + " not seeded"));
    }
}
