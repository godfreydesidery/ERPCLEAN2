package com.erp.modules.stock.domain.dto;

import java.time.Instant;
import java.util.List;

/**
 * Outbox payload for STOCK.TRANSFER.DISPATCHED (ADR-0028 D-12).
 * Consumed by {@link com.erp.modules.stock.events.TransferDispatchStockHandler}.
 */
public record TransferDispatchedPayload(
        String transferUid,
        Long companyId,
        Long sourceBranchId,
        Long sourceLocationId,
        Long inTransitLocationId,
        Instant dispatchedAt,
        List<LineItem> lines
) {
    /** Per-line item in the dispatch payload. */
    public record LineItem(
            Long productId,
            String productUid,
            Long unitId,
            java.math.BigDecimal qtyInBase,
            java.math.BigDecimal unitCostAmount,
            java.math.BigDecimal valueAmount
    ) {}
}
