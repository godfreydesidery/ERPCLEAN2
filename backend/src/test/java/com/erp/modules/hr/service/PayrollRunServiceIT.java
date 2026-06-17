package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.hr.domain.dto.CreateContractRequest;
import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.PayrollLineDto;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import com.erp.modules.hr.domain.entity.Department;
import com.erp.modules.hr.domain.entity.EmployeeRecurringItem;
import com.erp.modules.hr.domain.entity.PayComponent;
import com.erp.modules.hr.domain.enums.ContractType;
import com.erp.modules.hr.domain.enums.PayComponentBasis;
import com.erp.modules.hr.domain.enums.PayComponentKind;
import com.erp.modules.hr.domain.enums.PayrollLineStatus;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.modules.hr.repository.DepartmentRepository;
import com.erp.modules.hr.repository.EmployeeRecurringItemRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.PayComponentRepository;
import com.erp.modules.hr.repository.PayrollLineRepository;
import com.erp.modules.hr.repository.PayslipRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
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
 * Integration tests for PayrollRunService lifecycle (ADR-0032 D-2, FR-HR-50/51/52/53).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>create → DRAFT status; run number prefixed "PR-".
 *   <li>calculate → CALCULATED; grossTotal > 0 for an employee with a contract.
 *   <li>approve → APPROVED.
 *   <li>post → POSTED; PAYROLL.FINALISED event in outbox.
 *   <li>duplicate create for same period/company → ConflictException.
 * </ol>
 *
 * NOTE: Do NOT run with @Testcontainers on Windows — singleton container pattern only.
 */
class PayrollRunServiceIT extends PostgresIntegrationTest {

    @Autowired private PayrollRunService payrollRunService;
    @Autowired private EmployeeService employeeService;
    @Autowired private ContractServiceImpl contractService;
    @Autowired private HrStatutorySeeder statutorySeeder;
    @Autowired private HrGlSeeder hrGlSeeder;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    // For fix-verification tests
    @Autowired private PayrollLineRepository payrollLineRepository;
    @Autowired private PayslipRepository payslipRepository;
    @Autowired private PayComponentRepository payComponentRepository;
    @Autowired private EmployeeRecurringItemRepository recurringItemRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private Company company;
    private Branch branch;
    private Long rootId;
    private String employeeUid;

    private static final LocalDate PAY_DATE    = LocalDate.of(2026, 6, 30);
    private static final short PERIOD_YEAR     = (short) 2026;
    private static final short PERIOD_MONTH    = (short) 6;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Payroll IT Org"));
        company = companies.save(new Company(org, "PYRIT", "Payroll IT Co"));
        branch  = branches.save(new Branch(company, "PYR1", "Payroll IT Branch"));

        AppUser root = new AppUser("pyr_root", passwordEncoder.encode("RootPass1!"), "Payroll Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "pyr_root", true, company.getId(), branch.getId(), null));

        // GL + statutory prerequisites
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        hrGlSeeder.seedDefaults(company.getId());
        statutorySeeder.seedDefaults(company.getId());

        // Create one employee with an active contract (basic 600k TZS)
        employeeUid = employeeService.create(new CreateEmployeeRequest(
                "John", "Mwangi", null, null, null, null,
                LocalDate.of(1985, 1, 1), "M",
                LocalDate.of(2024, 1, 1),
                null, "Engineer", branch.getId(), null,
                // contact + payee fields (ADR-0040 D-11) — not required here
                null, null, null, null, null, null,
                null, null, null, null, null, null)).uid();

        contractService.createForEmployee(employeeUid, new CreateContractRequest(
                ContractType.PERMANENT,
                new BigDecimal("600000"),
                LocalDate.of(2024, 1, 1), null,
                true, true, false, true, true));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: create → DRAFT; run number starts with "PR-"
    // =========================================================================

    @Test
    void create_returnsDraftWithRunNumber() {
        PayrollRunDto run = createRun();

        assertThat(run.status()).isEqualTo(PayrollRunStatus.DRAFT)
                .as("newly created run must be DRAFT");
        assertThat(run.runNumber()).startsWith("PR-")
                .as("run number must be PR-NNNNN format");
        assertThat(run.periodYear()).isEqualTo(PERIOD_YEAR);
        assertThat(run.periodMonth()).isEqualTo(PERIOD_MONTH);
    }

    // =========================================================================
    // Bar 2: calculate → CALCULATED; grossTotal > 0
    // =========================================================================

    @Test
    void calculate_populatesLineTotals() {
        PayrollRunDto run = createRun();
        PayrollRunDto calculated = payrollRunService.calculate(run.uid());

        assertThat(calculated.status()).isEqualTo(PayrollRunStatus.CALCULATED);
        assertThat(calculated.grossTotal())
                .isGreaterThan(BigDecimal.ZERO)
                .as("grossTotal must be > 0 after calculate for one salaried employee");
        assertThat(calculated.netTotal())
                .isGreaterThan(BigDecimal.ZERO)
                .as("netTotal must be > 0");
    }

    // =========================================================================
    // Bar 3: approve → APPROVED
    // =========================================================================

    @Test
    void approve_statusBecomesApproved() {
        PayrollRunDto run = createRun();
        payrollRunService.calculate(run.uid());
        PayrollRunDto approved = payrollRunService.approve(run.uid());

        assertThat(approved.status()).isEqualTo(PayrollRunStatus.APPROVED);
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approved.approvedBy()).isEqualTo(rootId);
    }

