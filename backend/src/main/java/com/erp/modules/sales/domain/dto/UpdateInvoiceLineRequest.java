package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request DTO to update a line on a draft sales invoice.
 * Only quantity and discounts are updatable; product/unit are fixed at add time.
 */
public record UpdateInvoiceLineRequest(
        @NotNull @Positive BigDecimal quantity,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent
) {
}
