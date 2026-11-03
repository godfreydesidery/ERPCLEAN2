package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Edits a live order's header. A non-null {@code tableUid} moves a dine-in order to that table;
 * null fields are left as they are. A blank {@code customerName} clears the name.
 */
public record UpdateRestaurantOrderRequest(
        String tableUid,
        @Min(1) @Max(999) Short guestCount,
        @Size(max = 500) String notes,
        @Size(max = 120) String customerName
) {

    public UpdateRestaurantOrderRequest(String tableUid, Short guestCount, String notes) {
        this(tableUid, guestCount, notes, null);
    }
}
