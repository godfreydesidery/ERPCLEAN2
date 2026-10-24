package com.erp.modules.sales.domain.dto;

import java.util.List;

/**
 * Result of Send (ADR-0065 D-6): the order after the send, and one slip per station to print.
 * {@code slips} is empty when nothing new was waiting, or when the branch has no prep stations.
 */
public record RestaurantSendResultDto(RestaurantOrderDto order, List<PrepSlipDto> slips) {}
