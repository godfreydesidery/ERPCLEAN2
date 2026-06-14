package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Outbox payload for the {@code STOCK.RECEIVED} event emitted by GoodsReceiptServiceImpl
 * at receive (ADR-0011 D-7/D-8).
 *
 * <p><b>Payload reuse decision:</b> Purchases defines its own record here (same package discipline as
 * {@code SaleFinalisedPayload} living in {@code sales.domain.dto}). The fields are IDENTICAL to
 * {@code stock.domain.dto.StockReceivedPayload} — the outbox payload is JSON matched by field name,
 * so the two records produce the same wire shape and Stock's handler deserialises either.
 * This avoids a cross-module compile dependency from Purchases → Stock (ADR-0011 D-1 boundary).
 *
 * <p>Shape (fixed by ADR-0010 D-5 / ADR-0011 D-8 / ADR-0020 D-3):
 * {@code { receiptUid, companyId, branchId, receivedAt, lines:[{ productId, productUid, unitId,
 * qtyInBase, unitCostAmount }] }}
 *
 * <p>ADR-0020 D-3: {@code unitCostAmount} added — populated from
 * {@code goods_receipt_lines.unit_cost_amount} so the stock handler can recompute the moving average
 * and post the GRNI GL leg without a cross-module read.
 */
public record StockReceivedPayload(
        String  receiptUid,
        Long    companyId,
        Long    branchId,
        Instant receivedAt,
        List<LineItem> lines,
        /** Human-readable GRN number (e.g. GRN-0001) — used in GL journal memos (FOLLOW-001). */
        String  receiptNumber
) {
    /** Back-compat: callers that do not yet supply receiptNumber. */
    public StockReceivedPayload(String receiptUid, Long companyId, Long branchId,
                                Instant receivedAt, List<LineItem> lines) {
        this(receiptUid, companyId, branchId, receivedAt, lines, null);
    }
    public record LineItem(
            Long       productId,
            String     productUid,
            Long       unitId,
            BigDecimal qtyInBase,
            BigDecimal unitCostAmount   // NEW (ADR-0020 D-3) — goods_receipt_lines.unit_cost_amount
    ) {}
}
