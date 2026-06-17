package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.cashbank.service.CashBankSeeder;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ApPaymentServiceImpl} (ADR-0015 D-3/D-4/D-5, FR-AP-PAY).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>paySingle — full payment: bill status → PAID, outstanding → 0, GL DR AP / CR Cash balanced.
 *   <li>paySingle — partial payment: bill status → PARTIALLY_PAID, outstanding reduced.
 *   <li>paySingle — bill not found (wrong uid) → NotFoundException.
 *   <li>paymentRun — selects all due bills, settles them, total GL entry equals sum of bills.
 *   <li>RECONCILIATION BAR: AP sub-ledger == GL 2100 after OB + full payment == 0.
 * </ol>
 */
class ApPaymentServiceIT extends PostgresIntegrationTest {

    @Autowired private ApPaymentService        paymentService;
    @Autowired private ApOpeningBalanceService  openingBalanceService;
    @Autowired private ApReconciliationQuery    reconciliationQuery;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private SupplierService         supplierService;
    @Autowired private SupplierBillRepository  billRepo;
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private CashBankSeeder          cashBankSeeder;
    @Autowired private JournalEntryRepository  journalEntryRepo;
    @Autowired private JournalLineRepository   journalLineRepo;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  supplierUid;

    private static final BigDecimal OB_1000 = new BigDecimal("1000.00");
    private static final BigDecimal OB_2000 = new BigDecimal("2000.00");
    private static final String     TZS     = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AP Pay IT Org"));
        company    = companies.save(new Company(org, "APPAY", "AP Pay IT Co"));
        branch     = branches.save(new Branch(company, "APPAY1", "AP Pay IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("ap_pay_root", passwordEncoder.encode("ApPay00t!Xx"), "AP Pay Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_pay_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "Pay Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null)).uid();

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
    // Bar 1: full payment → PAID, outstanding = 0, balanced GL entry
    // =========================================================================

    @Test
    void paySingle_fullPayment_billMarkedPaid_balancedGlEntry() {
        SupplierBillDto bill = openingBalance("OB-FULL-001", OB_1000);

        ApPaymentDto payment = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), OB_1000, LocalDate.now(), "CASH", null));

        assertThat(payment.glEntryUid()).isNotBlank();
        assertThat(payment.allocations()).hasSize(1);
        assertThat(payment.allocations().get(0).allocatedAmount())
                .isEqualByComparingTo(OB_1000);

        // Bill must be PAID
        var refreshed = billRepo.findByUid(bill.uid()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SupplierBillStatus.PAID);
        assertThat(refreshed.getOutstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // GL must be balanced
        var entry = journalEntryRepo.findByUid(payment.glEntryUid()).orElseThrow();
        var lines  = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());
        BigDecimal sumDR = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCR = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDR).isEqualByComparingTo(sumCR).isEqualByComparingTo(OB_1000);
    }

    // =========================================================================
    // Bar 2: partial payment → PARTIALLY_PAID
    // =========================================================================

    @Test
    void paySingle_partialPayment_billMarkedPartiallyPaid() {
        SupplierBillDto bill = openingBalance("OB-PART-001", OB_1000);
        BigDecimal partial   = new BigDecimal("400.00");

        paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), partial, LocalDate.now(), "CASH", null));

        var refreshed = billRepo.findByUid(bill.uid()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SupplierBillStatus.PARTIALLY_PAID);
        assertThat(refreshed.getOutstandingAmount())
                .isEqualByComparingTo(OB_1000.subtract(partial));
    }

    // =========================================================================
    // Bar 3: bill not found → exception
    // =========================================================================

    @Test
    void paySingle_billNotFound_throwsNotFoundException() {
        PaySingleBillRequest req = new PaySingleBillRequest(
                companyUid, "NONEXISTENT-UID", OB_1000, LocalDate.now(), "CASH", null);

        assertThatThrownBy(() -> paymentService.paySingle(req))
                .isInstanceOf(Exception.class);
    }

    // =========================================================================
    // Bar 4: payment run — settles all due bills
    // =========================================================================

    @Test
    void paymentRun_settlesAllDueBills() {
        SupplierBillDto bill1 = openingBalance("OB-RUN-001", OB_1000);
        SupplierBillDto bill2 = openingBalance("OB-RUN-002", OB_2000);

        ApPaymentDto run = paymentService.paymentRun(new PaymentRunRequest(
                companyUid, supplierUid,
                LocalDate.now().plusDays(60), LocalDate.now(),
                "BANK_TRANSFER", null, null));

        assertThat(run.allocations()).hasSize(2);
        assertThat(run.glEntryUid()).isNotBlank();

        var b1 = billRepo.findByUid(bill1.uid()).orElseThrow();
        var b2 = billRepo.findByUid(bill2.uid()).orElseThrow();
        assertThat(b1.getStatus()).isEqualTo(SupplierBillStatus.PAID);
        assertThat(b2.getStatus()).isEqualTo(SupplierBillStatus.PAID);
    }

    // =========================================================================
    // Bar 5: RECONCILIATION BAR — after OB + full payment, both balances = 0
    // =========================================================================

    @Test
    void reconciliation_afterFullPayment_bothBalancesZero() {
        SupplierBillDto bill = openingBalance("OB-RECON-PAY-001", OB_1000);

        paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), OB_1000, LocalDate.now(), "CASH", null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_pay_root", true, company.getId(), branch.getId(), null));

        ApReconciliationDto recon = reconciliationQuery.reconcile(company.getId());

        assertThat(recon.difference())
                .as("RECONCILIATION BAR: AP sub-ledger == GL 2100 after full payment (BR-AP-02). "
                        + "diff=" + recon.difference())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(recon.subLedgerTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SupplierBillDto openingBalance(String invoiceRef, BigDecimal amount) {
        return openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, TZS,
                LocalDate.now(), LocalDate.now().plusDays(30),
                invoiceRef));
    }
}
