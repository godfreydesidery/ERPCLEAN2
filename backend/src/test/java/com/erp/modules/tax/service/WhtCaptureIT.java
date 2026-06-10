package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ap.service.ApOpeningBalanceService;
import com.erp.modules.ap.service.ApPaymentService;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.ar.service.ArOpeningBalanceService;
import com.erp.modules.ar.service.ArReceiptService;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.parties.service.SupplierService;
import com.erp.modules.tax.domain.dto.CreateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtTypeDto;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.modules.tax.repository.WhtTransactionRepository;
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
 * Integration tests for WHT_ON_PAYMENT (AP) and WHT_ON_RECEIPT (AR) GL postings and register
 * (ADR-0017 D-9, FR-VAT-10/11, BR-VAT-12). These are the riskiest untested paths: money moves
 * only if the WHT leg is correctly appended and the cash leg is correctly reduced.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>AP payment with WHT: GL entry = DR AP gross / CR cash (gross−wht) / CR WHT_PAYABLE wht;
 *       balanced; bill settled at gross; wht_transactions row created.
 *   <li>AR receipt with WHT: GL entry = DR cash (settled−wht) / DR WHT_RECEIVABLE wht / CR AR settled;
 *       balanced; wht_transactions row created.
 *   <li>WhtRegisterService groups transactions by kind with correct totals.
 * </ol>
 */
class WhtCaptureIT extends PostgresIntegrationTest {

    // ---- AP services ----
    @Autowired private ApPaymentService       apPaymentService;
    @Autowired private ApOpeningBalanceService apOpeningBalanceService;
    @Autowired private ApGlSeeder             apGlSeeder;
    @Autowired private SupplierService        supplierService;
    @Autowired private SupplierBillRepository billRepo;

    // ---- AR services ----
    @Autowired private ArReceiptService       arReceiptService;
    @Autowired private ArOpeningBalanceService arOpeningBalanceService;
    @Autowired private ArGlSeeder             arGlSeeder;
    @Autowired private CustomerService        customerService;

    // ---- WHT services under test ----
    @Autowired private WhtTypeService           whtTypeService;
    @Autowired private WhtRegisterService       whtRegisterService;
    @Autowired private WhtTransactionRepository whtTxnRepo;

    // ---- GL repos ----
    @Autowired private JournalEntryRepository   journalEntryRepo;
    @Autowired private JournalLineRepository    journalLineRepo;
    @Autowired private ChartOfAccountRepository accountRepo;

    // ---- GL seed services ----
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService  fiscalCalendarService;
    @Autowired private GlConfigService        glConfigService;
    @Autowired private CashBankSeeder         cashBankSeeder;

    // ---- IAM ----
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  supplierUid;
    private String  creditCustomerUid;

