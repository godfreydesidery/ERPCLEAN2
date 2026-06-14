package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Typed payload record for the {@code SALE.FINALISED} outbox event (ADR-0008 D-9, ADR-0009 D-3).
 *
 * <p>Shape: {@code { invoiceUid, companyId, branchId, finalisedAt, lines:[...], issuesStock }}.
 * The record is serialised to JSONB by {@link com.erp.platform.events.OutboxPublisher#publish} via Jackson.
 *
 * <p>{@code issuesStock} (ADR-0021 D-6): {@code true} for DIRECT walk-in invoices (the existing
 * behaviour — stock is issued on finalise). {@code false} for SO-sourced invoices (delivery already
 * issued stock; the invoice posts revenue only). Default {@code true} for backward-safety so
 * any pre-V18 serialised events continue to issue stock as expected.
 */
public record SaleFinalisedPayload(
        String invoiceUid,
        Long companyId,
        Long branchId,
        Instant finalisedAt,
        List<LineItem> lines,
        boolean issuesStock,
        /** Human-readable invoice number (e.g. INV-0001) — used in GL journal memos (FOLLOW-001). */
        String invoiceNumber
) {
    /**
     * Backward-compatible factory for code that does not supply {@code issuesStock}.
     * Preserves the pre-V18 contract: issuesStock defaults to {@code true}.
     */
    public SaleFinalisedPayload(String invoiceUid, Long companyId, Long branchId,
                                Instant finalisedAt, List<LineItem> lines) {
        this(invoiceUid, companyId, branchId, finalisedAt, lines, true, null);
    }

    /**
     * Backward-compatible factory for code that supplies issuesStock but not invoiceNumber.
     */
    public SaleFinalisedPayload(String invoiceUid, Long companyId, Long branchId,
                                Instant finalisedAt, List<LineItem> lines, boolean issuesStock) {
        this(invoiceUid, companyId, branchId, finalisedAt, lines, issuesStock, null);
    }

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
