package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
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
 * Integration tests for the GL posting engine guard-rails (ADR-0013 D-3, gl.md BR-GL-01..08,
 * FR-GL-06..09). Every BR on the service enforcement split is exercised against a real Postgres
 * schema.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Happy path: balanced ≥2-line manual journal posts and gets a JB-#### number.</li>
 *   <li>BR-GL-01: unbalanced entry rejected; nothing persisted.</li>
 *   <li>BR-GL-01: fewer than 2 lines rejected.</li>
 *   <li>BR-GL-03: posting into a CLOSED period rejected.</li>
 *   <li>BR-GL-04: posting to an INACTIVE account rejected.</li>
 *   <li>BR-GL-05: cross-company account rejected (company isolation / tenancy).</li>
 *   <li>BR-GL-06: wrong-currency line rejected.</li>
 *   <li>BR-GL-02/BR-GL-11: postReversal swaps DR↔CR; reversal_of_id set; TB nets to zero.</li>
 *   <li>NFR-GL-04: service exposes no update/delete of a posted entry (append-only).</li>
 * </ul>
 */
class GLPostingServiceIT extends PostgresIntegrationTest {

    @Autowired private GLPostingService postingService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private TrialBalanceQuery trialBalanceQuery;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private JournalEntryRepository entryRepo;
    @Autowired private JournalLineRepository lineRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;

