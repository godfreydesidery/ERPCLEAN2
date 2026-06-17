package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request to apply (or reapply) a credit note against one or more AR invoices (ADR-0040 D-6).
 * Mirrors {@link RecordReceiptRequest} allocation lines in structure.
 */
public record ApplyCreditNoteRequest(
        /** Uid of the credit note to apply. */
        String creditNoteUid,
        /** Allocation lines — each targeting one open AR invoice. */
        List<AllocationLineRequest> allocations
) {
    /** One allocation slice — targets a single open invoice. */
    public record AllocationLineRequest(
            /** Uid of the AR open item being reduced. */
            String arInvoiceUid,
            /** Amount (in CN document currency) to allocate from this CN to the invoice. */
            BigDecimal allocatedAmount
    ) {}
}
