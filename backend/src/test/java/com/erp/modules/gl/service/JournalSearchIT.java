package com.erp.modules.gl.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.JournalSearchCriteria;
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
import com.erp.platform.common.domain.Ulid;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

/** ACC-19: the journal list can be filtered and shows the source document's number. */
class JournalSearchIT extends PostgresIntegrationTest {

    @Autowired private JournalService journals;
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
    private Branch branch;
    private LocalDate jan;
    private LocalDate feb;
    private final String invoiceUid = Ulid.next();

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Journal Search IT Org"));
        company = companies.save(new Company(org, "JSIT", "Journal Search IT Co"));
        branch  = branches.save(new Branch(company, "JS1", "Journal Search IT Branch"));
        AppUser root = new AppUser("js_root", passwordEncoder.encode("RootPass1!"), "JS Root");
        root.setRoot(true);
        root = users.save(inOrganisation(root, org.getId()));
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "js_root", true, company.getId(), branch.getId(), null));
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        LocalDate start = fiscalCalendarService.listPeriods(company.getId()).get(0).startDate();
        jan = start.plusDays(4);
        feb = start.plusMonths(1).plusDays(4);

        post(jan, "Sale " + invoiceUid, JournalSourceType.SALES, invoiceUid, "1000", "4100");
        post(jan, "COGS — sale INV-0453", JournalSourceType.COGS, invoiceUid, "5100", "1300");
        post(feb, "Goods receipt GRN-0007", JournalSourceType.STOCK_RECEIPT, Ulid.next(), "1300", "2150");
        post(feb, "October rent", JournalSourceType.MANUAL, null, "5200", "3000");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void documentNumber_findsEveryJournalOfThatDocument_andLabelsTheSale() {
        List<JournalEntryDto> rows = search(new JournalSearchCriteria(null, null, null, null, "inv-0453"));
        assertThat(rows).extracting(JournalEntryDto::sourceType)
                .containsExactlyInAnyOrder(JournalSourceType.SALES, JournalSourceType.COGS);
        assertThat(rows).allSatisfy(r -> assertThat(r.documentRef()).isEqualTo("INV-0453"));
    }

    @Test
    void filtersBySourceTypeAccountAndDates() {
        assertThat(search(new JournalSearchCriteria(null, null, JournalSourceType.MANUAL, null, null)))
                .extracting(JournalEntryDto::description).containsExactly("October rent");

        String inventoryUid = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "1300")
                .map(ChartOfAccount::getUid).orElseThrow();
        assertThat(search(new JournalSearchCriteria(null, null, null, inventoryUid, null)))
                .extracting(JournalEntryDto::description)
                .containsExactlyInAnyOrder("COGS — sale INV-0453", "Goods receipt GRN-0007");

        assertThat(search(new JournalSearchCriteria(feb, feb, null, null, null)))
                .extracting(JournalEntryDto::description)
                .containsExactlyInAnyOrder("Goods receipt GRN-0007", "October rent");

        List<JournalEntryDto> grn = search(new JournalSearchCriteria(null, null, null, null, "GRN-0007"));
        assertThat(grn).hasSize(1);
        assertThat(grn.get(0).documentRef()).isEqualTo("GRN-0007");
    }

    @Test
    void noCriteria_listsEverything_manualHasNoDocumentRef() {
        List<JournalEntryDto> all = search(new JournalSearchCriteria(null, null, null, null, null));
        assertThat(all).hasSize(4);
        assertThat(all).filteredOn(r -> r.sourceType() == JournalSourceType.MANUAL)
                .allSatisfy(r -> assertThat(r.documentRef()).isNull());
    }

    // -------------------------------------------------------------------------

    private List<JournalEntryDto> search(JournalSearchCriteria c) {
        return journals.search(company.getId(), c, PageRequest.of(0, 50)).getContent();
    }

    private void post(LocalDate date, String description, JournalSourceType type, String ref,
                      String debitCode, String creditCode) {
        postingService.post(new JournalEntryDraft(company.getId(), branch.getId(), date,
                description, type, ref, null, null, List.of(
                        new JournalEntryDraft.LineDraft(account(debitCode), new BigDecimal("10"),
                                null, "TZS", null),
                        new JournalEntryDraft.LineDraft(account(creditCode), null,
                                new BigDecimal("10"), "TZS", null))));
    }

    private Long account(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("account " + code + " not seeded"));
    }
}
