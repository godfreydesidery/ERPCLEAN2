package com.erp.modules.stock.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO: enter sold/physical quantities for van reconciliation lines (ADR-0051 D-8).
 * {@code loadedQty}/{@code returnedQty} are optional overrides of the derived values.
 *
 * <p>{@code physicalQty} is OPTIONAL (backend review fix): the route agent's sold figure is the
 * key manual entry and is usually keyed before the physical van count is done, so a sold-only
 * entry must be saveable. When {@code physicalQty} is omitted, {@code sold} is still applied and
 * {@code expected} recomputed; variance stays unset (zero/null — see
 * {@link com.erp.modules.stock.domain.entity.VanReconciliationLine#enterCounts}) until a later
 * call supplies the physical count.
 */
public record EnterVanReconciliationLinesRequest(
        @NotEmpty @Valid List<LineEntry> lines
) {
    /** One line's entry. Identify the line by {@code lineId} or by {@code productUid} (either works). */
    public record LineEntry(
            Long lineId,
            String productUid,
            @NotNull BigDecimal soldQty,
            BigDecimal physicalQty,
            BigDecimal loadedQty,
            BigDecimal returnedQty
    ) {}
}
