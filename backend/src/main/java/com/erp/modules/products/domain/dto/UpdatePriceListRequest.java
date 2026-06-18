package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.PriceListScope;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** Request DTO to update a PriceList's name (code is immutable once set). */
public record UpdatePriceListRequest(
        @NotBlank String name,
        // --- P2 D5 pricing-resolution metadata (ADR-0041 D5) — all optional ---
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Boolean priceIncludesVat,
        Boolean isDefault,
        PriceListScope scope
) {
    /**
     * Backward-compatible constructor for callers that predate the P2 D5 pricing metadata.
     * Defaults all D5 fields to null (existing values kept), so no existing call site changes.
     */
    public UpdatePriceListRequest(String name) {
        this(name, null, null, null, null, null, null);
    }
}
