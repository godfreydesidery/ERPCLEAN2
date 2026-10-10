package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.ApplyCreditNoteRequest;
import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.ArRefundDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.RefundCustomerRequest;
import com.erp.modules.ar.domain.enums.ArCreditNoteStatus;
import com.erp.modules.ar.repository.ArCreditNoteRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.gl.domain.entity.JournalLine;
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
import com.erp.platform.common.api.ConflictException;
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
 * ARC-11: paying a customer's credit back — a deposit held on account, an unused credit note.
 * DR AR control / CR cash, OUT cash-book row, the credit reduced, AR control still equal to the
 * sub-ledger; over-refunds and later reversals / re-applications that would resurrect refunded
 * credit are refused.
 */
class ArRefundIT extends PostgresIntegrationTest {

    @Autowired private ArRefundService refundService;
    @Autowired private ArReceiptService receiptService;
    @Autowired private ArCreditNoteService creditNoteService;
    @Autowired private ArReconciliationQuery reconciliationQuery;
    @Autowired private ArGlSeeder arGlSeeder;
    @Autowired private CustomerService customerService;
    @Autowired private ArReceiptRepository receiptRepo;
    @Autowired private ArCreditNoteRepository creditNoteRepo;
    @Autowired private CashTransactionRepository cashTxnRepo;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private CashBankSeeder cashBankSeeder;
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

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Refund IT Org"));
        company = companies.save(new Company(org, "REFD", "Refund IT Co"));
        branch = branches.save(new Branch(company, "REFD1", "Refund IT Branch"));
        companyUid = company.getUid();
        AppUser root = new AppUser("refd_root", passwordEncoder.encode("Root1234!"), "Refund Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        setContext();

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Event Hotel",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void depositRefund_paysBackPartOfTheOnAccountMoney_glBalanced_cashOut_arStillReconciles() {
        ArReceiptDto deposit = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, new BigDecimal("1000"), "TZS", LocalDate.now(), "CASH",
                null, List.of()));
        assertThat(deposit.unallocatedAmount()).isEqualByComparingTo("1000");

        ArRefundDto refund = refundService.refund(new RefundCustomerRequest(
                RefundCustomerRequest.RECEIPT, deposit.uid(), new BigDecimal("400"), null, null,
                "Event cancelled"));

        assertThat(refund.remainingCredit()).isEqualByComparingTo("600");
        assertThat(receiptRepo.findByUid(deposit.uid()).orElseThrow().getUnallocatedAmount())
                .isEqualByComparingTo("600");
        List<JournalLine> lines = journalLineRepo.findByEntryIdOrderByLineNo(
                journalEntryRepo.findByUid(refund.journalEntryUid()).orElseThrow().getId());
        assertThat(debit(lines, "1200")).isEqualByComparingTo("400");
        assertThat(credit(lines, "1000")).isEqualByComparingTo("400");
        assertThat(sum(lines, true)).isEqualByComparingTo(sum(lines, false));

        CashTransaction out = cashTxnRepo.findByCompanyIdAndSourceRef(company.getId(), deposit.uid())
                .stream().filter(t -> t.getDirection() == CashTxnDirection.OUT).findFirst().orElseThrow();
        assertThat(out.getTxnType()).isEqualTo(CashTxnType.AR_RECEIPT);
        assertThat(out.getAmount()).isEqualByComparingTo("400");
        assertThat(out.getJournalEntryRef()).isEqualTo(refund.journalEntryUid());
        assertThat(out.getReversalOfTransactionId()).isNull();

        assertThat(reconciliationQuery.reconcile(company.getId()).difference())
                .isEqualByComparingTo(BigDecimal.ZERO);

        // More than the credit left is refused; reversing the part-refunded receipt is refused.
        assertThatThrownBy(() -> refundService.refund(new RefundCustomerRequest(
                RefundCustomerRequest.RECEIPT, deposit.uid(), new BigDecimal("700"), null, null,
                "Again")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("at most");
        assertThatThrownBy(() -> receiptService.reverse(deposit.uid(), "Wrong customer"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("refunded");
    }

    @Test
    void unusedCreditNote_refundedInFull_isUsedUp_andCannotBeReapplied() {
        ArCreditNoteDto note = creditNoteService.raise(new RaiseCreditNoteRequest(
                companyUid, customerUid, null, LocalDate.now(), new BigDecimal("500"),
                BigDecimal.ZERO, "TZS", "Price allowance"));
        assertThat(note.unappliedAmount()).isEqualByComparingTo("500");

        ArRefundDto refund = refundService.refund(new RefundCustomerRequest(
                RefundCustomerRequest.CREDIT_NOTE, note.uid(), new BigDecimal("500"), null, null,
                "Customer asked for cash"));

        assertThat(refund.remainingCredit()).isEqualByComparingTo("0");
        var stored = creditNoteRepo.findByUid(note.uid()).orElseThrow();
        assertThat(stored.getUnappliedAmount()).isEqualByComparingTo("0");
        assertThat(stored.getStatus()).isEqualTo(ArCreditNoteStatus.APPLIED);
        assertThat(reconciliationQuery.reconcile(company.getId()).difference())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThatThrownBy(() -> creditNoteService.reapply(
                new ApplyCreditNoteRequest(note.uid(), List.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("refunded");
    }

    @Test
    void refund_withoutReason_orBeyondNothing_isRefused() {
        ArReceiptDto deposit = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, new BigDecimal("300"), "TZS", LocalDate.now(), "CASH",
                null, List.of()));

        assertThatThrownBy(() -> refundService.refund(new RefundCustomerRequest(
                RefundCustomerRequest.RECEIPT, deposit.uid(), new BigDecimal("100"), null, null, " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cashTxnRepo.findByCompanyIdAndSourceRef(company.getId(), deposit.uid()))
                .hasSize(1);
    }

    // -------------------------------------------------------------------------

    private void setContext() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "refd_root", true, company.getId(), branch.getId(), null));
    }

    private static BigDecimal sum(List<JournalLine> lines, boolean debit) {
        return lines.stream()
                .map(l -> debit ? l.getDebitAmount() : l.getCreditAmount())
                .map(v -> v != null ? v : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal debit(List<JournalLine> lines, String code) {
        Long id = accountRepo.findByCompanyIdAndAccountCode(company.getId(), code).orElseThrow().getId();
        return lines.stream().filter(l -> id.equals(l.getAccountId()))
                .map(JournalLine::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal credit(List<JournalLine> lines, String code) {
        Long id = accountRepo.findByCompanyIdAndAccountCode(company.getId(), code).orElseThrow().getId();
        return lines.stream().filter(l -> id.equals(l.getAccountId()))
                .map(JournalLine::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
