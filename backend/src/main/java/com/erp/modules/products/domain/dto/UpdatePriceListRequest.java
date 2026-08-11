package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.PriceListScope;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * Request DTO to update a PriceList's name (code is immutable once set).
 *
 * <p>The metadata fields are a partial update: omitting one keeps the stored value. Callers that
 * want to change a field send it; there is deliberately no way to clear currency or the validity
 * window here, because every screen posting this form sends a subset and clearing-on-omission cost
 * real data.
 */
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
