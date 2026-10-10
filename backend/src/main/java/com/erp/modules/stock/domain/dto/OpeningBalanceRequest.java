package com.erp.modules.stock.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request body for an OPENING_BALANCE movement (ADR-0010 D-11, FR-STOCK-10).
 *
 * <p>Quantity must be positive (D-6: an opening balance seeds an initial level — negative opening is
 * rejected; "we already had negative" is an ADJUSTMENT). Branch from {@code RequestContext}.
 */
public record OpeningBalanceRequest(
        @NotBlank String productUid,
        @NotNull @Positive BigDecimal quantity,
        String note,
        /** Optional unit {@code quantity} is stated in (STK-08 / OPN-01). Null = base unit. */
        String unitUid,
        /**
         * Optional opening cost of ONE {@code unitUid} (PRD-07 / LSF-09). Null = the product's own
         * cost. Applied through the one-time opening valuation in the same transaction, which needs
         * {@code INVENTORY.OPENING.SET}.
         */
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitCost
) {

    /** Pre-STK-08 shape: quantity in base units, cost from the product. */
    public OpeningBalanceRequest(String productUid, BigDecimal quantity, String note) {
        this(productUid, quantity, note, null, null);
    }
}
