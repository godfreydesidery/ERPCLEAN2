package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;

public interface ArOpeningBalanceService {

    /**
     * Enter an AR opening balance for a customer at go-live (FR-AR-15).
     * Creates an ar_invoices row (source=OPENING_BALANCE) and posts
     * DR AR control / CR Opening Balance Equity synchronously (ADR-0014 D-4/D-6).
     */
    ArInvoiceDto setOpeningBalance(SetOpeningBalanceRequest req);
}
