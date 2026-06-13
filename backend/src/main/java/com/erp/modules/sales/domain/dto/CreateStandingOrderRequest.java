package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.StandingFrequency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to create a standing (recurring) order (ADR-0029 D-8).
 */
public record CreateStandingOrderRequest(
        @NotBlank String companyUid,
        @NotNull  Long branchId,
        @NotNull  Long customerId,
        @NotBlank String currency,
        @NotNull  StandingFrequency frequency,
        @NotNull  LocalDate startDate,
        LocalDate endDate,
        @NotEmpty @Valid List<LineRequest> lines,
        @Size(max = 500) String notes
) {
    public record LineRequest(
            @NotNull Long productId,
            @NotNull Long unitId,
            @NotNull @DecimalMin("0.0001") BigDecimal qty,
            @NotNull @DecimalMin("0.0001") BigDecimal qtyBase,
            @NotNull @DecimalMin("0.00")   BigDecimal unitPriceAmount
    ) {}
}
