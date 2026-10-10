package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.AdjustmentReason;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for a manual ADJUSTMENT movement (ADR-0010 D-11, FR-STOCK-09).
 *
 * <p>The active branch comes from {@code RequestContext} — not from this body (D-11 / ADR-0008 D-12).
 * {@code productUid} resolves the target product (cross-module by uid, not id).
 * {@code reasonCode} is mandatory (BR-STOCK-05, D-7); {@code note} is optional.
 *
 * <p>ADR-0025 D-6 (V28): {@code costCentreValueUid} and {@code departmentValueUid} are optional
 * dimension defaults for the adjustment expense GL leg. Null = untagged (NFR-CC-01).
 */
public record AdjustStockRequest(
        @NotBlank String productUid,
        /** Signed delta in {@code unitUid} (base units when null). May be positive or negative. */
        @NotNull BigDecimal quantity,
        @NotNull AdjustmentReason reasonCode,
        String note,
        // --- cost-centre (ADR-0025 D-6) ---
        String costCentreValueUid,   // nullable — untagged when null
        String departmentValueUid,   // nullable — untagged when null
        /**
         * Optional location to correct (STK-01). Must be an active location of the ACTIVE branch,
         * and not its in-transit location. Null = where the product actually sits in the branch
         * (ignoring empty and in-transit rows), or the branch default on first touch.
         */
        String locationUid,
        /**
         * Optional unit {@code quantity} is stated in (STK-08 / OPN-01): the product's base unit or
         * one of its pack sizes. Null = base unit (every caller that predates this). The quantity is
         * converted to base units with the pack factor before it is posted.
         */
        String unitUid
) {

    /** Pre-STK-08 shape: quantity in base units. */
    public AdjustStockRequest(String productUid, BigDecimal quantity, AdjustmentReason reasonCode,
                              String note, String costCentreValueUid, String departmentValueUid,
                              String locationUid) {
        this(productUid, quantity, reasonCode, note, costCentreValueUid, departmentValueUid,
                locationUid, null);
    }
}
