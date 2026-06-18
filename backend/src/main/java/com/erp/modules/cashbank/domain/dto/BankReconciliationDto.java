package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.ReconciliationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Response DTO for a bank reconciliation (ADR-0016 D-2e). */
public record BankReconciliationDto(
        Long id,
        String uid,
        Long companyId,
        Long cashBankAccountId,
        String reconciliationNumber,
        LocalDate statementDate,
        BigDecimal statementOpeningBalance,
        BigDecimal statementClosingBalance,
        BigDecimal clearedBookBalance,
        BigDecimal unreconciledAmount,
        ReconciliationStatus status,
        Long reconciledBy,
        Instant completedAt
) {}
