package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;

public interface ApOpeningBalanceService {

    /**
     * Enter an AP opening balance for a supplier. Creates a bill (source=OPENING_BALANCE),
     * posts DR OPENING_BALANCE_EQUITY / CR AP-control synchronously. AP.OPENING.SET.
     */
    SupplierBillDto setOpeningBalance(SetApOpeningBalanceRequest req);
}
