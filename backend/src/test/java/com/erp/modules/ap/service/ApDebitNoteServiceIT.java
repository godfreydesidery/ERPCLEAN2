package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ApDebitNoteServiceImpl} — the AP mirror of
 * {@link com.erp.modules.ar.service.ArCreditNoteServiceImpl} (ADR-0041 D3, P2 design batch 2).
 *
 * <p>GL timing (precise inverse of the AR credit-note path):
 * <pre>
 *   RAISE : full contra ONCE at DN rate — DR AP-control(baseTotal) / CR Purchases(baseNet)
 *           [+ CR VAT_INPUT(baseVat)]. No bill relief, no realized-FX at raise. (2-3 lines)
 *   APPLY : sub-ledger move only. Posts NOTHING to GL except the realized-FX plug per
 *           allocation when settlement_rate (= DN rate) differs from the bill's fx_rate.
 *           A bill-targeted raise auto-applies-full in the same TX (raise + immediate apply).
 *
 *   creditBase   = face × DN-date rate      (DR AP at raise)
 *   relievedBase = face × bill original rate (bill base relieved at apply)
 *   fxDelta      = relievedBase − creditBase  (= sumBaseRelieved − sumBaseSettled)
 *     fxDelta > 0 (rate ROSE since bill: dnRate &lt; billRate): bill base relieved exceeds DN
 *                 base → CR REALIZED_FX_GAIN (DR AP plug / CR 4920)
 *     fxDelta &lt; 0 (rate FELL since bill: dnRate &gt; billRate): DN base exceeds bill base
 *                 relieved → DR REALIZED_FX_LOSS (DR 5190 / CR AP plug)
 *     fxDelta == 0 (base-currency or same rate): no FX leg (byte-identical path)
 * </pre>
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Foreign DN against foreign bill, rate ROSE since bill (delta &gt; 0): CR REALIZED_FX_GAIN;
 *       AP DR at raise = creditBase; bill fully relieved; both entries GL-balanced.</li>
 *   <li>Foreign DN against foreign bill, rate FELL since bill (delta &lt; 0): DR REALIZED_FX_LOSS;
 *       both entries GL-balanced.</li>
 *   <li>Base-currency DN: byte-identical raise (exactly 2 GL lines, no FX leg).</li>
 *   <li>Currency mismatch (DN amount &gt; bill outstanding on a foreign bill) rejected.</li>
 *   <li>Standalone (unapplied) base-currency DN posts 2 balanced lines.</li>
 * </ol>
 */
class ApDebitNoteServiceIT extends PostgresIntegrationTest {

    @Autowired private ApDebitNoteService       debitNoteService;
    @Autowired private ApOpeningBalanceService  openingBalanceService;
    @Autowired private ApGlSeeder               apGlSeeder;
    @Autowired private SupplierService          supplierService;
    @Autowired private SupplierBillRepository   billRepo;
    @Autowired private CurrencyRateRepository   rateRepo;
    @Autowired private JournalEntryRepository   journalEntryRepo;
    @Autowired private JournalLineRepository    journalLineRepo;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private ChartOfAccountService    chartOfAccountService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;
    @Autowired private JdbcTemplate             jdbc;
    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private PasswordEncoder          passwordEncoder;
    @Autowired private IamTestData              testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  supplierUid;

    private static final String TZS = "TZS";
    private static final String USD = "USD";
    /** 1 USD = 2500 TZS — the original bill rate. */
    private static final BigDecimal RATE_BILL   = new BigDecimal("2500.00000000");
    /**
     * Rate at DN date LOWER than bill rate (2400 &lt; 2500):
     * fxDelta = relievedBase(2500×face) − creditBase(2400×face) &gt; 0 → CR FX_GAIN.
     */
    private static final BigDecimal RATE_LOWER  = new BigDecimal("2400.00000000");
    /**
     * Rate at DN date HIGHER than bill rate (2600 &gt; 2500):
     * fxDelta = relievedBase(2500×face) − creditBase(2600×face) &lt; 0 → DR FX_LOSS.
     */
    private static final BigDecimal RATE_HIGHER = new BigDecimal("2600.00000000");

