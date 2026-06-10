package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import java.time.LocalDate;

/**
 * WHT period register query (ADR-0017 D-9, FR-WHT-04).
 */
public interface WhtRegisterService {

    /**
     * Read wht_transactions for a company in [periodStart, periodEnd] by certificate_date,
     * grouped by kind (WHT_ON_PAYMENT / WHT_ON_RECEIPT) with totals.
     */
    WhtRegisterDto getRegister(Long companyId, LocalDate periodStart, LocalDate periodEnd);
}
