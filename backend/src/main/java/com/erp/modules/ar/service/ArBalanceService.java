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

    /**
     * Returns true when an AR open item exists for the given sales invoice uid AND its
     * outstanding_amount is strictly less than original_amount (i.e. at least one receipt or
     * credit-note allocation has been posted against it).
     *
     * <p>Used by {@code SalesInvoiceServiceImpl.voidInvoice} to block voiding a settled or
     * partially-settled FINALISED invoice (FLOW-ORDER-TO-CASH-027).
     */
    boolean hasAllocations(Long companyId, String sourceInvoiceUid);
}
