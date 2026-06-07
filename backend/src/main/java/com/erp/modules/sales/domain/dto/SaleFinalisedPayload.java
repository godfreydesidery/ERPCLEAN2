package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Typed payload record for the {@code SALE.FINALISED} outbox event (ADR-0008 D-9, ADR-0009 D-3).
 *
 * <p>Shape is EXACTLY as specified in ADR-0008 D-9:
 * {@code { invoiceUid, companyId, branchId, finalisedAt, lines:[{ productId, productUid, unitId, qtyInBase }] }}.
 * The record is serialised to JSONB by {@link com.erp.platform.events.OutboxPublisher#publish} via Jackson.
 *
 * <p>This is a Sales-module-owned DTO; it lives in {@code sales.domain.dto} — the payload data is
 * plain scalars and uids (no cross-module entity import), consistent with the uid-in-payload
 * discipline (ADR-0009 D-3).
 */
public record SaleFinalisedPayload(
        String invoiceUid,
        Long companyId,
        Long branchId,
        Instant finalisedAt,
        List<LineItem> lines
) {

    /**
     * Per-line item — what the stock consumer needs for deduction and recipe explosion.
     *
     * @param productId   internal product id (for stock ledger FK)
     * @param productUid  stable product uid (for cross-system references)
     * @param unitId      unit of measure id the quantity is expressed in
     * @param qtyInBase   quantity in the product's base unit (pre-converted, snapshotted at sale)
     */
    public record LineItem(
            Long productId,
            String productUid,
            Long unitId,
            BigDecimal qtyInBase
    ) {}
}
