package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.PayrollLineStatus;
import java.math.BigDecimal;

public record PayrollLineDto(
        Long id,
        String uid,
        Long payrollRunId,
        Long employeeId,
        String employeeNumber,
        String employeeName,
        String departmentName,
        BigDecimal grossAmount,
        BigDecimal taxableAmount,
        BigDecimal netAmount,
        BigDecimal payeAmount,
        BigDecimal nssfEmployeeAmount,
        BigDecimal heslbAmount,
        BigDecimal voluntaryDeductionTotal,
        BigDecimal loanDeductionTotal,
        BigDecimal nssfEmployerAmount,
        BigDecimal wcfEmployerAmount,
        BigDecimal sdlEmployerAmount,
        PayrollLineStatus status,
        String flagReason,
        String currency,
        // Employment-contract snapshot (P2-M5)
        Long contractId,
        // Payee snapshot fields (ADR-0040 D-11)
        String payeeMethod,
        String payeeAccountRef,
        String payeeBankName,
        String payeeAccountName
) {}
