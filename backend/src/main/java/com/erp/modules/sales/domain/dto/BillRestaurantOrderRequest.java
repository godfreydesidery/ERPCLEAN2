package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Prints the bill (ADR-0065 D-7). {@code customerId} — normally the till's walk-in customer — is
 * who the order is priced for, and is remembered on the order so payment charges the same price.
 * Null keeps the order's existing customer.
 */
public record BillRestaurantOrderRequest(
        Long customerId,
        @NotBlank @Size(max = 3) String currency
) {}
