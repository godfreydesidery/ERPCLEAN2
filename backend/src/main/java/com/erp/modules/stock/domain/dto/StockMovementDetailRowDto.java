package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * One movement row of the DETAIL Stock Movement report (K9).
 *
 * <p>{@code runningBalance} is the product's on-hand AFTER this movement — the period opening
 * balance plus the cumulative signed quantity of every movement up to and including this one
 * (ordered by {@code occurred_at}, then id). It is computed over the whole filtered set, so it stays
 * correct across page boundaries.
 *
 * @param movementUid        the append-only ledger row's uid
 * @param occurredAt         business time of the movement, ISO-8601 with the COMPANY's UTC offset
 *                           (the same zone the period window is computed in)
 * @param productCode        product code
 * @param productName        product description
 * @param unitLabel          base-unit symbol (falls back to the unit code)
 * @param movementType       the {@link com.erp.modules.stock.domain.enums.MovementType} name
 * @param direction          {@code IN} for a positive quantity, {@code OUT} for a negative one
 * @param quantity           SIGNED quantity in base units (+ into stock, − out of stock)
 * @param runningBalance     on-hand after this movement
 * @param reason             adjustment reason code, falling back to the free-text note
 * @param sourceDocumentType originating aggregate kind (SALES_INVOICE / GOODS_RECEIPT / …); null for manual
 * @param sourceDocumentUid  originating aggregate uid; null for manual movements
 */
public record StockMovementDetailRowDto(
        String     movementUid,
        String     occurredAt,
        String     productCode,
        String     productName,
        String     unitLabel,
        String     movementType,
        String     direction,
        BigDecimal quantity,
        BigDecimal runningBalance,
        String     reason,
        String     sourceDocumentType,
        String     sourceDocumentUid) {
}
