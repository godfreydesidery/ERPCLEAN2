package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.BlanketStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for a blanket order header + lines (ADR-0029 D-7).
 */
public record BlanketOrderDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String orderNumber,
        Long customerId,
        String currency,
        String validFrom,
        String validTo,
        BigDecimal totalCommittedAmount,
        BigDecimal totalDrawnAmount,
        BlanketStatus status,
        String notes,
        List<BlanketOrderLineDto> lines
) {}
