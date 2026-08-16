package com.erp.modules.ap.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import org.springframework.jdbc.core.JdbcTemplate;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.cashbank.service.CashBankSeeder;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for ADR-0036 T3 — realized FX gain/loss on AP payment settlement.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Foreign bill (USD, invoice rate=2500) paid at a LOWER rate (2400) →
 *       REALIZED_FX_GAIN: we paid LESS base than booked → gain.</li>
 *   <li>Foreign bill (USD, invoice rate=2500) paid at a HIGHER rate (2600) →
 *       REALIZED_FX_LOSS: we paid MORE base than booked → loss.</li>
 *   <li>Base-currency payment (TZS, rate=1) → NO FX leg (no-regression, D-8).</li>
 * </ol>
 *
 * <p>AP math (mirror of AR, signs inverted — D-5):
 * <ul>
 *   <li>DR AP control  = base_relieved = face × bill_fx_rate</li>
 *   <li>CR Cash        = base_settled  = face × settlement_rate</li>
 *   <li>fxDelta        = base_settled − base_relieved</li>
 *   <li>positive delta → paid MORE base than booked → LOSS → DR REALIZED_FX_LOSS (5190)</li>
 *   <li>negative delta → paid LESS base than booked → GAIN → CR REALIZED_FX_GAIN (4920)</li>
 * </ul>
 */
class FxApPaymentSettlementIT extends PostgresIntegrationTest {

    @Autowired private ApPaymentService              paymentService;
    @Autowired private ApOpeningBalanceService        openingBalanceService;
    @Autowired private ApGlSeeder                    apGlSeeder;
    @Autowired private SupplierService               supplierService;
    @Autowired private SupplierBillRepository        billRepo;
    @Autowired private ApPaymentAllocationRepository allocationRepo;
    @Autowired private JournalEntryRepository        journalEntryRepo;
    @Autowired private JournalLineRepository         journalLineRepo;
    @Autowired private ChartOfAccountRepository      accountRepo;
    @Autowired private ChartOfAccountService         chartOfAccountService;
    @Autowired private FiscalCalendarService         fiscalCalendarService;
    @Autowired private GlConfigService               glConfigService;
    @Autowired private CashBankSeeder                cashBankSeeder;
    @Autowired private CurrencyRateRepository        rateRepo;
    @Autowired private JdbcTemplate                  jdbc;
    @Autowired private OrganisationRepository        organisations;
    @Autowired private CompanyRepository             companies;
    @Autowired private BranchRepository              branches;
    @Autowired private AppUserRepository             users;
    @Autowired private PasswordEncoder               passwordEncoder;
    @Autowired private IamTestData                   testData;

    private Company  company;
    private Branch   branch;
    private Long     rootId;
    private String   companyUid;
    private String   supplierUid;

    /** USD 1,000 face — the foreign bill amount. */
    private static final BigDecimal USD_1000  = new BigDecimal("1000");
    /** Original bill rate: 1 USD = TZS 2,500 → base payable = TZS 2,500,000. */
    private static final BigDecimal RATE_2500 = new BigDecimal("2500.00000000");
    /** Settlement rate (gain — USD cheaper at settlement): 1 USD = TZS 2,400. */
    private static final BigDecimal RATE_2400 = new BigDecimal("2400.00000000");
    /** Settlement rate (loss — USD dearer at settlement): 1 USD = TZS 2,600. */
    private static final BigDecimal RATE_2600 = new BigDecimal("2600.00000000");

    private static final String TZS = "TZS";
    private static final String USD = "USD";

