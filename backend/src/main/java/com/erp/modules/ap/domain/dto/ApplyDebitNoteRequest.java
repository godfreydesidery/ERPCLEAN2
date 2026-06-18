package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request to apply (or reapply) a debit note against one or more supplier bills (ADR-0041 D3).
 * Mirrors {@link com.erp.modules.ar.domain.dto.ApplyCreditNoteRequest} in structure.
 */
public record ApplyDebitNoteRequest(
        /** Uid of the debit note to apply. */
        String debitNoteUid,
        /** Allocation lines — each targeting one open supplier bill. */
        List<AllocationLineRequest> allocations
) {
    /** One allocation slice — targets a single open bill. */
    public record AllocationLineRequest(
            /** Uid of the supplier bill (open item) being reduced. */
            String supplierBillUid,
            /** Amount (in DN document currency) to allocate from this DN to the bill. */
            BigDecimal allocatedAmount
    ) {}
}
