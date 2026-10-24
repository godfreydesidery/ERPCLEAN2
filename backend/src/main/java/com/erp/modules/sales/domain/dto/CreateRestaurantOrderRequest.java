package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Opens an order in the caller's active branch (ADR-0065 D-5). {@code orderType} null means
 * DINE_IN, which needs {@code tableUid}; a TAKEAWAY has no table.
 *
 * <p>{@code waiterUid}: the waiter who signed in on a shared till (ADR-0066 D-8). The server
 * re-verifies that it names a real user of this company holding {@code RESTAURANT.ORDER.MANAGE},
 * and records them as the order's server. Null = the signed-in user.
 */
public record CreateRestaurantOrderRequest(
        RestaurantOrderType orderType,
        String tableUid,
        @Min(1) @Max(999) Short guestCount,
        @Size(max = 500) String notes,
        @Size(max = 26) String waiterUid
) {

    public CreateRestaurantOrderRequest(RestaurantOrderType orderType, String tableUid,
                                        Short guestCount, String notes) {
        this(orderType, tableUid, guestCount, notes, null);
    }
}
