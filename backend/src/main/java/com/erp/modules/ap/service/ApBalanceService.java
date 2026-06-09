package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApBalanceDto;

public interface ApBalanceService {

    /** Current AP balance (sum of outstanding) for a supplier. AP.VIEW. */
    ApBalanceDto currentBalance(Long companyId, Long supplierId);
}
