package com.erp.modules.stock.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO: create a stock transfer (FR-INVD-08, ADR-0028 D-5).
 */
public record CreateStockTransferRequest(
        @NotBlank String sourceLocationUid,
        @NotBlank String destLocationUid,
        @NotNull LocalDate transferDate,
        /** Transfer mode: INSTANT (same-branch) or IN_TRANSIT (cross-branch, default). */
        @NotBlank String transferMode,
        @Size(max = 500) String notes,
        @NotEmpty @Valid List<LineRequest> lines
) {
    /** A single line item: product + quantity. */
    public record LineRequest(
            @NotBlank String productUid,
            @NotNull @Positive BigDecimal qty
    ) {}
}
