package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One day × cashier × currency line of the Payment Summary.
 *
 * <p>Every amount is what the business KEPT: tendered minus any change handed back
 * ({@code amount − change_amount}), the same netting the till's X/Z-read uses, so the cash column
 * here equals the cash the drawer should hold for those sales.
 *
 * @param date        the day the money was taken, in the company's time zone (ISO yyyy-MM-dd)
 * @param cashierUid  the user who took the payment; null if it was not recorded
 * @param payments    how many tenders make up the line (a split payment counts each tender)
 * @param total       cash + mobile money + card + cheque
 */
public record PaymentSummaryRowDto(
        String date,
        String cashierUid,
        String cashierName,
        String currency,
        BigDecimal cash,
        BigDecimal mobileMoney,
        BigDecimal card,
        BigDecimal cheque,
        BigDecimal total,
        long payments) {
}
