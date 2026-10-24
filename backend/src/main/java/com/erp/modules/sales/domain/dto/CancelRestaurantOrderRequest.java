package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * Cancels a live order (ADR-0065 D-9). Free while nothing was sent; with sent items it needs a
 * reason and the same authority as a void ({@code authorisedByUid} re-verified by the server).
 */
public record CancelRestaurantOrderRequest(
        @Size(max = 255) String reason,
        @Size(max = 26) String authorisedByUid
) {}