    // Gross bill / receipt amounts and WHT
    private static final BigDecimal GROSS_1000 = new BigDecimal("1000.00");
    private static final BigDecimal WHT_20     = new BigDecimal("20.00");      // 2% of 1000
    private static final BigDecimal CASH_980   = new BigDecimal("980.00");     // 1000 - 20
    private static final BigDecimal GROSS_1180 = new BigDecimal("1180.00");
    private static final BigDecimal WHT_24     = new BigDecimal("24.00");       // ~2% of 1180 (rounded)
    private static final BigDecimal CASH_1156  = new BigDecimal("1156.00");     // 1180 - 24
    private static final String     TZS        = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("WHT Capture IT Org"));
        company    = companies.save(new Company(org, "WHTIT", "WHT Capture IT Co"));
        branch     = branches.save(new Branch(company, "WHTIT1", "WHT Capture IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("wht_root", passwordEncoder.encode("WhtR00t1!Xx"), "WHT Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "wht_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "WHT Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS)).uid();

        creditCustomerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "WHT Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null)).uid();

        // Seed all GL prerequisites (CoA includes 1400/1500/2300/2400; glConfig includes
        // VAT_INPUT/VAT_DUE/WHT_PAYABLE/WHT_RECEIVABLE)
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Scenario (a): AP payment with WHT_ON_PAYMENT
    // =========================================================================

    /**
     * ADR-0017 D-9, FR-VAT-10.
     *
     * <p>Pay a 1000 gross bill, withholding 20 (2%). Expected GL:
     * <pre>
     *   DR ACCOUNTS_PAYABLE (2100)   1000
     *   CR cash/bank                  980
     *   CR WHT_PAYABLE      (2400)     20
     * </pre>
     * Balanced. Bill settled at gross. WHT certificate row created (kind=WHT_ON_PAYMENT,
     * partyKind=SUPPLIER, whtAmount=20, whtNumber starts "WHT-", journalEntryRef set).
     */
    @Test
    void paySingle_withWht_postsCorrectGlLegsAndCreatesWhtTransaction() {
        // Create a 1000 TZS AP opening-balance bill (gross, no VAT — simplest path to a payable)
        SupplierBillDto bill = apOpeningBalance("OB-WHT-001", GROSS_1000);

        // Create a 2% WHT_ON_PAYMENT type
        WhtTypeDto whtType = whtTypeService.create(new CreateWhtTypeRequest(
                companyUid, "WHT-2PCT-PAY", "2% Payment WHT",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));

        // Pay the bill with WHT
        var payment = apPaymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), GROSS_1000,
                LocalDate.now(), "CASH", null,
                null,           // cashBankAccountUid — use default
                whtType.uid(),  // whtTypeUid
                WHT_20));       // whtAmount

        assertThat(payment.glEntryUid()).isNotBlank();

        // Bill settled at gross
        var refreshedBill = billRepo.findByUid(bill.uid()).orElseThrow();
        assertThat(refreshedBill.getStatus())
                .as("bill must be PAID after full settlement (BR-AP)")
                .isEqualTo(SupplierBillStatus.PAID);
        assertThat(refreshedBill.getOutstandingAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);

        // --- GL assertions ---
        var entry = journalEntryRepo.findByUid(payment.glEntryUid()).orElseThrow();
        Long entryId = entry.getId();

        // Balanced
        BigDecimal sumDR = sumDebit(entryId);
        BigDecimal sumCR = sumCredit(entryId);
        assertThat(sumDR)
                .as("AP payment GL entry must be balanced")
                .isEqualByComparingTo(sumCR);

        // DR ACCOUNTS_PAYABLE (2100) == gross 1000
        Long apAcctId = accountId("2100");
        assertThat(debitOnAccount(entryId, apAcctId))
                .as("DR ACCOUNTS_PAYABLE (2100) must equal gross 1000 (ADR-0017 D-9)")
                .isEqualByComparingTo(GROSS_1000);

        // CR cash/bank == gross − wht == 980
        // The default cash account is 1000 (Cash) — seeded by cashBankSeeder.seedDefaults
        Long cashAcctId = accountId("1000");
        assertThat(creditOnAccount(entryId, cashAcctId))
                .as("CR cash (1000) must equal gross minus WHT (980)")
                .isEqualByComparingTo(CASH_980);

        // CR WHT_PAYABLE (2400) == wht 20
        Long whtPayableId = accountId("2400");
        assertThat(creditOnAccount(entryId, whtPayableId))
                .as("CR WHT_PAYABLE (2400) must equal WHT withheld (20)")
                .isEqualByComparingTo(WHT_20);

        // --- WHT transaction row ---
        var whtTxns = whtTxnRepo.findByCompanyIdAndCertificateDateBetween(
                company.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(whtTxns)
                .as("one wht_transactions row must be created for this payment")
                .hasSize(1);

        var whtTxn = whtTxns.get(0);
        assertThat(whtTxn.getWhtNumber())
                .as("certificate number must start with 'WHT-'")
                .startsWith("WHT-");
        assertThat(whtTxn.getKind())
                .as("kind must be WHT_ON_PAYMENT")
                .isEqualTo(WhtKind.WHT_ON_PAYMENT);
        assertThat(whtTxn.getPartyKind())
                .as("partyKind must be SUPPLIER")
                .isEqualTo("SUPPLIER");
        assertThat(whtTxn.getWhtAmount())
                .as("whtAmount on the certificate must equal 20")
                .isEqualByComparingTo(WHT_20);
        assertThat(whtTxn.getJournalEntryRef())
                .as("journalEntryRef must be linked back to the GL entry (ADR-0017 D-9)")
                .isEqualTo(payment.glEntryUid());
    }

    // =========================================================================
    // Scenario (b): AR receipt with WHT_ON_RECEIPT
    // =========================================================================

    /**
     * ADR-0017 D-9, FR-VAT-11.
     *
     * <p>Customer pays 1180 gross invoice, withholding 24 (~2%). Expected GL:
     * <pre>
     *   DR cash/bank                   1156  (1180 − 24)
     *   DR WHT_RECEIVABLE    (1500)       24
     *   CR ACCOUNTS_RECEIVABLE (1200)   1180
     * </pre>
     * Balanced. wht_transactions row created (kind=WHT_ON_RECEIPT, partyKind=CUSTOMER).
     */
    @Test
    void recordReceipt_withWht_postsCorrectGlLegsAndCreatesWhtTransaction() {
        // Create an AR opening balance open item for 1180 (simulates a finalised credit invoice)
        arOpeningBalance(GROSS_1180, LocalDate.now());

        // Create a 2% WHT_ON_RECEIPT type
        WhtTypeDto whtType = whtTypeService.create(new CreateWhtTypeRequest(
                companyUid, "WHT-2PCT-REC", "2% Receipt WHT",
                WhtKind.WHT_ON_RECEIPT, new BigDecimal("2.0000")));

        // Record the receipt with WHT: customer pays 1156 cash, withholds 24
        ArReceiptDto receipt = arReceiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                GROSS_1180, TZS, LocalDate.now(), "CASH", null,
                List.of(),     // auto-allocate
                null,          // cashBankAccountUid — use default
                whtType.uid(), // whtTypeUid
                WHT_24));      // whtAmount

        assertThat(receipt.glEntryUid()).isNotBlank();

        // --- GL assertions ---
        var entry = journalEntryRepo.findByUid(receipt.glEntryUid()).orElseThrow();
        Long entryId = entry.getId();

        // Balanced
        BigDecimal sumDR = sumDebit(entryId);
        BigDecimal sumCR = sumCredit(entryId);
        assertThat(sumDR)
                .as("AR receipt GL entry must be balanced")
                .isEqualByComparingTo(sumCR);

        // CR ACCOUNTS_RECEIVABLE (1200) == settled amount 1180
        Long arAcctId = accountId("1200");
        assertThat(creditOnAccount(entryId, arAcctId))
                .as("CR ACCOUNTS_RECEIVABLE (1200) must equal settled amount 1180 (ADR-0017 D-9)")
                .isEqualByComparingTo(GROSS_1180);

        // DR cash/bank == settled − wht == 1156
        Long cashAcctId = accountId("1000");
        assertThat(debitOnAccount(entryId, cashAcctId))
                .as("DR cash (1000) must equal received cash (1180 − 24 = 1156)")
                .isEqualByComparingTo(CASH_1156);

        // DR WHT_RECEIVABLE (1500) == wht 24
        Long whtReceivableId = accountId("1500");
        assertThat(debitOnAccount(entryId, whtReceivableId))
                .as("DR WHT_RECEIVABLE (1500) must equal WHT withheld by customer (24)")
                .isEqualByComparingTo(WHT_24);

        // --- WHT transaction row ---
        var whtTxns = whtTxnRepo.findByCompanyIdAndCertificateDateBetween(
                company.getId(), LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(whtTxns)
                .as("one wht_transactions row must be created for this receipt")
                .hasSize(1);

        var whtTxn = whtTxns.get(0);
        assertThat(whtTxn.getWhtNumber()).startsWith("WHT-");
        assertThat(whtTxn.getKind())
                .as("kind must be WHT_ON_RECEIPT")
                .isEqualTo(WhtKind.WHT_ON_RECEIPT);
        assertThat(whtTxn.getPartyKind())
                .as("partyKind must be CUSTOMER")
                .isEqualTo("CUSTOMER");
        assertThat(whtTxn.getWhtAmount())
                .as("whtAmount on the certificate must equal 24")
                .isEqualByComparingTo(WHT_24);
        assertThat(whtTxn.getJournalEntryRef())
                .as("journalEntryRef must be linked back to the GL entry (ADR-0017 D-9)")
                .isEqualTo(receipt.glEntryUid());
    }

    // =========================================================================
    // Scenario (c): WhtRegisterService groups by kind with correct totals
    // =========================================================================

    /**
     * ADR-0017 D-9, FR-VAT-12.
     *
     * <p>Record one WHT_ON_PAYMENT (20) and one WHT_ON_RECEIPT (24).
     * The register for today's period must show:
     *  - payableRows with 1 row, totalPayable == 20
     *  - receivableRows with 1 row, totalReceivable == 24
     */
    @Test
    void whtRegister_groupsByKind_withCorrectTotals() {
        // AP payment with WHT_ON_PAYMENT
        SupplierBillDto bill = apOpeningBalance("OB-REG-001", GROSS_1000);
        WhtTypeDto payType = whtTypeService.create(new CreateWhtTypeRequest(
                companyUid, "WHT-REG-PAY", "Reg Pay WHT",
                WhtKind.WHT_ON_PAYMENT, new BigDecimal("2.0000")));
        apPaymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), GROSS_1000,
                LocalDate.now(), "CASH", null,
                null, payType.uid(), WHT_20));

        // AR receipt with WHT_ON_RECEIPT — needs a fresh open item
        arOpeningBalance(GROSS_1180, LocalDate.now());
        WhtTypeDto recType = whtTypeService.create(new CreateWhtTypeRequest(
                companyUid, "WHT-REG-REC", "Reg Receipt WHT",
                WhtKind.WHT_ON_RECEIPT, new BigDecimal("2.0000")));
        arReceiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                GROSS_1180, TZS, LocalDate.now(), "CASH", null,
                List.of(), null, recType.uid(), WHT_24));

        // Restore context (receipt service TX boundary may clear it)
        RequestContext.set(new RequestContext.Principal(
                rootId, "wht_root", true, company.getId(), branch.getId(), null));

        WhtRegisterDto register = whtRegisterService.getRegister(
                company.getId(),
                LocalDate.now().withDayOfMonth(1),
                LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()));

        assertThat(register.payableRows())
                .as("register must have 1 WHT_ON_PAYMENT row")
                .hasSize(1);
        assertThat(register.totalPayable())
                .as("totalPayable must equal 20")
                .isEqualByComparingTo(WHT_20);

        assertThat(register.receivableRows())
                .as("register must have 1 WHT_ON_RECEIPT row")
                .hasSize(1);
        assertThat(register.totalReceivable())
                .as("totalReceivable must equal 24")
                .isEqualByComparingTo(WHT_24);

        // Spot-check the payable row shape
        var payRow = register.payableRows().get(0);
        assertThat(payRow.whtNumber()).startsWith("WHT-");
        assertThat(payRow.kind()).isEqualTo(WhtKind.WHT_ON_PAYMENT);
        assertThat(payRow.partyKind()).isEqualTo("SUPPLIER");
        assertThat(payRow.whtAmount()).isEqualByComparingTo(WHT_20);

        // Spot-check the receivable row shape
        var recRow = register.receivableRows().get(0);
        assertThat(recRow.whtNumber()).startsWith("WHT-");
        assertThat(recRow.kind()).isEqualTo(WhtKind.WHT_ON_RECEIPT);
        assertThat(recRow.partyKind()).isEqualTo("CUSTOMER");
        assertThat(recRow.whtAmount()).isEqualByComparingTo(WHT_24);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SupplierBillDto apOpeningBalance(String invoiceRef, BigDecimal amount) {
        return apOpeningBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, TZS,
                LocalDate.now(), LocalDate.now().plusDays(30), invoiceRef));
    }

    private void arOpeningBalance(BigDecimal amount, LocalDate date) {
        arOpeningBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, creditCustomerUid, amount, TZS,
                date, date.plusDays(30), null));
    }

    private Long accountId(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account not found: " + code));
    }

    private BigDecimal sumDebit(Long entryId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCredit(Long entryId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal debitOnAccount(Long entryId, Long accountId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .filter(l -> accountId.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal creditOnAccount(Long entryId, Long accountId) {
        return journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .filter(l -> accountId.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
