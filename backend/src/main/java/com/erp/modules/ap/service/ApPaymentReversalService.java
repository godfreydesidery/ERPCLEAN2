package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApPaymentDto;

/**
 * Reverse a posted supplier payment (AP-03): the clerk paid the wrong bill, paid twice, or the
 * money never left. Gated by AP.PAYMENT.REVERSE.
 */
public interface ApPaymentReversalService {

    /**
     * Posts the reversing journal of the payment (every leg swapped, so debits equal credits),
     * writes the opposite cash-book row on the same cash/bank account, restores the bills its
     * allocations relieved and stamps {@code reversed_at}. Refused when already reversed, when the
     * payment's own accounting period is closed, or when withholding tax was deducted from it.
     */
    ApPaymentDto reverse(String paymentUid, String reason);
}
