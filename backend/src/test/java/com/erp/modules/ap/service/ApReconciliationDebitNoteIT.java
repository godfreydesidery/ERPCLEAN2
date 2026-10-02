package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.domain.dto.ApplyDebitNoteRequest;
import com.erp.modules.ap.domain.dto.ApplyDebitNoteRequest.AllocationLineRequest;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.cashbank.domain.dto.ChequeBouncedPayload;
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
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AP sub-ledger vs GL 2100 reconciliation through the debit-note and payment-reversal paths, on
 * real Postgres (BR-AP-02: the difference must be zero whenever the books are right).
 *
 * <p>How each document moves the two sides (base currency, so face == base):
 * <pre>
 *   opening bill 1000   : GL CR AP 1000             | bill outstanding +1000
 *   raise DN 300        : GL DR AP  300 (full, once) | nothing on the bills; DN unapplied 300
 *   apply DN 100        : GL nothing                 | bill outstanding −100; DN unapplied −100
 *   pay 400             : GL DR AP  400              | bill outstanding −400
 *   payment bounced     : GL CR AP  400 (reversal)   | bill outstanding +400
 * </pre>
 * So the sub-ledger must be {@code Σ bill outstanding − Σ unallocated payments − Σ DN unapplied}:
 * the applied part of a DN already sits in the bill outstanding, only the unapplied remainder is
 * netted separately.
 */
class ApReconciliationDebitNoteIT extends PostgresIntegrationTest {

    @Autowired private ApReconciliationQuery    reconciliationQuery;
    @Autowired private ApBalanceService         balanceService;
    @Autowired private ApDebitNoteService       debitNoteService;
    @Autowired private ApPaymentService         paymentService;
    @Autowired private ApOpeningBalanceService  openingBalanceService;
    @Autowired private ApGlSeeder               apGlSeeder;
    @Autowired private CashBankSeeder           cashBankSeeder;
    @Autowired private SupplierService          supplierService;
    @Autowired private SupplierBillRepository   billRepo;
    @Autowired private ApPaymentRepository      paymentRepo;
    @Autowired private ChartOfAccountService    chartOfAccountService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;
    @Autowired private OutboxPublisher          outbox;
    @Autowired private DomainEventRepository    domainEventRepository;
    @Autowired private DomainEventDispatcher    dispatcher;
    @Autowired private TransactionTemplate      tx;
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
    private Long    supplierId;

