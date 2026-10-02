package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One line of the Goods Received Register.
 *
 * <p>A goods receipt that was later VOIDED appears twice, the way the stock movement report shows
 * it: once as a {@code RECEIPT} on the day it was received, and once as a {@code VOID} on the day
 * it was voided, with the quantity and value NEGATED. Each entry lands in the period its own date
 * falls in, so a register for a closed month never changes because a receipt in it was voided
 * later — the reversal shows up in the month it actually happened.
 *
 * @param entryType     {@code RECEIPT} or {@code VOID}
 * @param entryAt       when this entry happened (received_at, or voided_at for a VOID), ISO-8601 in
 *                      the company's UTC offset
 * @param receiptNumber GRN number
 * @param receiptUid    goods receipt uid (for a link to the receipt screen)
 * @param orderNumber   the purchase order this receipt drew down
 * @param direct        true for a receipt recorded without an LPO ("Receive Without Order") — its
 *                      order was raised automatically behind it
 * @param quantity      SIGNED received quantity, in the unit the goods were received in
 * @param unitCost      cost per received unit, as recorded on the receipt (before any landed cost)
 * @param value         SIGNED line value (quantity x unit cost), excluding VAT
 * @param currency      the receipt's currency
 */
public record GoodsReceivedRegisterRowDto(
        String     entryType,
        String     entryAt,
        String     receiptNumber,
        String     receiptUid,
        String     orderNumber,
        boolean    direct,
        String     supplierCode,
        String     supplierName,
        String     branchName,
        String     productCode,
        String     productName,
        String     unitName,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal value,
        String     currency
) {}
