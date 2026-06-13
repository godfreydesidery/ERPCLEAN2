package com.erp.modules.sales.domain.dto;

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
 * Request to create a blanket order (ADR-0029 D-7).
 */
public record CreateBlanketOrderRequest(
        @NotBlank String companyUid,
        @NotNull  Long branchId,
        @NotNull  Long customerId,
        @NotBlank String currency,
        @NotNull  LocalDate validFrom,
        @NotNull  LocalDate validTo,
        @NotEmpty @Valid List<LineRequest> lines,
        @Size(max = 500) String notes
) {
    public record LineRequest(
            @NotNull Long productId,
            @NotNull Long unitId,
            @NotNull @DecimalMin("0.0001") BigDecimal committedQtyBase,
            @NotNull @DecimalMin("0.00")   BigDecimal unitPriceAmount
    ) {}
}
