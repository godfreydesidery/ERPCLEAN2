package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Moves every item of {@code fromOrderUid} onto this order and closes it (ADR-0066 D-3). */
public record MergeRestaurantOrderRequest(@NotBlank String fromOrderUid) {}
