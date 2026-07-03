package com.erp.modules.manufacturing.domain.dto;

import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a work order header + component lines + operations (ADR-0035 D-1 API).
 * Includes both {@code id} (serialised as JSON string) and {@code uid} per PROJECT-CONVENTIONS.
 * branchName/branchCode are enrichment fields resolved at read time by the service (mirrors
 * SalesOrderDto's branch enrichment) so a branch manager can see which branch a work order
 * belongs to — only the internal branchId travelled before.
 */
public record WorkOrderDto(
        Long id,
        String uid,
        String woNumber,
        Long companyId,
        Long branchId,
        String branchName,
        String branchCode,
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
        Long targetLocationId,
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
