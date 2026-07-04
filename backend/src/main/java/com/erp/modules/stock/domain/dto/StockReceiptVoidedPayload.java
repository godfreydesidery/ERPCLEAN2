package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Typed payload record for the {@code STOCK.RECEIPT.VOIDED} outbox event (ADR-0010 D-5, ADR-0011).
 *
 * <p>Mirrors {@code StockReceivedPayload} without the received-at timestamp (the void is
 * instantaneous; reversal timestamps default to now()). Shape: {@code { receiptUid, companyId,
 * branchId, lines:[{ productId, productUid, unitId, qtyInBase, lotNumber, manufactureDate,
 * expiryDate, serialNumbers }] }}.
 *
 * <p>D-2: lot/batch + serial fields added to LineItem (mirrors {@code StockReceivedPayload.LineItem},
 * V76) so {@code GoodsReceiptReversalStockHandler} can back out {@code stock_batches}/
 * {@code stock_serials} rows written on receipt — symmetric with the forward path.
 *
 * <p>D-2 FIX B: {@code lotTracked} added to LineItem — the forward path
 * ({@code GoodsReceiptStockHandler.writeBatchTracking}) writes an {@code "UNTRACKED"} sentinel
 * batch whenever the product is lot-tracked, even if the received line carries no lot/expiry data
 * at all. Without knowing {@code product.lotTracked()} the reversal handler had no way to target
 * that sentinel batch, leaving it permanently inflated after a void. The producer
 * ({@code GoodsReceiptServiceImpl.voidReceipt}) now stamps this from the Product it already has
 * to hand.
 */
public record StockReceiptVoidedPayload(
        String receiptUid,
        Long companyId,
        Long branchId,
        List<LineItem> lines,
        /** Human-readable GRN number — used in GL journal memos (FOLLOW-001). */
        String receiptNumber
) {
    /** Back-compat: pre-FOLLOW-001 callers/tests that do not supply receiptNumber. */
    public StockReceiptVoidedPayload(String receiptUid, Long companyId, Long branchId,
                                     List<LineItem> lines) {
        this(receiptUid, companyId, branchId, lines, null);
    }

    public record LineItem(
            Long productId,
            String productUid,
            Long unitId,
            BigDecimal qtyInBase,
            // D-2 — lot/batch + serial tracking (all nullable/empty; soft; mirrors STOCK.RECEIVED)
            String       lotNumber,
            LocalDate    manufactureDate,
            LocalDate    expiryDate,
            List<String> serialNumbers,
            /** D-2 FIX B: mirrors {@code product.lotTracked()} at receipt time. */
            boolean      lotTracked
    ) {
        /** Back-compat: callers that supply only the pre-D-2 fields (no lot/serial/lotTracked). */
        public LineItem(Long productId, String productUid, Long unitId, BigDecimal qtyInBase) {
            this(productId, productUid, unitId, qtyInBase, null, null, null, List.of(), false);
        }

        /** Back-compat: pre-FIX-B callers that supply the D-2 lot/serial fields but not lotTracked. */
        public LineItem(Long productId, String productUid, Long unitId, BigDecimal qtyInBase,
                        String lotNumber, LocalDate manufactureDate, LocalDate expiryDate,
                        List<String> serialNumbers) {
            this(productId, productUid, unitId, qtyInBase, lotNumber, manufactureDate, expiryDate,
                 serialNumbers, false);
        }
    }
}
