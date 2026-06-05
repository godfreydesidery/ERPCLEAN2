package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-branch request under a company (addressed by {@code companyUid}). {@code makeDefault}
 * marks this branch the company default on creation (clearing any prior default in the same TX).
 */
public record CreateBranchRequest(
        @NotBlank @Size(max = 26) String companyUid,
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 64) String timeZone,
        boolean makeDefault) {
}
