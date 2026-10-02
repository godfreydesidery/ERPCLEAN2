package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ap.service.ApOpeningBalanceService;
import com.erp.modules.ap.service.ApPaymentService;
import com.erp.modules.ap.service.SupplierBillService;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.ar.service.ArOpeningBalanceService;
import com.erp.modules.ar.service.ArReceiptService;
import com.erp.modules.cashbank.domain.dto.CashGlReconciliationDto;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.cashbank.service.CashGlReconciliationQuery;
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
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.parties.service.SupplierService;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.tax.domain.dto.CreateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtTypeDto;
import com.erp.modules.tax.domain.enums.WhtKind;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Live-test defects 3–6 against real Postgres.
 *
 * <ul>
 *   <li><b>3.</b> A supplier payment of 400,000 with 20,000 WHT credited the bank 380,000 in the GL
 *       but wrote a 400,000 outflow to the cash book. The cash that leaves is the net; the cash
 *       book must equal the GL bank credit, and the cash-vs-GL reconciliation must read 0. Same on
 *       the AR side (a receipt the customer withheld from).</li>
 *   <li><b>4.</b> The WHT register printed the literal "Supplier" for every AP certificate.</li>
 *   <li><b>5.</b> Paying a HELD bill answered "not found".</li>
 *   <li><b>6.</b> A bill line accepted {@code vatRate: 18} (1800%).</li>
 * </ul>
 */
class WhtCashBookParityIT extends PostgresIntegrationTest {

    @Autowired private ApPaymentService        apPaymentService;
    @Autowired private ApOpeningBalanceService apOpeningBalanceService;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private ApPaymentRepository     apPayments;
    @Autowired private SupplierBillService     supplierBillService;
    @Autowired private SupplierService         supplierService;
    @Autowired private ArReceiptService        arReceiptService;
    @Autowired private ArOpeningBalanceService arOpeningBalanceService;
    @Autowired private ArGlSeeder              arGlSeeder;
    @Autowired private CustomerService         customerService;
    @Autowired private WhtTypeService          whtTypeService;
    @Autowired private WhtRegisterService      whtRegisterService;
    @Autowired private WhtCaptureService       whtCaptureService;
    @Autowired private JournalEntryRepository  journalEntryRepo;
    @Autowired private JournalLineRepository   journalLineRepo;
    @Autowired private CashTransactionRepository cashTxns;
    @Autowired private CashGlReconciliationQuery cashGlRecon;
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private CashBankSeeder          cashBankSeeder;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;
    @Autowired private JdbcTemplate            jdbc;

