package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * Totals of the Payment Summary for ONE currency. Amounts in different currencies are never added
 * together, so a report that took any foreign-currency tender carries one of these per currency.
 */
public record PaymentSummaryTotalsDto(
        String currency,
        BigDecimal cash,
        BigDecimal mobileMoney,
        BigDecimal card,
        BigDecimal cheque,
        BigDecimal total,
        long payments) {
}
