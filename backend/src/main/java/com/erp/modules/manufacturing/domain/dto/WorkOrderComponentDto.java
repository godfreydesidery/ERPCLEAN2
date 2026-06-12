package com.erp.modules.manufacturing.domain.dto;

import com.erp.modules.manufacturing.domain.enums.ComponentLineStatus;
import java.math.BigDecimal;

/** Response DTO for a work-order component line (ADR-0035 D-3). */
public record WorkOrderComponentDto(
        Long id,
        String uid,
        Long workOrderId,
        int lineNo,
        Long componentProductId,
        String componentProductCode,
        String componentProductName,
        BigDecimal plannedQty,
        BigDecimal issuedQty,
        BigDecimal issuedValue,
        BigDecimal unitCostAtIssue,
        boolean costSkipped,
        ComponentLineStatus status
) {}