    private static final LocalDate BILL_DATE = LocalDate.now().minusDays(10);

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("ApDN IT Org"));
        company    = companies.save(new Company(org, "APDN", "ApDN IT Co"));
        branch     = branches.save(new Branch(company, "APDN1", "ApDN IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("apdn_root", passwordEncoder.encode("Root1234!"), "ApDN Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "apdn_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "DN Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: foreign DN, rate ROSE since bill (RATE_LOWER < RATE_BILL → dnRate < billRate)
    // D3 GL timing — two entries:
    //   RAISE : DR AP 240k / CR Purchases 240k        (full contra at DN rate, no FX leg)
    //   APPLY : DR AP 10k / CR REALIZED_FX_GAIN 10k   (auto-apply-full realizes FX)
    //   creditBase = 100 × 2400 = 240,000 ; relievedBase = 100 × 2500 = 250,000
    //   net AP relieved over both entries = 240k DR + 10k DR = 250k = relievedBase ✓
    // =========================================================================

    @Test
    void raise_foreignDN_rateRoseSinceBill_booksFxGain_glBalanced() {
        seedRate(RATE_BILL,  BILL_DATE);
        seedRate(RATE_LOWER, LocalDate.now());

        String billUid = foreignOpenBill("USD-DN-GAIN-001", "100", RATE_BILL, "250000");

        RaiseDebitNoteRequest req = new RaiseDebitNoteRequest(
                companyUid, supplierUid, billUid,
                LocalDate.now(), new BigDecimal("100"), BigDecimal.ZERO, "FX DN rate rose", null);
        ApDebitNoteDto result = debitNoteService.raise(req);

        // RAISE entry: full contra at DN rate — exactly 2 lines, balanced, no FX leg.
        assertGlBalanced(result.glEntryUid());
        assertThat(journalLinesOf(result.glEntryUid()))
                .as("raise posts the full contra only (DR AP / CR Purchases), no FX leg").hasSize(2);
        assertApDrEquals(result.glEntryUid(), "240000");        // DR AP at DN rate (creditBase)
        assertPurchasesCrEquals(result.glEntryUid(), "240000"); // CR Purchases at DN rate

        // APPLY (auto-apply-full) realizes FX in a separate entry: rate rose → CR REALIZED_FX_GAIN.
        assertThat(dnAccountCr("4920")).as("realized FX gain (4920)").isEqualByComparingTo("10000");
        // Net AP relieved across raise + apply == relievedBase (bill original rate).
        assertThat(dnAccountNetDr("2100")).as("net AP relieved at bill rate")
                .isEqualByComparingTo("250000");

        // bill fully relieved (face + base) — full DN
        SupplierBill refreshed = billRepo.findByUid(billUid).orElseThrow();
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getBaseOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(refreshed.getStatus()).isEqualTo(SupplierBillStatus.PAID);
    }

    // =========================================================================
    // Bar 2: foreign DN, rate FELL since bill (RATE_HIGHER > RATE_BILL → dnRate > billRate)
    // D3 GL timing — two entries:
    //   RAISE : DR AP 260k / CR Purchases 260k         (full contra at DN rate, no FX leg)
    //   APPLY : DR REALIZED_FX_LOSS 10k / CR AP 10k    (auto-apply-full realizes FX)
    //   creditBase = 100 × 2600 = 260,000 ; relievedBase = 100 × 2500 = 250,000
    //   net AP relieved over both entries = 260k DR − 10k CR = 250k = relievedBase ✓
    // =========================================================================

    @Test
    void raise_foreignDN_rateFellSinceBill_booksFxLoss_glBalanced() {
        seedRate(RATE_BILL,   BILL_DATE);
        seedRate(RATE_HIGHER, LocalDate.now());

        String billUid = foreignOpenBill("USD-DN-LOSS-001", "100", RATE_BILL, "250000");

        RaiseDebitNoteRequest req = new RaiseDebitNoteRequest(
                companyUid, supplierUid, billUid,
                LocalDate.now(), new BigDecimal("100"), BigDecimal.ZERO, "FX DN rate fell", null);
        ApDebitNoteDto result = debitNoteService.raise(req);

        // RAISE entry: full contra at DN rate — exactly 2 lines, balanced, no FX leg.
        assertGlBalanced(result.glEntryUid());
        assertThat(journalLinesOf(result.glEntryUid()))
                .as("raise posts the full contra only (DR AP / CR Purchases), no FX leg").hasSize(2);
        assertApDrEquals(result.glEntryUid(), "260000");        // DR AP at DN rate (creditBase)
        assertPurchasesCrEquals(result.glEntryUid(), "260000"); // CR Purchases at DN rate

        // APPLY (auto-apply-full) realizes FX in a separate entry: rate fell → DR REALIZED_FX_LOSS.
        assertThat(dnAccountDr("5190")).as("realized FX loss (5190)").isEqualByComparingTo("10000");
        // Net AP relieved across raise + apply == relievedBase (bill original rate).
        assertThat(dnAccountNetDr("2100")).as("net AP relieved at bill rate")
                .isEqualByComparingTo("250000");

        // bill fully relieved — full DN
        SupplierBill refreshed = billRepo.findByUid(billUid).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SupplierBillStatus.PAID);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 3: base-currency DN — byte-identical, exactly 2 GL lines, no FX leg
    // =========================================================================

    @Test
    void raise_baseCurrencyDN_byteIdentical_twoLinesOnly_noFxLeg() {
        SupplierBillDto billDto = openingBalance("TZS-DN-BASE-001", new BigDecimal("1000"));

        RaiseDebitNoteRequest req = new RaiseDebitNoteRequest(
                companyUid, supplierUid, billDto.uid(),
                LocalDate.now(), new BigDecimal("1000"), BigDecimal.ZERO, "Base DN", null);
        ApDebitNoteDto result = debitNoteService.raise(req);

        var lines = journalLinesOf(result.glEntryUid());
        assertThat(lines).as("base-currency DN must have exactly 2 GL lines").hasSize(2);

        assertGlBalanced(result.glEntryUid());

        BigDecimal sumDebit = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDebit).isEqualByComparingTo(new BigDecimal("1000"));

        SupplierBill refreshed = billRepo.findByUid(billDto.uid()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SupplierBillStatus.PAID);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 4: currency mismatch / over-outstanding rejected
    // A USD bill outstanding is USD 100; a DN whose gross exceeds the bill outstanding
    // (interpreting the DN amount in the bill currency) is rejected at raise.
    // =========================================================================

    @Test
    void raise_amountExceedsBillOutstanding_rejected() {
        seedRate(RATE_BILL, LocalDate.now());
        String billUid = foreignOpenBill("USD-DN-OVER-001", "100", RATE_BILL, "250000");

        // Try to raise a DN for 250,000 against a bill whose outstanding is only USD 100.
        RaiseDebitNoteRequest req = new RaiseDebitNoteRequest(
                companyUid, supplierUid, billUid,
                LocalDate.now(), new BigDecimal("250000"), BigDecimal.ZERO, "Over outstanding", null);
        assertThatThrownBy(() -> debitNoteService.raise(req))
                .as("DN gross exceeding bill outstanding must be rejected")
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================
    // Bar 5: standalone (unapplied) DN in base currency — 2 balanced lines
    // =========================================================================

    @Test
    void raise_standalone_baseCurrency_twoBalancedLines() {
        RaiseDebitNoteRequest req = new RaiseDebitNoteRequest(
                companyUid, supplierUid, null,
                LocalDate.now(), new BigDecimal("500"), BigDecimal.ZERO, "Standalone", null);
        ApDebitNoteDto result = debitNoteService.raise(req);

        assertThat(result.glEntryUid()).isNotBlank();
        assertGlBalanced(result.glEntryUid());

        var lines = journalLinesOf(result.glEntryUid());
        assertThat(lines).as("standalone base DN must have exactly 2 GL lines").hasSize(2);

        BigDecimal sumDebit = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDebit).isEqualByComparingTo(new BigDecimal("500"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SupplierBillDto openingBalance(String invoiceRef, BigDecimal amount) {
        return openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, TZS,
                BILL_DATE, LocalDate.now().plusDays(60), invoiceRef));
    }

    private void seedRate(BigDecimal rate, LocalDate effectiveDate) {
        CurrencyRate row = new CurrencyRate(
                company.getId(), branch.getId(),
                USD, TZS, rate, effectiveDate,
                "SPOT", "IT-seed", rootId);
        rateRepo.save(row);
    }

    /**
     * Creates a foreign-currency AP bill for testing by creating a TZS opening balance (which
     * passes the base-currency GL gate), then native-patching currency=USD, the USD face, fx_rate
     * and both base amounts — all {@code updatable=false} in JPA. Mirrors the FX patch used in
     * {@link FxApPaymentSettlementIT}. Simulates what a real foreign SALE/PO bill would stamp.
     *
     * @param faceUsd   the USD face amount (e.g. "100")
     * @param billRate  the original bill rate (e.g. 2500)
     * @param baseAmount the base TZS amount at billRate (e.g. "250000")
     * @return the uid of the created open bill
     */
    private String foreignOpenBill(String invoiceRef, String faceUsd,
                                   BigDecimal billRate, String baseAmount) {
        SupplierBillDto dto = openingBalance(invoiceRef, new BigDecimal(baseAmount));
        Long billId = billRepo.findByUid(dto.uid()).orElseThrow().getId();
        jdbc.update("""
                UPDATE supplier_bills
                SET    currency = ?, fx_rate = ?, base_gross_amount = ?,
                       base_outstanding_amount = ?, outstanding_amount = ?, rate_at = NOW()
                WHERE  id = ?
                """, USD, billRate, new BigDecimal(baseAmount), new BigDecimal(baseAmount),
                new BigDecimal(faceUsd), billId);
        billRepo.flush();
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

    private void assertApDrEquals(String glEntryUid, String expected) {
        Long apId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "2100")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("AP account 2100 not seeded"));
        var lines = journalLinesOf(glEntryUid);
        BigDecimal apDr = lines.stream()
                .filter(l -> apId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(apDr)
                .as("AP control (2100) DR must be %s", expected)
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    private void assertPurchasesCrEquals(String glEntryUid, String expected) {
        Long purchId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "5150")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Purchases account 5150 not seeded"));
        var lines = journalLinesOf(glEntryUid);
        BigDecimal purchCr = lines.stream()
                .filter(l -> purchId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(purchCr)
                .as("Purchases (5150) CR must be %s", expected)
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    // -- DN-sourced GL movement helpers ------------------------------------------------
    // Sum a given account across the debit-note's GL entries only (raise + apply-FX), so the
    // opening-balance entry — which also touches AP 2100 — does not pollute the assertion.
    private BigDecimal dnAccountCr(String code)    { return dnAccountMovement(code, false); }
    private BigDecimal dnAccountDr(String code)    { return dnAccountMovement(code, true);  }
    private BigDecimal dnAccountNetDr(String code) {
        return dnAccountMovement(code, true).subtract(dnAccountMovement(code, false));
    }

    private BigDecimal dnAccountMovement(String accountCode, boolean debit) {
        Long acctId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), accountCode)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account " + accountCode + " not seeded"));
        return journalEntryRepo.findByCompanyId(company.getId(), org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(e -> e.getSourceType() == JournalSourceType.AP_DEBIT_NOTE)
                .flatMap(e -> journalLineRepo.findByEntryIdOrderByLineNo(e.getId()).stream())
                .filter(l -> acctId.equals(l.getAccountId()))
                .map(l -> {
                    BigDecimal v = debit ? l.getDebitAmount() : l.getCreditAmount();
                    return v != null ? v : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
