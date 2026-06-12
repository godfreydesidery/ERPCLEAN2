package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request body for POST /api/v1/work-orders/uid/{uid}/complete (ADR-0035 D-5). */
public record CompleteWorkOrderRequest(
        @NotNull @DecimalMin("0.000001") BigDecimal goodQty,
        BigDecimal scrapQty,
        /** true = allow goodQty > plannedQty (BR-MFG-08). */
        boolean allowOverRun,
        @NotNull LocalDate postingDate
) {}
