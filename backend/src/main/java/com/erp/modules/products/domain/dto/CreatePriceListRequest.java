package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.PriceListScope;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * Request DTO to create a new PriceList.
 * Carries {@code companyUid} (String) per ADR-0007 D-12.
 */
public record CreatePriceListRequest(
        @NotBlank String companyUid,
        @NotBlank String code,
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
     * Defaults all D5 fields to null (entity defaults kept), so no existing call site changes.
     */
    public CreatePriceListRequest(String companyUid, String code, String name) {
        this(companyUid, code, name, null, null, null, null, null, null);
    }
}
