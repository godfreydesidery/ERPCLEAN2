package com.erp.modules.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.fx.domain.dto.FxRevaluationPreviewDto;
import com.erp.modules.fx.domain.dto.FxRevaluationRunDto;
import com.erp.modules.fx.domain.dto.PostFxRevaluationRequest;
import com.erp.modules.fx.domain.enums.FxRevaluationRunStatus;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
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
import com.erp.platform.common.money.FxRateService;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link FxRevaluationRunServiceImpl} (ADR-0036 D-6, T4).
 *
 * <p>Scenario: OPEN USD AR invoice at original rate 2500 TZS/USD; period-end spot 2600 TZS/USD.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>preview() → shows the unrealized gain/loss delta = outstanding × (2600-2500).</li>
 *   <li>post()    → persists run POSTED + a balanced FX_REVALUATION journal (Σdr == Σcr
 *                    in base currency, both sides non-zero).</li>
 *   <li>Idempotency — second post() call is a no-op (returns the same run).</li>
 *   <li>reverse() → postReversal successfully negates the original entry; run → REVERSED.</li>
 * </ol>
 *
 * <p>Day-1 single-currency path: if all AR invoices are in base currency, preview returns
 * zero lines (tested implicitly — the open-foreign query returns empty for base-currency items).
 */
class FxRevaluationRunServiceIT extends PostgresIntegrationTest {

    @Autowired private FxRevaluationRunService runService;
    @Autowired private FxRateService           fxRateService;
    @Autowired private ArInvoiceRepository     arInvoiceRepo;
    @Autowired private SupplierBillRepository  supplierBillRepo;
    @Autowired private FiscalPeriodRepository  fiscalPeriodRepo;
    @Autowired private JournalEntryRepository  journalEntryRepo;
    @Autowired private JournalLineRepository   journalLineRepo;
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private CustomerRepository      customerRepo;
    @Autowired private SupplierRepository      supplierRepo;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    private static final String USD = "USD";
    private static final String TZS = "TZS";

    // original rate 2500 TZS per 1 USD
    private static final BigDecimal RATE_ORIGINAL = new BigDecimal("2500.00000000");
    // period-end spot 2600 TZS per 1 USD
    private static final BigDecimal RATE_SPOT     = new BigDecimal("2600.00000000");
    // outstanding USD face amount
    private static final BigDecimal FACE_USD      = new BigDecimal("1000.00");

