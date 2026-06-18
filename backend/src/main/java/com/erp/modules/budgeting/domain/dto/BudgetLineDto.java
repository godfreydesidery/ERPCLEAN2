package com.erp.modules.budgeting.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Budget line response DTO (ADR-0034 D-4c).
 */
public record BudgetLineDto(
        Long id,
        String uid,
        Long budgetVersionId,
        Long accountId,
        String accountUid,
        String accountCode,
        String accountName,
        Long fiscalPeriodId,
        String fiscalPeriodUid,
        int periodNo,
        BigDecimal amount,
        BigDecimal quantity,
        String currency,
        String lineMemo,
        Instant createdAt
) {}
