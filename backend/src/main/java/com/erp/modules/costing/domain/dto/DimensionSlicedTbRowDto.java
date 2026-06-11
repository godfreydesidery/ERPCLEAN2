package com.erp.modules.costing.domain.dto;

import java.math.BigDecimal;

/**
 * One row in the dimension-sliced trial balance (ADR-0025 D-8, FR-CC-14/16).
 * {@code net} = {@code totalDebit} - {@code totalCredit} (not necessarily zero — BR-CC-01).
 */
public record DimensionSlicedTbRowDto(
        Long valueId,
        String valueUid,
        String valueCode,
        String valueName,
        Long accountId,
        String accountUid,
        String accountCode,
        String accountName,
        String accountType,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal net
) {}
