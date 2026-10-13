package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.ArReconciliationDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.entity.JournalLine;
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
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * ARC-04: a wrong customer receipt can be reversed. The reversal posts the inverse journal (DR AR /
 * CR Cash), writes the opposite cash-book row on the same account, restores the invoices the
 * receipt settled and stamps {@code reversed_at}; a second reversal, a blank reason and a receipt
 * in a closed period are all refused with a friendly message.
 */
class ArReceiptReverseIT extends PostgresIntegrationTest {

    @Autowired private ArReceiptService          receiptService;
    @Autowired private ArReconciliationQuery     reconciliationQuery;
    @Autowired private ArBalanceService          balanceService;
    @Autowired private ArOpeningBalanceService   openingBalanceService;
    @Autowired private ArGlSeeder                arGlSeeder;
    @Autowired private CustomerService           customerService;
    @Autowired private ArInvoiceRepository       arInvoiceRepo;
    @Autowired private ArReceiptRepository       arReceiptRepo;
    @Autowired private CashTransactionRepository cashTxnRepo;
    @Autowired private JournalEntryRepository    journalEntryRepo;
    @Autowired private JournalLineRepository     journalLineRepo;
    @Autowired private ChartOfAccountRepository  accountRepo;
    @Autowired private ChartOfAccountService     chartOfAccountService;
    @Autowired private FiscalCalendarService     fiscalCalendarService;
    @Autowired private GlConfigService           glConfigService;
    @Autowired private CashBankSeeder            cashBankSeeder;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private IamTestData               testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  customerUid;
    private Long    customerId;

