package com.erp.modules.gl.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.dto.TrialBalanceRangeDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
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

/** ACC-14: trial balance as at a date, for a range, per branch, with opening/movement/closing. */
class TrialBalanceRangeIT extends PostgresIntegrationTest {

    @Autowired private TrialBalanceQuery query;
    @Autowired private GLPostingService postingService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch dar;
    private Branch arusha;
    private LocalDate janEnd;
    private LocalDate febStart;
    private LocalDate febEnd;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("TB Range IT Org"));
        company = companies.save(new Company(org, "TBRIT", "TB Range IT Co"));
        dar     = branches.save(new Branch(company, "DAR", "Dar"));
        arusha  = branches.save(new Branch(company, "ARU", "Arusha"));
        AppUser root = new AppUser("tbr_root", passwordEncoder.encode("RootPass1!"), "TB Root");
        root.setRoot(true);
        root = users.save(inOrganisation(root, org.getId()));
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "tbr_root", true, company.getId(), dar.getId(), null));
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        LocalDate start = fiscalCalendarService.listPeriods(company.getId()).get(0).startDate();
        janEnd   = start.plusMonths(1).minusDays(1);
        febStart = start.plusMonths(1);
        febEnd   = start.plusMonths(2).minusDays(1);

        post(start.plusDays(9), dar, "100");      // January, Dar
        post(febStart.plusDays(2), dar, "50");     // February, Dar
        post(febStart.plusDays(3), arusha, "30");  // February, Arusha
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void asAt_endOfJanuary_closesOnlyJanuary() {
        TrialBalanceRangeDto tb = query.computeRange(company.getId(), null, janEnd, null);
        assertThat(row(tb, "5200").closingDebit()).isEqualByComparingTo("100");
        assertThat(row(tb, "3000").closingCredit()).isEqualByComparingTo("100");
        assertThat(tb.closingDebit()).isEqualByComparingTo(tb.closingCredit());
        assertThat(tb.periodLabel()).isEqualTo("As at " + janEnd);
    }

    @Test
    void februaryRange_hasJanuaryAsOpening() {
        TrialBalanceRangeDto tb = query.computeRange(company.getId(), febStart, febEnd, null);
        TrialBalanceRangeDto.Row rent = row(tb, "5200");
        assertThat(rent.openingDebit()).isEqualByComparingTo("100");
        assertThat(rent.movementDebit()).isEqualByComparingTo("80");
        assertThat(rent.closingDebit()).isEqualByComparingTo("180");
        TrialBalanceRangeDto.Row equity = row(tb, "3000");
        assertThat(equity.openingCredit()).isEqualByComparingTo("100");
        assertThat(equity.closingCredit()).isEqualByComparingTo("180");
        assertThat(tb.openingDebit()).isEqualByComparingTo(tb.openingCredit());
        assertThat(tb.movementDebit()).isEqualByComparingTo(tb.movementCredit());

        TrialBalanceDto printable = TrialBalanceQuery.closingAsTotals(tb);
        assertThat(printable.totalDebits()).isEqualByComparingTo("180");
    }

    @Test
    void branchFilter_limitsToThatBranch() {
        TrialBalanceRangeDto tb = query.computeRange(company.getId(), febStart, febEnd, arusha.getUid());
        TrialBalanceRangeDto.Row rent = row(tb, "5200");
        assertThat(rent.openingDebit()).isEqualByComparingTo("0");
        assertThat(rent.movementDebit()).isEqualByComparingTo("30");
        assertThat(tb.branchName()).isEqualTo("Arusha");

        assertThatThrownBy(() -> query.computeRange(company.getId(), null, febEnd, "NO-SUCH-BRANCH"))
                .isInstanceOf(NotFoundException.class);
    }

    // -------------------------------------------------------------------------

    private static TrialBalanceRangeDto.Row row(TrialBalanceRangeDto tb, String code) {
        return tb.rows().stream().filter(r -> r.accountCode().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + code));
    }

    private void post(LocalDate date, Branch branch, String amount) {
        postingService.post(new JournalEntryDraft(company.getId(), branch.getId(), date,
                "Rent " + date, JournalSourceType.MANUAL, null, null, null, List.of(
                        new JournalEntryDraft.LineDraft(account("5200"), new BigDecimal(amount),
                                null, "TZS", null),
                        new JournalEntryDraft.LineDraft(account("3000"), null,
                                new BigDecimal(amount), "TZS", null))));
    }

    private Long account(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId).orElseThrow();
    }
}
