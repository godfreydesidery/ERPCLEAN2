package com.erp.modules.parties.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for the branch-association endpoint (POST branch uid) — associates a party with a branch.
 */
public record AssignPartyBranchRequest(@NotBlank String branchUid) {
}
