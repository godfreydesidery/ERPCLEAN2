package com.erp.modules.stock.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO: enter counted quantities for stock count lines (FR-INVD-13, ADR-0028 D-6).
 */
public record EnterCountRequest(
        @NotEmpty @Valid List<LineEntry> lines
) {
    /** Counted quantity entry for one line. */
    public record LineEntry(
            @NotNull Long lineId,
            @NotNull BigDecimal countedQty,
            String reasonCode,
            /**
             * Optional unit {@code countedQty} is stated in (STK-08 / OPN-01): the product's base
             * unit or one of its pack sizes. Null = base unit. Stored converted to base.
             */
            String unitUid
    ) {

        /** Pre-STK-08 shape: counted quantity in base units. */
        public LineEntry(Long lineId, BigDecimal countedQty, String reasonCode) {
            this(lineId, countedQty, reasonCode, null);
        }
    }
}
