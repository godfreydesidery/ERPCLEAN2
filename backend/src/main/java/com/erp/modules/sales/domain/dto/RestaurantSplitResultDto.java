package com.erp.modules.sales.domain.dto;

/** Result of a split: the original check (what is left) and the new one (what moved). */
public record RestaurantSplitResultDto(RestaurantOrderDto original, RestaurantOrderDto created) {}
