package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.domain.dto.ApUnconvertedAmountDto;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.entity.ApDebitNote;
import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.ApPaymentKind;
import com.erp.modules.ap.domain.enums.ApPaymentStatus;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApDebitNoteRepository;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
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
 * Owner ruling 2026-10-02 — "per currency, convert only reliable rows" — AP side, real Postgres.
 *
 * <p>One supplier carries:
 * <ul>
 *   <li>a TZS (base) opening-balance bill of 5,000 — GL CR 2100 5,000;</li>
 *   <li>a RELIABLE USD bill: 100 USD at 2,500 = 250,000 base — GL CR 2100 250,000;</li>
 *   <li>a RELIABLE USD unapplied debit note: 5 USD at 2,500 = 12,500 base — GL DR 2100 12,500;</li>
 *   <li>a V62-style USD bill: 40 USD, {@code fx_rate = 1}, base = 40 (the back-fill);</li>
 *   <li>a V62-style USD on-account payment: 10 USD unallocated, {@code fx_rate = 1}.</li>
 * </ul>
 */
class ApFxBalanceIT extends PostgresIntegrationTest {

    private static final String TZS = "TZS";
    private static final String USD = "USD";

    @Autowired private ApBalanceService         balanceService;
    @Autowired private ApReconciliationQuery    reconciliationQuery;
    @Autowired private ApOpeningBalanceService  openingBalanceService;
    @Autowired private ApGlSeeder               apGlSeeder;
    @Autowired private SupplierBillRepository   billRepo;
    @Autowired private ApPaymentRepository      paymentRepo;
    @Autowired private ApDebitNoteRepository    debitNoteRepo;
    @Autowired private SupplierService          supplierService;
    @Autowired private GLPostingService         glPosting;
    @Autowired private GLConfigResolver         glConfig;
    @Autowired private ChartOfAccountService    chartOfAccountService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;
    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private PasswordEncoder          passwordEncoder;
    @Autowired private TransactionTemplate      tx;
    @Autowired private IamTestData              testData;

    private Company company;
    private Long rootId;
    private Long supplierId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AP FX Bal IT Org"));
        company = companies.save(new Company(org, "APFXB", "AP FX Bal IT Co"));
        Branch branch = branches.save(new Branch(company, "APFXB1", "AP FX Bal IT Branch"));

        AppUser root = new AppUser("apfxb_root", passwordEncoder.encode("RootPass1!xx"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        RequestContext.set(new RequestContext.Principal(
                rootId, "apfxb_root", true, company.getId(), branch.getId(), null));

        SupplierDto supplier = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "FX Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        supplierId = supplier.id();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());

        LocalDate today = LocalDate.now();
        // (1) base TZS opening-balance bill — the service posts CR 2100 / DR 3100
        openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                company.getUid(), supplier.uid(), new BigDecimal("5000"), TZS,
                today, today.plusDays(30), "OB-TZS"));

        // (2) reliable USD bill, 100 @ 2500 = 250,000 base
        SupplierBill reliable = bill("USD-RELIABLE", "100", today);
        reliable.setFxRate(new BigDecimal("2500"));
        reliable.setBaseGrossAmount(new BigDecimal("250000"));
        reliable.setBaseOutstandingAmount(new BigDecimal("250000"));
        billRepo.save(reliable);
        postToApControl(new BigDecimal("250000"), "reliable USD bill");

        // (3) reliable USD unapplied debit note, 5 @ 2500 = 12,500 base
        ApDebitNote dn = new ApDebitNote(company.getId(), null, supplierId, "DN-USD-1", null,
                today, new BigDecimal("5"), new BigDecimal("5"), BigDecimal.ZERO, USD, "test",
                rootId);
        dn.setOrigin("STANDALONE");
        dn.setFxRate(new BigDecimal("2500"));
        dn.setBaseAmount(new BigDecimal("12500"));
        dn.setBaseUnappliedAmount(new BigDecimal("12500"));
        debitNoteRepo.save(dn);
        postToApControl(new BigDecimal("12500").negate(), "reliable USD debit note");

