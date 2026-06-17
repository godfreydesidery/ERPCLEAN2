package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.domain.enums.ArReceiptStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptAllocationRepository;
import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import com.erp.modules.gl.service.ChartOfAccountService;
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
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.sales.service.TaxRateSeeder;
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
 * Integration tests for ADR-0036 T3 — realized FX gain/loss on AR receipt settlement.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Foreign invoice (USD, rate=2500) settled at a DIFFERENT rate (2600) →
 *       a REALIZED_FX_GAIN line posts for the exact rate-delta, journal balances in base,
 *       invoice fully cleared (PAID, outstanding=0).</li>
 *   <li>Foreign invoice (USD, rate=2500) settled at a HIGHER rate (2400 — i.e. USD worth less now) →
 *       a REALIZED_FX_LOSS line posts.</li>
 *   <li>Base-currency settlement (TZS → TZS, rate=1) posts NO FX leg (no-regression,
 *       single-currency byte-identical path D-8).</li>
 * </ol>
 */
class FxArReceiptSettlementIT extends PostgresIntegrationTest {

    @Autowired private ArReceiptService              receiptService;
    @Autowired private ArOpeningBalanceService        openingBalanceService;
    @Autowired private ArGlSeeder                    arGlSeeder;
    @Autowired private CustomerService               customerService;
    @Autowired private TaxRateSeeder                 taxRateSeeder;
    @Autowired private ArInvoiceRepository           arInvoiceRepo;
    @Autowired private ArReceiptAllocationRepository allocationRepo;
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
    private String   customerUid;

    /** USD 1,000 face — the foreign invoice amount. */
    private static final BigDecimal USD_1000  = new BigDecimal("1000");
    /** Original invoice rate: 1 USD = TZS 2,500 → base = TZS 2,500,000. */
    private static final BigDecimal RATE_2500 = new BigDecimal("2500.00000000");
    /** Settlement rate (gain): 1 USD = TZS 2,600 → base cash = TZS 2,600,000. */
    private static final BigDecimal RATE_2600 = new BigDecimal("2600.00000000");
    /** Settlement rate (loss): 1 USD = TZS 2,400 → base cash = TZS 2,400,000. */
    private static final BigDecimal RATE_2400 = new BigDecimal("2400.00000000");

    private static final String TZS = "TZS";
    private static final String USD = "USD";

