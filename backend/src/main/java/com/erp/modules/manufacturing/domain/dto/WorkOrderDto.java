package com.erp.modules.manufacturing.domain.dto;

import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a work order header + component lines + operations (ADR-0035 D-1 API).
 * Includes both {@code id} (serialised as JSON string) and {@code uid} per PROJECT-CONVENTIONS.
 */
public record WorkOrderDto(
        Long id,
        String uid,
        String woNumber,
        Long companyId,
        Long branchId,
        Long finishedProductId,
        String finishedProductCode,
        String finishedProductName,
        Long bomId,
        String bomUid,
        BigDecimal plannedQty,
        BigDecimal goodQty,
        BigDecimal scrapQty,
        WorkOrderStatus status,
        BigDecimal wipDebitTotal,
        BigDecimal wipCreditTotal,
        BigDecimal labourAppliedTotal,
        BigDecimal overheadAppliedTotal,
        BigDecimal computedUnitCost,
        BigDecimal varianceAmount,
        boolean incompleteCost,
        Long costCentreValueId,
        LocalDate plannedDate,
        Instant releasedAt,
        Instant completedAt,
        Instant closedAt,
        Instant cancelledAt,
        String notes,
        Instant createdAt,
        Long createdBy,
        List<WorkOrderComponentDto> components,
        List<WorkOrderOperationDto> operations
) {}
