package com.erp.modules.routes.domain.dto;

import com.erp.modules.routes.domain.entity.RouteCustomer;

/**
 * Enriched response DTO for a route ↔ customer membership (ADR-0012 D-11).
 * Carries customer uid + display name resolved at read time (no snapshot — customer master is stable).
 */
public record RouteCustomerDto(
        Long routeId,
        Long customerId,
        String customerUid,
        String customerCode,
        String customerName,
        String assignedAt,
        Long assignedBy
) {

    /**
     * Build from entity with enriched customer fields (uid / code / name resolved by the service).
     */
    public static RouteCustomerDto from(RouteCustomer rc, String customerUid,
                                        String customerCode, String customerName) {
        return new RouteCustomerDto(
                rc.getRoute().getId(),
                rc.getCustomerId(),
                customerUid,
                customerCode,
                customerName,
                rc.getAssignedAt() != null ? rc.getAssignedAt().toString() : null,
                rc.getAssignedBy()
        );
    }
}
