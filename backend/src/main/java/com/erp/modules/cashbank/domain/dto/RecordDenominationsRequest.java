package com.erp.modules.cashbank.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

/** Request to record the counted denomination breakdown of a cash count (ADR-0050 D-7 PR-A). */
public record RecordDenominationsRequest(
        @NotEmpty @Valid List<Line> lines
) {
    /** One denomination note/coin face value and how many were counted. */
    public record Line(
            @NotNull @Positive BigDecimal denomination,
            @NotNull @PositiveOrZero Integer quantity
    ) {}
}
