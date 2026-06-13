package com.erp.modules.sales.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Quick-sale request via a POS session (ADR-0029 D-5).
 * Creates a DIRECT SalesInvoice tagged with the session uid and immediately finalises it.
 */
public record PosSaleRequest(
        @NotBlank String sessionUid,
        @NotNull  Long customerId,
        @NotNull  Long agentId,
        @NotBlank String currency,
        @NotEmpty @Valid List<LineItem> lines,
        /** Total tendered (for receipt printing, not stored on invoice). */
        BigDecimal tenderedAmount,
        @Size(max = 500) String notes
) {

    public record LineItem(
            @NotNull Long productId,
            @NotNull Long unitId,
            @NotNull @DecimalMin("0.0001") BigDecimal quantity,
            /** Client-submitted price; validated against list price by service. */
            @NotNull @DecimalMin("0.00") BigDecimal unitPrice,
            BigDecimal lineDiscountAmount
    ) {}
}
