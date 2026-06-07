package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Request DTO to associate a product with a branch (FR-PROD-20/21). */
public record AssignProductBranchRequest(
        @NotBlank String branchUid
) {
}
