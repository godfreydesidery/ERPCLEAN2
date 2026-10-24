package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional body of Send: the waiter signed in on a shared till, re-verified by the server and
 * named on the slips (ADR-0066 D-8). No body, or null, = the signed-in user.
 */
public record SendRestaurantOrderRequest(@Size(max = 26) String waiterUid) {}