    private static final String TZS = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AP Recon DN IT Org"));
        company    = companies.save(new Company(org, "APRDN", "AP Recon DN IT Co"));
        branch     = branches.save(new Branch(company, "APRDN1", "AP Recon DN IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("ap_rdn_root", passwordEncoder.encode("ApRdn00t!Xx"), "AP Recon Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();
        actAsRoot();

        SupplierDto supplier = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "Recon Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        supplierUid = supplier.uid();
        supplierId  = supplier.id();

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

    /**
     * Bill → unapplied DN → part-apply → payment → payment bounced: the reconciliation must read
     * zero at every step, and the supplier balance must equal the sub-ledger total throughout.
     */
    @Test
    void reconciliation_staysZero_throughUnappliedDebitNote_partialApply_andPaymentReversal() {
        SupplierBillDto bill = openingBalance("OB-RDN-001", new BigDecimal("1000"));
        assertReconciles("after the opening bill", "1000");

        // A general supplier credit — not tied to a bill, so nothing is applied at raise.
        ApDebitNoteDto dn = debitNoteService.raise(new RaiseDebitNoteRequest(
                companyUid, supplierUid, null, LocalDate.now(),
                new BigDecimal("300"), BigDecimal.ZERO, "Short delivery credit", null));
        assertThat(dn.unappliedAmount()).isEqualByComparingTo("300");
        assertReconciles("after an UNAPPLIED debit note", "700");

        debitNoteService.apply(new ApplyDebitNoteRequest(dn.uid(),
                List.of(new AllocationLineRequest(bill.uid(), new BigDecimal("100")))));
        assertThat(billRepo.findByUid(bill.uid()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo("900");
        assertReconciles("after applying part of the debit note (no double count)", "700");

        ApPaymentDto payment = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), new BigDecimal("400"), LocalDate.now(), "CHEQUE", "CHQ-1"));
        assertReconciles("after paying 400", "300");

        bounce(payment.uid());
        assertThat(paymentRepo.findByCompanyIdAndUid(company.getId(), payment.uid())
                .orElseThrow().getReversedAt()).as("payment stamped reversed").isNotNull();
        assertThat(billRepo.findByUid(bill.uid()).orElseThrow().getOutstandingAmount())
                .as("bounced payment restores the bill outstanding").isEqualByComparingTo("900");
        assertReconciles("after the payment cheque bounced", "700");

        // Apply the rest of the DN to a second bill (one allocation per DN/bill pair is a DB rule):
        // still zero, and the fully applied DN no longer contributes on its own.
        SupplierBillDto bill2 = openingBalance("OB-RDN-001B", new BigDecimal("500"));
        assertReconciles("after a second bill", "1200");
        debitNoteService.apply(new ApplyDebitNoteRequest(dn.uid(),
                List.of(new AllocationLineRequest(bill2.uid(), new BigDecimal("200")))));
        assertThat(billRepo.findByUid(bill2.uid()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo("300");
        assertReconciles("after fully applying the debit note", "1200");
    }

    /**
     * A reversed payment that still carried an on-account (unallocated) remainder: the GL reversal
     * puts the WHOLE payment back on AP, so the remainder must stop netting the sub-ledger too.
     * No live path leaves a remainder today (paySingle / payment runs allocate in full), so the
     * remainder is stamped directly — the state an on-account payment would leave.
     */
    @Test
    void reconciliation_staysZero_whenAReversedPaymentHadAnUnallocatedRemainder() {
        SupplierBillDto bill = openingBalance("OB-RDN-002", new BigDecimal("1000"));
        ApPaymentDto payment = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), new BigDecimal("400"), LocalDate.now(), "CHEQUE", "CHQ-2"));

        // Re-shape it into "400 paid, 300 allocated to the bill, 100 on account": GL is unchanged
        // (DR AP 400), sub-ledger = (1000 − 300) − 100 = 600 = GL. Still balanced.
        Long paymentId = paymentRepo.findByCompanyIdAndUid(company.getId(), payment.uid())
                .orElseThrow().getId();
        jdbc.update("UPDATE ap_payment_allocations SET allocated_amount = 300 WHERE ap_payment_id = ?",
                paymentId);
        jdbc.update("UPDATE ap_payments SET unallocated_amount = 100, status = 'PARTIAL' WHERE id = ?",
                paymentId);
        jdbc.update("UPDATE supplier_bills SET outstanding_amount = 700, base_outstanding_amount = 700"
                + " WHERE uid = ?", bill.uid());
        assertReconciles("with an on-account remainder", "600");

        bounce(payment.uid());
        assertThat(billRepo.findByUid(bill.uid()).orElseThrow().getOutstandingAmount())
                .isEqualByComparingTo("1000");
        assertThat(paymentRepo.findByCompanyIdAndUid(company.getId(), payment.uid())
                .orElseThrow().getUnallocatedAmount())
                .as("a reversed payment has nothing left on account").isEqualByComparingTo("0");
        assertReconciles("after the payment with a remainder bounced", "1000");
    }

    // -------------------------------------------------------------------------

    private void assertReconciles(String when, String expectedPayable) {
        actAsRoot();
        ApReconciliationDto recon = reconciliationQuery.reconcile(company.getId());
        assertThat(recon.glControlBalance()).as("GL 2100 %s", when)
                .isEqualByComparingTo(expectedPayable);
        assertThat(recon.subLedgerTotal()).as("AP sub-ledger %s", when)
                .isEqualByComparingTo(expectedPayable);
        assertThat(recon.difference()).as("reconciliation difference %s", when)
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceService.currentBalance(company.getId(), supplierId).outstandingBalance())
                .as("supplier balance %s matches the sub-ledger", when)
                .isEqualByComparingTo(expectedPayable);
    }

    /**
     * Drives the real consumer: a CHEQUE.BOUNCED (OUTBOUND, payment-linked) event on the outbox,
     * dispatched through the dispatcher proxy exactly as the poller would.
     */
    private void bounce(String paymentUid) {
        String eventUid = tx.execute(status -> outbox.publish(
                DomainEventType.CHEQUE_BOUNCED, DomainEventType.AGG_CHEQUE,
                0L, paymentUid /* stands in for the cheque uid (26 chars) */, company.getId(), branch.getId(),
                new ChequeBouncedPayload("CHQ-" + paymentUid, "OUTBOUND", null, paymentUid,
                        "Refer to drawer")));
        Long eventId = domainEventRepository.findAll().stream()
                .filter(e -> eventUid.equals(e.getUid()))
                .findFirst().orElseThrow().getId();
        dispatcher.dispatchOne(eventId);
        actAsRoot();
    }

    private void actAsRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_rdn_root", true, company.getId(), branch.getId(), null));
    }

    private SupplierBillDto openingBalance(String invoiceRef, BigDecimal amount) {
        return openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, TZS,
                LocalDate.now(), LocalDate.now().plusDays(30), invoiceRef));
    }
}
