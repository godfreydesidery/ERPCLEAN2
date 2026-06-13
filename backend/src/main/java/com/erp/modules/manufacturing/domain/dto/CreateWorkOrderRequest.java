package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request body for POST /api/v1/work-orders (ADR-0035 D-1). */
public record CreateWorkOrderRequest(
        @NotBlank String finishedProductUid,
        @NotNull @DecimalMin("0.000001") BigDecimal plannedQty,
        @NotBlank String branchUid,
        /** Optional: pin a specific BOM version uid (overrides the ACTIVE BOM). */
        String bomUid,
        LocalDate plannedDate,
        /** Optional cost-centre dimension value uid (ADR-0025 D-9). */
        String costCentreValueUid,
        String notes
) {}
