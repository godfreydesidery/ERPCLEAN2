package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Typed payload record for the {@code STOCK.RECEIVED} outbox event (ADR-0010 D-5, ADR-0011).
 *
 * <p>Shape mirrors {@code SaleFinalisedPayload} (ADR-0008 D-9), as specified in ADR-0010 D-5 /
 * ADR-0011: {@code { receiptUid, companyId, branchId, receivedAt, lines:[{ productId, productUid,
 * unitId, qtyInBase, unitCostAmount }] }}. Owned by the stock module until Purchases defines its own;
 * Stock only reads this payload — it does not produce it.
 *
 * <p>ADR-0020 D-3: {@code unitCostAmount} added to LineItem — populated by Purchases from
 * {@code goods_receipt_lines.unit_cost_amount}. A null value here is treated as a zero-cost receipt
 * with a WARN (defensive — in-flight events pre-D-3 carry no cost; the handler must not silently
 * skip the GL leg and break the recon).
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
            BigDecimal qtyInBase,
            BigDecimal unitCostAmount   // NEW (ADR-0020 D-3) — goods_receipt_lines.unit_cost_amount
    ) {
        /** Back-compat: a receipt with no costing (manual/legacy paths) — unit cost null. */
        public LineItem(Long productId, String productUid, Long unitId, BigDecimal qtyInBase) {
            this(productId, productUid, unitId, qtyInBase, null);
        }
    }
}
