package com.erp.modules.stock.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request body for an OPENING_BALANCE movement (ADR-0010 D-11, FR-STOCK-10).
 *
 * <p>Quantity must be positive (D-6: an opening balance seeds an initial level — negative opening is
 * rejected; "we already had negative" is an ADJUSTMENT). Branch from {@code RequestContext}.
 */
public record OpeningBalanceRequest(
        @NotBlank String productUid,
        @NotNull @Positive BigDecimal quantity,
        String note
) {}
