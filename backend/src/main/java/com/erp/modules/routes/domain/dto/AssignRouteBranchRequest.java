package com.erp.modules.routes.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to associate a branch with a route (FR-ROUTE-11, ADR-0012 D-11).
 */
public record AssignRouteBranchRequest(
        @NotBlank String branchUid
) {
}
