package com.erp.modules.purchases.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CaptureSupplierQuoteRequest(
        @NotBlank String rfqUid,
        @NotBlank String supplierUid,
        LocalDate validUntil,
        Short leadTimeDays,
        String notes,
        @NotEmpty @Valid List<LineRequest> lines
) {
    public record LineRequest(
            @NotNull Long rfqLineId,
            @NotNull @Positive BigDecimal quotedQty,
            @NotNull @Positive BigDecimal unitPriceAmount
    ) {}
}