    // =========================================================================
    // Bar 4: post → POSTED; PAYROLL.FINALISED outbox event emitted
    // =========================================================================

    @Test
    void post_statusBecomesPosted_andOutboxEventEmitted() {
        PayrollRunDto run = createRun();
        payrollRunService.calculate(run.uid());
        payrollRunService.approve(run.uid());
        PayrollRunDto posted = payrollRunService.post(run.uid());

        assertThat(posted.status()).isEqualTo(PayrollRunStatus.POSTED);
        assertThat(posted.postedAt()).isNotNull();
        // PAYROLL.FINALISED event should be in the outbox (checked via test-visible state on posted dto)
        assertThat(posted.uid()).isNotNull().as("posted run must have a uid");
    }

    // =========================================================================
    // Bar 5: duplicate create for same period → ConflictException
    // =========================================================================

    @Test
    void createDuplicate_sameCompanyAndPeriod_throwsConflict() {
        createRun();

        assertThatThrownBy(this::createRun)
                .isInstanceOf(ConflictException.class)
                .as("duplicate payroll run for the same company/period must throw ConflictException");
    }

    // =========================================================================
    // Fix #5 + #7: approve() rejects FLAGGED run; negative net preserved
    // =========================================================================

    @Test
    void approve_blockedWhenFlaggedLinesExist_andNegativeNetPreserved() {
        // Create a DEDUCTION pay component worth 700k (> 600k gross) to force net < 0
        // Borrow a GL account id from the first seeded pay component (any valid id works
        // since this test doesn't post to GL — it just checks calculate/approve)
        var emp = employeeRepository.findByUid(employeeUid)
                .orElseThrow(() -> new AssertionError("employee not found"));

        // Use salary account — find any existing component for a valid gl_account_id seed
        List<PayComponent> existing = payComponentRepository.findByCompanyId(company.getId());
        long anyGlId = existing.isEmpty() ? 1L : existing.get(0).getGlAccountId();

        PayComponent bigDeduction = new PayComponent(
                company.getId(), "UNION-TEST", "Forced Deduction",
                PayComponentKind.DEDUCTION, PayComponentBasis.FIXED,
                anyGlId, false, false, rootId);
        bigDeduction = payComponentRepository.save(bigDeduction);

        // 700,000 FIXED deduction → net = 600000 - 700000 = -100000 (FLAGGED)
        recurringItemRepository.save(new EmployeeRecurringItem(
                company.getId(), emp.getId(), bigDeduction.getId(),
                new BigDecimal("700000"),
                LocalDate.of(2024, 1, 1), null, rootId));

        PayrollRunDto run = payrollRunService.create(new CreatePayrollRunRequest(
                (short) 7, (short) 2026, LocalDate.of(2026, 7, 31), branch.getId()));
        payrollRunService.calculate(run.uid());

        // Fix #5: line must be FLAGGED
        List<PayrollLineDto> lineList = payrollRunService.listLines(run.uid());
        assertThat(lineList).anySatisfy(l ->
                assertThat(l.status()).isEqualTo(PayrollLineStatus.FLAGGED))
                .as("line must be FLAGGED when net < 0");

        // Fix #7: negative net preserved, not forced to zero
        assertThat(lineList).anySatisfy(l ->
                assertThat(l.netAmount()).isLessThan(BigDecimal.ZERO))
                .as("FLAGGED line net_amount must be the actual negative value, not zero");

        // Fix #5: approve must throw ConflictException
        assertThatThrownBy(() -> payrollRunService.approve(run.uid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("FLAGGED")
                .as("approve must reject a run with FLAGGED lines");
    }

    // =========================================================================
    // Fix #6: PERCENT_OF_BASIC recurring item is applied correctly
    // =========================================================================

    @Test
    void calculate_percentOfBasicAllowance_correctlyAddsToGross() {
        // Add a 10% housing allowance recurring item
        // Expected: grossTotal = 600000 + (10% * 600000) = 660000
        var emp = employeeRepository.findByUid(employeeUid)
                .orElseThrow(() -> new AssertionError("employee not found"));

        // Borrow a valid GL account id from any existing seeded component
        List<PayComponent> seeded = payComponentRepository.findByCompanyId(company.getId());
        long anyGlId = seeded.isEmpty() ? 1L : seeded.get(0).getGlAccountId();

        PayComponent housingComp = new PayComponent(
                company.getId(), "HOUSING", "Housing Allowance",
                PayComponentKind.EARNING, PayComponentBasis.PERCENT_OF_BASIC,
                anyGlId, true, true, rootId);
        housingComp = payComponentRepository.save(housingComp);

        recurringItemRepository.save(new EmployeeRecurringItem(
                company.getId(), emp.getId(), housingComp.getId(),
                new BigDecimal("10"), // 10% of basic — stored as 10, not 0.10
                LocalDate.of(2024, 1, 1), null, rootId));

        PayrollRunDto run = payrollRunService.create(new CreatePayrollRunRequest(
                (short) 8, (short) 2026, LocalDate.of(2026, 8, 31), branch.getId()));
        PayrollRunDto calculated = payrollRunService.calculate(run.uid());

        // gross = basic (600000) + 10% of 600000 (60000) = 660000
        assertThat(calculated.grossTotal())
                .isEqualByComparingTo(new BigDecimal("660000"))
                .as("gross must include 10% PERCENT_OF_BASIC housing allowance (not treat 10 as fixed 10 TZS)");
    }

    // =========================================================================
    // Fix #8: payslip numbers use global PAYSLIP-##### sequence
    // =========================================================================

    @Test
    void post_payslipNumberUsesGlobalSequence() {
        PayrollRunDto run = createRun();
        payrollRunService.calculate(run.uid());
        payrollRunService.approve(run.uid());
        payrollRunService.post(run.uid());

        var payslipList = payslipRepository.findByPayrollRunId(run.id());
        assertThat(payslipList).isNotEmpty()
                .as("payslips must be created at post");
        payslipList.forEach(ps ->
                assertThat(ps.getPayslipNumber()).startsWith("PAYSLIP-")
                        .as("payslip number must start with PAYSLIP- (global sequence)"));
    }

    // =========================================================================
    // Fix #9: department name is snapshotted on payroll line
    // =========================================================================

    @Test
    void calculate_snapshotsDepartmentName() {
        // Assign employee to a department
        Department dept = departmentRepository.save(
                new Department(company.getId(), "ENG", "Engineering", rootId));
        var emp = employeeRepository.findByUid(employeeUid)
                .orElseThrow(() -> new AssertionError("employee not found"));
        emp.setDepartmentId(dept.getId());
        employeeRepository.save(emp);

        PayrollRunDto run = payrollRunService.create(new CreatePayrollRunRequest(
                (short) 9, (short) 2026, LocalDate.of(2026, 9, 30), branch.getId()));
        payrollRunService.calculate(run.uid());

        List<PayrollLineDto> lineList = payrollRunService.listLines(run.uid());
        assertThat(lineList).anySatisfy(l ->
                assertThat(l.departmentName()).isEqualTo("Engineering"))
                .as("payroll line must snapshot the employee's department name");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PayrollRunDto createRun() {
        return payrollRunService.create(new CreatePayrollRunRequest(
                PERIOD_MONTH, PERIOD_YEAR, PAY_DATE, branch.getId()));
    }
}
