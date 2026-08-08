package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request DTO to update a line on a draft sales invoice.
 * Only quantity and discounts are updatable; product/unit are fixed at add time.
 * The positive-quantity rule is enforced in the service with a friendly, field-name-free message.
 *
 * @param discountAuthorisedByUid optional (K7) — see {@link AddInvoiceLineRequest}. Update is gated
 *        identically to add: raising a discount past the ceiling after the line exists must not be a
 *        way around the ceiling.
 */
public record UpdateInvoiceLineRequest(
        @NotNull BigDecimal quantity,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent,
        @Size(max = 26) String discountAuthorisedByUid
) {

    /** Back-compat: update with no manager authorisation attached. */
    public UpdateInvoiceLineRequest(BigDecimal quantity, BigDecimal lineDiscountAmount,
                                    BigDecimal lineDiscountPercent) {
        this(quantity, lineDiscountAmount, lineDiscountPercent, null);
    }
}
