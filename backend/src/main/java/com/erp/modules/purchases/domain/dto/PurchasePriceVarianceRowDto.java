package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One goods-receipt line whose price moved away from the order price.
 *
 * <p>Two comparisons, both against the PO price:
 * <ul>
 *   <li><b>Receipt vs order</b> — the cost the receipt recorded. A placed order's lines are frozen,
 *       and a receipt inherits its cost from the order line, so this is normally zero; it is shown
 *       so that a non-zero one stands out.</li>
 *   <li><b>Bill vs order</b> — the price on the supplier bill(s) claimed against this receipt line.
 *       This is where real price variance appears. Null when nothing has been billed against the
 *       line yet, and null (with the bill columns hidden) when the caller may not see supplier
 *       bills.</li>
 * </ul>
 * Percentages are of the PO price and are null when the PO price is zero (no meaningful base).
 * A positive variance means the business paid MORE than the order price.
 *
 * @param billedQty quantity billed against this receipt line (draft bills excluded)
 * @param billPrice quantity-weighted average bill unit price over those bills
 */
public record PurchasePriceVarianceRowDto(
        String     receivedAt,
        String     receiptNumber,
        String     receiptUid,
        String     orderNumber,
        String     supplierCode,
        String     supplierName,
        String     productCode,
        String     productName,
        String     unitName,
        BigDecimal receivedQty,
        BigDecimal poPrice,
        BigDecimal receiptCost,
        BigDecimal receiptVariancePerUnit,
        BigDecimal receiptVarianceTotal,
        BigDecimal receiptVariancePct,
        BigDecimal billedQty,
        BigDecimal billPrice,
        BigDecimal billVariancePerUnit,
        BigDecimal billVarianceTotal,
        BigDecimal billVariancePct,
        String     currency
) {}
