package com.erp.modules.sales.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Splits items off onto a new check at the same table (ADR-0066 D-2). {@code quantity} null moves
 * the whole line; less moves part of it ("one of the three teas").
 */
public record SplitRestaurantOrderRequest(@NotEmpty @Valid List<Item> items) {

    public record Item(
            @NotBlank String lineUid,
            @DecimalMin("0.0001") BigDecimal quantity
    ) {}
}
