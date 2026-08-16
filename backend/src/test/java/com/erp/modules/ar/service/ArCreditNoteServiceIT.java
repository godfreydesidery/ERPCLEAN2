package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.platform.common.money.FxRateService;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ArCreditNoteServiceImpl} — FX fix (ADR-0036 D-3/D-4/D-5, Task C).
 *
 * <p>GL entry balance proof:
 * <pre>
 *   creditBase = face × CN-date rate  (DR Revenue)
 *   relievedBase = face × invoice original fxRate  (CR AR)
 *   delta = relievedBase − creditBase
 *   delta > 0: DR Revenue creditBase / DR REALIZED_FX_LOSS delta / CR AR relievedBase
 *              ΣDR = creditBase + delta = relievedBase = ΣCR  ✓
 *   delta < 0: DR Revenue creditBase / CR AR relievedBase / CR REALIZED_FX_GAIN (-delta)
 *              ΣDR = creditBase = relievedBase + (-delta) = ΣCR  ✓
 *   delta == 0 (base-currency or same rate): DR Revenue / CR AR  [2 lines only]  ✓
 * </pre>
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Foreign CN applied to foreign invoice, rate ROSE (delta &lt; 0): CR REALIZED_FX_GAIN;
 *       AR CR = relievedBase; base_outstanding_amount decremented; GL balanced.
 *   <li>Foreign CN applied to foreign invoice, rate FELL (delta &gt; 0): DR REALIZED_FX_LOSS;
 *       AR CR = relievedBase; GL balanced.
 *   <li>Base-currency CN: byte-identical (2 lines, no FX leg).
 *   <li>Currency mismatch (note currency != invoice currency) rejected.
 *   <li>Standalone (unapplied) CN in base currency posts 2 lines correctly.
 * </ol>
 */
class ArCreditNoteServiceIT extends PostgresIntegrationTest {

    @Autowired private ArCreditNoteService creditNoteService;
    @Autowired private ArOpeningBalanceService openingBalanceService;
    @Autowired private ArGlSeeder arGlSeeder;
    @Autowired private CustomerService customerService;
    @Autowired private ArInvoiceRepository arInvoiceRepo;
    @Autowired private FxRateService fxRateService;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;
    private String companyUid;
    private String customerUid;

