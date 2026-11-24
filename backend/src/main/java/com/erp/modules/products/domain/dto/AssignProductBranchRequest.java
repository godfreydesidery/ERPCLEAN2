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
 *
 * <p>ADR-0073: {@code active} is "sold at this branch" and {@code branchPrice} is the item's price
 * there, per base unit. On an existing row a null price leaves the stored price as it was, so
 * {@code clearBranchPrice = true} is how a branch price is removed (back to the company price).
 */
public record AssignProductBranchRequest(
        @NotBlank String branchUid,
        Boolean active,
        @DecimalMin("0") BigDecimal reorderLevel,
        @DecimalMin("0") BigDecimal branchPrice,
        Boolean clearBranchPrice
) {

    /** Back-compatible single-field form (no overrides). */
    public AssignProductBranchRequest(String branchUid) {
        this(branchUid, null, null, null, null);
    }

    /** The form every caller used before {@code clearBranchPrice} existed. */
    public AssignProductBranchRequest(String branchUid, Boolean active, BigDecimal reorderLevel,
                                      BigDecimal branchPrice) {
        this(branchUid, active, reorderLevel, branchPrice, null);
    }
}
