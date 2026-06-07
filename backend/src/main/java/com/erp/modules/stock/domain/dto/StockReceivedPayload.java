package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Typed payload record for the {@code STOCK.RECEIVED} outbox event (ADR-0010 D-5, ADR-0011).
 *
 * <p>Shape mirrors {@code SaleFinalisedPayload} (ADR-0008 D-9), as specified in ADR-0010 D-5 /
 * ADR-0011: {@code { receiptUid, companyId, branchId, receivedAt, lines:[{ productId, productUid,
 * unitId, qtyInBase }] }}. Owned by the stock module until Purchases defines its own; Stock only
 * reads this payload — it does not produce it.
 */
public record StockReceivedPayload(
        String receiptUid,
        Long companyId,
        Long branchId,
        Instant receivedAt,
        List<LineItem> lines
) {

    public record LineItem(
            Long productId,
            String productUid,
            Long unitId,
            BigDecimal qtyInBase
    ) {}
}