    private static final String TZS = "TZS";
    private static final String USD = "USD";
    /** 1 USD = 2500 TZS — the original invoice rate */
    private static final BigDecimal RATE_INVOICE = new BigDecimal("2500.00000000");
    /**
     * Rate at CN date HIGHER than invoice rate (2600 &gt; 2500):
     * delta = relievedBase(2500×face) − creditBase(2600×face) &lt; 0 → need CR FX_GAIN to balance.
     */
    private static final BigDecimal RATE_HIGHER   = new BigDecimal("2600.00000000");
    /**
     * Rate at CN date LOWER than invoice rate (2400 &lt; 2500):
     * delta = relievedBase(2500×face) − creditBase(2400×face) &gt; 0 → need DR FX_LOSS to balance.
     */
    private static final BigDecimal RATE_LOWER    = new BigDecimal("2400.00000000");

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("ArCN IT Org"));
        company    = companies.save(new Company(org, "ARCN", "ArCN IT Co"));
        branch     = branches.save(new Branch(company, "ARCN1", "ArCN IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("arcn_root", passwordEncoder.encode("Root1234!"), "ArCN Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "arcn_root", true, company.getId(), branch.getId(), null));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "CN Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: foreign CN, rate ROSE since invoice (RATE_HIGHER > RATE_INVOICE)
    // D-6 GL timing — two entries:
    //   RAISE : DR Revenue 260k / CR AR 260k         (full contra at CN rate, no FX leg)
    //   APPLY : DR AR 10k / CR REALIZED_FX_GAIN 10k  (auto-apply-full realizes FX)
    //   creditBase = 100 × 2600 = 260,000 ; relievedBase = 100 × 2500 = 250,000
    //   net AR over both CN entries = 260k CR − 10k DR = 250k = relievedBase ✓
    // =========================================================================

    @Test
    void raise_foreignCN_rateRoseSinceInvoice_booksFxGain_glBalanced() {
        seedRate(RATE_INVOICE, LocalDate.now().minusDays(10));
        seedRate(RATE_HIGHER,  LocalDate.now());

        String invUid = foreignOpenItem("100", RATE_INVOICE, "250000");

        RaiseCreditNoteRequest req = new RaiseCreditNoteRequest(
                companyUid, customerUid, invUid,
                LocalDate.now(), new BigDecimal("100"), BigDecimal.ZERO, USD, "FX CN rate rose");
        ArCreditNoteDto result = creditNoteService.raise(req);

        // RAISE entry: full contra at CN rate — exactly 2 lines, balanced, no FX leg.
        assertGlBalanced(result.glEntryUid());
        assertThat(journalLinesOf(result.glEntryUid()))
                .as("raise posts the full contra only (DR Revenue / CR AR), no FX leg").hasSize(2);
        assertArCrEquals(result.glEntryUid(), "260000");      // CR AR at CN rate (creditBase)
        assertRevenueDrEquals(result.glEntryUid(), "260000"); // DR Revenue at CN rate

        // APPLY (auto-apply-full) realizes FX in a separate entry: rate rose → CR REALIZED_FX_GAIN.
        assertThat(cnAccountCr("4920")).as("realized FX gain (4920)").isEqualByComparingTo("10000");
        // Net AR relieved across raise + apply == relievedBase (invoice original rate).
        assertThat(cnAccountNetCr("1200")).as("net AR relieved at invoice rate")
                .isEqualByComparingTo("250000");

        // invoice fully relieved (base + face) — full CN
        var refreshed = arInvoiceRepo.findByUid(invUid).orElseThrow();
        assertThat(refreshed.getBaseOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getStatus()).isEqualTo(ArInvoiceStatus.PAID);
    }

    // =========================================================================
    // Bar 2: foreign CN, rate FELL since invoice (RATE_LOWER < RATE_INVOICE)
    // D-6 GL timing — two entries:
    //   RAISE : DR Revenue 240k / CR AR 240k          (full contra at CN rate, no FX leg)
    //   APPLY : DR REALIZED_FX_LOSS 10k / CR AR 10k   (auto-apply-full realizes FX)
    //   creditBase = 100 × 2400 = 240,000 ; relievedBase = 100 × 2500 = 250,000
    //   net AR over both CN entries = 240k CR + 10k CR = 250k = relievedBase ✓
    // =========================================================================

    @Test
    void raise_foreignCN_rateFellSinceInvoice_booksFxLoss_glBalanced() {
        seedRate(RATE_INVOICE, LocalDate.now().minusDays(10));
        seedRate(RATE_LOWER,   LocalDate.now());

        String invUid = foreignOpenItem("100", RATE_INVOICE, "250000");

        RaiseCreditNoteRequest req = new RaiseCreditNoteRequest(
                companyUid, customerUid, invUid,
                LocalDate.now(), new BigDecimal("100"), BigDecimal.ZERO, USD, "FX CN rate fell");
        ArCreditNoteDto result = creditNoteService.raise(req);

        // RAISE entry: full contra at CN rate — exactly 2 lines, balanced, no FX leg.
        assertGlBalanced(result.glEntryUid());
        assertThat(journalLinesOf(result.glEntryUid()))
                .as("raise posts the full contra only (DR Revenue / CR AR), no FX leg").hasSize(2);
        assertArCrEquals(result.glEntryUid(), "240000");      // CR AR at CN rate (creditBase)
        assertRevenueDrEquals(result.glEntryUid(), "240000"); // DR Revenue at CN rate

        // APPLY (auto-apply-full) realizes FX in a separate entry: rate fell → DR REALIZED_FX_LOSS.
        assertThat(cnAccountDr("5190")).as("realized FX loss (5190)").isEqualByComparingTo("10000");
        // Net AR relieved across raise + apply == relievedBase (invoice original rate).
        assertThat(cnAccountNetCr("1200")).as("net AR relieved at invoice rate")
                .isEqualByComparingTo("250000");

        // invoice fully relieved — full CN
        var refreshed = arInvoiceRepo.findByUid(invUid).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ArInvoiceStatus.PAID);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 3 (I-5): base-currency CN — byte-identical, exactly 2 GL lines, no FX leg
    // =========================================================================

    @Test
    void raise_baseCurrencyCN_byteIdentical_twoLinesOnly_noFxLeg() {
        ArInvoiceDto invDto = openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid, new BigDecimal("1000"), TZS,
                LocalDate.now(), LocalDate.now().plusDays(30), null));

        RaiseCreditNoteRequest req = new RaiseCreditNoteRequest(
                companyUid, customerUid, invDto.uid(),
                LocalDate.now(), new BigDecimal("1000"), BigDecimal.ZERO, TZS, "Base CN");
        ArCreditNoteDto result = creditNoteService.raise(req);

        var lines = journalLinesOf(result.glEntryUid());
        assertThat(lines).as("base-currency CN must have exactly 2 GL lines").hasSize(2);

        assertGlBalanced(result.glEntryUid());

        BigDecimal sumDebit = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDebit).isEqualByComparingTo(new BigDecimal("1000"));

        var refreshed = arInvoiceRepo.findByUid(invDto.uid()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ArInvoiceStatus.PAID);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 4: currency mismatch (CN currency != invoice currency) rejected
    // =========================================================================

    @Test
    void raise_currencyMismatch_rejected() {
        seedRate(RATE_INVOICE, LocalDate.now());
        String invUid = foreignOpenItem("100", RATE_INVOICE, "250000");

        // Try to raise a TZS CN against a USD invoice
        RaiseCreditNoteRequest req = new RaiseCreditNoteRequest(
                companyUid, customerUid, invUid,
                LocalDate.now(), new BigDecimal("250000"), BigDecimal.ZERO, TZS, "Mismatch");
        assertThatThrownBy(() -> creditNoteService.raise(req))
                .as("credit note currency TZS != invoice currency USD must be rejected")
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // Bar 5: standalone (unapplied) CN in base currency — 2 balanced lines
    // =========================================================================

    @Test
    void raise_standalone_baseCurrency_twoBalancedLines() {
        RaiseCreditNoteRequest req = new RaiseCreditNoteRequest(
                companyUid, customerUid, null,
                LocalDate.now(), new BigDecimal("500"), BigDecimal.ZERO, TZS, "Standalone");
        ArCreditNoteDto result = creditNoteService.raise(req);

        assertThat(result.glEntryUid()).isNotBlank();
        assertGlBalanced(result.glEntryUid());

        var lines = journalLinesOf(result.glEntryUid());
        assertThat(lines).as("standalone base CN must have exactly 2 GL lines").hasSize(2);

        BigDecimal sumDebit = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDebit).isEqualByComparingTo(new BigDecimal("500"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void seedRate(BigDecimal rate, LocalDate effectiveDate) {
        fxRateService.addRate(new UpsertRateRequest(
                company.getId(), USD, TZS, rate, effectiveDate, "SPOT", "test"));
    }

    /**
     * Creates a foreign-currency AR invoice for testing by:
     * 1. Creating a TZS opening balance (face = baseAmount) so the GL posting passes BR-GL-06.
     * 2. Native-patching currency → USD and outstanding_amount → faceUsd.
     * 3. Stamping the FX triple (fxRate, baseOriginalAmount, baseOutstandingAmount) via JPA setters.
     *
     * <p>This simulates what Task A's ArSalePostedHandler would set for a foreign SALE-sourced
     * open item. The test helper is needed because ArOpeningBalanceServiceImpl posts the GL entry
     * in document currency which the base-only GL engine would reject for USD.
     *
     * @param faceUsd   the USD face amount (e.g. "100")
     * @param fxRate    the original invoice rate (e.g. 2500)
     * @param baseAmount the base TZS amount at fxRate (e.g. "250000")
     * @return the uid of the created open item (use with ArInvoiceRepository.findByUid)
     */
    /**
     * Creates a foreign-currency AR invoice for testing.
     *
     * <p>Creates the opening balance in TZS (passes the GL base-currency gate), then uses a
     * single native SQL patch to set currency=USD, outstanding=faceUsd, fx_rate, and both base
     * amounts — all of which are {@code updatable=false} in JPA and therefore cannot be changed
     * via {@code save()}. This simulates what Task A's ArSalePostedHandler sets.
     */
    private String foreignOpenItem(String faceUsd, BigDecimal fxRate, String baseAmount) {
        ArInvoiceDto dto = openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid, new BigDecimal(baseAmount), TZS,
                LocalDate.now().minusDays(10), LocalDate.now().plusDays(60), null));
        var inv = arInvoiceRepo.findByUid(dto.uid()).orElseThrow();
        // Single native patch: all columns that are updatable=false in JPA in one shot.
        // clearAutomatically=true evicts the first-level cache so the next read hits the DB.
        arInvoiceRepo.patchForFxTest(inv.getId(), USD, new BigDecimal(faceUsd),
                fxRate, new BigDecimal(baseAmount), new BigDecimal(baseAmount));
        return dto.uid();
    }

    private java.util.List<com.erp.modules.gl.domain.entity.JournalLine> journalLinesOf(
            String glEntryUid) {
        var entry = journalEntryRepo.findByUid(glEntryUid).orElseThrow();
        return journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());
    }

    private void assertGlBalanced(String glEntryUid) {
        var lines = journalLinesOf(glEntryUid);
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr)
                .as("GL entry %s must be balanced (ΣDR == ΣCR)", glEntryUid)
                .isEqualByComparingTo(sumCr);
    }

    private void assertArCrEquals(String glEntryUid, String expected) {
        Long arId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "1200")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("AR account 1200 not seeded"));
        var lines = journalLinesOf(glEntryUid);
        BigDecimal arCr = lines.stream()
                .filter(l -> arId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(arCr)
                .as("AR control (1200) CR must be %s", expected)
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    private void assertRevenueDrEquals(String glEntryUid, String expected) {
        Long revId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "4100")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Revenue account 4100 not seeded"));
        var lines = journalLinesOf(glEntryUid);
        BigDecimal revDr = lines.stream()
                .filter(l -> revId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(revDr)
                .as("Revenue (4000) DR must be %s", expected)
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    // -- CN-sourced GL movement helpers ------------------------------------------------
    // Sum a given account across the credit-note's GL entries only (raise + apply-FX), so the
    // opening-balance entry — which also touches AR 1200 — does not pollute the assertion.
    private BigDecimal cnAccountCr(String code)    { return cnAccountMovement(code, false); }
    private BigDecimal cnAccountDr(String code)    { return cnAccountMovement(code, true);  }
    private BigDecimal cnAccountNetCr(String code) {
        return cnAccountMovement(code, false).subtract(cnAccountMovement(code, true));
    }

    private BigDecimal cnAccountMovement(String accountCode, boolean debit) {
        Long acctId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), accountCode)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account " + accountCode + " not seeded"));
        return journalEntryRepo.findByCompanyId(company.getId(), org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(e -> e.getSourceType() == JournalSourceType.AR_CREDIT_NOTE)
                .flatMap(e -> journalLineRepo.findByEntryIdOrderByLineNo(e.getId()).stream())
                .filter(l -> acctId.equals(l.getAccountId()))
                .map(l -> {
                    BigDecimal v = debit ? l.getDebitAmount() : l.getCreditAmount();
                    return v != null ? v : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
