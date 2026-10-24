package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Voids a SENT item (ADR-0065 D-9). {@code quantity} null voids the whole line; less than the line
 * voids part of it ("one of the three teas"). {@code authorisedByUid} is the manager step-up
 * ({@code POST /auth/verify-authority} for {@code RESTAURANT.ORDER.VOID}); the server re-verifies
 * it, and does not need it when the caller holds that permission themselves.
 */
public record VoidRestaurantOrderLineRequest(
        @NotBlank @Size(max = 255) String reason,
        @DecimalMin("0.0001") BigDecimal quantity,
        @Size(max = 26) String authorisedByUid
) {}
