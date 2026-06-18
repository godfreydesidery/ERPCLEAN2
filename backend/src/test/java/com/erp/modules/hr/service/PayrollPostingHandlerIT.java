package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.hr.domain.dto.CreateContractRequest;
import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import com.erp.modules.hr.domain.entity.EmployeeLoan;
import com.erp.modules.hr.domain.enums.ContractType;
import com.erp.modules.hr.domain.enums.LoanStatus;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.modules.hr.repository.EmployeeLoanRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
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
 * Integration tests for PayrollPostingHandler via outbox dispatch (ADR-0032 D-9, FR-HR-06).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>post() → PAYROLL.FINALISED event in outbox.
 *   <li>dispatch → balanced GL journal: sum(debit) == sum(credit) for the run uid.
 *   <li>run.glEntryUid is set after dispatch.
 *   <li>Idempotency: double-dispatch produces exactly one journal entry.
 *   <li>reverse() → PAYROLL.REVERSED event; dispatch → PAYROLL_REVERSAL journal entry.
 * </ol>
 *
 * NOTE: Do NOT run with @Testcontainers on Windows — singleton container pattern only.
 */
class PayrollPostingHandlerIT extends PostgresIntegrationTest {

    @Autowired private PayrollRunService payrollRunService;
    @Autowired private EmployeeService employeeService;
    @Autowired private ContractServiceImpl contractService;
    @Autowired private HrStatutorySeeder statutorySeeder;
    @Autowired private HrGlSeeder hrGlSeeder;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    // For fix #1/#2 loan-deduction balance test
    @Autowired private EmployeeLoanRepository loanRepository;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private ChartOfAccountRepository chartOfAccountRepository;
    @Autowired private GlConfigRepository glConfigRepository;

    private Company company;
    private Branch branch;
    private Long rootId;
    private String employeeUid;

