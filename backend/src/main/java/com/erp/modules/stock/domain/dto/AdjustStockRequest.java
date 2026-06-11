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
        /** Signed delta in base units. May be positive or negative (± ADJUSTMENT). */
        @NotNull BigDecimal quantity,
        @NotNull AdjustmentReason reasonCode,
        String note,
        // --- cost-centre (ADR-0025 D-6) ---
        String costCentreValueUid,   // nullable — untagged when null
        String departmentValueUid    // nullable — untagged when null
) {}
