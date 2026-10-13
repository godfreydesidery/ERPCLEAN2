package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One line of the printed purchase return / debit note (Kilimanjaro, "cannot export or print
 * purchase return").
 *
 * <p>Every figure is in the LINE's own unit — the unit the goods were received in (a crate when the
 * receipt line was a crate) — exactly as the return was entered and exactly as the supplier counts
 * what comes back. The base-unit quantity the stock ledger uses is deliberately not printed: a note
 * that says "24 PCS" against a delivery the supplier invoiced as "2 CRATE" starts an argument.
 *
 * @param returnedQty  quantity returned, in {@code unitName}
 * @param unitCost     the original receipt cost per {@code unitName}
 * @param lineValue    the stored value reversed for this line (the receipt's own cost for exactly
 *                     the quantity that left stock) — copied, never recomputed as qty × cost
 */
public record PurchaseReturnPrintLineDto(
        int        lineNo,
        String     productCode,
        String     productName,
        String     unitName,
        BigDecimal returnedQty,
        BigDecimal unitCost,
        BigDecimal lineValue
) {}
