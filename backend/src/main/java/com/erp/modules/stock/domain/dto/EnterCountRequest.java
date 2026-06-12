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
            String reasonCode
    ) {}
}
