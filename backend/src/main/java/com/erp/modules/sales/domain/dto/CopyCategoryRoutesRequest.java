package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Copy one branch's kitchen routing (department → station) to another branch of the same company.
 * Routes only to stations the target branch ALREADY has (matched by name); never creates a station,
 * never moves an existing route, never changes the target's default station.
 *
 * @param sourceBranchUid the branch whose routing is copied
 * @param targetBranchUid the branch that receives it
 */
public record CopyCategoryRoutesRequest(
        @NotBlank String sourceBranchUid,
        @NotBlank String targetBranchUid
) {
}
