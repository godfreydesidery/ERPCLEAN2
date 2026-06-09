package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArBalanceDto;

/**
 * Current AR balance per customer — the seam Sales reads at finalise for the credit-limit check
 * (ADR-0014 D-9/D-10, FR-AR-19). AR-owned; Sales reads via this interface + ArBalanceDto only,
 * never an AR entity or repository (D-11, NFR-AR-06).
 */
public interface ArBalanceService {

    /**
     * Returns the current AR balance for a customer.
     * balance = Σ outstanding_amount (OPEN/PARTIAL) − Σ unallocated_amount.
     * Company-scoped; never crosses tenant boundary.
     */
    ArBalanceDto currentBalance(Long companyId, Long customerId);
}
