package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateRecurringItemRequest(
        @NotNull Long payComponentId,
        @NotNull @PositiveOrZero BigDecimal amountOrPercent,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
