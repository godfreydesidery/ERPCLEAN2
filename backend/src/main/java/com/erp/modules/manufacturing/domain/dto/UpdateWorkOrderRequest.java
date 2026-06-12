package com.erp.modules.manufacturing.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Request body for PUT /api/v1/work-orders/uid/{uid} — PLANNED orders only (BR-MFG-04). */
public record UpdateWorkOrderRequest(
        BigDecimal plannedQty,
        String bomUid,
        String branchUid,
        LocalDate plannedDate,
        String costCentreValueUid,
        String notes
) {}
