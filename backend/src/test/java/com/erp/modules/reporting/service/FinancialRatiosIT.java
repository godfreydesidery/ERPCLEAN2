package com.erp.modules.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.reporting.domain.dto.AmountPairDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.FinancialRatioDto;
import com.erp.modules.reporting.domain.dto.FinancialRatiosDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.domain.dto.RatioInputDto;
import com.erp.modules.reporting.domain.dto.StatementSectionDto;
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
 * Financial Ratios on real Postgres: every input equals the figure the Income Statement / Balance
 * Sheet shows for the same dates, the results follow from the inputs, and a zero denominator gives
 * no value plus a reason — never zero.
 */
class FinancialRatiosIT extends PostgresIntegrationTest {

    private static final String TZS = "TZS";
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO   = LocalDate.of(2026, 6, 29); // 90 days

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
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private final Map<String, Long> acct = new HashMap<>();

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("RAT IT Org"));
        company = companies.save(new Company(org, "RATI", "Ratios IT Co"));
        branch  = branches.save(new Branch(company, "RATI1", "Ratios Branch"));

        AppUser root = new AppUser("rat_root", passwordEncoder.encode("RatRoot12!"), "RAT Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        RequestContext.set(new RequestContext.Principal(
                rootId, "rat_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        for (String code : List.of("1000", "1100", "1200", "1300", "2100", "3000", "4100", "5100", "5200")) {
            acct.put(code, accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                    .orElseThrow(() -> new AssertionError("Account " + code + " not seeded")).getId());
        }

        // Opening position (before FROM): capital 5000 in the bank, stock 1000 bought on credit
        post(JournalSourceType.OPENING_BALANCE, LocalDate.of(2026, 1, 2), "1100", "3000", "5000");
        post(JournalSourceType.STOCK_RECEIPT,   LocalDate.of(2026, 2, 1), "1300", "2100", "1000");
        // In the period: credit sales 3000, their cost 1200, more stock 800 on credit, rent 600
        post(JournalSourceType.SALES,           LocalDate.of(2026, 4, 10), "1200", "4100", "3000");
        post(JournalSourceType.COGS,            LocalDate.of(2026, 4, 10), "5100", "1300", "1200");
        post(JournalSourceType.STOCK_RECEIPT,   LocalDate.of(2026, 5, 1),  "1300", "2100", "800");
        post(JournalSourceType.MANUAL,          LocalDate.of(2026, 5, 15), "5200", "1100", "600");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void everyInput_equalsTheStatementFigure_andResultsFollow() {
        FinancialRatiosDto dto = reporting.financialRatios(company.getId(), FROM, TO, null);
        IncomeStatementDto pl  = reporting.incomeStatement(company.getId(), FROM, TO, null, null);
        BalanceSheetDto    bs  = reporting.balanceSheet(company.getId(), TO, FROM.minusDays(1));

        assertThat(dto.periodDays()).isEqualTo(90);
        assertThat(dto.incomeStatementTies()).isTrue();
        assertThat(dto.balanceSheetTies()).isTrue();

        BigDecimal revenue     = subtotal(pl.sections(), StatementSection.REVENUE).current();
        BigDecimal costOfSales = subtotal(pl.sections(), StatementSection.COST_OF_SALES).current();
        AmountPairDto currentAssets = subtotal(bs.sections(), StatementSection.CURRENT_ASSETS);
        AmountPairDto currentLiab   = subtotal(bs.sections(), StatementSection.CURRENT_LIABILITIES);

        FinancialRatioDto current = ratio(dto, "CURRENT_RATIO");
        assertThat(input(current, "Current assets")).isEqualByComparingTo(currentAssets.current());
        assertThat(input(current, "Current liabilities")).isEqualByComparingTo(currentLiab.current());
        // CA = bank 4400 + AR 3000 + stock 600 = 8000 ; CL = AP 1800
        assertThat(current.value()).isEqualByComparingTo("4.44");

        FinancialRatioDto quick = ratio(dto, "QUICK_RATIO");
        assertThat(input(quick, "Inventory")).isEqualByComparingTo("600");
        assertThat(quick.value()).isEqualByComparingTo("4.11"); // (8000 − 600) / 1800

        FinancialRatioDto gross = ratio(dto, "GROSS_MARGIN");
        assertThat(input(gross, "Gross profit")).isEqualByComparingTo(pl.grossProfit().current());
        assertThat(input(gross, "Revenue")).isEqualByComparingTo(revenue);
        assertThat(gross.value()).isEqualByComparingTo("60.00");

        FinancialRatioDto net = ratio(dto, "NET_MARGIN");
        assertThat(input(net, "Net profit")).isEqualByComparingTo(pl.netProfit().current());
        assertThat(net.value()).isEqualByComparingTo("40.00"); // 1200 / 3000

        FinancialRatioDto de = ratio(dto, "DEBT_TO_EQUITY");
        assertThat(input(de, "Total liabilities")).isEqualByComparingTo(bs.totalLiabilities().current());
        assertThat(input(de, "Total equity")).isEqualByComparingTo(bs.totalEquity().current());

        FinancialRatioDto roe = ratio(dto, "RETURN_ON_EQUITY");
        assertThat(input(roe, "Opening equity")).isEqualByComparingTo(bs.totalEquity().comparative());
        assertThat(input(roe, "Closing equity")).isEqualByComparingTo(bs.totalEquity().current());
        assertThat(roe.value()).isEqualByComparingTo("21.43"); // 1200 / ((5000 + 6200) / 2) × 100

        FinancialRatioDto turnover = ratio(dto, "INVENTORY_TURNOVER");
        assertThat(input(turnover, "Cost of sales")).isEqualByComparingTo(costOfSales);
        assertThat(input(turnover, "Opening inventory")).isEqualByComparingTo("1000");
        assertThat(input(turnover, "Closing inventory")).isEqualByComparingTo("600");
        assertThat(turnover.value()).isEqualByComparingTo("1.50"); // 1200 / 800

        assertThat(ratio(dto, "INVENTORY_DAYS").value()).isEqualByComparingTo("60.00"); // 800/1200×90
        assertThat(ratio(dto, "DEBTOR_DAYS").value()).isEqualByComparingTo("45.00");    // 1500/3000×90
        assertThat(ratio(dto, "CREDITOR_DAYS").value()).isEqualByComparingTo("105.00"); // 1400/1200×90
    }

    @Test
    void zeroDenominator_givesNoValueAndAReason_neverZero() {
        // A window with no trading at all: no revenue, no cost of sales
        FinancialRatiosDto dto = reporting.financialRatios(
                company.getId(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null);

        for (String key : List.of("GROSS_MARGIN", "NET_MARGIN", "DEBTOR_DAYS", "INVENTORY_DAYS", "CREDITOR_DAYS")) {
            FinancialRatioDto r = ratio(dto, key);
            assertThat(r.value()).as(key).isNull();
            assertThat(r.unavailableReason()).as(key).isNotBlank();
        }
        // Balance-sheet ratios still compute from the closing position
        assertThat(ratio(dto, "CURRENT_RATIO").value()).isNotNull();
        assertThat(ratio(dto, "CURRENT_RATIO").unavailableReason()).isNull();
    }

    // -------------------------------------------------------------------------

    private static FinancialRatioDto ratio(FinancialRatiosDto dto, String key) {
        return dto.ratios().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    private static BigDecimal input(FinancialRatioDto r, String label) {
        return r.inputs().stream().filter(i -> i.label().equals(label))
                .map(RatioInputDto::amount).findFirst().orElseThrow();
    }

    private static AmountPairDto subtotal(List<StatementSectionDto> sections, StatementSection key) {
        return sections.stream().filter(s -> s.sectionKey() == key)
                .map(StatementSectionDto::subtotal).findFirst().orElseThrow();
    }

    private void post(JournalSourceType source, LocalDate date,
                      String debitCode, String creditCode, String amount) {
        BigDecimal amt = new BigDecimal(amount);
        glPosting.post(new JournalEntryDraft(company.getId(), branch.getId(), date,
                "RAT IT " + source, source, null, null, rootId,
                List.of(new LineDraft(acct.get(debitCode), amt, null, TZS, null),
                        new LineDraft(acct.get(creditCode), null, amt, TZS, null))));
    }
}
