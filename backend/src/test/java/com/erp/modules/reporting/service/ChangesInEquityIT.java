package com.erp.modules.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.gl.service.YearEndCloseService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.ChangesInEquityDto;
import com.erp.modules.reporting.domain.dto.EquityMovementRowDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.domain.dto.StatementLineDto;
import com.erp.modules.reporting.domain.enums.StatementSection;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Statement of Changes in Equity on real Postgres.
 *
 * <p>Bars:
 * <ol>
 *   <li>Closing equity == the Balance Sheet's equity at the period end, component by component and
 *       in total; every component rolls forward (opening + movements == closing).
 *   <li>Movements are classified: opening balances, capital in, drawings, profit — and profit for the
 *       period equals the Income Statement's net profit.
 *   <li>A year-end close is a TRANSFER (earnings → 3900) that nets to zero — and the Income Statement
 *       for the closed year still reports the year's profit, not zero.
 *   <li>A period that crosses a fiscal-year start rolls current-year earnings into prior years and
 *       still ties.
 * </ol>
 */
class ChangesInEquityIT extends PostgresIntegrationTest {

    private static final String TZS = "TZS";
    private static final LocalDate FY_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate FY_END   = LocalDate.of(2026, 12, 31);

    @Autowired private ReportingService       reporting;
    @Autowired private GLPostingService       glPosting;
    @Autowired private YearEndCloseService    yearEndClose;
    @Autowired private ChartOfAccountService  chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private final Map<String, Long> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("SOCE IT Org"));
        company = companies.save(new Company(org, "SOCE", "SoCE IT Co"));
        branch  = branches.save(new Branch(company, "SOCE1", "SoCE Branch"));

        AppUser root = new AppUser("soce_root", passwordEncoder.encode("SoceRoot1!"), "SoCE Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        RequestContext.set(new RequestContext.Principal(
                rootId, "soce_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId()); // FY2026, January start
        glConfigService.seedDefaults(company.getId());
        for (String code : List.of("1000", "1100", "3000", "3900", "4100", "5200")) {
            acct.put(code, accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                    .orElseThrow(() -> new AssertionError("Account " + code + " not seeded")).getId());
        }

        post(JournalSourceType.OPENING_BALANCE, LocalDate.of(2026, 1, 2), "1100", "3000", "2000"); // opening capital
        post(JournalSourceType.MANUAL,          LocalDate.of(2026, 2, 1), "1000", "3000", "500");  // capital injected
        post(JournalSourceType.MANUAL,          LocalDate.of(2026, 3, 1), "3000", "1000", "100");  // drawings
        post(JournalSourceType.SALES,           LocalDate.of(2026, 4, 1), "1000", "4100", "1000");
        post(JournalSourceType.MANUAL,          LocalDate.of(2026, 5, 1), "5200", "1000", "300");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void closingEquity_equalsBalanceSheetEquity_andMovementsAreClassified() {
        LocalDate to = LocalDate.of(2026, 9, 30);
        ChangesInEquityDto soce = reporting.changesInEquity(company.getId(), FY_START, to);
        BalanceSheetDto bs = reporting.balanceSheet(company.getId(), to, FY_START.minusDays(1));
        IncomeStatementDto pl = reporting.incomeStatement(company.getId(), FY_START, to, null, null);

        assertTiesToBalanceSheet(soce, bs);
        assertThat(soce.profitForPeriod()).isEqualByComparingTo(pl.netProfit().current());
        assertThat(soce.profitForPeriod()).isEqualByComparingTo("700");

        EquityMovementRowDto capital = row(soce, "3000");
        assertThat(capital.opening()).isEqualByComparingTo("0");
        assertThat(capital.openingBalancesPosted()).isEqualByComparingTo("2000");
        assertThat(capital.capitalIntroduced()).isEqualByComparingTo("500");
        assertThat(capital.drawingsAndDividends()).isEqualByComparingTo("-100");
        assertThat(capital.closing()).isEqualByComparingTo("2400");

        EquityMovementRowDto currentYear = fold(soce, BalanceSheetBuilder.CURRENT_YEAR_LINE);
        assertThat(currentYear.profitForPeriod()).isEqualByComparingTo("700");
        assertThat(currentYear.closing()).isEqualByComparingTo("700");

        assertThat(soce.totals().closing()).isEqualByComparingTo("3100");
        assertThat(soce.transfersCheck().ties()).isTrue();
    }

    @Test
    void yearEndClose_isATransferIntoRetainedEarnings_andTheYearStillShowsItsProfit() {
        FiscalYearDto fy2026 = fiscalCalendarService.listFiscalYears(company.getId()).get(0);
        yearEndClose.closeFiscalYear(fy2026.uid());

        ChangesInEquityDto soce = reporting.changesInEquity(company.getId(), FY_START, FY_END);
        BalanceSheetDto bs = reporting.balanceSheet(company.getId(), FY_END, FY_START.minusDays(1));
        IncomeStatementDto pl = reporting.incomeStatement(company.getId(), FY_START, FY_END, null, null);

        assertTiesToBalanceSheet(soce, bs);

        // The closing journal is not income or expense: the closed year still reports its profit
        assertThat(pl.netProfit().current()).isEqualByComparingTo("700");
        assertThat(pl.reconciliation().ties()).isTrue();
        assertThat(soce.profitForPeriod()).isEqualByComparingTo("700");

        EquityMovementRowDto retained    = row(soce, "3900");
        EquityMovementRowDto currentYear = fold(soce, BalanceSheetBuilder.CURRENT_YEAR_LINE);
        assertThat(retained.transfers()).isEqualByComparingTo("700");
        assertThat(retained.closing()).isEqualByComparingTo("700");
        assertThat(currentYear.transfers()).isEqualByComparingTo("-700");
        assertThat(currentYear.closing()).isEqualByComparingTo("0");
        assertThat(soce.totals().transfers()).isEqualByComparingTo("0");
        assertThat(soce.transfersCheck().ties()).isTrue();
    }

    @Test
    void periodCrossingAFiscalYearStart_rollsEarningsIntoPriorYears_andStillTies() {
        fiscalCalendarService.openFiscalYear(new OpenFiscalYearRequest(company.getUid(), "FY2027", 1, 2027));
        post(JournalSourceType.SALES, LocalDate.of(2027, 2, 1), "1000", "4100", "250");

        LocalDate from = LocalDate.of(2026, 7, 1);
        LocalDate to   = LocalDate.of(2027, 6, 30);
        ChangesInEquityDto soce = reporting.changesInEquity(company.getId(), from, to);
        BalanceSheetDto bs = reporting.balanceSheet(company.getId(), to, from.minusDays(1));

        assertTiesToBalanceSheet(soce, bs);

        // Opening (30 Jun 2026): FY2026 earnings to date 700 are "current year".
        // Closing (30 Jun 2027): those 700 are "prior years"; current year is FY2027's 250.
        EquityMovementRowDto prior       = fold(soce, BalanceSheetBuilder.PRIOR_YEARS_LINE);
        EquityMovementRowDto currentYear = fold(soce, BalanceSheetBuilder.CURRENT_YEAR_LINE);
        assertThat(currentYear.opening()).isEqualByComparingTo("700");
        assertThat(prior.transfers()).isEqualByComparingTo("700");
        assertThat(prior.closing()).isEqualByComparingTo("700");
        assertThat(currentYear.profitForPeriod()).isEqualByComparingTo("250");
        assertThat(currentYear.closing()).isEqualByComparingTo("250");
        assertThat(soce.transfersCheck().ties()).isTrue();
    }

    // -------------------------------------------------------------------------

    /** SoCE closing == BS equity (each line and in total); every row rolls forward; bar ties. */
    private static void assertTiesToBalanceSheet(ChangesInEquityDto soce, BalanceSheetDto bs) {
        assertThat(bs.reconciliation().ties()).as("the Balance Sheet itself balances").isTrue();
        assertThat(soce.reconciliation().ties()).as(soce.reconciliation().label()).isTrue();
        assertThat(soce.totals().closing()).isEqualByComparingTo(bs.totalEquity().current());
        assertThat(soce.balanceSheetClosingEquity()).isEqualByComparingTo(bs.totalEquity().current());
        assertThat(soce.totals().opening()).isEqualByComparingTo(bs.totalEquity().comparative());

        List<StatementLineDto> bsEquity = bs.sections().stream()
                .filter(s -> s.sectionKey() == StatementSection.EQUITY)
                .findFirst().orElseThrow().lines();
        assertThat(soce.rows()).hasSameSizeAs(bsEquity);
        for (int i = 0; i < bsEquity.size(); i++) {
            EquityMovementRowDto r = soce.rows().get(i);
            assertThat(r.component()).isEqualTo(bsEquity.get(i).accountName());
            assertThat(r.closing()).as("closing " + r.component())
                    .isEqualByComparingTo(bsEquity.get(i).amounts().current());
            assertThat(r.ties()).as("roll-forward " + r.component()).isTrue();
            assertThat(r.rolledForward()).isEqualByComparingTo(r.closing());
        }
    }

    private static EquityMovementRowDto row(ChangesInEquityDto soce, String code) {
        return soce.rows().stream().filter(r -> code.equals(r.accountCode())).findFirst().orElseThrow();
    }

    private static EquityMovementRowDto fold(ChangesInEquityDto soce, String name) {
        return soce.rows().stream().filter(r -> r.earningsFold() && name.equals(r.component()))
                .findFirst().orElseThrow();
    }

    private void post(JournalSourceType source, LocalDate date,
                      String debitCode, String creditCode, String amount) {
        BigDecimal amt = new BigDecimal(amount);
        glPosting.post(new JournalEntryDraft(company.getId(), branch.getId(), date,
                "SoCE IT " + source, source, null, null, rootId,
                List.of(new LineDraft(acct.get(debitCode), amt, null, TZS, null),
                        new LineDraft(acct.get(creditCode), null, amt, TZS, null))));
    }
}
