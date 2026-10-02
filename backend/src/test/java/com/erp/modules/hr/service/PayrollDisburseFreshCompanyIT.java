package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.service.CashDirectEntryService;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.hr.domain.dto.CreateContractRequest;
import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.DisburseRequest;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import com.erp.modules.hr.domain.enums.ContractType;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.bootstrap.CompanyProvisioningService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Live-test defect 3: on a freshly provisioned company no payroll run could reach PAID — disburse
 * answered 409 "Counter GL account 2550 does not allow manual posting". Disbursement reused the
 * USER direct-entry path, whose counter-account guards refuse 2550 Net Wages Payable (a
 * PAYROLL_CLEARING control account provisioned with allowManualPosting=false) by design.
 *
 * <p>The company goes through the real provisioning chain, so the accounts, flags and default
 * cash account under test are exactly what a new tenant gets.
 */
class PayrollDisburseFreshCompanyIT extends PostgresIntegrationTest {

    @Autowired private CompanyProvisioningService provisioning;
    @Autowired private PayrollRunService          payrollRunService;
    @Autowired private EmployeeService            employeeService;
    @Autowired private ContractServiceImpl        contractService;
    @Autowired private CashDirectEntryService     directEntryService;
    @Autowired private CashBankAccountRepository  cashAccounts;
    @Autowired private TransactionTemplate        tx;
    @Autowired private GLConfigResolver           glConfig;
    @Autowired private OrganisationRepository     organisations;
    @Autowired private CompanyRepository          companies;
    @Autowired private BranchRepository           branches;
    @Autowired private AppUserRepository          users;
    @Autowired private PasswordEncoder            passwordEncoder;
    @Autowired private IamTestData                testData;
    @Autowired private JdbcTemplate               jdbc;

    private static final LocalDate PAY_DATE = LocalDate.of(2026, 6, 30);

    private Company company;
    private Branch  hq;
    private Branch  arusha;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Payroll Fresh Org"));
        company = companies.save(new Company(org, "PYFR", "Payroll Fresh Co"));
        hq = new Branch(company, "PYFR1", "HQ");
        hq.setDefault(true);
        hq = branches.save(hq);
        arusha = branches.save(new Branch(company, "PYFR2", "Arusha"));

        AppUser root = new AppUser("pyfr_root", passwordEncoder.encode("RootPass12345"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        // Session sits in HQ; the payroll run below belongs to Arusha.
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "pyfr_root", true, company.getId(), hq.getId(), null));

        provisioning.provisionDefaults(company.getId(), "TZS", "TZS", List.of("TZS"));

        String employeeUid = employeeService.create(new CreateEmployeeRequest(
                "Asha", "Mrema", null, null, null, null,
                LocalDate.of(1990, 1, 1), "F",
                LocalDate.of(2024, 1, 1),
                null, "Clerk", arusha.getId(), null,
                null, null, null, null, null, null,
                null, null, null, null, null, null)).uid();
        contractService.createForEmployee(employeeUid, new CreateContractRequest(
                ContractType.PERMANENT, new BigDecimal("800000"),
                LocalDate.of(2024, 1, 1), null,
                true, true, false, true, true));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void freshCompany_calculateApprovePostDisburse_reachesPaid() {
        // The precondition the live run tripped over — provisioned exactly like this.
        ChartOfAccount netWages = resolve(company.getId(), GlConfigKey.NET_WAGES_PAYABLE);
        assertThat(netWages.getAccountCode()).isEqualTo("2550");
        assertThat(netWages.isAllowManualPosting()).isFalse();

        CashBankAccount cash = cashAccounts.findByCompanyIdAndIsDefaultTrue(company.getId())
                .orElseThrow();

        PayrollRunDto run = payrollRunService.create(new CreatePayrollRunRequest(
                (short) 6, (short) 2026, PAY_DATE, arusha.getId()));
        payrollRunService.calculate(run.uid());
        payrollRunService.approve(run.uid());
        PayrollRunDto posted = payrollRunService.post(run.uid());
        assertThat(posted.netTotal()).isPositive();

        PayrollRunDto paid = payrollRunService.disburse(run.uid(),
                new DisburseRequest(cash.getUid(), PAY_DATE));

        assertThat(paid.status()).isEqualTo(PayrollRunStatus.PAID);

        // The settlement: DR Net Wages Payable = net total, booked to the RUN's branch (Arusha),
        // not the session's (HQ) — it clears the liability the run credited there.
        List<Map<String, Object>> legs = jdbc.queryForList("""
                SELECT e.branch_id, l.branch_id AS line_branch, l.debit_amount
                FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                WHERE e.company_id = ? AND e.source_type = 'CASH_DIRECT' AND l.account_id = ?
                """, company.getId(), netWages.getId());
        assertThat(legs).hasSize(1);
        assertThat(((Number) legs.get(0).get("branch_id")).longValue()).isEqualTo(arusha.getId());
        assertThat(((Number) legs.get(0).get("line_branch")).longValue()).isEqualTo(arusha.getId());
        assertThat((BigDecimal) legs.get(0).get("debit_amount"))
                .isEqualByComparingTo(posted.netTotal());

        Long cashTxnBranch = jdbc.queryForObject(
                "SELECT branch_id FROM cash_transactions WHERE company_id = ? AND counter_gl_account_id = ?",
                Long.class, company.getId(), netWages.getId());
        assertThat(cashTxnBranch).isEqualTo(arusha.getId());
    }

    /** The user-facing direct entry still refuses a control account — only the system path is open. */
    @Test
    void userDirectEntry_toNetWagesPayable_isStillRefused() {
        ChartOfAccount netWages = resolve(company.getId(), GlConfigKey.NET_WAGES_PAYABLE);
        CashBankAccount cash = cashAccounts.findByCompanyIdAndIsDefaultTrue(company.getId())
                .orElseThrow();

        assertThatThrownBy(() -> directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                company.getUid(), cash.getUid(), CashTxnDirection.OUT, new BigDecimal("1000"),
                PAY_DATE, netWages.getUid(), "attempt")))
                .isInstanceOf(ConflictException.class);
    }

    /** GLConfigResolver is MANDATORY-propagation (it runs inside a posting) — give it a TX here. */
    private com.erp.modules.gl.domain.entity.ChartOfAccount resolve(Long companyId, GlConfigKey key) {
        return tx.execute(st -> glConfig.resolve(companyId, key));
    }
}