    private static final LocalDate BILL_DATE    = LocalDate.of(2026, 1, 10);
    private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 2, 10);

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("FX AP Settlement IT Org"));
        company    = companies.save(new Company(org, "FXAP", "FX AP Settlement IT Co"));
        branch     = branches.save(new Branch(company, "FXAP1", "FX AP Settlement IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("fxap_root", passwordEncoder.encode("FxApR00t!Xx"), "FX AP Root");
        root.setRoot(true);
        root   = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "fxap_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: USD bill paid at lower rate → REALIZED_FX_GAIN (AP)
    // =========================================================================

    @Test
    void fxPayment_gainPath_realizedFxGainLinePosted_journalBalanced() {
        // Create a TZS opening-balance bill, then override to USD terms at rate 2500
        SupplierBillDto billDto = openingBalance("USD-BILL-GAIN-001", new BigDecimal("2500000"));

        // Override to USD 1,000 @ 2,500 using native SQL to bypass updatable=false columns
        // (currency, fx_rate, base_gross_amount, rate_at are all immutable in JPA — correct for
        // production; bypassed here to simulate what T2 would stamp on a real foreign bill).
        Long billId = billRepo.findByUid(billDto.uid()).orElseThrow().getId();
        jdbc.update("""
                UPDATE supplier_bills
                SET    currency = ?, fx_rate = ?, base_gross_amount = ?,
                       base_outstanding_amount = ?, outstanding_amount = ?, rate_at = NOW()
                WHERE  id = ?
                """, USD, RATE_2500, new BigDecimal("2500000"), new BigDecimal("2500000"),
                USD_1000, billId);
        billRepo.flush();

        // Settlement rate 2,400 < bill rate 2,500 → we pay LESS base than booked → GAIN
        seedRate(USD, TZS, RATE_2400, PAYMENT_DATE);

        ApPaymentDto payment = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, billDto.uid(), USD_1000, PAYMENT_DATE, "BANK_TRANSFER", null));

        assertThat(payment.glEntryUid()).isNotBlank();

        // Bill must be PAID
        SupplierBill refreshed = billRepo.findByUid(billDto.uid()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SupplierBillStatus.PAID);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        var entry = journalEntryRepo.findByUid(payment.glEntryUid()).orElseThrow();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());

        // Journal balanced in base
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr)
                .as("AP journal must be balanced in base currency (sacred Σbase invariant)")
                .isEqualByComparingTo(sumCr);

        // Must have REALIZED_FX_GAIN credit line (account 4920)
        Long fxGainId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "4920")
                .map(ChartOfAccount::getId).orElseThrow();
        boolean hasFxGainCredit = lines.stream()
                .anyMatch(l -> fxGainId.equals(l.getAccountId())
                            && l.getCreditAmount() != null
                            && l.getCreditAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(hasFxGainCredit)
                .as("REALIZED_FX_GAIN credit must be posted when settlement rate < bill rate (AP gain)")
                .isTrue();

        // FX gain = (2500 - 2400) × 1000 = TZS 100,000
        BigDecimal actualGain = lines.stream()
                .filter(l -> fxGainId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualGain).isEqualByComparingTo(new BigDecimal("100000"));

        // AP DR = base_relieved = 1000 × 2500 = 2,500,000
        Long apAccountId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "2100")
                .map(ChartOfAccount::getId).orElseThrow();
        BigDecimal apDr = lines.stream()
                .filter(l -> apAccountId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(apDr)
                .as("DR AP control = face × bill rate = 1000 × 2500 = 2,500,000 TZS")
                .isEqualByComparingTo(new BigDecimal("2500000"));

        // Cash CR = base_settled = 1000 × 2400 = 2,400,000
        // Compute as totalCR - gainCR (avoids hard-coding the cash account code which varies by seeder)
        BigDecimal gainCr = lines.stream()
                .filter(l -> fxGainId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cashCr = sumCr.subtract(gainCr);
        assertThat(cashCr)
                .as("CR Cash = face × settlement rate = 1000 × 2400 = 2,400,000 TZS")
                .isEqualByComparingTo(new BigDecimal("2400000"));

        // Allocation must have base_allocated_amount and settlement_rate stamped
        var allocs = allocationRepo.findByApPaymentId(payment.id());
        assertThat(allocs).hasSize(1);
        assertThat(allocs.get(0).getBaseAllocatedAmount())
                .isEqualByComparingTo(new BigDecimal("2400000"));
        assertThat(allocs.get(0).getSettlementRate()).isEqualByComparingTo(RATE_2400);
    }

    // =========================================================================
    // Bar 2: USD bill paid at higher rate → REALIZED_FX_LOSS (AP)
    // =========================================================================

    @Test
    void fxPayment_lossPath_realizedFxLossLinePosted_journalBalanced() {
        SupplierBillDto billDto = openingBalance("USD-BILL-LOSS-001", new BigDecimal("2500000"));

        Long billId = billRepo.findByUid(billDto.uid()).orElseThrow().getId();
        jdbc.update("""
                UPDATE supplier_bills
                SET    currency = ?, fx_rate = ?, base_gross_amount = ?,
                       base_outstanding_amount = ?, outstanding_amount = ?, rate_at = NOW()
                WHERE  id = ?
                """, USD, RATE_2500, new BigDecimal("2500000"), new BigDecimal("2500000"),
                USD_1000, billId);
        billRepo.flush();

        // Settlement rate 2,600 > bill rate 2,500 → we pay MORE base → LOSS
        seedRate(USD, TZS, RATE_2600, PAYMENT_DATE);

        ApPaymentDto payment = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, billDto.uid(), USD_1000, PAYMENT_DATE, "BANK_TRANSFER", null));

        var entry = journalEntryRepo.findByUid(payment.glEntryUid()).orElseThrow();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());

        // Journal balanced
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr).isEqualByComparingTo(sumCr);

        // Must have REALIZED_FX_LOSS debit line (account 5190)
        Long fxLossId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "5190")
                .map(ChartOfAccount::getId).orElseThrow();
        boolean hasFxLossDr = lines.stream()
                .anyMatch(l -> fxLossId.equals(l.getAccountId())
                            && l.getDebitAmount() != null
                            && l.getDebitAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(hasFxLossDr)
                .as("REALIZED_FX_LOSS debit must be posted when settlement rate > bill rate (AP loss)")
                .isTrue();

        // FX loss = (2600 - 2500) × 1000 = TZS 100,000
        BigDecimal actualLoss = lines.stream()
                .filter(l -> fxLossId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualLoss).isEqualByComparingTo(new BigDecimal("100000"));
    }

    // =========================================================================
    // Bar 3: Base-currency payment → NO FX leg (no-regression, D-8)
    // =========================================================================

    @Test
    void baseCurrencyPayment_noFxLeg_singleCurrencyByteIdentical() {
        BigDecimal amount = new BigDecimal("50000");
        SupplierBillDto billDto = openingBalance("TZS-BILL-NOFX-001", amount);

        ApPaymentDto payment = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, billDto.uid(), amount, LocalDate.now(), "CASH", null));

        var entry = journalEntryRepo.findByUid(payment.glEntryUid()).orElseThrow();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());

        // Must be exactly 2 lines: DR AP / CR Cash (no FX leg)
        assertThat(lines)
                .as("Base-currency payment must post exactly 2 lines (DR AP + CR Cash), no FX leg (D-8)")
                .hasSize(2);

        // Journal balanced
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr).isEqualByComparingTo(sumCr).isEqualByComparingTo(amount);

        // No FX gain or loss account must appear (accounts 4920 / 5190)
        Long fxGainId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "4920")
                .map(ChartOfAccount::getId).orElseThrow();
        Long fxLossId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "5190")
                .map(ChartOfAccount::getId).orElseThrow();
        boolean hasFxLine = lines.stream()
                .anyMatch(l -> fxGainId.equals(l.getAccountId()) || fxLossId.equals(l.getAccountId()));
        assertThat(hasFxLine)
                .as("No FX gain/loss line must appear in a base-currency AP payment (D-8)")
                .isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SupplierBillDto openingBalance(String invoiceRef, BigDecimal amount) {
        return openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, TZS,
                BILL_DATE, BILL_DATE.plusDays(30), invoiceRef));
    }

    private void seedRate(String from, String to, BigDecimal rate, LocalDate effectiveDate) {
        CurrencyRate row = new CurrencyRate(
                company.getId(), branch.getId(),
                from, to, rate, effectiveDate,
                "SPOT", "IT-seed", rootId);
        rateRepo.save(row);
    }
}
