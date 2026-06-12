package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreatePayeBandSetRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @PositiveOrZero BigDecimal taxFreeThreshold,
        String description,
        @NotNull List<BandRequest> bands
) {
    public record BandRequest(
            short bandNo,
            @NotNull @PositiveOrZero BigDecimal lowerBound,
            @NotNull @PositiveOrZero BigDecimal marginalRate,
            @NotNull @PositiveOrZero BigDecimal cumulativeFixedTax
    ) {}
}
