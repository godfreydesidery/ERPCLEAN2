package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBranch;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO for a product–branch association (FR-PROD-20/21).
 * {@code active}, {@code reorderLevel} and {@code branchPrice} are the PRD-09 branch overrides
 * (additive; null reorder level / price = inherit the product-level value).
 */
public record ProductBranchDto(
        Long branchId,
        String assignedAt,
        Long assignedBy,
        boolean active,
        BigDecimal reorderLevel,
        BigDecimal branchPrice
) {

    public static ProductBranchDto from(ProductBranch pb) {
        return new ProductBranchDto(
                pb.getBranchId(),
                pb.getAssignedAt() != null ? pb.getAssignedAt().toString() : null,
                pb.getAssignedBy(),
                pb.isActive(),
                pb.getReorderLevel(),
                pb.getBranchPrice()
        );
    }

    public static ProductBranchDto of(Long branchId, Instant assignedAt, Long assignedBy) {
        return new ProductBranchDto(
                branchId,
                assignedAt != null ? assignedAt.toString() : null,
                assignedBy,
                true,
                null,
                null
        );
    }
}