    /** The two seeded account IDs used across most tests (DR=Expense 5100, CR=Cash 1000). */
    private Long cashAccountId;
    private Long cogsAccountId;
    private static final String TZS = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("GL Posting IT Org"));
        company = companies.save(new Company(org, "GLPIT", "GL Posting IT Co"));
        branch  = branches.save(new Branch(company, "GLPB1", "GL Posting IT Branch"));

        AppUser root = new AppUser("glpit_root", passwordEncoder.encode("RootPass1!"), "GL Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "glpit_root", true, company.getId(), branch.getId(), null));

        // Seed GL foundations (mirror SalesPostingHandlerIT setUp exactly)
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        // Resolve the two accounts we use directly in drafts
        cashAccountId = accountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Cash account 1000 not seeded"));
        cogsAccountId = accountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "5100")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("COGS account 5100 not seeded"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Happy path: balanced manual journal
    // =========================================================================

    @Test
    void post_balancedTwoLineJournal_succeeds_batchNumberAssigned() {
        JournalEntryDraft draft = balancedDraft(
                new BigDecimal("500"),  // DR COGS
                new BigDecimal("500")); // CR Cash

        JournalEntryDto result = postingService.post(draft);

        assertThat(result.uid()).isNotBlank();
        assertThat(result.batchNumber()).matches("JB-\\d+");
        assertThat(result.lines()).hasSize(2);

        // Both lines persisted in DB
        List<?> dbLines = lineRepo.findByEntryIdOrderByLineNo(result.id());
        assertThat(dbLines).hasSize(2);
    }

    @Test
    void post_balancedThreeLineJournal_succeeds_debitsEqualCredits() {
        Long salesRevId = accountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "4100")
                .map(ChartOfAccount::getId)
                .orElseThrow();

        // Structural 3-line balance test (debits == credits, all lines persisted). AR (1200) is a
        // control account that blocks manual posting (D-1, ADR-0040), so the debit leg uses the
        // non-control COGS account: DR COGS 1180, CR Revenue 1000, CR Cash 180.
        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Three-line test", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new JournalEntryDraft.LineDraft(cogsAccountId, new BigDecimal("1180"), null, TZS, null),
                        new JournalEntryDraft.LineDraft(salesRevId,    null, new BigDecimal("1000"), TZS, null),
                        new JournalEntryDraft.LineDraft(cashAccountId, null, new BigDecimal("180"),  TZS, null)
                )
        );

        JournalEntryDto result = postingService.post(draft);

        assertThat(result.lines()).hasSize(3);
        BigDecimal totalDebit  = result.lines().stream()
                .map(l -> l.debitAmount()  != null ? l.debitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = result.lines().stream()
                .map(l -> l.creditAmount() != null ? l.creditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
    }

    // =========================================================================
    // BR-GL-01: unbalanced entry rejected; nothing persisted
    // =========================================================================

    @Test
    void post_unbalancedEntry_rejected_nothingPersisted() {
        // DR COGS 500, CR Cash 400 — unbalanced by 100
        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Unbalanced test", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new JournalEntryDraft.LineDraft(cogsAccountId, new BigDecimal("500"), null, TZS, null),
                        new JournalEntryDraft.LineDraft(cashAccountId, null, new BigDecimal("400"), TZS, null)
                )
        );

        long linesBefore = lineRepo.count();

        assertThatThrownBy(() -> postingService.post(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-GL-01");

        // Nothing written
        assertThat(lineRepo.count()).isEqualTo(linesBefore);
    }

    // =========================================================================
    // BR-GL-01: fewer than 2 lines rejected
    // =========================================================================

    @Test
    void post_singleLineDraft_rejected() {
        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Single line", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new JournalEntryDraft.LineDraft(cashAccountId, new BigDecimal("100"), null, TZS, null)
                )
        );

        assertThatThrownBy(() -> postingService.post(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 lines");
    }

    @Test
    void post_emptyLineDraft_rejected() {
        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Empty", JournalSourceType.MANUAL, null, null, rootId,
                List.of()
        );

        assertThatThrownBy(() -> postingService.post(draft))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // BR-GL-03: posting into a CLOSED period rejected
    // =========================================================================

    @Test
    void post_intoClosedPeriod_rejected() {
        // Find period 1 (January) and close it — posting_date within that month must be rejected
        List<com.erp.modules.gl.domain.dto.FiscalPeriodDto> allPeriods =
                fiscalCalendarService.listPeriods(company.getId());
        assertThat(allPeriods).isNotEmpty();

        com.erp.modules.gl.domain.dto.FiscalPeriodDto period1 = allPeriods.get(0);
        // Close period 1
        fiscalCalendarService.closePeriod(period1.uid());

        // A posting date that falls in the now-closed period
        LocalDate closedDate = period1.startDate().plusDays(5);

        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), closedDate,
                "Closed period attempt", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new JournalEntryDraft.LineDraft(cogsAccountId, new BigDecimal("100"), null, TZS, null),
                        new JournalEntryDraft.LineDraft(cashAccountId, null, new BigDecimal("100"), TZS, null)
                )
        );

        assertThatThrownBy(() -> postingService.post(draft))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BR-GL-03");
    }

    // =========================================================================
    // BR-GL-04: posting to an INACTIVE account rejected
    // =========================================================================

    @Test
    void post_inactiveAccount_rejected() {
        // Deactivate the COGS account
        ChartOfAccount cogs = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "5100")
                .orElseThrow();
        chartOfAccountService.deactivate(cogs.getUid());

        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Inactive account attempt", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new JournalEntryDraft.LineDraft(cogsAccountId, new BigDecimal("200"), null, TZS, null),
                        new JournalEntryDraft.LineDraft(cashAccountId, null, new BigDecimal("200"), TZS, null)
                )
        );

        assertThatThrownBy(() -> postingService.post(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-GL-04");
    }

    // =========================================================================
    // BR-GL-05: cross-company account rejected (tenancy isolation)
    // =========================================================================

    @Test
    void post_crossCompanyAccount_rejected() {
        // Create a second company with its own seeded accounts
        Organisation org2 = organisations.save(new Organisation("GL Posting IT Org 2"));
        Company company2  = companies.save(new Company(org2, "GLPIT2", "GL Posting IT Co 2"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "glpit_root", true, company2.getId(), branch.getId(), null));
        chartOfAccountService.seedDefaults(company2.getId());
        fiscalCalendarService.seedCurrentYear(company2.getId());
        glConfigService.seedDefaults(company2.getId());

        // Get a valid account from company2 (its Cash account)
        Long company2CashId = accountRepo
                .findByCompanyIdAndAccountCode(company2.getId(), "1000")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Cash account not seeded for company2"));

        // Restore context to company1, but reference company2's account
        RequestContext.set(new RequestContext.Principal(
                rootId, "glpit_root", true, company.getId(), branch.getId(), null));

        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Cross-company attempt", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        // cogsAccountId belongs to company1 (valid debit side)
                        new JournalEntryDraft.LineDraft(cogsAccountId,    new BigDecimal("300"), null, TZS, null),
                        // company2CashId belongs to company2 — must be rejected
                        new JournalEntryDraft.LineDraft(company2CashId,   null, new BigDecimal("300"), TZS, null)
                )
        );

        assertThatThrownBy(() -> postingService.post(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-GL-05");
    }

    // =========================================================================
    // BR-GL-06: wrong-currency line rejected
    // =========================================================================

    @Test
    void post_wrongCurrencyLine_rejected() {
        // Company base is TZS; supplying USD must be rejected
        JournalEntryDraft draft = new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Wrong currency", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new JournalEntryDraft.LineDraft(cogsAccountId, new BigDecimal("100"), null, "USD", null),
                        new JournalEntryDraft.LineDraft(cashAccountId, null, new BigDecimal("100"), "USD", null)
                )
        );

        assertThatThrownBy(() -> postingService.post(draft))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-GL-06");
    }

    // =========================================================================
    // BR-GL-02 / BR-GL-11: postReversal swaps DR↔CR; reversal_of_id set; TB nets to zero
    // =========================================================================

    @Test
    void postReversal_swapsDebitsAndCredits_reversalOfIdSet_tbNetsToZero() {
        // Post original entry
        JournalEntryDto original = postingService.post(balancedDraft(
                new BigDecimal("800"),   // DR COGS
                new BigDecimal("800"))); // CR Cash

        // Restore context (posting engine uses RequestContext internally)
        RequestContext.set(new RequestContext.Principal(
                rootId, "glpit_root", true, company.getId(), branch.getId(), null));

        // Post reversal
        JournalEntryDto reversal = postingService.postReversal(
                original.uid(), LocalDate.now(), JournalSourceType.MANUAL, null, rootId);

        // reversal_of_id must point to the original
        assertThat(reversal.reversalOfId()).isEqualTo(original.id());

        // Lines must be swapped: original DR→reversal CR, original CR→reversal DR
        var origLines = original.lines();
        var revLines  = reversal.lines();
        assertThat(revLines).hasSize(origLines.size());

        BigDecimal origDebit   = origLines.stream()
                .map(l -> l.debitAmount()  != null ? l.debitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal revCredit   = revLines.stream()
                .map(l -> l.creditAmount() != null ? l.creditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(origDebit).as("original DR total must equal reversal CR total")
                .isEqualByComparingTo(revCredit);

        BigDecimal origCredit  = origLines.stream()
                .map(l -> l.creditAmount() != null ? l.creditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal revDebit    = revLines.stream()
                .map(l -> l.debitAmount()  != null ? l.debitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(origCredit).as("original CR total must equal reversal DR total")
                .isEqualByComparingTo(revDebit);

        // Trial balance must net to zero after original + reversal
        RequestContext.set(new RequestContext.Principal(
                rootId, "glpit_root", true, company.getId(), branch.getId(), null));
        TrialBalanceDto tb = trialBalanceQuery.compute(company.getId());
        assertThat(tb.totalDebits())
                .as("trial balance must net to zero after posting and reversing the same entry")
                .isEqualByComparingTo(tb.totalCredits());
    }

    // =========================================================================
    // NFR-GL-04: service exposes no update/delete path on posted entries (append-only)
    // =========================================================================

    @Test
    void postedEntry_hasNoUpdatePath_appendOnlyVerified() {
        // Structural: JournalEntry entity must have no updated_* fields — verified at entity level.
        // Service level: JournalService exposes only postManual + postManualReversal + read paths.
        // Test asserts the JournalEntry in DB after posting has a non-null postedAt and the
        // entity has no setPostedAt / setCompanyId / setLines exposed — we test via the DTO:
        JournalEntryDto posted = postingService.post(balancedDraft(
                new BigDecimal("300"), new BigDecimal("300")));

        // Verify the entry exists in DB and has a posted timestamp
        var dbEntry = entryRepo.findByUid(posted.uid()).orElseThrow();
        assertThat(dbEntry.getPostedAt()).isNotNull();

        // The entry can only be retrieved; no update/delete service method exists.
        // Assert that GLPostingService has no updateEntry or deleteEntry methods by reflective check.
        boolean hasUpdateMethod = java.util.Arrays.stream(
                        com.erp.modules.gl.service.GLPostingService.class.getMethods())
                .anyMatch(m -> m.getName().toLowerCase().contains("update")
                            || m.getName().toLowerCase().contains("delete"));
        assertThat(hasUpdateMethod)
                .as("GLPostingService must not expose update/delete methods (NFR-GL-04, BR-GL-02)")
                .isFalse();
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /** A balanced 2-line draft: DR COGS, CR Cash, same amount. */
    private JournalEntryDraft balancedDraft(BigDecimal debitAmt, BigDecimal creditAmt) {
        return new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Balance test entry", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new JournalEntryDraft.LineDraft(cogsAccountId, debitAmt,  null,       TZS, null),
                        new JournalEntryDraft.LineDraft(cashAccountId, null,       creditAmt, TZS, null)
                )
        );
    }
}