    /** Invoice date — the original booking date. */
    private static final LocalDate INVOICE_DATE   = LocalDate.of(2026, 1, 10);
    /** Settlement date — rate may differ. */
    private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 2, 10);

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("FX AR Settlement IT Org"));
        company    = companies.save(new Company(org, "FXAR", "FX AR Settlement IT Co"));
        branch     = branches.save(new Branch(company, "FXAR1", "FX AR Settlement IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("fxar_root", passwordEncoder.encode("FxArR00t!Xx"), "FX AR Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "fxar_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());

        var custDto = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        customerUid = custDto.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: Foreign invoice settled at higher rate → REALIZED_FX_GAIN
    // =========================================================================

    @Test
    void fxSettlement_gainPath_realizedFxGainLinePosted_journalBalanced() {
        // 1. Create an opening-balance AR invoice in TZS but then stamp fxRate=2500 to simulate
        //    a USD invoice booked at the invoice rate.
        //    Face = USD 1,000. base_original = USD 1,000 × 2,500 = TZS 2,500,000.
        //    (We use TZS 2,500 as the face to keep openingBalance simple, then override fx_rate.)
        var invDto = openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid,
                new BigDecimal("2500000"), TZS, // face in TZS for OB — but we rewrite to USD below
                INVOICE_DATE, INVOICE_DATE.plusDays(30), "USD-INV-001"));

        // Override the invoice to be USD 1,000 @ 2,500 using native SQL to bypass updatable=false
        // (fx_rate, base_original_amount, rate_at are immutable in JPA — correct for production;
        // bypassed here to simulate what T2 would stamp on a real foreign sale invoice).
        Long invId = arInvoiceRepo.findByUid(invDto.uid()).orElseThrow().getId();
        jdbc.update("""
                UPDATE ar_invoices
                SET    fx_rate = ?, base_original_amount = ?, base_outstanding_amount = ?,
                       outstanding_amount = ?, rate_at = NOW()
                WHERE  id = ?
                """, RATE_2500, new BigDecimal("2500000"), new BigDecimal("2500000"),
                USD_1000, invId);
        // Evict from first-level cache so the service re-reads from DB
        arInvoiceRepo.flush();

        // 2. Seed a settlement rate for USD→TZS on the settlement date at 2,600 (higher → gain)
        seedRate(USD, TZS, RATE_2600, SETTLEMENT_DATE);

        // 3. Record receipt: USD 1,000 at settlement rate 2,600 → base cash = TZS 2,600,000
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid,
                USD_1000, USD, SETTLEMENT_DATE, "BANK_TRANSFER", "FX-GAIN-REF", List.of()));

        // 4. Assertions — allocation
        assertThat(receipt.status()).isEqualTo(ArReceiptStatus.ALLOCATED);
        assertThat(receipt.unallocatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receipt.allocations()).hasSize(1);
        assertThat(receipt.allocations().get(0).allocatedAmount())
                .isEqualByComparingTo(USD_1000);

        // Invoice must be PAID
        ArInvoice refreshed = arInvoiceRepo.findByUid(invDto.uid()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(ArInvoiceStatus.PAID);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // 5. GL assertions
        assertThat(receipt.glEntryUid()).isNotBlank();
        var entry = journalEntryRepo.findByUid(receipt.glEntryUid()).orElseThrow();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());

        // Journal must be balanced in base (Σ DR == Σ CR)
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr)
                .as("Journal must be balanced in base currency (sacred Σbase invariant)")
                .isEqualByComparingTo(sumCr);

        // There must be a REALIZED_FX_GAIN credit line (account 4920 per DEFAULT_ACCOUNTS)
        Long fxGainAccountId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "4920")
                .map(ChartOfAccount::getId).orElseThrow();
        boolean hasFxGainCredit = lines.stream()
                .anyMatch(l -> fxGainAccountId.equals(l.getAccountId())
                            && l.getCreditAmount() != null
                            && l.getCreditAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(hasFxGainCredit)
                .as("A REALIZED_FX_GAIN credit line must be posted when settlement rate > invoice rate")
                .isTrue();

        // FX gain amount = (2600 - 2500) × 1000 = TZS 100,000
        BigDecimal expectedFxGain = new BigDecimal("100000");
        BigDecimal actualFxGain = lines.stream()
                .filter(l -> fxGainAccountId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualFxGain)
                .as("FX gain = (settlement_rate - invoice_rate) × face = (2600-2500) × 1000 = 100,000 TZS")
                .isEqualByComparingTo(expectedFxGain);

        // Cash DR must equal base cash = USD 1,000 × 2,600 = TZS 2,600,000
        Long cashAccountId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(ChartOfAccount::getId).orElseThrow();
        BigDecimal cashDr = lines.stream()
                .filter(l -> cashAccountId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(cashDr)
                .as("Cash DR = USD 1,000 × settlement_rate 2,600 = TZS 2,600,000")
                .isEqualByComparingTo(new BigDecimal("2600000"));

        // AR CR must equal base relieved = USD 1,000 × 2,500 = TZS 2,500,000
        Long arAccountId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "1200")
                .map(ChartOfAccount::getId).orElseThrow();
        BigDecimal arCr = lines.stream()
                .filter(l -> arAccountId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(arCr)
                .as("AR CR = USD 1,000 × invoice_rate 2,500 = TZS 2,500,000")
                .isEqualByComparingTo(new BigDecimal("2500000"));

        // Allocation must have base_allocated_amount and settlement_rate stamped
        var allocations = allocationRepo.findByReceiptId(
                receiptService.getByUid(receipt.uid()).id());
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).getBaseAllocatedAmount())
                .isEqualByComparingTo(new BigDecimal("2600000"));
        assertThat(allocations.get(0).getSettlementRate())
                .isEqualByComparingTo(RATE_2600);
    }

    // =========================================================================
    // Bar 2: Foreign invoice settled at lower rate → REALIZED_FX_LOSS
    // =========================================================================

    @Test
    void fxSettlement_lossPath_realizedFxLossLinePosted_journalBalanced() {
        // Simulate USD 1,000 invoice at rate 2,500 → base TZS 2,500,000
        var invDto = openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid,
                new BigDecimal("2500000"), TZS,
                INVOICE_DATE, INVOICE_DATE.plusDays(30), "USD-INV-LOSS-001"));

        Long invId = arInvoiceRepo.findByUid(invDto.uid()).orElseThrow().getId();
        jdbc.update("""
                UPDATE ar_invoices
                SET    fx_rate = ?, base_original_amount = ?, base_outstanding_amount = ?,
                       outstanding_amount = ?, rate_at = NOW()
                WHERE  id = ?
                """, RATE_2500, new BigDecimal("2500000"), new BigDecimal("2500000"),
                USD_1000, invId);
        arInvoiceRepo.flush();

        // Settlement rate 2,400 < invoice rate 2,500 → we receive less base → FX LOSS
        seedRate(USD, TZS, RATE_2400, SETTLEMENT_DATE);

        ArReceiptDto receiptLoss = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid,
                USD_1000, USD, SETTLEMENT_DATE, "BANK_TRANSFER", "FX-LOSS-REF", List.of()));

        assertThat(receiptLoss.glEntryUid()).isNotBlank();
        var entry = journalEntryRepo.findByUid(receiptLoss.glEntryUid()).orElseThrow();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());

        // Journal balanced
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr).isEqualByComparingTo(sumCr);

        // Must have REALIZED_FX_LOSS debit line (account 5190 per DEFAULT_ACCOUNTS)
        Long fxLossAccountId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "5190")
                .map(ChartOfAccount::getId).orElseThrow();
        boolean hasFxLossDr = lines.stream()
                .anyMatch(l -> fxLossAccountId.equals(l.getAccountId())
                            && l.getDebitAmount() != null
                            && l.getDebitAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(hasFxLossDr)
                .as("A REALIZED_FX_LOSS debit line must be posted when settlement rate < invoice rate")
                .isTrue();

        // FX loss = (2500 - 2400) × 1000 = TZS 100,000
        BigDecimal expectedFxLoss = new BigDecimal("100000");
        BigDecimal actualFxLoss = lines.stream()
                .filter(l -> fxLossAccountId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(actualFxLoss)
                .as("FX loss = (invoice_rate - settlement_rate) × face = (2500-2400) × 1000 = 100,000 TZS")
                .isEqualByComparingTo(expectedFxLoss);
    }

    // =========================================================================
    // Bar 3: Base-currency settlement → NO FX leg (no-regression, D-8)
    // =========================================================================

    @Test
    void baseCurrencySettlement_noFxLeg_singleCurrencyByteIdentical() {
        // TZS invoice (base currency, rate = 1) settled in TZS
        BigDecimal amount = new BigDecimal("50000");
        var invDto = openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid,
                amount, TZS, LocalDate.now(), LocalDate.now().plusDays(30), "TZS-INV-001"));

        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid,
                amount, TZS, LocalDate.now(), "CASH", null, List.of()));

        assertThat(receipt.status()).isEqualTo(ArReceiptStatus.ALLOCATED);

        var entry = journalEntryRepo.findByUid(receipt.glEntryUid()).orElseThrow();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());

        // Must be exactly 2 lines: DR Cash / CR AR (no FX leg)
        assertThat(lines)
                .as("Base-currency settlement must post exactly 2 lines (DR Cash + CR AR), no FX leg (D-8)")
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
                .as("No FX gain/loss line must appear in a base-currency settlement (D-8)")
                .isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Seed a SPOT rate for (fromCurrency → toCurrency) on a given date for this company. */
    private void seedRate(String from, String to, BigDecimal rate, LocalDate effectiveDate) {
        CurrencyRate row = new CurrencyRate(
                company.getId(), branch.getId(),
                from, to, rate, effectiveDate,
                "SPOT", "IT-seed", rootId);
        rateRepo.save(row);
    }
}
