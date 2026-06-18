package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.LoanStatus;
import com.erp.modules.hr.domain.enums.LoanType;
import java.math.BigDecimal;
import java.time.Instant;
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
        String currency,
        // Loan terms (P2 D6, ADR-0041)
        BigDecimal interestRate,
        LoanType loanType,
        Long approvedBy,
        Instant approvedAt,
        Integer termMonths
) {}
