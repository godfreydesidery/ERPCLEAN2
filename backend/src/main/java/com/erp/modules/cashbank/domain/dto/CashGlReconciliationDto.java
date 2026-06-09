package com.erp.modules.cashbank.domain.dto;

import java.math.BigDecimal;

/**
 * Cash account book balance vs linked GL account balance (ADR-0016 D-9, FR-CASH-17).
 * A non-zero difference is a finance-grade defect.
 */
public record CashGlReconciliationDto(
        String cashBankAccountUid,
        String accountName,
        Long linkedGlAccountId,
        String linkedGlAccountCode,
        BigDecimal bookBalance,
        BigDecimal linkedGlBalance,
        BigDecimal difference
) {}
