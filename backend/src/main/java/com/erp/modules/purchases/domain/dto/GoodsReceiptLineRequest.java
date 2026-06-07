package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One line within a CreateGoodsReceiptRequest: specifies which PO line to draw down
 * and how much to receive (ADR-0011 D-12).
 *
 * <p>purchaseOrderLineUid is resolved by the service (company-scoped, F16 child-by-parent).
 * receivedQty is in the same unit as the PO line's unit_id.
 * Over-receipt (receivedQty > outstanding) is rejected by the service (BR-PURCH-10, ADR-0011 D-3).
 */
public record GoodsReceiptLineRequest(
        String     purchaseOrderLineUid,
        BigDecimal receivedQty
) {}
