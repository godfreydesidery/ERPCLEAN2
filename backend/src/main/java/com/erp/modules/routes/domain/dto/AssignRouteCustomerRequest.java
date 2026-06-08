package com.erp.modules.routes.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to assign a customer to a route (FR-ROUTE-05, ADR-0012 D-11).
 * Target addressed by uid; service resolves to scalar id + same-company guard.
 */
public record AssignRouteCustomerRequest(
        @NotBlank String customerUid
) {
}
