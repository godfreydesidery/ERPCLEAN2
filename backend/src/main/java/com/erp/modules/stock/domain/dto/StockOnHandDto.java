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
 *
 * <p>{@code productCode} and {@code productName} are enriched by the service layer from the
 * products module (cross-module boundary: service calls ProductService, not a repository join).
 * They may be {@code null} only when the entity-only factory overload is used (e.g. tests).
 *
 * <p>{@code locationUid}/{@code locationName} are enriched by the service layer from
 * {@code StockLocationRepository} — an intra-module (stock-owns-stock) lookup, not a cross-module
 * call. Added so a product stocked at more than one location within a branch produces
 * distinguishable rows (busy-day-sim finding: {@code listOnHand} previously returned raw
 * per-location rows with no way to tell them apart or know which location each belonged to).
 * {@code locationId} is always populated (a scalar column on the entity itself); the uid/name may
 * be {@code null} only when the entity-only factory overload is used (e.g. tests).
 */
public record StockOnHandDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        /** Scalar FK to stock_locations.id — the physical location within the branch (ADR-0028 D-4). */
        Long locationId,
        /** Location's external uid — enriched by the service layer for display/addressing. */
        String locationUid,
        /** Location's display name — enriched by the service layer for display. */
        String locationName,
        Long productId,
        /** Product code — denormalised from the products module for display; never null in API responses. */
        String productCode,
        /** Product name — denormalised from the products module for display; never null in API responses. */
        String productName,
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

    /**
     * Entity-only factory — {@code productCode}/{@code productName} and
     * {@code locationUid}/{@code locationName} will be {@code null}. {@code locationId} is still
     * populated (it lives on the entity itself). Use only in contexts where the display labels are
     * not needed (e.g. internal tests that assert quantity/flags only). API responses should use
     * {@link #from(StockOnHand, String, String, String, String)}.
     */
    public static StockOnHandDto from(StockOnHand s) {
        return from(s, null, null, null, null);
    }

    /**
     * Enriched factory — carries product code + name, but no location display fields.
     * Retained for the existing {@code setReorderLevel} response path.
     *
     * @param productCode  the product's short code (from {@code ProductService.getById})
     * @param productName  the product's display name (from {@code ProductService.getById})
     */
    public static StockOnHandDto from(StockOnHand s, String productCode, String productName) {
        return from(s, productCode, productName, null, null);
    }

    /**
     * Fully-enriched factory — carries product code/name and location uid/name alongside the
     * on-hand figures. This is the factory used by the on-hand list API response path.
     *
     * @param productCode   the product's short code (from {@code ProductService.getById})
     * @param productName   the product's display name (from {@code ProductService.getById})
     * @param locationUid   the row's location uid (from {@code StockLocationRepository})
     * @param locationName  the row's location display name (from {@code StockLocationRepository})
     */
    public static StockOnHandDto from(StockOnHand s, String productCode, String productName,
                                       String locationUid, String locationName) {
        boolean neg = s.getQuantity().compareTo(BigDecimal.ZERO) < 0;
        boolean low = s.getReorderLevel() != null
                && s.getQuantity().compareTo(s.getReorderLevel()) <= 0;
        return new StockOnHandDto(
                s.getId(),
                s.getUid(),
                s.getCompanyId(),
                s.getBranchId(),
                s.getLocationId(),
                locationUid,
                locationName,
                s.getProductId(),
                productCode,
                productName,
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
