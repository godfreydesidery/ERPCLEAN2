package com.erp.modules.fx.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to add a new effective-dated rate row (ADR-0036 D-2).
 *
 * <p>No edit-in-place: a correction is a new effective-dated row. Hence this is always an INSERT.
 * The service validates: rate > 0; from/to codes active in currencies; effective_date present.
 */
public record UpsertRateRequest(
        @NotNull  Long       companyId,
        @NotBlank @Size(min=3, max=3) String fromCurrency,
        @NotBlank @Size(min=3, max=3) String toCurrency,
        @NotNull  @Positive BigDecimal rate,
        @NotNull  LocalDate  effectiveDate,
        @Size(max=20) String rateType,
        @Size(max=40) String source
) {}
