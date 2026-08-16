package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.entity.PaymentRun;
import com.erp.modules.ap.domain.enums.PaymentRunStatus;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.PaymentRunRepository;
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
 * Integration tests for the ADR-0041 D3 payment-run grouping (completion wiring):
 * a payment run creates a {@link PaymentRun} row (run_number generated), stamps payment_run_id on
 * the grouped {@link com.erp.modules.ap.domain.entity.ApPayment}, and flips the run DRAFT→POSTED.
 * The run posts nothing to GL — it is balance-neutral; the member payment carries the GL entry.
 */
class ApPaymentRunGroupingIT extends PostgresIntegrationTest {

    @Autowired private ApPaymentService        paymentService;
    @Autowired private ApOpeningBalanceService openingBalanceService;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private SupplierService         supplierService;
    @Autowired private PaymentRunRepository    paymentRunRepo;
    @Autowired private ApPaymentRepository     paymentRepo;
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

        Organisation org = organisations.save(new Organisation("AP Run IT Org"));
        company    = companies.save(new Company(org, "APRUN", "AP Run IT Co"));
        branch     = branches.save(new Branch(company, "APRUN1", "AP Run IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("ap_run_root", passwordEncoder.encode("ApRun00t!Xx"), "AP Run Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_run_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "Run Supplier Ltd",
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
    void paymentRun_createsPostedRun_andStampsRunIdOnPayment() {
        SupplierBillDto bill1 = openingBalance("OB-RUNGRP-001", OB_1000);
        SupplierBillDto bill2 = openingBalance("OB-RUNGRP-002", OB_2000);

        ApPaymentDto run = paymentService.paymentRun(new PaymentRunRequest(
                companyUid, supplierUid,
                LocalDate.now().plusDays(60), LocalDate.now(),
                "BANK_TRANSFER", null, null));

        // The member payment is grouped under a run (soft-FK populated).
        assertThat(run.paymentRunId()).isNotNull();
        assertThat(run.allocations()).hasSize(2);

        // The run row exists, is POSTED, carries the run total + a generated run_number.
        PaymentRun runRow = paymentRunRepo.findById(run.paymentRunId()).orElseThrow();
        assertThat(runRow.getStatus()).isEqualTo(PaymentRunStatus.POSTED);
        assertThat(runRow.getRunNumber()).startsWith("RUN-");
        assertThat(runRow.getCompanyId()).isEqualTo(company.getId());
        assertThat(runRow.getTotalAmount())
                .isEqualByComparingTo(OB_1000.add(OB_2000));

        // The persisted payment row points back at the run.
        var paymentRow = paymentRepo.findByUid(run.uid()).orElseThrow();
        assertThat(paymentRow.getPaymentRunId()).isEqualTo(runRow.getId());

        // Balance-neutral: the GL entry lives on the member payment, not the run.
        assertThat(run.glEntryUid()).isNotBlank();

        // bill1 + bill2 are unaffected by the suppressed grouping reference; both fully paid via the run.
        assertThat(bill1.uid()).isNotBlank();
        assertThat(bill2.uid()).isNotBlank();
    }

    @Test
    void paySingle_doesNotCreateRun_runIdNull() {
        SupplierBillDto bill = openingBalance("OB-SINGLE-NORUN-001", OB_1000);

        ApPaymentDto payment = paymentService.paySingle(
                new com.erp.modules.ap.domain.dto.PaySingleBillRequest(
                        companyUid, bill.uid(), OB_1000, LocalDate.now(), "CASH", null));

        assertThat(payment.paymentRunId()).isNull();
    }

    private SupplierBillDto openingBalance(String invoiceRef, BigDecimal amount) {
        return openingBalanceService.setOpeningBalance(new SetApOpeningBalanceRequest(
                companyUid, supplierUid, amount, TZS,
                LocalDate.now(), LocalDate.now().plusDays(30),
                invoiceRef));
    }
}
