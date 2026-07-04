package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Outbox payload for the {@code STOCK.RECEIPT.VOIDED} compensating event emitted by
 * GoodsReceiptServiceImpl at void (ADR-0011 D-7/D-8).
 *
 * <p>Fields are IDENTICAL to {@code stock.domain.dto.StockReceiptVoidedPayload} — same wire shape,
 * no cross-module compile dependency (Purchases-owned, per the boundary in ADR-0011 D-1).
 *
 * <p>Shape (ADR-0011 D-8): {@code { receiptUid, companyId, branchId, lines:[...] }}
 * Lines are included for diagnostic traceability; Stock's reversal handler does NOT rely on them —
 * it reverses from its own ledger by {@code source_document_uid = receiptUid} (ADR-0010 D-5).
 *
 * <p>D-2: lot/batch + serial fields added to LineItem (mirrors {@code StockReceivedPayload.LineItem},
 * V76) so the reversal handler can back out {@code stock_batches}/{@code stock_serials} rows written
 * on receipt — symmetric with the forward path.
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
        Long   companyId,
        Long   branchId,
        List<LineItem> lines,
        /** Human-readable GRN number — used in GL journal memos (FOLLOW-001). */
        String receiptNumber
) {
    /** Back-compat: callers that do not yet supply receiptNumber. */
    public StockReceiptVoidedPayload(String receiptUid, Long companyId, Long branchId,
                                     List<LineItem> lines) {
        this(receiptUid, companyId, branchId, lines, null);
    }
    public record LineItem(
            Long       productId,
            String     productUid,
            Long       unitId,
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
