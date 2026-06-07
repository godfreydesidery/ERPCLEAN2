package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBranch;
import java.time.Instant;

/** Response DTO for a product–branch association (FR-PROD-20/21). */
public record ProductBranchDto(
        Long branchId,
        String assignedAt,
        Long assignedBy
) {

    public static ProductBranchDto from(ProductBranch pb) {
        return new ProductBranchDto(
                pb.getBranchId(),
                pb.getAssignedAt() != null ? pb.getAssignedAt().toString() : null,
                pb.getAssignedBy()
        );
    }

    public static ProductBranchDto of(Long branchId, Instant assignedAt, Long assignedBy) {
        return new ProductBranchDto(
                branchId,
                assignedAt != null ? assignedAt.toString() : null,
                assignedBy
        );
    }
}
