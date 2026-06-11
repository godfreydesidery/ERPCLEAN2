package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateDeliveryRequest(
        @NotBlank String salesOrderUid,
        @NotNull LocalDate deliveryDate,
        String notes,
        @NotEmpty List<DeliveryLineRequest> lines
) {
    public record DeliveryLineRequest(
            @NotBlank String salesOrderLineUid,
            @NotNull BigDecimal qtyDelivered
    ) {}
}
