package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Grant-role request. Binds a role to a user scoped to a company (and optionally a branch).
 * {@code branchUid} null = company-wide grant (FR-IAM-13). BR-5: if a branch is supplied its
 * company must match {@code companyUid} (validated in the service).
 */
public record GrantRoleRequest(
        @NotBlank String userUid,
        @NotBlank String roleUid,
        @NotBlank String companyUid,
        String branchUid) {
}
