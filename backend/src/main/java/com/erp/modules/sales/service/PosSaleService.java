package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;

/**
 * POS quick-sale: create a DIRECT SalesInvoice tagged to the session, resolve prices,
 * compute totals, and immediately finalise it (ADR-0029 D-5, FR-SD-15).
 */
public interface PosSaleService {

    /**
     * Process a POS sale — creates and finalises an invoice in one transaction.
     *
     * @return the finalised invoice DTO (for receipt printing)
     */
    SalesInvoiceDto processSale(PosSaleRequest request);
}
