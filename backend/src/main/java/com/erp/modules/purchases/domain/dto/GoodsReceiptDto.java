package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-only response DTO for a Goods Receipt (ADR-0011 D-12).
 *
 * <p>Kilimanjaro K2 (2026-08-08) adds {@code supplierName}, {@code currency} and
 * {@code receiptTotalAmount}. The printed GRN now shows the per-line cost and the receipt value
 * (superseding the qty-only rule in BR-DOC-07 / ADR-0023 D-5 for the GRN — the delivery note stays
 * qty-only). The receipt-level total has no source column, so the <b>purchases service computes
 * it</b> and hands it over on this DTO: BR-DOC-02 / BR-DOC-09 forbid the documents module from
 * deriving any amount of its own.
 */
public record GoodsReceiptDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        Long   purchaseOrderId,
        String purchaseOrderUid,
        String receiptNumber,
        GoodsReceiptStatus status,
        Long   supplierId,
        /** K2: supplier name snapshot carried from the backing PO; null when it cannot be resolved. */
        String supplierName,
        Instant receivedAt,
        Instant voidedAt,
        String  voidReason,
        String  notes,
        /** K2: document currency (ISO 4217) carried from the backing PO. */
        String  currency,
        /** K2: Σ line_cost_amount over this receipt's lines — computed by the service, never re-derived downstream. */
        BigDecimal receiptTotalAmount,
        Instant createdAt,
        List<GoodsReceiptLineDto> lines
) {
    /**
     * Convenience factory when the PO uid is not immediately available.
     * Sets purchaseOrderUid to null — prefer the overload that supplies it.
     */
    public static GoodsReceiptDto from(GoodsReceipt gr, List<GoodsReceiptLineDto> lines) {
        return from(gr, null, null, null, null, lines);
    }

    public static GoodsReceiptDto from(GoodsReceipt gr, String purchaseOrderUid,
                                       List<GoodsReceiptLineDto> lines) {
        return from(gr, purchaseOrderUid, null, null, null, lines);
    }

    /**
     * Full factory (K2). {@code receiptTotalAmount} is computed by the purchases service — the
     * documents module only formats it.
     */
    public static GoodsReceiptDto from(GoodsReceipt gr, String purchaseOrderUid,
                                       String supplierName, String currency,
                                       BigDecimal receiptTotalAmount,
                                       List<GoodsReceiptLineDto> lines) {
        return new GoodsReceiptDto(
                gr.getId(), gr.getUid(),
                gr.getCompanyId(), gr.getBranchId(),
                gr.getPurchaseOrderId(),
                purchaseOrderUid,
                gr.getReceiptNumber(), gr.getStatus(),
                gr.getSupplierId(), supplierName,
                gr.getReceivedAt(), gr.getVoidedAt(), gr.getVoidReason(),
                gr.getNotes(), currency, receiptTotalAmount,
                gr.getCreatedAt(),
                lines);
    }
}
