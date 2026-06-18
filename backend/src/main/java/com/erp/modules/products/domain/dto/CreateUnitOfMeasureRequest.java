package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.DimensionType;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to create a new UnitOfMeasure.
 * Carries {@code companyUid} (String) per the uid-in-URL convention (ADR-0007).
 */
public record CreateUnitOfMeasureRequest(
        @NotBlank String companyUid,
        @NotBlank String code,
        @NotBlank String name,
        // --- P2 D5 unit metadata (ADR-0041 D5) — all optional ---
        String symbol,
        DimensionType dimensionType,
        Short decimalPlaces,
        Boolean fractional
) {
    /**
     * Backward-compatible constructor for callers that predate the P2 D5 unit metadata.
     * Defaults symbol/dimensionType/decimalPlaces/fractional to null (entity defaults kept), so no
     * existing call site changes.
     */
    public CreateUnitOfMeasureRequest(String companyUid, String code, String name) {
        this(companyUid, code, name, null, null, null, null);
    }
}
