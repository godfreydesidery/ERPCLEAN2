package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import java.time.Instant;
import java.util.List;

/**
 * Read-only response DTO for a Goods Receipt (ADR-0011 D-12).
 */
public record GoodsReceiptDto(
        Long   id,
        String uid,
        Long   companyId,
        Long   branchId,
        Long   purchaseOrderId,
        String receiptNumber,
        GoodsReceiptStatus status,
        Long   supplierId,
        Instant receivedAt,
        Instant voidedAt,
        String  voidReason,
        String  notes,
        Instant createdAt,
        List<GoodsReceiptLineDto> lines
) {
    public static GoodsReceiptDto from(GoodsReceipt gr, List<GoodsReceiptLineDto> lines) {
        return new GoodsReceiptDto(
                gr.getId(), gr.getUid(),
                gr.getCompanyId(), gr.getBranchId(),
                gr.getPurchaseOrderId(),
                gr.getReceiptNumber(), gr.getStatus(),
                gr.getSupplierId(),
                gr.getReceivedAt(), gr.getVoidedAt(), gr.getVoidReason(),
                gr.getNotes(), gr.getCreatedAt(),
                lines);
    }
}
