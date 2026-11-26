package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Start one branch's menu and branch prices from another branch's (ADR-0073). Fills gaps only: an
 * item the target branch already has an entry for is never touched.
 *
 * @param sourceBranchUid the branch to copy from
 * @param targetBranchUid the branch to fill (must be in the same company)
 */
public record CopyBranchMenuRequest(
        @NotBlank String sourceBranchUid,
        @NotBlank String targetBranchUid
) {
}
