package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.entity.StockOnHand;
import java.math.BigDecimal;

/**
 * Full response DTO for a stock-on-hand row (ADR-0010 D-11).
 *
 * <p>Derived flags ({@code negative}, {@code low}) are computed here, not stored on the entity
 * (D-2 — negative is a flagged, queryable state, not a forbidden one; low is indicator-only).
 * Quantities serialise as strings to avoid JS precision loss (the global Long-as-string config
 * handles Long ids; BigDecimal are also serialised as strings via the same Jackson config).
 */
public record StockOnHandDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long productId,
        BigDecimal quantity,
        BigDecimal reorderLevel,
        /** Optional max-stock indicator threshold (P2-M5). */
        BigDecimal maxQty,
        /** Last applied movement timestamp (P2-M5 snapshot), ISO string or null. */
        String lastMovementAt,
        /** Last physical count timestamp (P2-M5 snapshot), ISO string or null. */
        String lastCountedAt,
        /** Derived: quantity < 0 (overselling indicator, FR-STOCK-04). */
        boolean negative,
        /** Derived: reorder_level IS NOT NULL AND quantity <= reorder_level (low-stock indicator). */
        boolean low,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static StockOnHandDto from(StockOnHand s) {
        boolean neg = s.getQuantity().compareTo(BigDecimal.ZERO) < 0;
        boolean low = s.getReorderLevel() != null
                && s.getQuantity().compareTo(s.getReorderLevel()) <= 0;
        return new StockOnHandDto(
                s.getId(),
                s.getUid(),
                s.getCompanyId(),
                s.getBranchId(),
                s.getProductId(),
                s.getQuantity(),
                s.getReorderLevel(),
                s.getMaxQty(),
                s.getLastMovementAt() != null ? s.getLastMovementAt().toString() : null,
                s.getLastCountedAt() != null ? s.getLastCountedAt().toString() : null,
                neg,
                low,
                s.getVersion(),
                s.getCreatedAt() != null ? s.getCreatedAt().toString() : null,
                s.getCreatedBy(),
                s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null,
                s.getUpdatedBy()
        );
    }
}
