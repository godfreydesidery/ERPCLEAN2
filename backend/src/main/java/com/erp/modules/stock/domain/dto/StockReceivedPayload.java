package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Typed payload record for the {@code STOCK.RECEIVED} outbox event (ADR-0010 D-5, ADR-0011).
 *
 * <p>Shape mirrors {@code SaleFinalisedPayload} (ADR-0008 D-9), as specified in ADR-0010 D-5 /
 * ADR-0011: {@code { receiptUid, companyId, branchId, receivedAt, lines:[{ productId, productUid,
 * unitId, qtyInBase, unitCostAmount, lotNumber, manufactureDate, expiryDate, serialNumbers }] }}.
 * Owned by the stock module until Purchases defines its own; Stock only reads this payload —
 * it does not produce it.
 *
 * <p>ADR-0020 D-3: {@code unitCostAmount} added to LineItem — populated by Purchases from
 * {@code goods_receipt_lines.unit_cost_amount}. A null value here is treated as a zero-cost receipt
 * with a WARN (defensive — in-flight events pre-D-3 carry no cost; the handler must not silently
 * skip the GL leg and break the recon).
 *
 * <p>V76: lot/serial fields added to LineItem — all nullable/empty; stock handler uses them
 * to call StockBatchService.receiveQty / StockSerialService.record.
 */
public record StockReceivedPayload(
        String receiptUid,
        Long companyId,
        Long branchId,
        Instant receivedAt,
        List<LineItem> lines,
        /** Human-readable GRN number (e.g. GRN-0001) — used in GL journal memos (FOLLOW-001). */
        String receiptNumber
) {

    public record LineItem(
            Long productId,
            String productUid,
            Long unitId,
            BigDecimal qtyInBase,
            BigDecimal unitCostAmount,   // ADR-0020 D-3 — goods_receipt_lines.unit_cost_amount
            // V76 — lot/batch + serial tracking (all nullable/empty; soft)
            String       lotNumber,
            LocalDate    manufactureDate,
            LocalDate    expiryDate,
            List<String> serialNumbers
    ) {
        /** Back-compat: a receipt with no costing and no lot/serial (manual/legacy paths). */
        public LineItem(Long productId, String productUid, Long unitId, BigDecimal qtyInBase) {
            this(productId, productUid, unitId, qtyInBase, null, null, null, null, List.of());
        }

        /** Back-compat: a receipt with cost but no lot/serial (pre-V76 paths). */
        public LineItem(Long productId, String productUid, Long unitId,
                        BigDecimal qtyInBase, BigDecimal unitCostAmount) {
            this(productId, productUid, unitId, qtyInBase, unitCostAmount,
                    null, null, null, List.of());
        }
    }

    /** Back-compat: pre-FOLLOW-001 callers/tests that do not supply receiptNumber. */
    public StockReceivedPayload(String receiptUid, Long companyId, Long branchId,
                                Instant receivedAt, List<LineItem> lines) {
        this(receiptUid, companyId, branchId, receivedAt, lines, null);
    }
}
