package com.erp.modules.routes.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to assign an EXTERNAL agent to a route (FR-ROUTE-08, ADR-0012 D-11).
 * {@code isPrimary} is optional — when true the agent is designated as the route's primary coverer
 * (FR-ROUTE-09), driving the invoice route default (FR-ROUTE-13).
 */
public record AssignRouteAgentRequest(
        @NotBlank String agentUid,
        boolean isPrimary
) {
}
