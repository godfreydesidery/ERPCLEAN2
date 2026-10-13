package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArRefundDto;
import com.erp.modules.ar.domain.dto.RefundCustomerRequest;

/**
 * Customer refunds (ARC-11): pay back money held on account on a receipt, or an unused credit note,
 * in cash or by bank. Gated AR.REFUND.
 */
public interface ArRefundService {

    /**
     * Posts DR AR control / CR the cash-bank account's GL, writes the OUT cash-book row and reduces
     * the source document's unused credit, in one transaction. Refused when the amount exceeds the
     * unused credit, the document is reversed, foreign-currency, or a voided sale's credit note.
     */
    ArRefundDto refund(RefundCustomerRequest request);
}
