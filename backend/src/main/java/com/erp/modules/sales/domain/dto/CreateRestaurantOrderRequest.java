package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Opens an order in the caller's active branch (ADR-0065 D-5). {@code orderType} null means
 * DINE_IN, which needs {@code tableUid}; a TAKEAWAY has no table.
 */
public record CreateRestaurantOrderRequest(
        RestaurantOrderType orderType,
        String tableUid,
        @Min(1) @Max(999) Short guestCount,
        @Size(max = 500) String notes
) {}
