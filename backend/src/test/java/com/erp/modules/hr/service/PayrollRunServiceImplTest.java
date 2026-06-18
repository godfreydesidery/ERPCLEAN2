package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.service.CashDirectEntryService;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.PayrollLine;
import com.erp.modules.hr.domain.entity.PayrollRun;
import com.erp.modules.hr.domain.enums.PaymentMethod;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.hr.domain.enums.PayrollLineStatus;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
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
import com.erp.platform.audit.AuditService;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for PayrollRunServiceImpl — payee snapshot resolution (ADR-0040 D-11)
 * and EFT CSV export formatting.
 */
class PayrollRunServiceImplTest {

    // ---- mocks (only what the constructor needs) ----
    private PayrollRunRepository               runs;
    private PayrollLineRepository              lines;
    private PayrollLineItemRepository          lineItems;
    private PayrollStatutorySnapshotRepository snapshots;
    private PayslipRepository                  payslips;
    private EmployeeRepository                 employees;
    private EmploymentContractRepository       contracts;
    private EmployeeRecurringItemRepository    recurringItems;
    private EmployeeLoanRepository             loans;
    private PayComponentRepository             payComponents;
    private DepartmentRepository               departments;
    private LeaveRequestRepository             leaveRequests;
    private StatutoryCalculator                calculator;
    private HrNumberGenerator                  numberGenerator;
    private OutboxPublisher                    outbox;
    private ScopeGuard                         scopeGuard;
    private AuditService                       audit;
    private CashDirectEntryService             cashDirectEntryService;
    private GLConfigResolver                   glConfigResolver;
    private CompanyRepository                  companies;

    private PayrollRunServiceImpl service;

    @BeforeEach
    void setUp() {
        runs               = mock(PayrollRunRepository.class);
        lines              = mock(PayrollLineRepository.class);
        lineItems          = mock(PayrollLineItemRepository.class);
        snapshots          = mock(PayrollStatutorySnapshotRepository.class);
        payslips           = mock(PayslipRepository.class);
        employees          = mock(EmployeeRepository.class);
        contracts          = mock(EmploymentContractRepository.class);
        recurringItems     = mock(EmployeeRecurringItemRepository.class);
        loans              = mock(EmployeeLoanRepository.class);
        payComponents      = mock(PayComponentRepository.class);
        departments        = mock(DepartmentRepository.class);
        leaveRequests      = mock(LeaveRequestRepository.class);
        calculator         = mock(StatutoryCalculator.class);
        numberGenerator    = mock(HrNumberGenerator.class);
        outbox             = mock(OutboxPublisher.class);
        scopeGuard         = mock(ScopeGuard.class);
        audit              = mock(AuditService.class);
        cashDirectEntryService = mock(CashDirectEntryService.class);
        glConfigResolver   = mock(GLConfigResolver.class);
        companies          = mock(CompanyRepository.class);

        service = new PayrollRunServiceImpl(
                runs, lines, lineItems, snapshots, payslips,
                employees, contracts, recurringItems, loans, payComponents,
                departments, leaveRequests, calculator, numberGenerator,
                outbox, scopeGuard, audit, cashDirectEntryService, glConfigResolver, companies);

        RequestContext.set(new RequestContext.Principal(1L, "test@erp.com", false, 10L, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // =========================================================================
    // snapshotPayee — invoked via calculate(); tested via reflection on private method
    // =========================================================================

    @Nested
    class SnapshotPayeeTests {

        /**
         * BANK_TRANSFER with a valid bank_account_no — snapshot fields populated, line stays OK.
         */
        @Test
        void bankTransfer_withAccount_snapshotsAndLeavesLineOk() throws Exception {
            Employee emp = makeEmployee(PaymentMethod.BANK_TRANSFER,
                    "ACC-001", null, "National Bank", "John Doe Account");
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getPayeeMethod()).isEqualTo("BANK_TRANSFER");
            assertThat(line.getPayeeAccountRef()).isEqualTo("ACC-001");
            assertThat(line.getPayeeBankName()).isEqualTo("National Bank");
            assertThat(line.getPayeeAccountName()).isEqualTo("John Doe Account");
            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.OK);
            assertThat(line.getFlagReason()).isNull();
        }

        /**
         * BANK_TRANSFER with blank bank_account_no — line must be FLAGGED.
         */
        @Test
        void bankTransfer_missingAccount_flagsLine() throws Exception {
            Employee emp = makeEmployee(PaymentMethod.BANK_TRANSFER,
                    "", null, "National Bank", "John Doe Account");
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.FLAGGED);
            assertThat(line.getFlagReason()).contains("bank_account_no is missing");
            // account ref should not be set
            assertThat(line.getPayeeAccountRef()).isNull();
        }

        /**
         * BANK_TRANSFER with null bank_account_no — line must be FLAGGED.
         */
        @Test
        void bankTransfer_nullAccount_flagsLine() throws Exception {
            Employee emp = makeEmployee(PaymentMethod.BANK_TRANSFER,
                    null, null, "Azania Bank", null);
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.FLAGGED);
            assertThat(line.getFlagReason()).contains("BANK_TRANSFER");
        }

