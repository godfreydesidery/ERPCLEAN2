package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosSessionStatus;
import java.math.BigDecimal;

/**
 * Response DTO for a POS session (ADR-0029 D-5).
 */
public record PosSessionDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long posTillId,
        Long cashierId,
        PosSessionStatus status,
        String openedAt,
        String closedAt,
        String reconciledAt,
        BigDecimal openingFloatAmount,
        BigDecimal countedCashAmount,
        BigDecimal expectedCashAmount,
        BigDecimal varianceAmount,
        Long varianceJournalId,
        String notes
) {}
