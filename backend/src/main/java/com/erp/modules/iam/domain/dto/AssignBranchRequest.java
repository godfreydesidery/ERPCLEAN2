package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Assign a user to a branch (DATA-MODEL §1.9). {@code makeDefault} optionally promotes this
 * assignment to the user's single default in the same operation (the previous default is cleared).
 */
public record AssignBranchRequest(
        @NotBlank @Size(max = 26) String userUid,
        @NotBlank @Size(max = 26) String branchUid,
        boolean makeDefault) {
}
