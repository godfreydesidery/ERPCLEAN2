package com.erp.modules.ap.domain.dto;

import com.erp.modules.products.domain.enums.VatStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Per-line bill entry request.
 * D-8: vatStatus / vatRate / glAccountUid are optional. When omitted, no VAT is applied and
 * the PURCHASES GL config key is used for service-line debit routing.
 */
public record BillLineRequest(
        Long productId,
        String poLineUid,
        String grLineUid,
        @NotBlank String description,
        @NotNull @Positive BigDecimal billedQty,
        @NotNull BigDecimal unitCostAmount,
        // D-8: optional per-line VAT
        VatStatus vatStatus,
        BigDecimal vatRate,
        // D-8: optional GL override for service lines (uid of chart_of_accounts entry)
        String glAccountUid
) {
    /** Back-compat: construct without VAT/GL override fields (no VAT, PURCHASES default). */
    public BillLineRequest(Long productId, String poLineUid, String grLineUid,
                           String description, BigDecimal billedQty, BigDecimal unitCostAmount) {
        this(productId, poLineUid, grLineUid, description, billedQty, unitCostAmount,
                null, null, null);
    }
}