        /**
         * MOBILE_MONEY with a valid mobile_money_no — snapshot fields populated, line stays OK.
         */
        @Test
        void mobileMoney_withNumber_snapshotsAndLeavesLineOk() throws Exception {
            Employee emp = makeEmployee(PaymentMethod.MOBILE_MONEY,
                    null, "+255754123456", null, null);
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getPayeeMethod()).isEqualTo("MOBILE_MONEY");
            assertThat(line.getPayeeAccountRef()).isEqualTo("+255754123456");
            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.OK);
        }

        /**
         * MOBILE_MONEY with blank mobile_money_no — line must be FLAGGED.
         */
        @Test
        void mobileMoney_missingNumber_flagsLine() throws Exception {
            Employee emp = makeEmployee(PaymentMethod.MOBILE_MONEY,
                    null, "  ", null, null);
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.FLAGGED);
            assertThat(line.getFlagReason()).contains("mobile_money_no is missing");
        }

        /**
         * CASH — no account ref required; method snapshotted, line stays OK.
         */
        @Test
        void cash_noAccountRequired_lineOk() throws Exception {
            Employee emp = makeEmployee(PaymentMethod.CASH, null, null, null, null);
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getPayeeMethod()).isEqualTo("CASH");
            assertThat(line.getPayeeAccountRef()).isNull();
            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.OK);
        }

        /**
         * CHEQUE — no account ref required; method snapshotted, line stays OK.
         */
        @Test
        void cheque_noAccountRequired_lineOk() throws Exception {
            Employee emp = makeEmployee(PaymentMethod.CHEQUE, null, null, null, null);
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getPayeeMethod()).isEqualTo("CHEQUE");
            assertThat(line.getPayeeAccountRef()).isNull();
            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.OK);
        }

        /**
         * No payment method set — all snapshot fields stay null, line stays OK.
         */
        @Test
        void noPaymentMethod_leavesLineUntouched() throws Exception {
            Employee emp = makeEmployee(null, null, null, null, null);
            PayrollLine line = makePayrollLine();

            invokeSnapshotPayee(emp, line);

            assertThat(line.getPayeeMethod()).isNull();
            assertThat(line.getStatus()).isEqualTo(PayrollLineStatus.OK);
        }
    }

    // =========================================================================
    // exportEftBatch — CSV formatting
    // =========================================================================

    @Nested
    class ExportEftBatchTests {

        @Test
        void exportEftBatch_emptyRun_returnsHeaderOnly() {
            PayrollRun run = makeRun("RUN-UID-001", 10L);
            when(runs.findByUid("RUN-UID-001")).thenReturn(Optional.of(run));
            when(lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId())).thenReturn(List.of());

            String csv = service.exportEftBatch("RUN-UID-001");

            assertThat(csv).isEqualTo(
                    "employee_number,employee_name,payee_method,payee_bank_name,"
                  + "payee_account_name,payee_account_ref,net_amount,currency");
        }

        @Test
        void exportEftBatch_singleLine_correctColumns() {
            PayrollRun run = makeRun("RUN-UID-002", 10L);
            when(runs.findByUid("RUN-UID-002")).thenReturn(Optional.of(run));

            PayrollLine line = makePayrollLine();
            setField(line, "employeeNumber", "EMP-000001");
            setField(line, "employeeName", "Alice Smith");
            line.setPayeeMethod("BANK_TRANSFER");
            line.setPayeeBankName("National Bank");
            line.setPayeeAccountName("Alice Smith Account");
            line.setPayeeAccountRef("ACC-9999");
            line.setNetAmount(new BigDecimal("1500000.0000"));
            setField(line, "currency", CurrencyCode.of("TZS"));

            when(lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId())).thenReturn(List.of(line));

            String csv = service.exportEftBatch("RUN-UID-002");

            String[] csvLines = csv.split("\n");
            assertThat(csvLines).hasSize(2);
            assertThat(csvLines[0]).startsWith("employee_number,");
            String dataRow = csvLines[1];
            assertThat(dataRow).contains("EMP-000001");
            assertThat(dataRow).contains("Alice Smith");
            assertThat(dataRow).contains("BANK_TRANSFER");
            assertThat(dataRow).contains("National Bank");
            assertThat(dataRow).contains("Alice Smith Account");
            assertThat(dataRow).contains("ACC-9999");
            assertThat(dataRow).contains("1500000.0000");
            assertThat(dataRow).contains("TZS");
        }

        @Test
        void exportEftBatch_nameWithComma_wrapsInQuotes() {
            PayrollRun run = makeRun("RUN-UID-003", 10L);
            when(runs.findByUid("RUN-UID-003")).thenReturn(Optional.of(run));

            PayrollLine line = makePayrollLine();
            setField(line, "employeeNumber", "EMP-000002");
            setField(line, "employeeName", "Smith, Bob");   // comma in name
            line.setPayeeMethod("CASH");
            line.setNetAmount(new BigDecimal("500000.0000"));
            setField(line, "currency", CurrencyCode.of("TZS"));

            when(lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId())).thenReturn(List.of(line));

            String csv = service.exportEftBatch("RUN-UID-003");
            String dataRow = csv.split("\n")[1];

            // Name with comma must be quoted
            assertThat(dataRow).contains("\"Smith, Bob\"");
        }

        @Test
        void exportEftBatch_multipleLines_correctRowCount() {
            PayrollRun run = makeRun("RUN-UID-004", 10L);
            when(runs.findByUid("RUN-UID-004")).thenReturn(Optional.of(run));

            PayrollLine l1 = makePayrollLine();
            setField(l1, "employeeNumber", "EMP-000001");
            setField(l1, "employeeName", "Alice");
            l1.setPayeeMethod("BANK_TRANSFER");
            l1.setNetAmount(new BigDecimal("1000000.0000"));
            setField(l1, "currency", CurrencyCode.of("TZS"));

            PayrollLine l2 = makePayrollLine();
            setField(l2, "employeeNumber", "EMP-000002");
            setField(l2, "employeeName", "Bob");
            l2.setPayeeMethod("MOBILE_MONEY");
            l2.setNetAmount(new BigDecimal("800000.0000"));
            setField(l2, "currency", CurrencyCode.of("TZS"));

            when(lines.findByPayrollRunIdOrderByEmployeeIdAsc(run.getId())).thenReturn(List.of(l1, l2));

            String csv = service.exportEftBatch("RUN-UID-004");
            String[] csvLines = csv.split("\n");

            assertThat(csvLines).hasSize(3); // header + 2 data rows
            assertThat(csvLines[1]).contains("EMP-000001");
            assertThat(csvLines[2]).contains("EMP-000002");
        }
    }

    // =========================================================================
    // helpers
    // =========================================================================

    /** Build an Employee with payee fields set via reflection (constructor sets companyId/id only). */
    private Employee makeEmployee(PaymentMethod method, String bankAccountNo,
                                   String mobileMoneyNo, String bankName, String bankAccountName) {
        Employee emp = new Employee(10L, null, "EMP-000001", "John", "Doe",
                java.time.LocalDate.of(2020, 1, 1), 1L);
        emp.setPaymentMethod(method);
        emp.setBankAccountNo(bankAccountNo);
        emp.setMobileMoneyNo(mobileMoneyNo);
        emp.setBankName(bankName);
        emp.setBankAccountName(bankAccountName);
        return emp;
    }

    /** Build a PayrollLine with required constructor fields set. */
    private PayrollLine makePayrollLine() {
        PayrollLine line = new PayrollLine(1L, 10L, 2L, "EMP-000001", "John Doe", null, "TZS", 1L);
        return line;
    }

    /** Build a PayrollRun with a known uid and id (set via reflection). */
    private PayrollRun makeRun(String uid, Long companyId) {
        PayrollRun run = new PayrollRun(companyId, null, "PR-0001",
                (short) 2024, (short) 6, java.time.LocalDate.of(2024, 6, 30), 1L);
        setField(run, "id", 999L);
        // Set uid via the protected method on UidEntity
        try {
            Method m = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            m.setAccessible(true);
            m.invoke(run, uid);
        } catch (Exception e) {
            throw new RuntimeException("Could not set uid on PayrollRun", e);
        }
        return run;
    }

    /** Invoke the private snapshotPayee(Employee, PayrollLine) method via reflection. */
    private void invokeSnapshotPayee(Employee emp, PayrollLine line) throws Exception {
        Method m = PayrollRunServiceImpl.class
                .getDeclaredMethod("snapshotPayee", Employee.class, PayrollLine.class);
        m.setAccessible(true);
        m.invoke(service, emp, line);
    }

    /** Set a field value via reflection (handles inherited fields). */
    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot set field " + fieldName, e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
