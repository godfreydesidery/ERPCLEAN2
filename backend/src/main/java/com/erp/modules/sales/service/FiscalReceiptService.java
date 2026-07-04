package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.FiscalReceiptDto;

public interface FiscalReceiptService {

    /**
     * Issue-or-retry the fiscal receipt for a FINALISED invoice (ADR-0049 §4). A single idempotent
     * operation: no row → create + attempt; row in {@code FAILED}/{@code NOT_CONFIGURED}/
     * {@code PENDING} → retry in place; row {@code ISSUED} → no-op (returns the existing receipt);
     * row {@code VOID} → 409.
     */
    FiscalReceiptDto issue(String invoiceUid);

    /** Scoped read of the invoice's fiscal receipt. Throws {@code NotFoundException} if none exists. */
    FiscalReceiptDto getForInvoice(String invoiceUid);
}
