package com.erp.modules.purchases.domain.dto;

import java.util.List;

/**
 * Request DTO to create a draft Goods Receipt against a PO (ADR-0011 D-12).
 *
 * <p>purchaseOrderUid identifies the PO; company/branch/supplier are inherited from it.
 * lines: which PO lines to draw down and how much to receive.
 */
public record CreateGoodsReceiptRequest(
        String purchaseOrderUid,
        String notes,
        List<GoodsReceiptLineRequest> lines
) {}
