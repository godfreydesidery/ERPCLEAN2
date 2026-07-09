package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to update a line on a draft sales invoice.
 * Only quantity and discounts are updatable; product/unit are fixed at add time.
 * The positive-quantity rule is enforced in the service with a friendly, field-name-free message.
 */
public record UpdateInvoiceLineRequest(
        @NotNull BigDecimal quantity,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent
) {
}
