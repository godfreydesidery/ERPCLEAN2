package com.erp.modules.ap.domain.dto;

import com.erp.modules.products.domain.enums.VatStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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
        /** Unit cost must be >= 0; negative values violate chk_supplier_bill_amounts (issue #18). */
        @NotNull @PositiveOrZero BigDecimal unitCostAmount,
        // D-8: optional per-line VAT
        VatStatus vatStatus,
        BigDecimal vatRate,
        // D-8: optional GL override for service lines (uid of chart_of_accounts entry)
        String glAccountUid,
        // ADR-0041 D4: optional per-line dimension tags (soft-FK → dimension_values.id). When set,
        // these flow onto the GL P&L leg this service line generates (BillMatchServiceImpl). A bill
        // raised from a PO can pass the PO line's dimensions through here. null = untagged.
        Long costCentreValueId,
        Long departmentValueId
) {
    /** Back-compat: construct without VAT/GL override fields (no VAT, PURCHASES default). */
    public BillLineRequest(Long productId, String poLineUid, String grLineUid,
                           String description, BigDecimal billedQty, BigDecimal unitCostAmount) {
        this(productId, poLineUid, grLineUid, description, billedQty, unitCostAmount,
                null, null, null, null, null);
    }

    /** Back-compat: construct with VAT/GL override but without dimension tags (ADR-0040 D-8 callers). */
    public BillLineRequest(Long productId, String poLineUid, String grLineUid,
                           String description, BigDecimal billedQty, BigDecimal unitCostAmount,
                           VatStatus vatStatus, BigDecimal vatRate, String glAccountUid) {
        this(productId, poLineUid, grLineUid, description, billedQty, unitCostAmount,
                vatStatus, vatRate, glAccountUid, null, null);
    }
}
