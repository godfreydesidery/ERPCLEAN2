package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to add a line to a draft sales invoice (ADR-0008 D-12, FR-SALES-04).
 * productUid and unitUid are scoped to the invoice's company (F15 pattern).
 * lineDiscountAmount and lineDiscountPercent are alternative forms — at most one should be set.
 * The positive-quantity rule is enforced in the service with a friendly message (a {@code @Positive}
 * annotation would leak the raw field name into the user-facing error).
 */
public record AddInvoiceLineRequest(
        @NotBlank String productUid,
        @NotBlank String unitUid,
        @NotNull BigDecimal quantity,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent
) {
}
