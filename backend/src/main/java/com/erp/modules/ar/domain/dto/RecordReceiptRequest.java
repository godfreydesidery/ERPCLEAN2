package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to record a customer receipt (FR-AR-06).
 * allocations is optional — if empty the receipt is fully on-account (BR-AR-05).
 */
public record RecordReceiptRequest(
        String companyUid,
        String customerUid,
        BigDecimal amount,
        String currency,
        LocalDate receiptDate,
        String tenderType,
        String bankReference,
        /** Manual override allocations (oldest-first used when this is empty). */
        List<AllocationLineRequest> allocations
) {
    /** One allocation line in the create request. */
    public record AllocationLineRequest(
            String arInvoiceUid,
            BigDecimal allocatedAmount
    ) {}
}