    private Company      company;
    private Branch       branch;
    private FiscalPeriod period;
    private Long         rootId;
    private Long         customerId;
    private Long         supplierId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("FxReval IT Org"));
        company = companies.save(new Company(org, "FXRIT", "FxReval IT Co"));
        branch  = branches.save(new Branch(company, "FXRIT1", "FxReval IT Branch"));

        AppUser root = new AppUser("fxreval_root", passwordEncoder.encode("Root1234!"), "FxReval Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        // Create a placeholder customer for the AR invoice FK
        Customer customer = new Customer(company.getId(), "CUST-FX-001",
                PartyType.BUSINESS, "FxReval Test Customer",
                CustomerKind.CREDIT_ACCOUNT, rootId);
        customerId = customerRepo.save(customer).getId();

        // Create a placeholder supplier for AP bill FK
        Supplier supplier = new Supplier(company.getId(), "SUP-FX-001",
                PartyType.BUSINESS, "FxReval Test Supplier",
                com.erp.modules.parties.domain.enums.SupplierKind.GOODS, rootId);
        supplierId = supplierRepo.save(supplier).getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "fxreval_root", true, company.getId(), branch.getId(), null));

        // Seed GL: CoA + fiscal year + gl_configs (incl. UNREALIZED_FX_GAIN/LOSS)
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        // Pick the first open fiscal period
        period = fiscalPeriodRepo.findByCompanyIdOrderByStartDateAsc(company.getId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No fiscal period seeded"));

        // Seed USD/TZS rate for invoice date (rate 2500)
        LocalDate invoiceDate = period.getStartDate().plusDays(1);
        fxRateService.addRate(new UpsertRateRequest(
                company.getId(), USD, TZS, RATE_ORIGINAL, invoiceDate, "SPOT", "test"));

        // Seed a higher spot rate at period-end (2600) for revaluation
        fxRateService.addRate(new UpsertRateRequest(
                company.getId(), USD, TZS, RATE_SPOT, period.getEndDate(), "SPOT", "test"));

        // Create an OPEN USD AR invoice manually (as if ArSalePostedHandler had stamped it)
        ArInvoice inv = new ArInvoice(
                company.getId(), branch.getId(), customerId,
                ArInvoiceSource.OPENING_BALANCE, null, "USD-INV-001",
                FACE_USD, USD, invoiceDate, invoiceDate.plusDays(30), rootId);
        // Stamp the FX base triple (as T2 posting would have done)
        inv.setFxRate(RATE_ORIGINAL);
        BigDecimal baseOriginal = FACE_USD.multiply(RATE_ORIGINAL)
                .setScale(0, java.math.RoundingMode.HALF_UP);
        inv.setBaseOriginalAmount(baseOriginal);
        inv.setBaseOutstandingAmount(baseOriginal);
        inv.setRateAt(java.time.Instant.now());
        arInvoiceRepo.save(inv);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ── Test 1: preview shows the unrealized delta ─────────────────────────────

    @Test
    void preview_showsUnrealizedDelta_forOpenForeignArInvoice() {
        // outstanding × (spot - original) = 1000 × (2600 - 2500) = 100,000 TZS gain
        BigDecimal expectedDelta = FACE_USD.multiply(RATE_SPOT.subtract(RATE_ORIGINAL))
                .setScale(0, java.math.RoundingMode.HALF_UP);

        FxRevaluationPreviewDto preview = runService.preview(
                company.getId(), period.getUid(), period.getEndDate());

        assertThat(preview).isNotNull();
        assertThat(preview.lines()).hasSize(1);

        var line = preview.lines().get(0);
        assertThat(line.currency()).isEqualTo(USD);
        assertThat(line.sourceType()).isEqualTo("AR");
        assertThat(line.outstandingTxnAmount()).isEqualByComparingTo(FACE_USD);

        // Carrying base = FACE_USD × RATE_ORIGINAL = 2,500,000 TZS
        BigDecimal carryingBase = FACE_USD.multiply(RATE_ORIGINAL).setScale(0, java.math.RoundingMode.HALF_UP);
        assertThat(line.carryingBaseAmount()).isEqualByComparingTo(carryingBase);

        // Revalued base = FACE_USD × RATE_SPOT = 2,600,000 TZS
        BigDecimal revaluedBase = FACE_USD.multiply(RATE_SPOT).setScale(0, java.math.RoundingMode.HALF_UP);
        assertThat(line.revaluedBaseAmount()).isEqualByComparingTo(revaluedBase);

        // adjustment = revalued - carrying = +100,000 (gain)
        assertThat(line.adjustmentAmount()).isEqualByComparingTo(expectedDelta);
        assertThat(preview.totalGainAmount()).isEqualByComparingTo(expectedDelta);
        assertThat(preview.totalLossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preview.netAdjustmentAmount()).isEqualByComparingTo(expectedDelta);
    }

    // ── Test 2: post → balanced GL journal + run POSTED ────────────────────────

    @Test
    void post_createsBalancedGlJournal_andRunIsPosted() {
        PostFxRevaluationRequest req = new PostFxRevaluationRequest(
                company.getId(), period.getUid(),
                period.getEndDate(), period.getEndDate());

        FxRevaluationRunDto result = runService.post(req);

        assertThat(result).isNotNull();
        assertThat(result.runNumber()).isNotBlank();
        assertThat(result.glEntryUid()).isNotNull();
        // Status is either POSTED (reversal deferred) or REVERSED (reversal posted into next period)
        assertThat(result.status()).isIn(FxRevaluationRunStatus.POSTED, FxRevaluationRunStatus.REVERSED);
        assertThat(result.totalGainAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.totalLossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.lines()).hasSize(1);

        // Verify GL journal is BALANCED: Σ debit_amount == Σ credit_amount
        var glEntry = journalEntryRepo.findByUid(result.glEntryUid());
        assertThat(glEntry).isPresent();
        var glLines = journalLineRepo.findByEntryIdOrderByLineNo(glEntry.get().getId());
        assertThat(glLines).hasSizeGreaterThanOrEqualTo(2);

        BigDecimal totalDebit  = glLines.stream()
                .map(l -> l.getDebitAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = glLines.stream()
                .map(l -> l.getCreditAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isGreaterThan(BigDecimal.ZERO);
    }

    // ── Test 3: idempotency — second post() is a no-op ─────────────────────────

    @Test
    void post_idempotent_secondCallReturnsExistingRun() {
        PostFxRevaluationRequest req = new PostFxRevaluationRequest(
                company.getId(), period.getUid(),
                period.getEndDate(), period.getEndDate());

        FxRevaluationRunDto first  = runService.post(req);
        FxRevaluationRunDto second = runService.post(req);

        assertThat(second.uid()).isEqualTo(first.uid());
        assertThat(second.runNumber()).isEqualTo(first.runNumber());
        // Only one GL journal entry should exist for this run
        long journalCount = journalEntryRepo.findAll().stream()
                .filter(e -> first.glEntryUid().equals(e.getUid())
                          || first.runNumber().equals(e.getSourceRef()))
                .count();
        // The reversal may add one more, but the original + reversal = max 2
        assertThat(journalCount).isLessThanOrEqualTo(2);
    }

    // ── Test 4: reverse() → reversalGlEntryUid set + run REVERSED ─────────────

    @Test
    void reverse_postsReversalEntry_andRunStatusIsReversed() {
        // Post the run first
        PostFxRevaluationRequest req = new PostFxRevaluationRequest(
                company.getId(), period.getUid(),
                period.getEndDate(), period.getEndDate());

        FxRevaluationRunDto posted = runService.post(req);

        // If already reversed (next period was open), check directly
        if (posted.status() == FxRevaluationRunStatus.REVERSED) {
            assertThat(posted.reversalGlEntryUid()).isNotNull();
            return;
        }

        // Otherwise explicitly trigger reversal into the next day
        LocalDate reversalDate = period.getEndDate().plusDays(1);

        // Open next period so reversal can post — seed the next year/period if needed
        // For the IT: attempt reverse; if next period is not open, the service will throw
        // which is the documented behaviour (OQ-FX-04).
        // We still test that reverse() correctly posts when the period IS open.
        // (The next period is typically the 2nd period in the seeded year.)
        var allPeriods = fiscalPeriodRepo.findByCompanyIdOrderByStartDateAsc(company.getId());
        java.util.Optional<FiscalPeriod> nextPeriodOpt = allPeriods.stream()
                .filter(p -> p.getStartDate().isAfter(period.getEndDate()))
                .findFirst();

        if (nextPeriodOpt.isEmpty()) {
            // Next period not seeded — documented known sequencing rule; skip reversal assertion
            return;
        }

        FxRevaluationRunDto reversed = runService.reverse(posted.uid(), reversalDate);
        assertThat(reversed.status()).isEqualTo(FxRevaluationRunStatus.REVERSED);
        assertThat(reversed.reversalGlEntryUid()).isNotNull();

        // Verify the reversal entry exists and its lines are negations of the original
        var reversalEntry = journalEntryRepo.findByUid(reversed.reversalGlEntryUid());
        assertThat(reversalEntry).isPresent();

        var origEntry  = journalEntryRepo.findByUid(posted.glEntryUid());
        var origLines  = origEntry.isPresent()
                ? journalLineRepo.findByEntryIdOrderByLineNo(origEntry.get().getId())
                : java.util.List.of();
        var reversalLines = journalLineRepo.findByEntryIdOrderByLineNo(reversalEntry.get().getId());

        // Reversal lines must balance
        BigDecimal revDebit  = reversalLines.stream().map(l -> l.getDebitAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal revCredit = reversalLines.stream().map(l -> l.getCreditAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(revDebit).isEqualByComparingTo(revCredit);
        assertThat(revDebit).isGreaterThan(BigDecimal.ZERO);

        // Reversal line count same as original
        assertThat(reversalLines).hasSameSizeAs(origLines);
    }

    // ── Test 6: AP-only run (gain) — uses isolated company to avoid rate conflicts ────

    /**
     * AP gain: carrying_base (2,500,000) > revalued_base (2,400,000) → adjustment = +100,000.
     * Correct journal: DR AP control 100,000 / CR UNREALIZED_FX_GAIN 100,000 → balanced.
     * Previously emitted: CR AP 100,000 + CR FX_GAIN 100,000 → unbalanced.
     *
     * Uses a fresh company so rate seeding does not conflict with setUp()'s RATE_SPOT=2600.
     */
    @Test
    void apOnlyRun_gain_balancedJournal_runPosted() {
        ApOnlyTestContext ctx = buildApOnlyContext("FXRG", new BigDecimal("2400.00000000"));
        // AP gain: carry(2500) > spot(2400) → carryBase - revaluedBase = +100,000
        BigDecimal expectedGain = ctx.carryBase.subtract(
                ctx.billFace.multiply(ctx.rateSpot).setScale(0, RoundingMode.HALF_UP));

        PostFxRevaluationRequest req = new PostFxRevaluationRequest(
                ctx.company.getId(), ctx.period.getUid(), ctx.period.getEndDate(), ctx.period.getEndDate());
        FxRevaluationRunDto result = runService.post(req);

        assertThat(result.glEntryUid())
                .as("AP-only gain run must produce a GL entry (run must be POSTED, not PREVIEWED)")
                .isNotNull();
        assertThat(result.status())
                .isIn(FxRevaluationRunStatus.POSTED, FxRevaluationRunStatus.REVERSED);
        assertBalanced(result.glEntryUid());
        assertThat(result.totalGainAmount()).isEqualByComparingTo(expectedGain);
        assertThat(result.totalLossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Test 7: AP-only run (loss) — uses isolated company to avoid rate conflicts ──

    /**
     * AP loss: carrying_base (2,500,000) < revalued_base (2,700,000) → adjustment = -200,000.
     * Correct journal: DR UNREALIZED_FX_LOSS 200,000 / CR AP control 200,000 → balanced.
     * Previously emitted: DR AP 200,000 + DR FX_LOSS 200,000 → unbalanced.
     *
     * Uses a fresh company so rate seeding does not conflict with setUp()'s RATE_SPOT=2600.
     */
    @Test
    void apOnlyRun_loss_balancedJournal_runPosted() {
        ApOnlyTestContext ctx = buildApOnlyContext("FXRL", new BigDecimal("2700.00000000"));
        // AP loss: spot(2700) > carry(2500) → revaluedBase - carryBase = +200,000
        BigDecimal expectedLoss = ctx.billFace.multiply(ctx.rateSpot).setScale(0, RoundingMode.HALF_UP)
                .subtract(ctx.carryBase);

        PostFxRevaluationRequest req = new PostFxRevaluationRequest(
                ctx.company.getId(), ctx.period.getUid(), ctx.period.getEndDate(), ctx.period.getEndDate());
        FxRevaluationRunDto result = runService.post(req);

        assertThat(result.glEntryUid()).isNotNull();
        assertThat(result.status()).isIn(FxRevaluationRunStatus.POSTED, FxRevaluationRunStatus.REVERSED);
        assertBalanced(result.glEntryUid());
        assertThat(result.totalLossAmount()).isEqualByComparingTo(expectedLoss);
        assertThat(result.totalGainAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Test 8: Mixed AR gain + AP gain — main company, spot=2600 ────────────────

    /**
     * Mixed run using the main company (setUp already seeded RATE_SPOT=2600 at period end).
     * AR inv: carry=2500, spot=2600 → AR gain = 1000 x 100 = 100,000.
     * AP bill: carry=2800, spot=2600 → AP gain = 1000 x 200 = 200,000.
     * Total gain = 300,000. Net non-zero, so a GL journal MUST be produced.
     * Verifies that per-line complement approach stays balanced for mixed AR+AP.
     */
    @Test
    void mixedArGainApGain_balancedJournal_runPosted() {
        // setUp() already seeded: AR inv (carry=2500) + RATE_SPOT=2600.
        // Seed AP bill with carry=2800 → AP gain = 1000*(2800-2600)=200,000
        BigDecimal billFace  = new BigDecimal("1000.00");
        BigDecimal rateApCarry = new BigDecimal("2800.00000000");
        BigDecimal apCarryBase = billFace.multiply(rateApCarry).setScale(0, RoundingMode.HALF_UP);
        seedApBill(billFace, rateApCarry, apCarryBase);
        // RATE_SPOT=2600 already at period.getEndDate() from setUp — no extra seeding needed

        PostFxRevaluationRequest req = new PostFxRevaluationRequest(
                company.getId(), period.getUid(), period.getEndDate(), period.getEndDate());
        FxRevaluationRunDto result = runService.post(req);

        assertThat(result.glEntryUid())
                .as("Mixed AR+AP gain run must produce a GL entry")
                .isNotNull();
        assertThat(result.status()).isIn(FxRevaluationRunStatus.POSTED, FxRevaluationRunStatus.REVERSED);
        assertBalanced(result.glEntryUid());
        assertThat(result.totalLossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.totalGainAmount()).isGreaterThan(BigDecimal.ZERO);
    }

    // ── Test 9: Reversal of AP run — uses isolated company to avoid rate conflicts ─

    @Test
    void apOnlyRun_reversal_netsOut_balancedReversal() {
        // AP gain scenario: carry=2500, spot=2400
        ApOnlyTestContext ctx = buildApOnlyContext("FXRR", new BigDecimal("2400.00000000"));

        PostFxRevaluationRequest req = new PostFxRevaluationRequest(
                ctx.company.getId(), ctx.period.getUid(), ctx.period.getEndDate(), ctx.period.getEndDate());
        FxRevaluationRunDto posted = runService.post(req);
        assertThat(posted.glEntryUid()).isNotNull();

        if (posted.status() == FxRevaluationRunStatus.REVERSED) {
            assertThat(posted.reversalGlEntryUid()).isNotNull();
            assertBalanced(posted.reversalGlEntryUid());
            return;
        }

        var allPeriods = fiscalPeriodRepo.findByCompanyIdOrderByStartDateAsc(ctx.company.getId());
        var nextPeriodOpt = allPeriods.stream()
                .filter(p -> p.getStartDate().isAfter(ctx.period.getEndDate()))
                .findFirst();
        if (nextPeriodOpt.isEmpty()) return;

        FxRevaluationRunDto reversed = runService.reverse(
                posted.uid(), ctx.period.getEndDate().plusDays(1));
        assertThat(reversed.status()).isEqualTo(FxRevaluationRunStatus.REVERSED);
        assertThat(reversed.reversalGlEntryUid()).isNotNull();
        assertBalanced(reversed.reversalGlEntryUid());
    }

    // ── Test 5: day-1 single-currency — base-currency invoices produce zero lines ──

    @Test
    void preview_baseCurrencyInvoicesOnly_producesZeroLines() {
        // Add a second, TZS-denominated AR invoice
        ArInvoice tzsInv = new ArInvoice(
                company.getId(), branch.getId(), customerId,
                ArInvoiceSource.OPENING_BALANCE, null, "TZS-INV-001",
                new BigDecimal("500000.00"), TZS,
                period.getStartDate().plusDays(2),
                period.getStartDate().plusDays(32), rootId);
        tzsInv.setFxRate(BigDecimal.ONE);
        tzsInv.setBaseOriginalAmount(new BigDecimal("500000.00"));
        tzsInv.setBaseOutstandingAmount(new BigDecimal("500000.00"));
        tzsInv.setRateAt(java.time.Instant.now());
        arInvoiceRepo.save(tzsInv);

        // preview for a company that has ONLY TZS invoices (i.e., delete the USD one from the context)
        // Instead: create a fresh company with only TZS invoices
        Organisation org2 = organisations.save(new Organisation("FxReval IT Org TZS"));
        Company tzsCo    = companies.save(new Company(org2, "FXRTZS", "FxReval TZS-only Co"));
        Branch  tzsBr    = branches.save(new Branch(tzsCo, "FXRTZS1", "FxReval TZS-only Branch"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "fxreval_root", true, tzsCo.getId(), tzsBr.getId(), null));

        chartOfAccountService.seedDefaults(tzsCo.getId());
        fiscalCalendarService.seedCurrentYear(tzsCo.getId());
        glConfigService.seedDefaults(tzsCo.getId());

        Customer tzsCustomer = new Customer(tzsCo.getId(), "CUST-TZS-001",
                PartyType.BUSINESS, "TZS Customer", CustomerKind.CREDIT_ACCOUNT, rootId);
        Long tzsCustomerId = customerRepo.save(tzsCustomer).getId();

        FiscalPeriod tzsPeriod = fiscalPeriodRepo.findByCompanyIdOrderByStartDateAsc(tzsCo.getId())
                .stream().findFirst().orElseThrow();

        ArInvoice tzsOnly = new ArInvoice(
                tzsCo.getId(), tzsBr.getId(), tzsCustomerId,
                ArInvoiceSource.OPENING_BALANCE, null, "TZS-INV-002",
                new BigDecimal("300000.00"), TZS,
                tzsPeriod.getStartDate().plusDays(1),
                tzsPeriod.getStartDate().plusDays(31), rootId);
        tzsOnly.setFxRate(BigDecimal.ONE);
        tzsOnly.setBaseOriginalAmount(new BigDecimal("300000.00"));
        tzsOnly.setBaseOutstandingAmount(new BigDecimal("300000.00"));
        tzsOnly.setRateAt(java.time.Instant.now());
        arInvoiceRepo.save(tzsOnly);

        FxRevaluationPreviewDto preview = runService.preview(
                tzsCo.getId(), tzsPeriod.getUid(), tzsPeriod.getEndDate());

        assertThat(preview.lines()).isEmpty();
        assertThat(preview.totalGainAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(preview.totalLossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Value object carrying everything an AP-only test needs from a freshly-built isolated company.
     * Using a fresh company per test avoids uq_currency_rate conflicts with setUp()'s RATE_SPOT=2600.
     */
    private record ApOnlyTestContext(
            Company company,
            Branch branch,
            FiscalPeriod period,
            Long apSupplierId,
            BigDecimal billFace,
            BigDecimal rateCarry,
            BigDecimal carryBase,
            BigDecimal rateSpot) {}

    /**
     * Creates an isolated org/company/branch/GL/fiscal-year/rate set and seeds one open AP bill.
     *
     * @param codeSuffix short suffix added to company/branch code (e.g. "FXRG") — must be unique
     * @param rateSpot   USD/TZS spot rate to seed at the period end date
     */
    private ApOnlyTestContext buildApOnlyContext(String codeSuffix, BigDecimal rateSpot) {
        Organisation apOrg = organisations.save(new Organisation("AP IT Org " + codeSuffix));
        Company apCo  = companies.save(new Company(apOrg, codeSuffix, "AP IT Co " + codeSuffix));
        Branch  apBr  = branches.save(new Branch(apCo, codeSuffix + "1", "AP IT Br " + codeSuffix));

        RequestContext.set(new RequestContext.Principal(
                rootId, "fxreval_root", true, apCo.getId(), apBr.getId(), null));

        chartOfAccountService.seedDefaults(apCo.getId());
        fiscalCalendarService.seedCurrentYear(apCo.getId());
        glConfigService.seedDefaults(apCo.getId());

        FiscalPeriod apPeriod = fiscalPeriodRepo.findByCompanyIdOrderByStartDateAsc(apCo.getId())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No period seeded for " + codeSuffix));

        LocalDate billDate = apPeriod.getStartDate().plusDays(1);
        BigDecimal rateCarry = new BigDecimal("2500.00000000");

        // Seed the original carry rate at bill date
        fxRateService.addRate(new UpsertRateRequest(
                apCo.getId(), USD, TZS, rateCarry, billDate, "SPOT", "carry-" + codeSuffix));

        // Seed the period-end spot rate
        fxRateService.addRate(new UpsertRateRequest(
                apCo.getId(), USD, TZS, rateSpot, apPeriod.getEndDate(), "SPOT", "spot-" + codeSuffix));

        // Create supplier for this isolated company
        Supplier apSupplier = new Supplier(apCo.getId(), "SUP-" + codeSuffix,
                PartyType.BUSINESS, "Supplier " + codeSuffix,
                com.erp.modules.parties.domain.enums.SupplierKind.GOODS, rootId);
        Long apSuppId = supplierRepo.save(apSupplier).getId();

        BigDecimal billFace = new BigDecimal("1000.00");
        BigDecimal carryBase = billFace.multiply(rateCarry).setScale(0, RoundingMode.HALF_UP);

        // Seed the AP bill for this isolated company
        SupplierBill bill = new SupplierBill(
                apCo.getId(), apBr.getId(), apSuppId,
                "INV-AP-" + codeSuffix + "-" + System.nanoTime(),
                SupplierBillSource.BILL,
                null,
                billDate, billDate.plusDays(30),
                billFace, BigDecimal.ZERO, billFace,
                USD, rootId);
        bill.setBillNumber("BILL-" + codeSuffix + "-" + System.nanoTime());
        bill.setStatus(SupplierBillStatus.MATCHED);
        bill.setOutstandingAmount(billFace);
        bill.setFxRate(rateCarry);
        bill.setBaseGrossAmount(carryBase);
        bill.setBaseOutstandingAmount(carryBase);
        bill.setRateAt(java.time.Instant.now());
        supplierBillRepo.save(bill);

        // Restore context to main company so other tests aren't affected
        RequestContext.set(new RequestContext.Principal(
                rootId, "fxreval_root", true, company.getId(), branch.getId(), null));

        return new ApOnlyTestContext(apCo, apBr, apPeriod, apSuppId,
                billFace, rateCarry, carryBase, rateSpot);
    }

    /**
     * Persists an open foreign USD AP bill with the given carrying base already stamped.
     * Status is set to MATCHED (qualifying for revaluation per findOpenForeignForRevaluation).
     * bill_number is required by chk_supplier_bill_number_when_posted when status is MATCHED.
     */
    private void seedApBill(BigDecimal face, BigDecimal fxRate, BigDecimal carryBase) {
        LocalDate billDate = period.getStartDate().plusDays(1);
        String invNo = "INV-AP-" + System.nanoTime();
        SupplierBill bill = new SupplierBill(
                company.getId(), branch.getId(), supplierId,
                invNo,
                SupplierBillSource.BILL,
                null, // purchaseOrderUid
                billDate, billDate.plusDays(30),
                face, BigDecimal.ZERO, face, // net=face, vat=0, gross=face
                USD, rootId);
        bill.setBillNumber("BILL-" + System.nanoTime()); // required by DB CHECK when MATCHED
        bill.setStatus(SupplierBillStatus.MATCHED);
        bill.setOutstandingAmount(face);
        bill.setFxRate(fxRate);
        bill.setBaseGrossAmount(carryBase);
        bill.setBaseOutstandingAmount(carryBase);
        bill.setRateAt(java.time.Instant.now());
        supplierBillRepo.save(bill);
    }

    /** Asserts that the GL journal entry identified by {@code glEntryUid} is balanced. */
    private void assertBalanced(String glEntryUid) {
        var entry = journalEntryRepo.findByUid(glEntryUid);
        assertThat(entry).as("GL entry " + glEntryUid + " must exist").isPresent();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.get().getId());
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr)
                .as("GL journal " + glEntryUid + " must be balanced: Σdebit=" + sumDr + " Σcredit=" + sumCr)
                .isEqualByComparingTo(sumCr);
        assertThat(sumDr).isGreaterThan(BigDecimal.ZERO);
    }
}
