package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.DimensionType;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to update a UnitOfMeasure's display name.
 * Code and company are not updatable (immutable after creation).
 */
public record UpdateUnitOfMeasureRequest(
        @NotBlank String name,
        // --- P2 D5 unit metadata (ADR-0041 D5) — all optional ---
        String symbol,
        DimensionType dimensionType,
        Short decimalPlaces,
        Boolean fractional
) {
    /**
     * Backward-compatible constructor for callers that predate the P2 D5 unit metadata.
     * Defaults symbol/dimensionType/decimalPlaces/fractional to null (existing values kept), so no
     * existing call site changes.
     */
    public UpdateUnitOfMeasureRequest(String name) {
        this(name, null, null, null, null);
    }
}
