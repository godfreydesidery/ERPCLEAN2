package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One purchase-order line still waiting for goods, as at the report date.
 *
 * <p>Quantities are in the unit the line was ORDERED in (cartons stay cartons), so they read the
 * same as the order itself. Over-receipt within tolerance never shows a negative outstanding — a
 * line that is fully (or over-) received is simply not open.
 *
 * @param orderDate        the day the order was placed, in the company's time zone
 * @param expectedDate     the line's required-by date, falling back to the order's expected date
 * @param receivedQty      received up to the report date, net of receipts voided by then
 * @param outstandingValue outstandingQty x unitCost, excluding VAT
 * @param ageDays          days from the order date to the report date
 * @param overdue          true when an expected date exists and has passed by the report date
 */
public record OpenPurchaseOrderRowDto(
        String     orderNumber,
        String     orderUid,
        String     orderDate,
        String     expectedDate,
        String     supplierCode,
        String     supplierName,
        String     branchName,
        String     productCode,
        String     productName,
        String     unitName,
        BigDecimal orderedQty,
        BigDecimal receivedQty,
        BigDecimal outstandingQty,
        BigDecimal unitCost,
        BigDecimal outstandingValue,
        String     currency,
        long       ageDays,
        boolean    overdue
) {}
