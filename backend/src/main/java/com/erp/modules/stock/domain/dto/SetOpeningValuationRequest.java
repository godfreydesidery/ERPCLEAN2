package com.erp.modules.stock.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request to set the one-time opening inventory cost/value for an existing on-hand row
 * (ADR-0020 D-5b, FR-INV-06). Gated by INVENTORY.OPENING.SET.
 */
public record SetOpeningValuationRequest(

        /** uid of the stock_on_hand row to value. */
        @NotNull
        String stockOnHandUid,

        /**
         * Opening unit cost in base currency (&gt;= 0).
         * Value = on_hand_qty × openingCost is computed and posted DR 1300 / CR 3100.
         */
        @NotNull
        @DecimalMin(value = "0.0000", inclusive = true)
        BigDecimal openingCost
) {}
