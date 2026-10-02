package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.domain.dto.ArReconciliationDto;
import com.erp.modules.ar.domain.dto.ArUnconvertedAmountDto;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.entity.ArCreditNote;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.entity.ArReceipt;
import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.repository.ArCreditNoteRepository;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GLConfigResolver;
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
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.sales.service.CreditExposureCalculator;
import com.erp.platform.common.money.FxRateService;
import com.erp.platform.common.money.Money;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner ruling 2026-10-02 — "per currency, convert only reliable rows" — on real Postgres.
 *
 * <p>One customer carries:
 * <ul>
 *   <li>a TZS (base) opening balance of 5,000 — GL DR 1200 5,000;</li>
 *   <li>a RELIABLE USD open item: 100 USD at 2,500 = 250,000 base — GL DR 1200 250,000;</li>
 *   <li>a RELIABLE USD unapplied credit note: 5 USD at 2,500 = 12,500 base — GL CR 1200 12,500;</li>
 *   <li>a V62-style USD open item: 40 USD, {@code fx_rate = 1}, base = 40 (the back-fill);</li>
 *   <li>a V62-style USD on-account receipt: 10 USD unallocated, {@code fx_rate = 1}.</li>
 * </ul>
 * The V62 rows carry no GL of their own here, so the reliable part must tie to GL 1200 exactly.
 */
class ArFxBalanceIT extends PostgresIntegrationTest {

    private static final String TZS = "TZS";
    private static final String USD = "USD";

    @Autowired private ArBalanceService          balanceService;
    @Autowired private ArReconciliationQuery     reconciliationQuery;
    @Autowired private CreditExposureCalculator  creditExposure;
    @Autowired private ArOpeningBalanceService   openingBalanceService;
    @Autowired private ArGlSeeder                arGlSeeder;
    @Autowired private ArInvoiceRepository       invoiceRepo;
    @Autowired private ArReceiptRepository       receiptRepo;
    @Autowired private ArCreditNoteRepository    creditNoteRepo;
    @Autowired private CustomerService           customerService;
    @Autowired private FxRateService             fxRateService;
    @Autowired private GLPostingService          glPosting;
    @Autowired private GLConfigResolver          glConfig;
    @Autowired private ChartOfAccountService     chartOfAccountService;
    @Autowired private FiscalCalendarService     fiscalCalendarService;
    @Autowired private GlConfigService           glConfigService;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private TransactionTemplate      tx;
    @Autowired private IamTestData               testData;

    private Company company;
    private Long rootId;
    private Long customerId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AR FX Bal IT Org"));
        company = companies.save(new Company(org, "ARFXB", "AR FX Bal IT Co"));
        Branch branch = branches.save(new Branch(company, "ARFXB1", "AR FX Bal IT Branch"));

