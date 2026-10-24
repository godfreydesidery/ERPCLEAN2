package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Fires held items (ADR-0066 D-5): those of {@code course}, or every held item when it is null.
 * They are sent in one round, like Send.
 */
public record FireRestaurantOrderRequest(@Min(1) @Max(9) Short course) {}
