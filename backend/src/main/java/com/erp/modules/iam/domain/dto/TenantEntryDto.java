package com.erp.modules.iam.domain.dto;

/**
 * Where the platform operator lands when it enters a tenant (owner, 2026-11-07): that tenant's
 * default branch. The web sends {@code branchUid} as {@code X-Branch-Uid}; the filter then runs the
 * request as that tenant.
 */
public record TenantEntryDto(String organisationUid, String organisationName, String companyName,
                             String branchUid, String branchName, String branchCode) {
}
