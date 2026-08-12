package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One printed GRN line (Kilimanjaro K9, 2026-08-12).
 *
 * <p>Shaped for the vendor goods-received note the client actually signs: item code, description,
 * quantity, cost price, selling price, the previous cost price and the margin those two imply, then
 * the line amount.
 *
 * <p>Every figure is resolved ONCE, here in purchases, and copied by the documents module — which is
 * forbidden from deriving amounts of its own (BR-DOC-02 / BR-DOC-09).
 *
 * @param costPrice     {@code goods_receipt_lines.unit_cost_amount} — what this receipt paid
 * @param sellingPrice  the company default price list's price for the product in the receipt
 *                      currency; null when the product is unpriced (blank beats a confident 0.00)
 * @param lastCostPrice the unit cost on the most recent EARLIER RECEIVED receipt of the same
 *                      product; null on a first-ever receipt
 * @param marginPercent {@code (selling − cost) / selling × 100}; null when there is no selling price
 *                      to divide by. Negative when the shelf price is below what was just paid —
 *                      printed as-is, because that is exactly the case the buyer is checking for.
 * @param vatStatus     the product's VAT status, which drives the band table at the foot
 */
public record GoodsReceiptPrintLineDto(
        int        lineNo,
        String     productCode,
        String     productName,
        BigDecimal receivedQty,
        String     unitName,
        BigDecimal costPrice,
        BigDecimal sellingPrice,
        BigDecimal lastCostPrice,
        BigDecimal marginPercent,
        BigDecimal amount,
        String     vatStatus
) {}
