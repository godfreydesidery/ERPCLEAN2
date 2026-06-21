package com.erp.modules.hr.service;

import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.service.CashDirectEntryService;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.DisburseRequest;
import com.erp.modules.hr.domain.dto.PayrollLineDto;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import com.erp.modules.hr.domain.entity.Department;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.EmployeeLoan;
import com.erp.modules.hr.domain.entity.EmployeeRecurringItem;
import com.erp.modules.hr.domain.entity.EmploymentContract;
import com.erp.modules.hr.domain.entity.PayComponent;
import com.erp.modules.hr.domain.entity.PayrollLine;
import com.erp.modules.hr.domain.entity.PayrollLineItem;
import com.erp.modules.hr.domain.entity.PayrollRun;
import com.erp.modules.hr.domain.entity.PayrollStatutorySnapshot;
import com.erp.modules.hr.domain.entity.Payslip;
import com.erp.modules.hr.domain.enums.EmploymentStatus;
import com.erp.modules.hr.domain.enums.PayComponentBasis;
import com.erp.modules.hr.domain.enums.PayComponentKind;
import com.erp.modules.hr.domain.enums.PaymentMethod;
import com.erp.modules.hr.domain.enums.PayrollLineStatus;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.modules.hr.domain.event.PayrollFinalisedPayload;
import com.erp.modules.hr.domain.event.PayrollReversedPayload;
import com.erp.modules.hr.repository.DepartmentRepository;
import com.erp.modules.hr.repository.EmployeeLoanRepository;
import com.erp.modules.hr.repository.EmployeeRecurringItemRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.EmploymentContractRepository;
import com.erp.modules.hr.repository.LeaveRequestRepository;
import com.erp.modules.hr.repository.PayComponentRepository;
import com.erp.modules.hr.repository.PayrollLineItemRepository;
import com.erp.modules.hr.repository.PayrollLineRepository;
import com.erp.modules.hr.repository.PayrollRunRepository;
import com.erp.modules.hr.repository.PayrollStatutorySnapshotRepository;
import com.erp.modules.hr.repository.PayslipRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PayrollRunServiceImpl implements PayrollRunService {

    private final PayrollRunRepository                runs;
    private final PayrollLineRepository               lines;
    private final PayrollLineItemRepository           lineItems;
    private final PayrollStatutorySnapshotRepository  snapshots;
    private final PayslipRepository                   payslips;
    private final EmployeeRepository                  employees;
    private final EmploymentContractRepository        contracts;
    private final EmployeeRecurringItemRepository     recurringItems;
    private final EmployeeLoanRepository              loans;
    private final PayComponentRepository              payComponents;
    private final DepartmentRepository                departments;
    private final LeaveRequestRepository              leaveRequests;
    private final StatutoryCalculator                 calculator;
    private final HrNumberGenerator                   numberGenerator;
    private final OutboxPublisher                     outbox;
    private final ScopeGuard                          scopeGuard;
    private final AuditService                        audit;
    private final CashDirectEntryService              cashDirectEntryService;
    private final GLConfigResolver                    glConfigResolver;
    private final CompanyRepository                   companies;

    /** Default working days per month (MONTHLY pay frequency, FR-HR-13). */
    private static final int DEFAULT_PERIOD_WORKING_DAYS = 22;

    public PayrollRunServiceImpl(PayrollRunRepository runs,
                                  PayrollLineRepository lines,
                                  PayrollLineItemRepository lineItems,
                                  PayrollStatutorySnapshotRepository snapshots,
                                  PayslipRepository payslips,
                                  EmployeeRepository employees,
                                  EmploymentContractRepository contracts,
                                  EmployeeRecurringItemRepository recurringItems,
                                  EmployeeLoanRepository loans,
                                  PayComponentRepository payComponents,
                                  DepartmentRepository departments,
                                  LeaveRequestRepository leaveRequests,
                                  StatutoryCalculator calculator,
                                  HrNumberGenerator numberGenerator,
                                  OutboxPublisher outbox,
                                  ScopeGuard scopeGuard,
                                  AuditService audit,
                                  CashDirectEntryService cashDirectEntryService,
                                  GLConfigResolver glConfigResolver,
                                  CompanyRepository companies) {
        this.runs                 = runs;
        this.lines                = lines;
        this.lineItems            = lineItems;
        this.snapshots            = snapshots;
        this.payslips             = payslips;
        this.employees            = employees;
        this.contracts            = contracts;
        this.recurringItems       = recurringItems;
        this.loans                = loans;
        this.payComponents        = payComponents;
        this.departments          = departments;
        this.leaveRequests        = leaveRequests;
        this.calculator           = calculator;
        this.numberGenerator      = numberGenerator;
        this.outbox               = outbox;
        this.scopeGuard           = scopeGuard;
        this.audit                = audit;
        this.cashDirectEntryService = cashDirectEntryService;
        this.glConfigResolver     = glConfigResolver;
        this.companies            = companies;
    }

    @Override
    public PayrollRunDto create(CreatePayrollRunRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);

        if (!runs.findActiveForPeriod(companyId, req.periodYear(), req.periodMonth()).isEmpty()) {
            throw new ConflictException("Active payroll run already exists for period "
                    + req.periodYear() + "-" + req.periodMonth());
        }

        String runNumber = numberGenerator.nextPayrollRun(companyId);
        PayrollRun run = new PayrollRun(companyId, req.branchId(), runNumber,
                req.periodYear(), req.periodMonth(), req.payDate(), p.userId());
        runs.save(run);
        audit.record(AuditEvent.of(AuditActions.HR_PAYROLL_RUN_CREATE, "payroll_runs", run.getId(), null));
        return toDto(run);
    }

    @Override
    @Transactional(readOnly = true)
    public PayrollRunDto getByUid(String uid) {
        PayrollRun run = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());
        return toDto(run);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PayrollRunDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return runs.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public PayrollRunDto calculate(String uid) {
        RequestContext.Principal p = RequestContext.get();
        PayrollRun run = requireByUid(uid);
        scopeGuard.assertCanActIn(p, run.getCompanyId());
        // Allow recalculate from DRAFT or CALCULATED or APPROVED (ADR-0032 D-2, D-6)
        if (run.getStatus() != PayrollRunStatus.DRAFT
                && run.getStatus() != PayrollRunStatus.CALCULATED
                && run.getStatus() != PayrollRunStatus.APPROVED) {
            throw new ConflictException(
                    "Payroll run can only be (re)calculated in DRAFT/CALCULATED/APPROVED status; current: "
                    + run.getStatus());
        }

        Long companyId = run.getCompanyId();
        LocalDate payDate = run.getPayDate();

        // Period bounds for leave overlap query (1st to last day of payroll month)
        YearMonth ym = YearMonth.of(run.getPeriodYear(), run.getPeriodMonth());
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd   = ym.atEndOfMonth();

        // Delete any previously calculated lines (idempotent recalculate).
        // Use deleteAllInBatch so the SQL DELETEs are sent to Postgres immediately — without a
        // flush the write-behind cache would batch the new INSERTs before the DELETEs, violating
        // the unique constraint uq_payroll_line_run_employee (HR-PAY-033).
        List<PayrollLine> existing = lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId());
        if (!existing.isEmpty()) {
            lineItems.deleteAllInBatch(
                    existing.stream()
                            .flatMap(l -> lineItems.findByPayrollLineIdOrderByItemKindAscLabelAsc(l.getId()).stream())
                            .toList());
            existing.forEach(l -> snapshots.findByPayrollLineId(l.getId()).ifPresent(snapshots::delete));
            lines.deleteAllInBatch(existing);
        }

        // Collect active employees with active contracts
        List<Employee> activeEmployees = employees.findByCompanyIdAndStatus(companyId, EmploymentStatus.ACTIVE);
        int headcount = (int) activeEmployees.stream()
                .filter(e -> contracts.findByCompanyIdAndEmployeeIdAndActiveTrue(companyId, e.getId()).isPresent())
                .count();

        BigDecimal grossRunTotal  = BigDecimal.ZERO;
        BigDecimal deductRunTotal = BigDecimal.ZERO;
        BigDecimal netRunTotal    = BigDecimal.ZERO;
        BigDecimal erCostTotal    = BigDecimal.ZERO;

        for (Employee emp : activeEmployees) {
            var contractOpt = contracts.findByCompanyIdAndEmployeeIdAndActiveTrue(companyId, emp.getId());
            if (contractOpt.isEmpty()) continue; // skip employees without active contract

            EmploymentContract contract = contractOpt.get();
            BigDecimal basicSalary = contract.getBaseSalaryAmount();
            String currency = CurrencyCode.value(contract.getCurrency());

            // --- Fix #9: snapshot department name ---
            String departmentName = null;
            if (emp.getDepartmentId() != null) {
                departmentName = departments.findById(emp.getDepartmentId())
                        .map(Department::getName).orElse(null);
            }

            PayrollLine line = new PayrollLine(run.getId(), companyId, emp.getId(),
                    emp.getEmployeeNumber(), emp.getFullName(),
                    departmentName, currency, p.userId());
            lines.save(line);

            // --- EARNINGS: base salary ---
            BigDecimal grossAmount    = basicSalary;
            BigDecimal taxableAmount  = basicSalary;
            BigDecimal pensionable    = basicSalary;

            lineItems.save(new PayrollLineItem(line.getId(), companyId, null,
                    "EARNING", "Basic Salary", basicSalary, null, p.userId()));

            // --- EARNINGS & DEDUCTIONS: recurring items (allowances / deductions) ---
            // Fix #6: use pc.getBasis() to detect percentage items
            // Fix #2: handle DEDUCTION-kind components properly
            BigDecimal voluntaryDeductTotal = BigDecimal.ZERO;
            List<EmployeeRecurringItem> recurring = recurringItems.findActiveForEmployee(companyId, emp.getId(), payDate);
            for (EmployeeRecurringItem ri : recurring) {
                var pcOpt = payComponents.findById(ri.getPayComponentId());
                if (pcOpt.isEmpty()) continue;
                PayComponent pc = pcOpt.get();
                BigDecimal itemAmount = ri.getAmountOrPercent();
                // Fix #6: use basis field, not scale/value heuristic
                if (pc.getBasis() == PayComponentBasis.PERCENT_OF_BASIC) {
                    itemAmount = basicSalary.multiply(itemAmount)
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                }
                if (pc.getKind() == PayComponentKind.EARNING) {
                    grossAmount   = grossAmount.add(itemAmount);
                    if (pc.isTaxable())     taxableAmount = taxableAmount.add(itemAmount);
                    if (pc.isPensionable()) pensionable   = pensionable.add(itemAmount);
                    lineItems.save(new PayrollLineItem(line.getId(), companyId, pc.getId(),
                            "EARNING", pc.getName(), itemAmount, pc.getGlAccountId(), p.userId()));
                } else {
                    // Fix #2: DEDUCTION kind — reduce net, record as DEDUCTION line item
                    voluntaryDeductTotal = voluntaryDeductTotal.add(itemAmount);
                    lineItems.save(new PayrollLineItem(line.getId(), companyId, pc.getId(),
                            "DEDUCTION", pc.getName(), itemAmount, pc.getGlAccountId(), p.userId()));
                }
            }

            // --- Fix #3: Unpaid leave pro-rata (FR-HR-13) ---
            BigDecimal unpaidLeaveDays = leaveRequests.sumApprovedUnpaidDaysOverlapping(
                    companyId, emp.getId(), periodStart, periodEnd);
            if (unpaidLeaveDays == null) unpaidLeaveDays = BigDecimal.ZERO;

            // --- STATUTORY DEDUCTIONS ---
            StatutoryCalculator.StatutoryResult stat = calculator.compute(
                    companyId, payDate, grossAmount, basicSalary, pensionable, headcount,
                    unpaidLeaveDays, DEFAULT_PERIOD_WORKING_DAYS);

            BigDecimal payeAmt    = contract.isPayeResident()  ? stat.paye()         : BigDecimal.ZERO;
            BigDecimal nssfEmpAmt = contract.isNssfMember()    ? stat.nssfEmployee() : BigDecimal.ZERO;
            BigDecimal nssfErAmt  = contract.isNssfMember()    ? stat.nssfEmployer() : BigDecimal.ZERO;
            BigDecimal wcfAmt     = contract.isWcfCovered()    ? stat.wcfEmployer()  : BigDecimal.ZERO;
            BigDecimal sdlAmt     = contract.isSdlCounted()    ? stat.sdlEmployer()  : BigDecimal.ZERO;
            BigDecimal heslbAmt   = contract.isHeslbBorrower() ? stat.heslb()        : BigDecimal.ZERO;

            // --- LOAN DEDUCTIONS ---
            List<EmployeeLoan> activeLoans = loans.findActiveWithBalance(companyId, emp.getId());
            BigDecimal loanDeductTotal = BigDecimal.ZERO;
            for (EmployeeLoan loan : activeLoans) {
                BigDecimal installment = loan.getInstallmentAmount()
                        .min(loan.getOutstandingAmount());
                loanDeductTotal = loanDeductTotal.add(installment);
                lineItems.save(new PayrollLineItem(line.getId(), companyId, null,
                        "DEDUCTION", "Loan Repayment: " + loan.getLoanNumber(),
                        installment, loan.getGlAccountId(), p.userId()));
            }

            // --- COMPUTE NET ---
            BigDecimal totalDeductions = payeAmt.add(nssfEmpAmt).add(heslbAmt)
                    .add(loanDeductTotal).add(voluntaryDeductTotal);
            BigDecimal netAmount = grossAmount.subtract(totalDeductions);
            // Fix #7: preserve negative net amount — do NOT force to zero (ADR-0032 D-6 chk_payroll_line_net_nonneg)
            if (netAmount.compareTo(BigDecimal.ZERO) < 0) {
                line.setStatus(PayrollLineStatus.FLAGGED);
                line.setFlagReason("Net amount negative after deductions: " + netAmount.toPlainString());
                // netAmount stays negative — DB allows it on FLAGGED lines
            }

            // Write summary to line
            line.setGrossAmount(grossAmount);
            line.setTaxableAmount(taxableAmount);
            line.setNetAmount(netAmount);
            line.setPayeAmount(payeAmt);
            line.setNssfEmployeeAmount(nssfEmpAmt);
            line.setHeslbAmount(heslbAmt);
            line.setVoluntaryDeductionTotal(voluntaryDeductTotal);
            line.setLoanDeductionTotal(loanDeductTotal);
            line.setNssfEmployerAmount(nssfErAmt);
            line.setWcfEmployerAmount(wcfAmt);
            line.setSdlEmployerAmount(sdlAmt);
            line.setUpdatedAt(Instant.now());
            line.setUpdatedBy(p.userId());

            // --- Payee snapshot (ADR-0040 D-11) ---
            // Snapshot the employee's payee target onto the line at calculate time.
            // If the method is set but the required target is missing/blank, FLAG the line.
            snapshotPayee(emp, line);

            // Statutory snapshot for reproducibility (NFR-HR-02)
            PayrollStatutorySnapshot snap = new PayrollStatutorySnapshot(
                    line.getId(), companyId, payDate, p.userId());
            snap.setAppliedPayeBandSetUid(stat.payeBandSetUid());
            snap.setAppliedNssfSetUid(stat.nssfSetUid());
            snap.setAppliedWcfSetUid(stat.wcfSetUid());
            snap.setAppliedSdlSetUid(stat.sdlSetUid());
            snap.setAppliedHeslbSetUid(stat.heslbSetUid());
            snapshots.save(snap);

            // Accumulate run totals
            BigDecimal employerCost = nssfErAmt.add(wcfAmt).add(sdlAmt);
            grossRunTotal  = grossRunTotal.add(grossAmount);
            deductRunTotal = deductRunTotal.add(totalDeductions);
            netRunTotal    = netRunTotal.add(netAmount);
            erCostTotal    = erCostTotal.add(employerCost);
        }

        run.setGrossTotal(grossRunTotal);
        run.setDeductionTotal(deductRunTotal);
        run.setNetTotal(netRunTotal);
        run.setEmployerCostTotal(erCostTotal);
        run.setStatus(PayrollRunStatus.CALCULATED);
        run.setCalculatedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        run.setUpdatedBy(p.userId());

        audit.record(AuditEvent.of(AuditActions.HR_PAYROLL_RUN_CALCULATE, "payroll_runs", run.getId(), null));
        return toDto(run);
    }

    @Override
    public PayrollRunDto approve(String uid) {
        RequestContext.Principal p = RequestContext.get();
        PayrollRun run = requireByUid(uid);
        scopeGuard.assertCanActIn(p, run.getCompanyId());
        requireStatus(run, PayrollRunStatus.CALCULATED);

        // Fix #5: Guard — no FLAGGED lines allowed (ADR-0032 D-2/D-6, BR-HR-07)
        List<PayrollLine> flaggedLines = lines.findByPayrollRunIdAndStatus(run.getId(), PayrollLineStatus.FLAGGED);
        if (!flaggedLines.isEmpty()) {
            String reasons = flaggedLines.stream()
                    .map(l -> l.getEmployeeNumber() + ": " + l.getFlagReason())
                    .collect(Collectors.joining("; "));
            throw new ConflictException("Cannot approve run with FLAGGED payroll lines. Resolve: " + reasons);
        }

        run.setStatus(PayrollRunStatus.APPROVED);
        run.setApprovedAt(Instant.now());
        run.setApprovedBy(p.userId());
        run.setUpdatedAt(Instant.now());
        run.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_PAYROLL_RUN_APPROVE, "payroll_runs", run.getId(), null));
        return toDto(run);
    }

    @Override
    public PayrollRunDto post(String uid) {
        RequestContext.Principal p = RequestContext.get();
        PayrollRun run = requireByUid(uid);
        scopeGuard.assertCanActIn(p, run.getCompanyId());
        requireStatus(run, PayrollRunStatus.APPROVED);

        // Publish outbox event; PayrollPostingHandler will post the GL journal and update glEntryUid
        outbox.publish(DomainEventType.PAYROLL_FINALISED, DomainEventType.AGG_PAYROLL_RUN,
                run.getId(), run.getUid(), run.getCompanyId(), run.getBranchId(),
                new PayrollFinalisedPayload(run.getUid(), run.getCompanyId(), run.getBranchId(),
                        run.getPayDate(), run.getRunNumber()));

        run.setStatus(PayrollRunStatus.POSTED);
        run.setPostedAt(Instant.now());
        run.setPostedBy(p.userId());
        run.setUpdatedAt(Instant.now());
        run.setUpdatedBy(p.userId());

        // Generate payslips
        generatePayslips(run, p.userId());

        audit.record(AuditEvent.of(AuditActions.HR_PAYROLL_RUN_POST, "payroll_runs", run.getId(), null));
        return toDto(run);
    }

    @Override
    public PayrollRunDto disburse(String uid, DisburseRequest req) {
        RequestContext.Principal p = RequestContext.get();
        PayrollRun run = requireByUid(uid);
        scopeGuard.assertCanActIn(p, run.getCompanyId());
        if (run.getStatus() != PayrollRunStatus.POSTED) {
            throw new ConflictException("Payroll run must be in POSTED status to disburse.");
        }
        if (run.getNetTotal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ConflictException("Payroll run net total must be > 0 to disburse.");
        }

        // Fix #4: post cash disbursement — DR NET_WAGES_PAYABLE / CR bank GL (ADR-0032 D-9, FR-HR-20)
        String companyUid = companies.findById(run.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Company", run.getCompanyId().toString()))
                .getUid();
        String netWagesPayableUid = glConfigResolver
                .resolve(run.getCompanyId(), GlConfigKey.NET_WAGES_PAYABLE)
                .getUid();
        LocalDate txnDate = req.txnDate() != null ? req.txnDate() : run.getPayDate();
        cashDirectEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyUid,
                req.cashBankAccountUid(),
                CashTxnDirection.OUT,
                run.getNetTotal(),
                txnDate,
                netWagesPayableUid,
                "Net wages disbursement for " + run.getRunNumber()
        ));

        run.setStatus(PayrollRunStatus.PAID);
        run.setPaidAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        run.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_PAYROLL_RUN_DISBURSE, "payroll_runs", run.getId(), null));
        return toDto(run);
    }

    @Override
    public PayrollRunDto reverse(String uid) {
        RequestContext.Principal p = RequestContext.get();
        PayrollRun run = requireByUid(uid);
        scopeGuard.assertCanActIn(p, run.getCompanyId());
        if (run.getStatus() != PayrollRunStatus.POSTED && run.getStatus() != PayrollRunStatus.PAID) {
            throw new ConflictException("Only POSTED or PAID payroll runs can be reversed.");
        }

        outbox.publish(DomainEventType.PAYROLL_REVERSED, DomainEventType.AGG_PAYROLL_RUN,
                run.getId(), run.getUid(), run.getCompanyId(), run.getBranchId(),
                new PayrollReversedPayload(run.getUid(), run.getCompanyId(), run.getBranchId(),
                        run.getPayDate(), run.getRunNumber(), run.getGlEntryUid()));

        run.setStatus(PayrollRunStatus.REVERSED);
        run.setReversedAt(Instant.now());
        run.setUpdatedAt(Instant.now());
        run.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_PAYROLL_RUN_REVERSE, "payroll_runs", run.getId(), null));
        return toDto(run);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayrollLineDto> listLines(String uid) {
        PayrollRun run = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());
        return lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId()).stream().map(this::toLineDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public String exportEftBatch(String runUid) {
        PayrollRun run = requireByUid(runUid);
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());

        List<PayrollLine> runLines = lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId());

        String header = "employee_number,employee_name,payee_method,payee_bank_name,"
                + "payee_account_name,payee_account_ref,net_amount,currency";

        String rows = runLines.stream()
                .map(l -> Stream.of(
                        csvEscape(l.getEmployeeNumber()),
                        csvEscape(l.getEmployeeName()),
                        csvEscape(l.getPayeeMethod()),
                        csvEscape(l.getPayeeBankName()),
                        csvEscape(l.getPayeeAccountName()),
                        csvEscape(l.getPayeeAccountRef()),
                        l.getNetAmount().toPlainString(),
                        csvEscape(CurrencyCode.value(l.getCurrency()))
                ).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n"));

        return rows.isEmpty() ? header : header + "\n" + rows;
    }

    // --- private helpers ---

    /**
     * Snapshots the employee's payee details onto the payroll line.
     * If the payment_method is set but its required account target is blank, flags the line.
     */
    private void snapshotPayee(Employee emp, PayrollLine line) {
        PaymentMethod method = emp.getPaymentMethod();
        if (method == null) {
            // No payment method configured — leave snapshot fields null, line stays OK.
            return;
        }

        line.setPayeeMethod(method.name());
        line.setPayeeBankName(emp.getBankName());
        line.setPayeeAccountName(emp.getBankAccountName());

        switch (method) {
            case BANK_TRANSFER -> {
                String acct = emp.getBankAccountNo();
                if (acct == null || acct.isBlank()) {
                    line.setStatus(PayrollLineStatus.FLAGGED);
                    line.setFlagReason("Payment method is BANK_TRANSFER but bank_account_no is missing on employee "
                            + emp.getEmployeeNumber());
                } else {
                    line.setPayeeAccountRef(acct);
                }
            }
            case MOBILE_MONEY -> {
                String mno = emp.getMobileMoneyNo();
                if (mno == null || mno.isBlank()) {
                    line.setStatus(PayrollLineStatus.FLAGGED);
                    line.setFlagReason("Payment method is MOBILE_MONEY but mobile_money_no is missing on employee "
                            + emp.getEmployeeNumber());
                } else {
                    line.setPayeeAccountRef(mno);
                }
            }
            case CASH, CHEQUE -> {
                // No account reference required for CASH/CHEQUE.
            }
        }
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        // Wrap in quotes if the value contains a comma, quote, or newline.
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void generatePayslips(PayrollRun run, Long userId) {
        List<PayrollLine> runLines = lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId());
        for (PayrollLine line : runLines) {
            if (payslips.findByPayrollRunIdAndEmployeeId(run.getId(), line.getEmployeeId()).isPresent()) continue;
            BigDecimal totalDeduct = line.getPayeAmount()
                    .add(line.getNssfEmployeeAmount())
                    .add(line.getHeslbAmount())
                    .add(line.getLoanDeductionTotal())
                    .add(line.getVoluntaryDeductionTotal());
            BigDecimal employerCost = line.getNssfEmployerAmount()
                    .add(line.getWcfEmployerAmount())
                    .add(line.getSdlEmployerAmount());
            // Fix #8: use global sequence for unique PAYSLIP-##### numbers (ADR-0032 D-14)
            String slipNumber = numberGenerator.nextPayslip(run.getCompanyId());
            payslips.save(new Payslip(run.getCompanyId(), run.getId(), line.getId(), line.getEmployeeId(),
                    slipNumber, run.getPayDate(),
                    line.getGrossAmount(), totalDeduct, line.getNetAmount(), employerCost,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, userId));
        }
    }

    private void requireStatus(PayrollRun run, PayrollRunStatus expected) {
        if (run.getStatus() != expected) {
            throw new ConflictException("Payroll run must be in " + expected + " status; current: " + run.getStatus());
        }
    }

    private PayrollRun requireByUid(String uid) {
        return runs.findByUid(uid).orElseThrow(() -> NotFoundException.of("PayrollRun", uid));
    }

    private PayrollRunDto toDto(PayrollRun r) {
        return new PayrollRunDto(r.getId(), r.getUid(), r.getCompanyId(), r.getBranchId(),
                r.getRunNumber(), r.getPeriodYear(), r.getPeriodMonth(), r.getPayDate(),
                r.getStatus(), r.getGrossTotal(), r.getDeductionTotal(), r.getNetTotal(),
                r.getEmployerCostTotal(), r.getCalculatedAt(), r.getApprovedAt(),
                r.getPostedAt(), r.getPaidAt(), r.getReversedAt(),
                r.getApprovedBy(), r.getPostedBy(), r.getGlEntryUid(), r.getReversalOfRunUid());
    }

    private PayrollLineDto toLineDto(PayrollLine l) {
        return new PayrollLineDto(l.getId(), l.getUid(), l.getPayrollRunId(), l.getEmployeeId(),
                l.getEmployeeNumber(), l.getEmployeeName(), l.getDepartmentName(),
                l.getGrossAmount(), l.getTaxableAmount(), l.getNetAmount(),
                l.getPayeAmount(), l.getNssfEmployeeAmount(), l.getHeslbAmount(),
                l.getVoluntaryDeductionTotal(), l.getLoanDeductionTotal(),
                l.getNssfEmployerAmount(), l.getWcfEmployerAmount(), l.getSdlEmployerAmount(),
                l.getStatus(), l.getFlagReason(), CurrencyCode.value(l.getCurrency()),
                l.getContractId(),
                l.getPayeeMethod(), l.getPayeeAccountRef(), l.getPayeeBankName(), l.getPayeeAccountName());
    }
}
