package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.LoanStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record EmployeeLoanDto(
        Long id,
        String uid,
        Long companyId,
        Long employeeId,
        String employeeName,
        String loanNumber,
        BigDecimal principalAmount,
        BigDecimal installmentAmount,
        BigDecimal outstandingAmount,
        Long glAccountId,
        LoanStatus status,
        LocalDate startDate,
        String currency
) {}
