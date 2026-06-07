package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed payload record for the {@code STOCK.RECEIPT.VOIDED} outbox event (ADR-0010 D-5, ADR-0011).
 *
 * <p>Mirrors {@code StockReceivedPayload} without the received-at timestamp (the void is
 * instantaneous; reversal timestamps default to now()). Shape: {@code { receiptUid, companyId,
 * branchId, lines:[{ productId, productUid, unitId, qtyInBase }] }}.
 */
public record StockReceiptVoidedPayload(
        String receiptUid,
        Long companyId,
        Long branchId,
        List<LineItem> lines
) {

    public record LineItem(
            Long productId,
            String productUid,
            Long unitId,
            BigDecimal qtyInBase
    ) {}
}
