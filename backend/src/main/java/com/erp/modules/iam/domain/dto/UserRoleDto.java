package com.erp.modules.iam.domain.dto;

/**
 * Response shape for a user-role assignment (DATA-MODEL §1.8). Exposes uids for user, company and
 * branch rather than numeric ids — callers address all three by uid. {@code branchUid} is null for
 * company-wide grants. {@code grantedAt} is an ISO-8601 string.
 */
public record UserRoleDto(
        Long id,
        String uid,
        String userUid,
        String roleCode,
        String companyUid,
        String branchUid,
        String grantedAt) {
}
