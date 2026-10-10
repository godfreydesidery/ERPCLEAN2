package com.erp.modules.gl.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.dto.GlPostingExceptionDto;
import com.erp.modules.gl.domain.dto.GlPostingRepostResultDto;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryGlPoster.ReceiptLeg;
import com.erp.platform.common.api.ConflictException;
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

/**
 * ACC-02: an automatic GL posting that fails is no longer only a WARN in the log. It is recorded
 * as a posting exception, listed, and can be re-posted exactly once after its cause is fixed.
 */
class GlPostingExceptionIT extends PostgresIntegrationTest {

    @Autowired private GLPostingSafeInvoker invoker;
    @Autowired private InventoryGlPoster inventoryPoster;
    @Autowired private GlPostingExceptionService exceptions;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private JournalEntryRepository entryRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private FiscalPeriodDto period1;
    private LocalDate closedDate;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("GL Exceptions IT Org"));
        company = companies.save(new Company(org, "GLEXIT", "GL Exceptions IT Co"));
        branch  = branches.save(new Branch(company, "GLEX1", "GL Exceptions IT Branch"));
        AppUser root = new AppUser("glex_root", passwordEncoder.encode("RootPass1!"), "GL Root");
        root.setRoot(true);
        root = users.save(inOrganisation(root, org.getId()));
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "glex_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        period1 = fiscalCalendarService.listPeriods(company.getId()).get(0);
        fiscalCalendarService.closePeriod(period1.uid());
        closedDate = period1.startDate().plusDays(4);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void saleIntoClosedPeriod_isCaptured_listed_andRepostedExactlyOnceAfterReopen() {
        String invoiceUid = Ulid.next();

        attempt(() -> invoker.postSaleInNewTx(company.getId(), branch.getId(), invoiceUid, "TZS",
                new BigDecimal("118.00"), new BigDecimal("100.00"), new BigDecimal("18.00"),
                true, closedDate));
        // A redelivered event fails the same way — still ONE exception for the source.
        attempt(() -> invoker.postSaleInNewTx(company.getId(), branch.getId(), invoiceUid, "TZS",
                new BigDecimal("118.00"), new BigDecimal("100.00"), new BigDecimal("18.00"),
                true, closedDate));

        assertThat(salesJournals(invoiceUid)).isZero();
        List<GlPostingExceptionDto> open = listOpen();
        assertThat(open).hasSize(1);
        GlPostingExceptionDto ex = open.get(0);
        assertThat(ex.kind()).isEqualTo("SALE");
        assertThat(ex.sourceType()).isEqualTo("SALES");
        assertThat(ex.sourceRef()).isEqualTo(invoiceUid);
        assertThat(ex.postingDate()).isEqualTo(closedDate);
        assertThat(ex.amount()).isEqualByComparingTo("118.00");
        assertThat(ex.reason()).contains("closed");
        assertThat(ex.status()).isEqualTo("OPEN");

        // Still closed: the re-post is refused with the reason, and nothing new is recorded.
        assertThatThrownBy(() -> exceptions.repost(company.getId(), ex.uid(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("still fails");
        assertThat(listOpen()).hasSize(1);
        assertThat(salesJournals(invoiceUid)).isZero();

        fiscalCalendarService.reopenPeriod(period1.uid());

        GlPostingRepostResultDto result = exceptions.repost(company.getId(), ex.uid(), null);
        assertThat(result.outcome()).isEqualTo("REPOSTED");
        assertThat(result.journalEntryUid()).isNotBlank();
        assertThat(result.batchNumber()).matches("JB-\\d+");
        assertThat(result.postingDate()).isEqualTo(closedDate);
        assertThat(salesJournals(invoiceUid)).isEqualTo(1);

        // Exactly once: a second click is refused and posts nothing.
        assertThatThrownBy(() -> exceptions.repost(company.getId(), ex.uid(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been resolved");
        assertThat(salesJournals(invoiceUid)).isEqualTo(1);

        assertThat(listOpen()).isEmpty();
        List<GlPostingExceptionDto> all = exceptions.list(company.getId(), "SALES", null, null,
                true, PageRequest.of(0, 20)).getContent();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).status()).isEqualTo("RESOLVED");
        assertThat(all.get(0).outcome()).isEqualTo("REPOSTED");
        assertThat(all.get(0).journalEntryUid()).isEqualTo(result.journalEntryUid());
        assertThat(all.get(0).resolvedBy()).isEqualTo("glex_root");

        // Tie-out reads the re-posted sale on the GL side (no invoice rows exist in this fixture).
        var tieOut = exceptions.salesTieOut(company.getId(), period1.startDate(), period1.endDate());
        assertThat(tieOut.glRevenue()).isEqualByComparingTo("100.00");
        assertThat(tieOut.glVat()).isEqualByComparingTo("18.00");
        assertThat(tieOut.vatDifference()).isEqualByComparingTo("-18.00");
    }

    @Test
    void stockReceiptLegs_roundTrip_andRepostOnAnotherDate() {
        String receiptUid = Ulid.next();
        attempt(() -> inventoryPoster.postReceiptInNewTx(company.getId(), branch.getId(),
                closedDate, receiptUid, "GRN-0007", "TZS",
                List.of(new ReceiptLeg(Ulid.next(), "SKU-1", new BigDecimal("1234.5678")),
                        new ReceiptLeg(Ulid.next(), "SKU-2", new BigDecimal("10.00")))));

        GlPostingExceptionDto ex = listOpen().get(0);
        assertThat(ex.kind()).isEqualTo("STOCK_RECEIPT");
        assertThat(ex.documentNumber()).isEqualTo("GRN-0007");
        assertThat(ex.amount()).isEqualByComparingTo("1244.5678");

        // The period stays closed; the accountant posts it into an open date instead.
        LocalDate openDate = period1.endDate().plusDays(3);
        GlPostingRepostResultDto result = exceptions.repost(company.getId(), ex.uid(), openDate);
        assertThat(result.outcome()).isEqualTo("REPOSTED");
        assertThat(result.postingDate()).isEqualTo(openDate);
        var entry = entryRepo.findByCompanyIdAndUid(company.getId(), result.journalEntryUid())
                .orElseThrow();
        assertThat(entry.getTotalDebit()).isEqualByComparingTo("1244.5678");
        assertThat(entry.getDescription()).contains("GRN-0007");
    }

    @Test
    void journalAppearedSinceFailure_closesAsAlreadyPosted_withoutPostingAgain() {
        String ref = Ulid.next();
        attempt(() -> invoker.postInNewTx(draft(closedDate, ref)));
        GlPostingExceptionDto ex = listOpen().get(0);
        assertThat(ex.kind()).isEqualTo("JOURNAL_DRAFT");

        // Someone posts it by other means on an open date.
        assertThat(invoker.postInNewTx(draft(period1.endDate().plusDays(3), ref))).isNotNull();

        GlPostingRepostResultDto result = exceptions.repost(company.getId(), ex.uid(), null);
        assertThat(result.outcome()).isEqualTo("ALREADY_POSTED");
        assertThat(entryRepo.countByCompanyIdAndSourceTypeAndSourceRef(
                company.getId(), JournalSourceType.CASH_DIRECT, ref)).isEqualTo(1);
        assertThat(listOpen()).isEmpty();
    }

    @Test
    void otherCompanyCannotSeeOrRepost() {
        attempt(() -> invoker.postInNewTx(draft(closedDate, Ulid.next())));
        GlPostingExceptionDto ex = listOpen().get(0);

        Company other = companies.save(new Company(company.getOrganisation(), "GLEXO", "Other Co"));
        assertThat(exceptions.list(other.getId(), null, null, null, true, PageRequest.of(0, 20))
                .getContent()).isEmpty();
        assertThatThrownBy(() -> exceptions.repost(other.getId(), ex.uid(), null))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);
    }

    // -------------------------------------------------------------------------

    private List<GlPostingExceptionDto> listOpen() {
        return exceptions.list(company.getId(), null, null, null, false, PageRequest.of(0, 20))
                .getContent();
    }

    private long salesJournals(String invoiceUid) {
        return entryRepo.countByCompanyIdAndSourceTypeAndSourceRef(
                company.getId(), JournalSourceType.SALES, invoiceUid);
    }

    private JournalEntryDraft draft(LocalDate date, String ref) {
        Long cash = account("1000");
        Long cogs = account("5100");
        return new JournalEntryDraft(company.getId(), branch.getId(), date, "Cash entry " + ref,
                JournalSourceType.CASH_DIRECT, ref, null, null, List.of(
                        new JournalEntryDraft.LineDraft(cogs, new BigDecimal("50"), null, "TZS", null),
                        new JournalEntryDraft.LineDraft(cash, null, new BigDecimal("50"), "TZS", null)));
    }

    private Long account(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow();
    }

    /** The posters swallow; their REQUIRES_NEW commit may still report the rollback. */
    private static void attempt(Runnable call) {
        try {
            call.run();
        } catch (org.springframework.transaction.TransactionException expected) {
            // swallowed posting — the poster's own transaction was rolled back
        }
    }
}
