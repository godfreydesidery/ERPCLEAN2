package com.erp.modules.sales.domain.dto;

import java.util.List;

/**
 * Result of a void or a cancel (ADR-0065 D-9): the order afterwards and the VOID slips to print at
 * the stations that had already received the items (empty when none had).
 */
public record RestaurantVoidResultDto(RestaurantOrderDto order, List<PrepSlipDto> slips) {}
