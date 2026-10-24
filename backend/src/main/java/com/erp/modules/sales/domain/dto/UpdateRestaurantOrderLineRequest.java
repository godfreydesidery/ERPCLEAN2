package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Changes an UNSENT item's quantity and note. A sent item can only be voided. */
public record UpdateRestaurantOrderLineRequest(
        @NotNull @DecimalMin("0.0001") BigDecimal quantity,
        @Size(max = 200) String note
) {}
