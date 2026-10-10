package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.entity.JournalLine;
import com.erp.modules.gl.domain.enums.JournalSourceType;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * AP-03: reversing a wrong supplier payment — reversing journal (debits = credits), the money back
 * IN on the cash book, the bill payable again, refusals for a double reverse and a closed period.
 */
class ApPaymentReverseIT extends PostgresIntegrationTest {

    @Autowired private ApPaymentService paymentService;
    @Autowired private ApPaymentReversalService reversalService;
    @Autowired private ApOpeningBalanceService openingBalanceService;
    @Autowired private ApGlSeeder apGlSeeder;
    @Autowired private SupplierService supplierService;
    @Autowired private ApPaymentRepository paymentRepo;
    @Autowired private SupplierBillRepository billRepo;
    @Autowired private CashTransactionRepository cashTxnRepo;
    @Autowired private JournalEntryRepository journalEntries;
    @Autowired private JournalLineRepository journalLines;
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
    @Autowired private com.erp.platform.common.time.CompanyCalendar calendar;

    private Company company;
    private Branch branch;
    private Long rootId;
    private String companyUid;
    private String supplierUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("AP Reverse IT Org"));
        company = companies.save(new Company(org, "APREV", "AP Reverse IT Co"));
        branch = branches.save(new Branch(company, "APREV1", "AP Reverse IT Branch"));
        companyUid = company.getUid();
        AppUser root = new AppUser("ap_rev_root", passwordEncoder.encode("ApRev00t!Xx"), "AP Rev Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        setContext();

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "Reverse Supplier Ltd",
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

    @Test
    void reverse_restoresTheBill_postsABalancedReversal_andPutsTheCashBackInTheBook() {
        SupplierBillDto bill = openingBalance("OB-REV-001", new BigDecimal("1000"), today());
        ApPaymentDto paid = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), new BigDecimal("1000"), today(), "CASH", null));
        assertThat(billRepo.findByUid(bill.uid()).orElseThrow().getStatus())
                .isEqualTo(SupplierBillStatus.PAID);

        ApPaymentDto reversed = reversalService.reverse(paid.uid(), "Paid the wrong bill");

        assertThat(reversed.reversedAt()).isNotNull();
        assertThat(reversed.unallocatedAmount()).isEqualByComparingTo("0");
        var restored = billRepo.findByUid(bill.uid()).orElseThrow();
        assertThat(restored.getOutstandingAmount()).isEqualByComparingTo("1000");
        assertThat(restored.getStatus()).isEqualTo(SupplierBillStatus.MATCHED);

        JournalEntry reversal = journalEntries.findAll().stream()
                .filter(e -> company.getId().equals(e.getCompanyId())
                        && e.getSourceType() == JournalSourceType.AP_PAYMENT
                        && paid.uid().equals(e.getSourceRef())
                        && e.getReversalOfId() != null)
                .findFirst().orElseThrow();
        List<JournalLine> lines = journalLines.findByEntryIdOrderByLineNo(reversal.getId());
        assertThat(sum(lines, true)).isEqualByComparingTo(sum(lines, false));

        List<CashTransaction> rows = cashRows(paid.uid());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getDirection()).isEqualTo(CashTxnDirection.OUT);
        assertThat(rows.get(1).getDirection()).isEqualTo(CashTxnDirection.IN);
        assertThat(rows.get(1).getTxnType()).isEqualTo(CashTxnType.AP_PAYMENT);
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo("1000");
        assertThat(rows.get(1).getReversalOfTransactionId()).isEqualTo(rows.get(0).getId());
        assertThat(rows.get(1).getJournalEntryRef()).isEqualTo(reversal.getUid());

        // The bill can be paid again (it is payable, not stuck as PAID).
        ApPaymentDto again = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), new BigDecimal("1000"), today(), "CASH", null));
        assertThat(again.uid()).isNotEqualTo(paid.uid());
    }

    @Test
    void reverse_twice_isRefused() {
        SupplierBillDto bill = openingBalance("OB-REV-002", new BigDecimal("500"), today());
        ApPaymentDto paid = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), new BigDecimal("500"), today(), "CASH", null));
        reversalService.reverse(paid.uid(), "Duplicate");

        assertThatThrownBy(() -> reversalService.reverse(paid.uid(), "Again"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been reversed");
        assertThat(cashRows(paid.uid())).hasSize(2);
    }

    @Test
    void reverse_withoutReason_isRefused() {
        SupplierBillDto bill = openingBalance("OB-REV-003", new BigDecimal("500"), today());
        ApPaymentDto paid = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), new BigDecimal("500"), today(), "CASH", null));

        assertThatThrownBy(() -> reversalService.reverse(paid.uid(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThat(paymentRepo.findByUid(paid.uid()).orElseThrow().getReversedAt()).isNull();
    }

    @Test
    void reverse_inClosedPeriod_isRefused() {
        LocalDate lastMonth = today().withDayOfMonth(1).minusDays(1);
        assumeThat(lastMonth.getYear()).isEqualTo(today().getYear());
        SupplierBillDto bill = openingBalance("OB-REV-004", new BigDecimal("700"), lastMonth);
        ApPaymentDto paid = paymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), new BigDecimal("700"), lastMonth, "CASH", null));
        var period = fiscalCalendarService.listPeriods(company.getId()).stream()
                .filter(p -> !lastMonth.isBefore(p.startDate()) && !lastMonth.isAfter(p.endDate()))
                .findFirst().orElseThrow();
        fiscalCalendarService.closePeriod(period.uid());
        setContext();

        assertThatThrownBy(() -> reversalService.reverse(paid.uid(), "Wrong supplier"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("closed");
        assertThat(paymentRepo.findByUid(paid.uid()).orElseThrow().getReversedAt()).isNull();
        assertThat(cashRows(paid.uid())).hasSize(1);
    }

    // -------------------------------------------------------------------------

    private void setContext() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_rev_root", true, company.getId(), branch.getId(), null));
    }

    private SupplierBillDto openingBalance(String ref, BigDecimal amount, LocalDate date) {
        return openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, "TZS", date, date.plusDays(30), ref));
    }

    private List<CashTransaction> cashRows(String sourceRef) {
        return cashTxnRepo.findByCompanyIdAndSourceRef(company.getId(), sourceRef).stream()
                .sorted(Comparator.comparing(CashTransaction::getId)).toList();
    }

    private static BigDecimal sum(List<JournalLine> lines, boolean debit) {
        return lines.stream()
                .map(l -> debit ? l.getDebitAmount() : l.getCreditAmount())
                .map(v -> v != null ? v : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The business day in the company's zone (store UTC, derive in company zone). */
    private LocalDate today() {
        return calendar.today(company.getId());
    }
}