        // (4) V62-style USD bill: fx_rate 1, base == face
        SupplierBill v62 = bill("USD-V62", "40", today.minusYears(1));
        v62.setBaseGrossAmount(new BigDecimal("40"));
        v62.setBaseOutstandingAmount(new BigDecimal("40"));
        billRepo.save(v62);

        // (5) V62-style USD on-account payment: 10 unallocated, fx_rate 1
        ApPayment pay = new ApPayment(company.getId(), null, supplierId, "PY-USD-V62",
                ApPaymentKind.SINGLE, today.minusYears(1), new BigDecimal("10"), USD, "CASH",
                null, rootId);
        pay.setUnallocatedAmount(new BigDecimal("10"));
        pay.setStatus(ApPaymentStatus.UNALLOCATED);
        paymentRepo.save(pay);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void balance_isBaseOverReliableRows_andListsTheV62RowsPerCurrency() {
        ApBalanceDto bal = balanceService.currentBalance(company.getId(), supplierId);

        // 5,000 + 250,000 − 12,500 — the V62 USD rows are NOT summed in at par
        assertThat(bal.outstandingBalance()).isEqualByComparingTo("242500");
        assertThat(bal.currency()).isEqualTo(TZS);
        assertThat(bal.unconverted()).singleElement().satisfies(u -> {
            assertThat(u.currency()).isEqualTo(USD);
            assertThat(u.amount()).isEqualByComparingTo("30");   // 40 open − 10 on account
            assertThat(u.itemCount()).isEqualTo(2);
        });
    }

    @Test
    void reconciliation_reliableRowsTieToGl_andV62RowsAreReportedUnconverted() {
        ApReconciliationDto rec = reconciliationQuery.reconcile(company.getId());

        assertThat(rec.subLedgerTotal()).isEqualByComparingTo("242500");
        assertThat(rec.glControlBalance()).isEqualByComparingTo("242500");
        assertThat(rec.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rec.unconverted()).extracting(ApUnconvertedAmountDto::currency)
                .containsExactly(USD);
        assertThat(rec.unconverted().get(0).amount()).isEqualByComparingTo("30");
    }

    // ------------------------------------------------------------------------------------------

    private SupplierBill bill(String ref, String gross, LocalDate date) {
        BigDecimal g = new BigDecimal(gross);
        SupplierBill b = new SupplierBill(company.getId(), null, supplierId, ref,
                SupplierBillSource.OPENING_BALANCE, null, date, date.plusDays(30),
                g, BigDecimal.ZERO, g, USD, rootId);
        b.setBillNumber("BL-" + ref);
        b.setOutstandingAmount(g);
        b.setStatus(SupplierBillStatus.MATCHED);
        return b;
    }

    /** Posts a base-currency journal against AP control (positive = CR) with OBE as contra. */
    private void postToApControl(BigDecimal amount, String memo) {
        tx.executeWithoutResult(st -> {
            Long ap  = glConfig.resolve(company.getId(), GlConfigKey.ACCOUNTS_PAYABLE).getId();
            Long obe = glConfig.resolve(company.getId(), GlConfigKey.OPENING_BALANCE_EQUITY).getId();
            BigDecimal abs = amount.abs();
            boolean creditAp = amount.signum() > 0;
            glPosting.post(new JournalEntryDraft(
                    company.getId(), null, LocalDate.now(), memo,
                    JournalSourceType.OPENING_BALANCE, null, null, rootId, List.of(
                            new LineDraft(ap, creditAp ? BigDecimal.ZERO : abs,
                                    creditAp ? abs : BigDecimal.ZERO, TZS, memo),
                            new LineDraft(obe, creditAp ? abs : BigDecimal.ZERO,
                                    creditAp ? BigDecimal.ZERO : abs, TZS, memo))));
        });
    }
}
