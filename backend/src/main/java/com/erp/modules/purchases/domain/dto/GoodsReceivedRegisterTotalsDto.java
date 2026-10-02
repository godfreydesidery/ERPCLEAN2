package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * Footer of the Goods Received Register — computed over the WHOLE matching set, not the page.
 *
 * @param receipts            goods receipts received in the period (each counted once)
 * @param voids               goods receipts voided in the period
 * @param lines               register lines (receipt lines plus void reversal lines)
 * @param value               net value in the company's base currency (receipts less voids)
 * @param rowsInOtherCurrency lines in a foreign currency, left OUT of {@code value} rather than
 *                            added to it as if they were the same money
 */
public record GoodsReceivedRegisterTotalsDto(
        long       receipts,
        long       voids,
        long       lines,
        BigDecimal value,
        long       rowsInOtherCurrency
) {}
