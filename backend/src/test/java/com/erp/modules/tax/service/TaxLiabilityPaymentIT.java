package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ap.service.ApOpeningBalanceService;
import com.erp.modules.ap.service.ApPaymentService;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.hr.domain.dto.RecordStatutoryPaymentRequest;
import com.erp.modules.hr.domain.dto.StatutoryLiabilityBalanceDto;
import com.erp.modules.hr.domain.enums.StatutoryLiability;
import com.erp.modules.hr.service.HrGlSeeder;
import com.erp.modules.hr.service.PayrollStatutoryPaymentService;
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
import com.erp.modules.tax.domain.dto.AddVatAdjustmentRequest;
import com.erp.modules.tax.domain.dto.CreateWhtTypeRequest;
import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.RecordTaxPaymentRequest;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.dto.WhtPaymentResultDto;
import com.erp.modules.tax.domain.dto.WhtPeriodPaymentRequest;
import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtTypeDto;
import com.erp.modules.tax.domain.enums.VatAdjustmentReason;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ACC-07 — VAT Due, WHT Payable and the payroll statutory payables can be paid down in the books:
 * each payment posts DR the liability / CR the chosen cash/bank account through a cash transaction,
 * and over-payment is refused.
 */
class TaxLiabilityPaymentIT extends PostgresIntegrationTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2099-12-31T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired private VatReturnService               vatReturnService;
    @Autowired private VatAdjustmentService           vatAdjustmentService;
    @Autowired private WhtRegisterService             whtRegisterService;
    @Autowired private WhtTypeService                 whtTypeService;
    @Autowired private PayrollStatutoryPaymentService statutoryPayments;
    @Autowired private ApOpeningBalanceService        apOpeningBalanceService;
    @Autowired private ApPaymentService               apPaymentService;
    @Autowired private SupplierService                supplierService;
    @Autowired private ChartOfAccountService          chartOfAccountService;
    @Autowired private FiscalCalendarService          fiscalCalendarService;
    @Autowired private GlConfigService                glConfigService;
    @Autowired private ApGlSeeder                     apGlSeeder;
    @Autowired private ArGlSeeder                     arGlSeeder;
    @Autowired private HrGlSeeder                     hrGlSeeder;
    @Autowired private CashBankSeeder                 cashBankSeeder;
    @Autowired private GLPostingService               glPosting;
    @Autowired private GLConfigResolver               glConfig;
    @Autowired private PlatformTransactionManager     txManager;
    @Autowired private JdbcTemplate                   jdbc;
    @Autowired private OrganisationRepository         organisations;
    @Autowired private CompanyRepository              companies;
    @Autowired private BranchRepository               branches;
    @Autowired private AppUserRepository              users;
    @Autowired private PasswordEncoder                passwordEncoder;
    @Autowired private IamTestData                    testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  cashAccountUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Tax Pay IT Org"));
        company    = companies.save(new Company(org, "TXPAY", "Tax Pay IT Co"));
        branch     = branches.save(new Branch(company, "TXPAY1", "Tax Pay IT Branch"));
        companyUid = company.getUid();
        AppUser root = new AppUser("txpay_root", passwordEncoder.encode("TxPay0t1!xx"), "Tax Pay Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        asRoot();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
        hrGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());
        cashAccountUid = jdbc.queryForObject(
                "SELECT uid FROM cash_bank_accounts WHERE company_id = ? ORDER BY id LIMIT 1",
                String.class, company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void filedVatReturn_isPaidInParts_andVatDueClears() {
        YearMonth jan = YearMonth.of(LocalDate.now().getYear(), 1);
        VatReturnDto r = vatReturnService.open(
                new OpenVatReturnRequest(companyUid, jan.getYear(), jan.getMonthValue()));
        assertThatThrownBy(() -> vatReturnService.recordPayment(r.uid(),
                new RecordTaxPaymentRequest(cashAccountUid, jan.atDay(25), null, null)))
                .as("a DRAFT cannot be paid").isInstanceOf(ConflictException.class);

        vatAdjustmentService.addAdjustment(r.uid(), new AddVatAdjustmentRequest(
                VatAdjustmentReason.OTHER, VatAdjustmentSign.INCREASE, new BigDecimal("500"), "Under-declared"));
        VatReturnDto filed = vatReturnService.file(r.uid(),
                new FileVatReturnRequest("TRA-PAY", jan.plusMonths(1).atDay(15)));
        assertThat(filed.netVat()).isEqualByComparingTo("500");
        assertThat(balance("2300")).as("VAT Due credit 500").isEqualByComparingTo("-500");

        LocalDate payDate = jan.plusMonths(1).atDay(18);
        VatReturnDto part = vatReturnService.recordPayment(filed.uid(),
                new RecordTaxPaymentRequest(cashAccountUid, payDate, new BigDecimal("200"), "CN-1"));
        assertThat(part.paidAmount()).isEqualByComparingTo("200");
        assertThatThrownBy(() -> vatReturnService.recordPayment(filed.uid(),
                new RecordTaxPaymentRequest(cashAccountUid, payDate, new BigDecimal("301"), null)))
                .as("over-payment refused").isInstanceOf(IllegalArgumentException.class);

        VatReturnDto full = vatReturnService.recordPayment(filed.uid(),
                new RecordTaxPaymentRequest(cashAccountUid, payDate, null, "CN-2"));
        assertThat(full.paidAmount()).isEqualByComparingTo("500");
        assertThat(full.paymentReference()).isEqualTo("CN-2");
        assertThat(balance("2300")).as("VAT Due paid down to zero").isEqualByComparingTo("0");
        assertThat(cashTxnCount()).as("one cash transaction per payment").isEqualTo(2);
        assertThatThrownBy(() -> vatReturnService.recordPayment(filed.uid(),
                new RecordTaxPaymentRequest(cashAccountUid, payDate, null, null)))
                .as("nothing left to pay").isInstanceOf(ConflictException.class);
    }

    @Test
    void whtDeductedFromSuppliers_isPaidForThePeriod_andWhtPayableClears() {
        SupplierDto supplier = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "WHT Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        WhtTypeDto type = whtTypeService.create(new CreateWhtTypeRequest(
                companyUid, "WHT-5", "WHT 5%", WhtKind.WHT_ON_PAYMENT, new BigDecimal("5")));
        LocalDate today = LocalDate.now();
        for (String ref : List.of("OB-W-1", "OB-W-2")) {
            SupplierBillDto bill = apOpeningBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                    companyUid, supplier.uid(), new BigDecimal("100000"), "TZS", today,
                    today.plusDays(30), ref));
            apPaymentService.paySingle(new PaySingleBillRequest(companyUid, bill.uid(),
                    new BigDecimal("100000"), today, "CASH", null, null, type.uid(), new BigDecimal("5000")));
        }
        assertThat(balance("2400")).as("WHT Payable credit 10,000").isEqualByComparingTo("-10000");

        // One certificate is paid on its own, booked to the bank.
        WhtRegisterDto reg = whtRegisterService.getRegister(company.getId(), today, today);
        String firstUid = reg.payableRows().get(0).uid();
        assertThat(firstUid).isNotBlank();
        WhtPaymentResultDto one = whtRegisterService.remit(firstUid,
                new com.erp.modules.tax.domain.dto.WhtRemitRequest(
                        YearMonth.from(today).toString(), "TRA-W-1", cashAccountUid, today));
        assertThat(one.amountPaid()).isEqualByComparingTo("5000");

        // The rest of the period in one payment.
        WhtPaymentResultDto rest = whtRegisterService.payPeriod(new WhtPeriodPaymentRequest(
                company.getId(), today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()),
                cashAccountUid, today, "TRA-W-2"));
        assertThat(rest.certificatesRemitted()).isEqualTo(1);
        assertThat(rest.amountPaid()).isEqualByComparingTo("5000");
        assertThat(balance("2400")).as("WHT Payable paid down to zero").isEqualByComparingTo("0");
        assertThat(whtRegisterService.getRegister(company.getId(), today, today).payableRows())
                .allSatisfy(row -> assertThat(row.remitted()).isTrue());
        assertThatThrownBy(() -> whtRegisterService.payPeriod(new WhtPeriodPaymentRequest(
                company.getId(), today, today, cashAccountUid, today, "TRA-W-3")))
                .as("nothing left to pay").isInstanceOf(ConflictException.class);
    }

    @Test
    void payrollStatutoryLiability_isPaid_andCannotBeOverpaid() {
        // What a payroll posting leaves on PAYE Payable (2500).
        Long cid = company.getId();
        new TransactionTemplate(txManager).executeWithoutResult(st -> glPosting.post(
                new JournalEntryDraft(cid, branch.getId(), LocalDate.now(), "Payroll",
                        JournalSourceType.PAYROLL, "PR-TEST", null, rootId, List.of(
                        new LineDraft(glConfig.resolve(cid, GlConfigKey.SALARY_EXPENSE).getId(),
                                new BigDecimal("75000"), BigDecimal.ZERO, "TZS", "salary"),
                        new LineDraft(glConfig.resolve(cid, GlConfigKey.PAYE_PAYABLE).getId(),
                                BigDecimal.ZERO, new BigDecimal("75000"), "TZS", "paye")))));

        BigDecimal owed = statutoryPayments.outstanding().stream()
                .filter(b -> b.liability() == StatutoryLiability.PAYE)
                .map(StatutoryLiabilityBalanceDto::outstanding)
                .findFirst().orElseThrow();
        assertThat(owed).isEqualByComparingTo("75000");

        assertThatThrownBy(() -> statutoryPayments.pay(new RecordStatutoryPaymentRequest(
                StatutoryLiability.PAYE, cashAccountUid, LocalDate.now(), new BigDecimal("75001"), null)))
                .isInstanceOf(IllegalArgumentException.class);
        statutoryPayments.pay(new RecordStatutoryPaymentRequest(
                StatutoryLiability.PAYE, cashAccountUid, LocalDate.now(), new BigDecimal("75000"), "TRA-PAYE"));
        assertThat(balance("2500")).as("PAYE Payable paid down to zero").isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------

    private void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "txpay_root", true, company.getId(), branch.getId(), null));
    }

    private BigDecimal balance(String code) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(jl.debit_amount - jl.credit_amount), 0)
                FROM journal_lines jl JOIN chart_of_accounts a ON a.id = jl.account_id
                WHERE jl.company_id = ? AND a.account_code = ?
                """, BigDecimal.class, company.getId(), code);
    }

    private int cashTxnCount() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM cash_transactions WHERE company_id = ? AND txn_type = 'DIRECT_ENTRY'",
                Integer.class, company.getId());
        return n != null ? n : 0;
    }
}