    private static final String     TZS      = "TZS";
    private static final BigDecimal GROSS    = new BigDecimal("400000");
    private static final BigDecimal WHT      = new BigDecimal("20000");
    private static final BigDecimal NET_CASH = new BigDecimal("380000");

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private SupplierDto supplier;
    private SupplierDto supplier2;
    private String  customerUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("WHT Parity IT Org"));
        company    = companies.save(new Company(org, "WHTP", "WHT Parity IT Co"));
        branch     = branches.save(new Branch(company, "WHTP1", "WHT Parity Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("whtp_root", passwordEncoder.encode("WhtP4r1ty!Xx"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        actAsRoot();

        supplier  = newSupplier("Mbasha Holdings Ltd");
        supplier2 = newSupplier("Kariakoo Traders");
        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Joseph Ulimboka",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

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

    // ---- 3. cash book == GL bank leg --------------------------------------------------------

    @Test
    void supplierPaymentWithWht_cashBookOutflowEqualsGlBankCredit_andReconciliationIsZero() {
        SupplierBillDto bill = apOpeningBalance(supplier, "OB-P-1", GROSS);
        WhtTypeDto type = paymentWhtType("WHT-5-PAY");

        ApPaymentDto pay = apPaymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), GROSS, LocalDate.now(), "BANK_TRANSFER", "TT-9",
                null, type.uid(), WHT));

        Long entryId = journalEntryRepo.findByUid(pay.glEntryUid()).orElseThrow().getId();
        Long cashGl = accountOf(pay.cashBankAccountUid());
        BigDecimal glBankCredit = journalLineRepo.findByEntryIdOrderByLineNo(entryId).stream()
                .filter(l -> cashGl.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(glBankCredit).isEqualByComparingTo(NET_CASH);

        List<CashTransaction> book = cashTxns.findByCompanyIdAndSourceRef(company.getId(), pay.uid());
        assertThat(book).singleElement().satisfies(t ->
                assertThat(t.getAmount()).as("cash book outflow == GL bank credit (net of WHT)")
                        .isEqualByComparingTo(glBankCredit));

        CashGlReconciliationDto recon = cashGlRecon.reconcileOne(pay.cashBankAccountUid());
        assertThat(recon.difference()).as("cash vs GL").isEqualByComparingTo(BigDecimal.ZERO);

        // The payment header now records what was withheld and the certificate it produced.
        assertThat(pay.whtAmount()).isEqualByComparingTo(WHT);
        assertThat(pay.whtTransactionUid()).isNotBlank();
        // AP still relieved the bill at gross.
        assertThat(pay.amount()).isEqualByComparingTo(GROSS);
    }

    @Test
    void customerReceiptWithWht_cashBookInflowEqualsGlBankDebit_andReconciliationIsZero() {
        arOpeningBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid, GROSS, TZS, LocalDate.now(), LocalDate.now().plusDays(30), null));
        WhtTypeDto type = whtTypeService.create(new CreateWhtTypeRequest(
                companyUid, "WHT-5-REC", "5% receipt WHT", WhtKind.WHT_ON_RECEIPT, new BigDecimal("5")));

        ArReceiptDto rc = arReceiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, GROSS, TZS, LocalDate.now(), "CASH", null,
                List.of(), null, type.uid(), WHT));
        actAsRoot();

        List<CashTransaction> book = cashTxns.findByCompanyIdAndSourceRef(company.getId(), rc.uid());
        assertThat(book).singleElement().satisfies(t ->
                assertThat(t.getAmount()).isEqualByComparingTo(NET_CASH));
        String accountUid = jdbc.queryForObject(
                "SELECT a.uid FROM cash_bank_accounts a JOIN cash_transactions t"
                        + " ON t.cash_bank_account_id = a.id WHERE t.source_ref = ?",
                String.class, rc.uid());
        assertThat(cashGlRecon.reconcileOne(accountUid).difference())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void paymentRunWithWht_acrossTwoSuppliers_isRefused_andForOneSupplierBooksTheNet() {
        SupplierBillDto b1 = apOpeningBalance(supplier, "OB-R-1", GROSS);
        SupplierBillDto b2 = apOpeningBalance(supplier2, "OB-R-2", new BigDecimal("100000"));
        WhtTypeDto type = paymentWhtType("WHT-5-RUN");

        assertThatThrownBy(() -> apPaymentService.paymentRun(new PaymentRunRequest(
                companyUid, null, LocalDate.now().plusDays(60), LocalDate.now(), "BANK_TRANSFER",
                "RUN-1", List.of(b1.uid(), b2.uid()), null, type.uid(), WHT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single supplier");

        ApPaymentDto run = apPaymentService.paymentRun(new PaymentRunRequest(
                companyUid, supplier.uid(), LocalDate.now().plusDays(60), LocalDate.now(),
                "BANK_TRANSFER", "RUN-2", List.of(b1.uid()), null, type.uid(), WHT));
        assertThat(cashTxns.findByCompanyIdAndSourceRef(company.getId(), run.uid()))
                .singleElement().satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo(NET_CASH));
        assertThat(cashGlRecon.reconcileOne(run.cashBankAccountUid()).difference())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void whtAtOrAboveTheAmountPaid_orWithoutAType_isAFriendlyBadRequest() {
        SupplierBillDto bill = apOpeningBalance(supplier, "OB-V-1", GROSS);
        WhtTypeDto type = paymentWhtType("WHT-5-VAL");

        assertThatThrownBy(() -> apPaymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), GROSS, LocalDate.now(), "CASH", null, null, type.uid(), GROSS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The withholding tax must be less than the amount paid.");
        assertThatThrownBy(() -> apPaymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), GROSS, LocalDate.now(), "CASH", null, null, null, WHT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choose the withholding tax type for the amount withheld.");
    }

    // ---- 4. the register names the supplier -------------------------------------------------

    @Test
    void whtRegister_namesTheRealSupplier_forNewCertificates_andForOldPlaceholderOnes() {
        SupplierBillDto bill = apOpeningBalance(supplier, "OB-N-1", GROSS);
        WhtTypeDto type = paymentWhtType("WHT-5-NAME");
        apPaymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), GROSS, LocalDate.now(), "CASH", null, null, type.uid(), WHT));

        // A certificate written the way every AP payment wrote it before the fix.
        whtCaptureService.captureOnPayment(company.getId(), branch.getId(), type.uid(),
                supplier2.id(), "Supplier", null, "01LEGACYPAYMENTUID00000000",
                new BigDecimal("100000"), new BigDecimal("5000"), TZS, LocalDate.now(), null, rootId);

        String stored = jdbc.queryForObject(
                "SELECT party_name FROM wht_transactions WHERE company_id = ? AND party_id = ?",
                String.class, company.getId(), supplier.id());
        assertThat(stored).as("new rows store the real name").isEqualTo("Mbasha Holdings Ltd");

        WhtRegisterDto reg = whtRegisterService.getRegister(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertThat(reg.payableRows()).extracting(r -> r.partyName())
                .containsExactlyInAnyOrder("Mbasha Holdings Ltd", "Kariakoo Traders");
    }

    // ---- 5. a held bill says it is on hold ---------------------------------------------------

    @Test
    void payingAHeldBill_isAFriendlyConflict_notANotFound() {
        SupplierBillDto bill = apOpeningBalance(supplier, "OB-H-1", GROSS);
        jdbc.update("UPDATE supplier_bills SET status = 'HELD' WHERE uid = ?", bill.uid());

        assertThatThrownBy(() -> apPaymentService.paySingle(new PaySingleBillRequest(
                companyUid, bill.uid(), GROSS, LocalDate.now(), "CASH", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This bill is on hold and can't be paid until it is released.");

        // Named in a payment run, it is refused with the reason rather than silently skipped.
        SupplierBillDto ok = apOpeningBalance(supplier, "OB-H-2", new BigDecimal("1000"));
        assertThatThrownBy(() -> apPaymentService.paymentRun(new PaymentRunRequest(
                companyUid, supplier.uid(), LocalDate.now().plusDays(60), LocalDate.now(), "CASH",
                null, List.of(ok.uid(), bill.uid()))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("on hold");
    }

    // ---- 6. VAT rate scale ------------------------------------------------------------------

    @Test
    void aBillLineVatRateOf18_isRefused_butPoint18IsAccepted() {
        BillLineRequest bad = new BillLineRequest(null, null, null, "Cement", BigDecimal.ONE,
                new BigDecimal("1000"), VatStatus.STANDARD, new BigDecimal("18"), null);
        assertThatThrownBy(() -> supplierBillService.enterBill(new EnterBillRequest(
                companyUid, supplier.uid(), "SI-VAT-1", null, LocalDate.now(), null,
                null, TZS, null, List.of(bad))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Line 1: the VAT rate must be a fraction between 0 and 1 — enter 0.18 for 18%.");

        BillLineRequest good = new BillLineRequest(null, null, null, "Cement", BigDecimal.ONE,
                new BigDecimal("1000"), VatStatus.STANDARD, new BigDecimal("0.18"), null);
        SupplierBillDto entered = supplierBillService.enterBill(new EnterBillRequest(
                companyUid, supplier.uid(), "SI-VAT-2", null, LocalDate.now(), null,
                null, TZS, null, List.of(good)));
        assertThat(entered.vatAmount()).isEqualByComparingTo("180");
    }

    // -------------------------------------------------------------------------

    private void actAsRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "whtp_root", true, company.getId(), branch.getId(), null));
    }

    private SupplierDto newSupplier(String name) {
        return supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, name,
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
    }

    private WhtTypeDto paymentWhtType(String code) {
        return whtTypeService.create(new CreateWhtTypeRequest(
                companyUid, code, code, WhtKind.WHT_ON_PAYMENT, new BigDecimal("5")));
    }

    private SupplierBillDto apOpeningBalance(SupplierDto s, String ref, BigDecimal amount) {
        return apOpeningBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, s.uid(), amount, TZS, LocalDate.now(), LocalDate.now().plusDays(30), ref));
    }

    private Long accountOf(String cashBankAccountUid) {
        return jdbc.queryForObject("SELECT gl_account_id FROM cash_bank_accounts WHERE uid = ?",
                Long.class, cashBankAccountUid);
    }
}