    private static final LocalDate PAY_DATE   = LocalDate.of(2026, 6, 30);
    private static final short PERIOD_YEAR    = (short) 2026;
    private static final short PERIOD_MONTH   = (short) 6;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("GL Payroll IT Org"));
        company = companies.save(new Company(org, "GLPYR", "GL Payroll IT Co"));
        branch  = branches.save(new Branch(company, "GLP1", "GL Payroll IT Branch"));

        AppUser root = new AppUser("glp_root", passwordEncoder.encode("RootPass1!"), "GL Payroll Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "glp_root", true, company.getId(), branch.getId(), null));

        // GL + statutory prerequisites
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        hrGlSeeder.seedDefaults(company.getId());
        statutorySeeder.seedDefaults(company.getId());

        // One salaried employee
        employeeUid = employeeService.create(new CreateEmployeeRequest(
                "Amina", "Hassan", null, null, null, null,
                LocalDate.of(1990, 5, 15), "F",
                LocalDate.of(2024, 1, 1),
                null, "Accountant", branch.getId(), null,
                // contact + payee fields (ADR-0040 D-11) — not required here
                null, null, null, null, null, null,
                null, null, null, null, null, null)).uid();

        contractService.createForEmployee(employeeUid, new CreateContractRequest(
                ContractType.PERMANENT,
                new BigDecimal("800000"),
                LocalDate.of(2024, 1, 1), null,
                true, true, false, true, true));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: post() puts PAYROLL.FINALISED event into the outbox
    // =========================================================================

    @Test
    void post_emitsPayrollFinalisedEvent() {
        PayrollRunDto posted = createCalculateApprovePost();

        DomainEvent event = getPendingEvent(DomainEventType.PAYROLL_FINALISED, posted.uid());
        assertThat(event).isNotNull()
                .as("PAYROLL.FINALISED event must exist in outbox after post()");
        assertThat(event.getAggregateUid()).isEqualTo(posted.uid());
    }

    // =========================================================================
    // Bar 2: dispatch → balanced GL journal (DR == CR)
    // =========================================================================

    @Test
    void dispatch_producesBalancedJournalEntry() {
        PayrollRunDto posted = createCalculateApprovePost();
        DomainEvent event = getPendingEvent(DomainEventType.PAYROLL_FINALISED, posted.uid());

        dispatcher.dispatchOne(event.getId());

        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> posted.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries).hasSize(1).as("exactly one GL journal entry for the payroll run");

        Long entryId = entries.get(0).getId();
        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entryId);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2)
                .as("journal must have at least DR Salary + CR Net Wages lines");

        BigDecimal sumDebit  = lines.stream()
                .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumDebit).isEqualByComparingTo(sumCredit)
                .as("payroll GL journal must be balanced: DR == CR");
    }

    // =========================================================================
    // Bar 3: run.glEntryUid is set after dispatch
    // =========================================================================

    @Test
    void dispatch_setsGlEntryUidOnRun() {
        PayrollRunDto posted = createCalculateApprovePost();
        DomainEvent event = getPendingEvent(DomainEventType.PAYROLL_FINALISED, posted.uid());

        dispatcher.dispatchOne(event.getId());

        // Restore context for read
        RequestContext.set(new RequestContext.Principal(
                rootId, "glp_root", true, company.getId(), branch.getId(), null));

        PayrollRunDto after = payrollRunService.getByUid(posted.uid());
        assertThat(after.glEntryUid()).isNotNull()
                .as("run.glEntryUid must be populated after GL journal is posted");
    }

    // =========================================================================
    // Bar 4: idempotency — double dispatch yields exactly one journal entry
    // =========================================================================

    @Test
    void dispatchTwice_idempotent_onlyOneJournalEntry() {
        PayrollRunDto posted = createCalculateApprovePost();
        DomainEvent event = getPendingEvent(DomainEventType.PAYROLL_FINALISED, posted.uid());

        dispatcher.dispatchOne(event.getId());
        dispatcher.dispatchOne(event.getId()); // second dispatch — event already DISPATCHED

        long count = journalEntryRepo.findAll().stream()
                .filter(e -> posted.uid().equals(e.getSourceRef()))
                .count();
        assertThat(count).isEqualTo(1)
                .as("idempotent double-dispatch must produce exactly one GL entry");
    }

    // =========================================================================
    // Bar 5: reverse() emits PAYROLL.REVERSED; dispatch creates reversal entry
    // =========================================================================

    @Test
    void reverse_emitsReversedEvent_andDispatchCreatesReversalEntry() {
        PayrollRunDto posted = createCalculateApprovePost();

        // Dispatch posting event first (sets glEntryUid)
        DomainEvent postEvent = getPendingEvent(DomainEventType.PAYROLL_FINALISED, posted.uid());
        dispatcher.dispatchOne(postEvent.getId());

        // Restore context and reverse
        RequestContext.set(new RequestContext.Principal(
                rootId, "glp_root", true, company.getId(), branch.getId(), null));
        PayrollRunDto reversed = payrollRunService.reverse(posted.uid());
        assertThat(reversed.status()).isEqualTo(PayrollRunStatus.REVERSED);

        // PAYROLL.REVERSED event must be in outbox
        DomainEvent reversalEvent = getPendingEvent(DomainEventType.PAYROLL_REVERSED, posted.uid());
        assertThat(reversalEvent).isNotNull()
                .as("PAYROLL.REVERSED event must be in outbox after reverse()");

        // Dispatch the reversal — should create a second journal entry (the reversal)
        dispatcher.dispatchOne(reversalEvent.getId());

        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> posted.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries).hasSize(2)
                .as("two GL entries expected: original PAYROLL + PAYROLL_REVERSAL");
    }

    // =========================================================================
    // Fix #1/#2: GL journal balanced when employee has a loan deduction
    // =========================================================================

    @Test
    void dispatch_withLoanDeduction_journalStillBalanced() {
        // Attach an active loan to the employee — this adds a DEDUCTION line-item
        // that must post as CR to the loan-receivable account.
        // Without fix #1, the handler would not post the CR → DR > CR → balance fails.
        var emp = employeeRepository.findByUid(employeeUid)
                .orElseThrow(() -> new AssertionError("employee not found"));

        // Resolve loan-receivable GL account (seeded by hrGlSeeder)
        Long loanAccountId = glConfigRepository
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.EMPLOYEE_LOAN_RECEIVABLE)
                .map(cfg -> cfg.getAccountId())
                .orElseThrow(() -> new AssertionError("EMPLOYEE_LOAN_RECEIVABLE config not seeded"));

        // Loan: principal 100k, installment 50k — less than gross so net stays positive (no FLAGGED)
        // Loans now start PENDING (issue #14 lifecycle fix); activate it so payroll deducts it.
        EmployeeLoan testLoan = new EmployeeLoan(
                company.getId(), emp.getId(), "LN-TEST-001",
                new BigDecimal("100000"), new BigDecimal("50000"),
                loanAccountId,
                LocalDate.of(2024, 1, 1), "TZS", rootId);
        testLoan.setStatus(LoanStatus.ACTIVE);
        loanRepository.save(testLoan);

        PayrollRunDto posted = createCalculateApprovePost();
        DomainEvent event = getPendingEvent(DomainEventType.PAYROLL_FINALISED, posted.uid());
        dispatcher.dispatchOne(event.getId());

        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> posted.uid().equals(e.getSourceRef()))
                .toList();
        assertThat(entries).hasSize(1)
                .as("exactly one GL entry for run with loan deduction");

        Long entryId = entries.get(0).getId();
        var jLines = journalLineRepo.findByEntryIdOrderByLineNo(entryId);

        BigDecimal sumDebit  = jLines.stream()
                .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = jLines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumDebit).isEqualByComparingTo(sumCredit)
                .as("journal must balance (DR == CR) even when loan deduction creates a CR leg");

        // Also verify the loan-receivable account appears as a CR line
        boolean hasLoanCrLine = jLines.stream().anyMatch(l ->
                loanAccountId.equals(l.getAccountId())
                && l.getCreditAmount() != null
                && l.getCreditAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(hasLoanCrLine)
                .as("journal must contain CR line for loan-receivable account (fix #1)")
                .isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PayrollRunDto createCalculateApprovePost() {
        PayrollRunDto run = payrollRunService.create(new CreatePayrollRunRequest(
                PERIOD_MONTH, PERIOD_YEAR, PAY_DATE, branch.getId()));
        payrollRunService.calculate(run.uid());
        payrollRunService.approve(run.uid());
        return payrollRunService.post(run.uid());
    }

    private DomainEvent getPendingEvent(String eventType, String aggregateUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType())
                          && aggregateUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + eventType + " event found for run uid=" + aggregateUid));
    }
}
