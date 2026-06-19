package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;

/**
 * POS quick-sale: create a DIRECT SalesInvoice tagged to the session, resolve prices,
 * compute totals, and immediately finalise it (ADR-0029 D-5, FR-SD-15).
 */
public interface PosSaleService {

    /**
     * Backward-compatible overload — no idempotency key (existing callers / tests).
     * Delegates to {@link #processSale(String, PosSaleRequest)} with a {@code null} key.
     */
    default SalesInvoiceDto processSale(PosSaleRequest request) {
        return processSale(null, request);
    }

    /**
     * Process a POS sale — creates and finalises an invoice in one transaction.
     *
     * @param idempotencyKey optional client {@code Idempotency-Key} (nullable/blank = no dedup).
     *                       A repeat with the same key, per company, returns the ORIGINAL invoice
     *                       instead of creating a second sale (ADR-0042 D-1) — safe to retry an
     *                       ambiguous POST.
     * @return the finalised invoice DTO (for receipt printing)
     */
    SalesInvoiceDto processSale(String idempotencyKey, PosSaleRequest request);

    /**
     * Reverse (void) a POS sale at the till (ADR-0042 D-2). Delegates to the existing invoice void —
     * which reverses revenue/VAT/<b>cash</b> (the cash sale's {@code SALES_REVERSAL}) and stock — and
     * enforces the POS rules: the invoice must be a POS sale and its session must still be OPEN (so
     * the drawer absorbs the refund). A settled/closed session is a back-office void, not a till
     * reversal.
     *
     * @param invoiceUid the POS sale invoice to reverse
     * @param reason     why (recorded on the void + audit)
     */
    void reverseSale(String invoiceUid, String reason);
}
