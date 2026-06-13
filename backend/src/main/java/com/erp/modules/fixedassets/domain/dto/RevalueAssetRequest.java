package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.RevaluationDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Revalue a fixed asset (ADR-0030 D-6e). delta_amount is always positive; direction governs the sign. */
public record RevalueAssetRequest(
        @NotNull RevaluationDirection direction,
        @NotNull @Positive BigDecimal deltaAmount,
        @NotNull LocalDate revaluationDate,
        String reason
) {}
