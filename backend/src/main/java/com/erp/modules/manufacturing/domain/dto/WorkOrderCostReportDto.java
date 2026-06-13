package com.erp.modules.manufacturing.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Work-order cost report (FR-MFG-13): planned-vs-actual component consumption,
 * applied labour/overhead, WIP accumulators, computed cost, variance.
 */
public record WorkOrderCostReportDto(
        String woNumber,
        String finishedProductCode,
        String finishedProductName,
        BigDecimal plannedQty,
        BigDecimal goodQty,
        BigDecimal scrapQty,
        BigDecimal wipDebitTotal,
        BigDecimal wipCreditTotal,
        BigDecimal labourAppliedTotal,
        BigDecimal overheadAppliedTotal,
        BigDecimal computedUnitCost,
        BigDecimal varianceAmount,
        boolean incompleteCost,
        List<WorkOrderComponentDto> components,
        List<WorkOrderOperationDto> operations
) {}
