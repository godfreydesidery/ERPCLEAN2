package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Changes an UNSENT item's quantity and note, and (ADR-0066 D-5) its course and hold — null keeps
 * them as they are. A sent item can only be voided; an add-on changes with its item.
 */
public record UpdateRestaurantOrderLineRequest(
        @NotNull @DecimalMin("0.0001") BigDecimal quantity,
        @Size(max = 200) String note,
        @Min(1) @Max(9) Short course,
        Boolean held
) {

    public UpdateRestaurantOrderLineRequest(BigDecimal quantity, String note) {
        this(quantity, note, null, null);
    }
}
