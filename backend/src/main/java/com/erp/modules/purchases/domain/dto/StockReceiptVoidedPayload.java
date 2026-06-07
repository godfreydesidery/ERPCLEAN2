package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
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
 */
public record StockReceiptVoidedPayload(
        String receiptUid,
        Long   companyId,
        Long   branchId,
        List<LineItem> lines
) {
    public record LineItem(
            Long       productId,
            String     productUid,
            Long       unitId,
            BigDecimal qtyInBase
    ) {}
}
