package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request DTO to add a line to a draft sales invoice (ADR-0008 D-12, FR-SALES-04).
 * productUid and unitUid are scoped to the invoice's company (F15 pattern).
 * lineDiscountAmount and lineDiscountPercent are alternative forms — at most one should be set.
 */
public record AddInvoiceLineRequest(
        @NotBlank String productUid,
        @NotBlank String unitUid,
        @NotNull @Positive BigDecimal quantity,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent
) {
}
