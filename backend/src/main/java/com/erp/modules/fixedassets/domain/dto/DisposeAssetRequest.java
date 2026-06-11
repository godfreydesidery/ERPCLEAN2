package com.erp.modules.fixedassets.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Dispose of an asset by SALE — proceeds >= 0 (ADR-0030 D-6c). */
public record DisposeAssetRequest(
        @NotNull LocalDate disposalDate,
        @NotNull @PositiveOrZero BigDecimal proceedsAmount,
        String reason
) {}
