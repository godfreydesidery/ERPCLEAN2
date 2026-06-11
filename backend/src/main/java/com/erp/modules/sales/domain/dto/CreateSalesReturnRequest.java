package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to create a sales return / RMA against a delivery (ADR-0021 D-11, Stage 2).
 *
 * <p>The return is created directly in CONFIRMED status (stock-in + COGS-reversal event +
 * credit note all happen on create). RET-#### allocated at create.
 */
public record CreateSalesReturnRequest(
        @NotNull String deliveryUid,
        @NotNull LocalDate returnDate,
        String reason,
        @NotEmpty List<ReturnLineRequest> lines
) {
    /**
     * Per-line return qty.
     *
     * @param deliveryLineUid  the delivery line being returned
     * @param qtyReturned      quantity to return (in the delivery line's unit; base in v1)
     */
    public record ReturnLineRequest(
            @NotNull String deliveryLineUid,
            @NotNull BigDecimal qtyReturned
    ) {}
}
