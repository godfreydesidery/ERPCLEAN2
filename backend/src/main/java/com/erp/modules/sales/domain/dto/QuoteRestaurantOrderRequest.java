package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Prices an order as it would be paid, with an optional discount. Read-only. */
public record QuoteRestaurantOrderRequest(
        Long customerId,
        @NotBlank @Size(max = 3) String currency,
        @DecimalMin("0") @DecimalMax("100") BigDecimal discountPercent,
        @DecimalMin("0") BigDecimal discountAmount
) {}
