package com.erp.modules.cashbank.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request to open a bank reconciliation (FR-CASH-13). */
public record OpenReconciliationRequest(
        @NotBlank String companyUid,
        @NotBlank String cashBankAccountUid,
        @NotNull LocalDate statementDate,
        BigDecimal statementOpeningBalance,
        @NotNull BigDecimal statementClosingBalance
) {}
