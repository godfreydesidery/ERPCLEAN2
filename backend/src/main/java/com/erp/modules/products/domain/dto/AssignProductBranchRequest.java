package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Request DTO to associate a product with a branch (FR-PROD-20/21).
 *
 * <p>PRD-09: the branch-level overrides that the Product Master collects ({@code active},
 * {@code reorderLevel}, {@code branchPrice}) were silently discarded. All optional: a null
 * {@code active} means "active", a null reorder level / branch price means "inherit the
 * product-level value".
 */
public record AssignProductBranchRequest(
        @NotBlank String branchUid,
        Boolean active,
        @DecimalMin("0") BigDecimal reorderLevel,
        @DecimalMin("0") BigDecimal branchPrice
) {

    /** Back-compatible single-field form (no overrides). */
    public AssignProductBranchRequest(String branchUid) {
        this(branchUid, null, null, null);
    }
}
