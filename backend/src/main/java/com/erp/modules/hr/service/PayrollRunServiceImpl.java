package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.DisburseRequest;
import com.erp.modules.hr.domain.dto.PayrollLineDto;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
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
import com.erp.modules.hr.domain.enums.PayrollLineStatus;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.modules.hr.domain.event.PayrollFinalisedPayload;
import com.erp.modules.hr.domain.event.PayrollReversedPayload;
import com.erp.modules.hr.repository.EmployeeLoanRepository;
import com.erp.modules.hr.repository.EmployeeRecurringItemRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.EmploymentContractRepository;
import com.erp.modules.hr.repository.PayComponentRepository;
import com.erp.modules.hr.repository.PayrollLineItemRepository;
import com.erp.modules.hr.repository.PayrollLineRepository;
import com.erp.modules.hr.repository.PayrollRunRepository;
import com.erp.modules.hr.repository.PayrollStatutorySnapshotRepository;
import com.erp.modules.hr.repository.PayslipRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
    private final StatutoryCalculator                 calculator;
    private final HrNumberGenerator                   numberGenerator;
    private final OutboxPublisher                     outbox;
    private final ScopeGuard                          scopeGuard;
    private final AuditService                        audit;

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
                                  StatutoryCalculator calculator,
                                  HrNumberGenerator numberGenerator,
                                  OutboxPublisher outbox,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.runs           = runs;
        this.lines          = lines;
        this.lineItems      = lineItems;
        this.snapshots      = snapshots;
        this.payslips       = payslips;
        this.employees      = employees;
        this.contracts      = contracts;
        this.recurringItems = recurringItems;
        this.loans          = loans;
        this.payComponents  = payComponents;
        this.calculator     = calculator;
        this.numberGenerator = numberGenerator;
        this.outbox         = outbox;
        this.scopeGuard     = scopeGuard;
        this.audit          = audit;
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
        requireStatus(run, PayrollRunStatus.DRAFT);

        Long companyId = run.getCompanyId();
        LocalDate payDate = run.getPayDate();

        // Delete any previously calculated lines (idempotent recalculate)
        List<PayrollLine> existing = lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId());
        if (!existing.isEmpty()) {
            lineItems.deleteAll(
                    existing.stream()
                            .flatMap(l -> lineItems.findByPayrollLineIdOrderByItemKindAscLabelAsc(l.getId()).stream())
                            .toList());
            existing.forEach(l -> snapshots.findByPayrollLineId(l.getId()).ifPresent(snapshots::delete));
            lines.deleteAll(existing);
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
            String currency = contract.getCurrency();

            PayrollLine line = new PayrollLine(run.getId(), companyId, emp.getId(),
                    emp.getEmployeeNumber(), emp.getFullName(),
                    null, currency, p.userId());
            lines.save(line);

            // --- EARNINGS: base salary ---
            BigDecimal grossAmount    = basicSalary;
            BigDecimal taxableAmount  = basicSalary;
            BigDecimal pensionable    = basicSalary;

            lineItems.save(new PayrollLineItem(line.getId(), companyId, null,
                    "EARNING", "Basic Salary", basicSalary, null, p.userId()));

            // --- EARNINGS: recurring items (allowances / benefits) ---
            List<EmployeeRecurringItem> recurring = recurringItems.findActiveForEmployee(companyId, emp.getId(), payDate);
            for (EmployeeRecurringItem ri : recurring) {
                var pcOpt = payComponents.findById(ri.getPayComponentId());
                if (pcOpt.isEmpty()) continue;
                PayComponent pc = pcOpt.get();
                BigDecimal itemAmount = ri.getAmountOrPercent();
                if (itemAmount.scale() > 0 && itemAmount.compareTo(BigDecimal.ONE) < 0) {
                    // percentage-based: apply against basic
                    itemAmount = basicSalary.multiply(itemAmount)
                            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                }
                grossAmount = grossAmount.add(itemAmount);
                if (pc.isTaxable())      taxableAmount = taxableAmount.add(itemAmount);
                if (pc.isPensionable())  pensionable   = pensionable.add(itemAmount);
                lineItems.save(new PayrollLineItem(line.getId(), companyId, pc.getId(),
                        "EARNING", pc.getName(), itemAmount, pc.getGlAccountId(), p.userId()));
            }

            // --- STATUTORY DEDUCTIONS ---
            StatutoryCalculator.StatutoryResult stat = calculator.compute(
                    companyId, payDate, grossAmount, basicSalary, pensionable, headcount);

            BigDecimal payeAmt          = contract.isPayeResident()   ? stat.paye()           : BigDecimal.ZERO;
            BigDecimal nssfEmpAmt       = contract.isNssfMember()      ? stat.nssfEmployee()   : BigDecimal.ZERO;
            BigDecimal nssfErAmt        = contract.isNssfMember()      ? stat.nssfEmployer()   : BigDecimal.ZERO;
            BigDecimal wcfAmt           = contract.isWcfCovered()      ? stat.wcfEmployer()    : BigDecimal.ZERO;
            BigDecimal sdlAmt           = contract.isSdlCounted()      ? stat.sdlEmployer()    : BigDecimal.ZERO;
            BigDecimal heslbAmt         = contract.isHeslbBorrower()   ? stat.heslb()          : BigDecimal.ZERO;

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
                    .add(loanDeductTotal);
            BigDecimal netAmount = grossAmount.subtract(totalDeductions);
            if (netAmount.compareTo(BigDecimal.ZERO) < 0) {
                line.setStatus(PayrollLineStatus.FLAGGED);
                line.setFlagReason("Net amount negative after deductions");
                netAmount = BigDecimal.ZERO;
            }

            // Write summary to line
            line.setGrossAmount(grossAmount);
            line.setTaxableAmount(taxableAmount);
            line.setNetAmount(netAmount);
            line.setPayeAmount(payeAmt);
            line.setNssfEmployeeAmount(nssfEmpAmt);
            line.setHeslbAmount(heslbAmt);
            line.setLoanDeductionTotal(loanDeductTotal);
            line.setNssfEmployerAmount(nssfErAmt);
            line.setWcfEmployerAmount(wcfAmt);
            line.setSdlEmployerAmount(sdlAmt);
            line.setUpdatedAt(Instant.now());
            line.setUpdatedBy(p.userId());

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
        // Disbursement via Cash & Bank is handled by the PayrollPostingHandler
        // which reacts to PAYROLL_FINALISED and calls CashDirectEntryService.
        // Here we only record the disbursement timestamp.
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

    // --- private helpers ---

    private void generatePayslips(PayrollRun run, Long userId) {
        List<PayrollLine> runLines = lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId());
        int seq = 1;
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
            String slipNumber = run.getRunNumber() + "-" + String.format("%04d", seq++);
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
                l.getStatus(), l.getFlagReason(), l.getCurrency());
    }
}
