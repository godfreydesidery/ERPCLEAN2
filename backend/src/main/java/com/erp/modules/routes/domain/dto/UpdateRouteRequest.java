package com.erp.modules.routes.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to update a route's mutable profile fields (name and location identifier).
 * Code and company are immutable after creation (BR-ROUTE-01, BR-ROUTE-06).
 */
public record UpdateRouteRequest(
        @NotBlank String name,
        String locationIdentifier
) {
}