        AppUser root = new AppUser("arfxb_root", passwordEncoder.encode("RootPass1!xx"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        RequestContext.set(new RequestContext.Principal(
                rootId, "arfxb_root", true, company.getId(), branch.getId(), null));

        CustomerDto customer = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        customerId = customer.id();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());

        LocalDate today = LocalDate.now();
        // (1) base TZS opening balance — the service posts DR 1200 / CR 3100
        openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                company.getUid(), customer.uid(), new BigDecimal("5000"), TZS,
                today, today.plusDays(30), "OB-TZS"));

        // (2) reliable USD open item, 100 @ 2500 = 250,000 base
        ArInvoice reliable = new ArInvoice(company.getId(), null, customerId,
                ArInvoiceSource.OPENING_BALANCE, null, "USD-RELIABLE",
                new BigDecimal("100"), USD, today, today.plusDays(30), rootId);
        reliable.setFxRate(new BigDecimal("2500"));
        reliable.setBaseOriginalAmount(new BigDecimal("250000"));
        reliable.setBaseOutstandingAmount(new BigDecimal("250000"));
        invoiceRepo.save(reliable);
        postToArControl(new BigDecimal("250000"), "reliable USD item");

        // (3) reliable USD unapplied credit note, 5 @ 2500 = 12,500 base
        ArCreditNote cn = new ArCreditNote(company.getId(), null, customerId, "CN-USD-1", null,
                today, new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO, USD,
                "test", ArCreditNoteOrigin.STANDALONE, rootId);
        cn.setFxRate(new BigDecimal("2500"));
        cn.setBaseAmount(new BigDecimal("12500"));
        cn.setBaseUnappliedAmount(new BigDecimal("12500"));
        creditNoteRepo.save(cn);
        postToArControl(new BigDecimal("12500").negate(), "reliable USD credit note");

        // (4) V62-style USD open item: fx_rate 1, base == face
        ArInvoice v62 = new ArInvoice(company.getId(), null, customerId,
                ArInvoiceSource.OPENING_BALANCE, null, "USD-V62",
                new BigDecimal("40"), USD, today.minusYears(1), today.minusYears(1).plusDays(30),
                rootId);
        v62.setBaseOriginalAmount(new BigDecimal("40"));
        v62.setBaseOutstandingAmount(new BigDecimal("40"));
        invoiceRepo.save(v62);

        // (5) V62-style USD on-account receipt: 10 unallocated, fx_rate 1
        receiptRepo.save(new ArReceipt(company.getId(), null, customerId, "RC-USD-V62",
                today.minusYears(1), new BigDecimal("10"), USD, "CASH", rootId));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void balance_isBaseOverReliableRows_andListsTheV62RowsPerCurrency() {
        ArBalanceDto bal = balanceService.currentBalance(company.getId(), customerId);

        // 5,000 + 250,000 − 12,500 — the V62 USD rows are NOT summed in at par
        assertThat(bal.balance()).isEqualByComparingTo("242500");
        assertThat(bal.currency()).isEqualTo(TZS);
        assertThat(bal.unconverted()).singleElement().satisfies(u -> {
            assertThat(u.currency()).isEqualTo(USD);
            assertThat(u.amount()).isEqualByComparingTo("30");   // 40 open − 10 on account
            assertThat(u.itemCount()).isEqualTo(2);
        });
    }

    @Test
    void reconciliation_reliableRowsTieToGl_andV62RowsAreReportedUnconverted() {
        ArReconciliationDto rec = reconciliationQuery.reconcile(company.getId());

        assertThat(rec.subLedgerTotal()).isEqualByComparingTo("242500");
        assertThat(rec.glControlBalance()).isEqualByComparingTo("242500");
        assertThat(rec.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rec.unconverted()).extracting(ArUnconvertedAmountDto::currency)
                .containsExactly(USD);
        assertThat(rec.unconverted().get(0).amount()).isEqualByComparingTo("30");
    }

    @Test
    void creditCheck_countsUnconvertedAtTodaysRate() {
        // historic 2,500 and today's 2,600 — the unconverted 30 USD must use TODAY's rate
        addRate("2500", LocalDate.now().minusDays(30));
        addRate("2600", LocalDate.now());

        Money limit = new Money(new BigDecimal("321000"), TZS);
        CreditExposureCalculator.Assessment a = creditExposure.assess(company.getId(), customerId,
                new BigDecimal("1000"), TZS, limit, LocalDate.now());

        // 242,500 + 30 × 2,600 + 1,000 = 321,500
        assertThat(a.exposure()).isEqualByComparingTo("321500");
        assertThat(a.rateMissing()).isFalse();
        assertThat(a.breached()).isTrue();

        CreditExposureCalculator.Assessment ok = creditExposure.assess(company.getId(), customerId,
                new BigDecimal("1000"), TZS, new Money(new BigDecimal("321500"), TZS),
                LocalDate.now());
        assertThat(ok.breached()).as("exactly at the limit is not over it").isFalse();
    }

    @Test
    void creditCheck_foreignNewDocumentIsConvertedAtTodaysRate() {
        addRate("2600", LocalDate.now());

        CreditExposureCalculator.Assessment a = creditExposure.assess(company.getId(), customerId,
                new BigDecimal("2"), USD, new Money(new BigDecimal("1000000"), TZS),
                LocalDate.now());

        // 242,500 + 30 × 2,600 + 2 × 2,600 = 325,700
        assertThat(a.exposure()).isEqualByComparingTo("325700");
        assertThat(a.breached()).isFalse();
    }

    @Test
    void creditCheck_noRateForAnUnconvertedCurrency_failsClosed() {
        // no USD rate at all: the 30 USD cannot be valued, so the check must not pass
        CreditExposureCalculator.Assessment a = creditExposure.assess(company.getId(), customerId,
                new BigDecimal("1000"), TZS, new Money(new BigDecimal("99999999"), TZS),
                LocalDate.now());

        assertThat(a.rateMissing()).isTrue();
        assertThat(a.missingRateCurrencies()).containsExactly(USD);
        assertThat(a.breached()).as("a missing rate is treated as over the limit").isTrue();
        assertThat(CreditExposureCalculator.missingRateSentence(a.missingRateCurrencies()))
                .isEqualTo("The credit limit could not be checked because there is no exchange "
                        + "rate for USD. Add a USD rate under Currency rates and try again.");
    }

    // ------------------------------------------------------------------------------------------

    private void addRate(String rate, LocalDate effective) {
        fxRateService.addRate(new UpsertRateRequest(
                company.getId(), USD, TZS, new BigDecimal(rate), effective, "SPOT", "test"));
    }

    /** Posts a base-currency journal against AR control (positive = DR) with OBE as contra. */
    private void postToArControl(BigDecimal amount, String memo) {
        tx.executeWithoutResult(st -> {
            Long ar  = glConfig.resolve(company.getId(), GlConfigKey.ACCOUNTS_RECEIVABLE).getId();
            Long obe = glConfig.resolve(company.getId(), GlConfigKey.OPENING_BALANCE_EQUITY).getId();
            BigDecimal abs = amount.abs();
            boolean debitAr = amount.signum() > 0;
            glPosting.post(new JournalEntryDraft(
                    company.getId(), null, LocalDate.now(), memo,
                    JournalSourceType.OPENING_BALANCE, null, null, rootId, List.of(
                            new LineDraft(ar, debitAr ? abs : BigDecimal.ZERO,
                                    debitAr ? BigDecimal.ZERO : abs, TZS, memo),
                            new LineDraft(obe, debitAr ? BigDecimal.ZERO : abs,
                                    debitAr ? abs : BigDecimal.ZERO, TZS, memo))));
        });
    }
}
