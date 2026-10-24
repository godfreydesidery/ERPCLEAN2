package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Adds an item to an order (ADR-0065 D-5). Tapping the same item again, with no note, adds to the
 * existing unsent line instead of creating a second one. {@code quantity} null means 1.
 */
public record AddRestaurantOrderLineRequest(
        @NotNull Long productId,
        @NotNull Long unitId,
        @DecimalMin("0.0001") BigDecimal quantity,
        @Size(max = 200) String note
) {}