    private static final String TZS = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("RctRev IT Org"));
        company    = companies.save(new Company(org, "RREV", "RctRev IT Co"));
        branch     = branches.save(new Branch(company, "RREV1", "RctRev IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("rrev_root", passwordEncoder.encode("Root1234!"), "RRev Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        setContext();

        var customer = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Wrong Bar",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        customerUid = customer.uid();
        customerId  = customer.id();

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

    private void setContext() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "rrev_root", true, company.getId(), branch.getId(), null));
    }

    @Test
    void reverse_restoresAllocations_reversesGlAndCashBook_stampsReceipt() {
        // Invoice 1000; a receipt of 1500 settles it and leaves 500 on account.
        ArInvoiceDto inv = openItem(new BigDecimal("1000"));
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, new BigDecimal("1500"), TZS, LocalDate.now(), "CASH",
                null, List.of()));
        assertThat(arInvoiceRepo.findByUid(inv.uid()).orElseThrow().getStatus())
                .isEqualTo(ArInvoiceStatus.PAID);
        assertThat(receipt.reversedAt()).isNull();
        CashTransaction cashIn = settlementRows(receipt.uid()).get(0);

        ArReceiptDto reversed = receiptService.reverse(receipt.uid(), "Keyed against the wrong bar");

        // Sub-ledger: invoice owed again, nothing on account, receipt stamped.
        var refreshedInv = arInvoiceRepo.findByUid(inv.uid()).orElseThrow();
        assertThat(refreshedInv.getOutstandingAmount()).isEqualByComparingTo("1000");
        assertThat(refreshedInv.getStatus()).isEqualTo(ArInvoiceStatus.OPEN);
        assertThat(reversed.reversedAt()).isNotNull();
        assertThat(reversed.unallocatedAmount()).isEqualByComparingTo("0");
        assertThat(arReceiptRepo.findByUid(receipt.uid()).orElseThrow().getReversedAt()).isNotNull();

        // GL: one balanced reversing entry, the exact inverse of the receipt (DR AR / CR Cash).
        JournalEntry original = journalEntryRepo.findByUid(receipt.glEntryUid()).orElseThrow();
        assertThat(original.isReversed()).isTrue();
        JournalEntry reversal = journalEntryRepo
                .findByCompanyId(company.getId(), Pageable.unpaged()).stream()
                .filter(e -> e.getSourceType() == JournalSourceType.AR_RECEIPT)
                .filter(e -> !e.getId().equals(original.getId()))
                .max(Comparator.comparing(JournalEntry::getId))
                .orElseThrow(() -> new AssertionError("no reversing entry"));
        assertThat(reversal.getDescription()).contains("Keyed against the wrong bar");
        List<JournalLine> lines = journalLineRepo.findByEntryIdOrderByLineNo(reversal.getId());
        assertThat(sum(lines, true)).isEqualByComparingTo(sum(lines, false));
        assertThat(debit(lines, "1200")).isEqualByComparingTo("1500");
        assertThat(credit(lines, "1000")).isEqualByComparingTo("1500");

        // Cash book: the opposite row on the same account, linked to the original and the reversal.
        List<CashTransaction> rows = settlementRows(receipt.uid());
        assertThat(rows).hasSize(2);
        CashTransaction out = rows.stream()
                .filter(t -> t.getDirection() == CashTxnDirection.OUT).findFirst().orElseThrow();
        assertThat(out.getTxnType()).isEqualTo(CashTxnType.AR_RECEIPT);
        assertThat(out.getAmount()).isEqualByComparingTo("1500");
        assertThat(out.getCashBankAccountId()).isEqualTo(cashIn.getCashBankAccountId());
        assertThat(out.getReversalOfTransactionId()).isEqualTo(cashIn.getId());
        assertThat(out.getJournalEntryRef()).isEqualTo(reversal.getUid());

        // AR control and the sub-ledger still agree; the customer owes the invoice again.
        setContext();
        ArReconciliationDto recon = reconciliationQuery.reconcile(company.getId());
        assertThat(recon.glControlBalance()).isEqualByComparingTo("1000");
        assertThat(recon.difference()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceService.currentBalance(company.getId(), customerId).balance())
                .isEqualByComparingTo("1000");
    }

    @Test
    void reverse_twice_isRefused_andChangesNothing() {
        openItem(new BigDecimal("800"));
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, new BigDecimal("800"), TZS, LocalDate.now(), "CASH",
                null, List.of()));
        receiptService.reverse(receipt.uid(), "Duplicate entry");
        long entries = journalEntryRepo.findByCompanyId(company.getId(), Pageable.unpaged())
                .getTotalElements();

        assertThatThrownBy(() -> receiptService.reverse(receipt.uid(), "Again"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been reversed")
                .hasMessageNotContaining(receipt.uid());
        assertThat(journalEntryRepo.findByCompanyId(company.getId(), Pageable.unpaged())
                .getTotalElements()).isEqualTo(entries);
        assertThat(settlementRows(receipt.uid())).hasSize(2);
    }

    @Test
    void reverse_withoutReason_isRefused() {
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, new BigDecimal("300"), TZS, LocalDate.now(), "CASH",
                null, List.of()));
        assertThatThrownBy(() -> receiptService.reverse(receipt.uid(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThat(arReceiptRepo.findByUid(receipt.uid()).orElseThrow().getReversedAt()).isNull();
    }

    @Test
    void reverse_inClosedPeriod_isRefused() {
        LocalDate lastMonth = LocalDate.now().withDayOfMonth(1).minusDays(1);
        assumeThat(lastMonth.getYear()).isEqualTo(LocalDate.now().getYear());
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, new BigDecimal("400"), TZS, lastMonth, "CASH",
                null, List.of()));
        var period = fiscalCalendarService.listPeriods(company.getId()).stream()
                .filter(p -> !lastMonth.isBefore(p.startDate()) && !lastMonth.isAfter(p.endDate()))
                .findFirst().orElseThrow();
        fiscalCalendarService.closePeriod(period.uid());
        setContext();

        assertThatThrownBy(() -> receiptService.reverse(receipt.uid(), "Wrong amount"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("closed");
        assertThat(arReceiptRepo.findByUid(receipt.uid()).orElseThrow().getReversedAt()).isNull();
        assertThat(settlementRows(receipt.uid())).hasSize(1);
    }

    // -------------------------------------------------------------------------

    private ArInvoiceDto openItem(BigDecimal amount) {
        return openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid, amount, TZS,
                LocalDate.now(), LocalDate.now().plusDays(30), null));
    }

    private List<CashTransaction> settlementRows(String receiptUid) {
        return cashTxnRepo.findByCompanyIdAndSourceRef(company.getId(), receiptUid).stream()
                .sorted(Comparator.comparing(CashTransaction::getId)).toList();
    }

    private static BigDecimal sum(List<JournalLine> lines, boolean debit) {
        return lines.stream()
                .map(l -> debit ? l.getDebitAmount() : l.getCreditAmount())
                .map(v -> v != null ? v : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal debit(List<JournalLine> lines, String code) {
        Long id = accountId(code);
        return lines.stream().filter(l -> id.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal credit(List<JournalLine> lines, String code) {
        Long id = accountId(code);
        return lines.stream().filter(l -> id.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Long accountId(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account " + code + " not seeded"));
    }
}
