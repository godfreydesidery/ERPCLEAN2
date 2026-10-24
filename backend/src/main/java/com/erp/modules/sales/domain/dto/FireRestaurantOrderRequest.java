package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Fires held items (ADR-0066 D-5): those of {@code course}, or every held item when it is null.
 * They are sent in one round, like Send. {@code waiterUid} as on Send.
 */
public record FireRestaurantOrderRequest(@Min(1) @Max(9) Short course,
                                         @Size(max = 26) String waiterUid) {

    public FireRestaurantOrderRequest(Short course) {
        this(course, null);
    }
}
