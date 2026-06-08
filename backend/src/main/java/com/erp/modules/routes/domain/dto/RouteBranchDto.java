package com.erp.modules.routes.domain.dto;

import com.erp.modules.routes.domain.entity.RouteBranch;

/**
 * Enriched response DTO for a route ↔ branch association (ADR-0012 D-11).
 */
public record RouteBranchDto(
        Long routeId,
        Long branchId,
        String branchUid,
        String branchCode,
        String branchName,
        String assignedAt,
        Long assignedBy
) {

    public static RouteBranchDto from(RouteBranch rb, String branchUid,
                                      String branchCode, String branchName) {
        return new RouteBranchDto(
                rb.getRoute().getId(),
                rb.getBranchId(),
                branchUid,
                branchCode,
                branchName,
                rb.getAssignedAt() != null ? rb.getAssignedAt().toString() : null,
                rb.getAssignedBy()
        );
    }
}
