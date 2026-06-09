package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArReceiptStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Response DTO for an AR receipt plus its allocation lines. */
public record ArReceiptDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long customerId,
        String receiptNumber,
        LocalDate receiptDate,
        BigDecimal amount,
        BigDecimal unallocatedAmount,
        String currency,
        String tenderType,
        String bankReference,
        String glEntryUid,
        ArReceiptStatus status,
        List<AllocationDto> allocations
) {
    /** One allocation slice within the receipt. */
    public record AllocationDto(
            Long id,
            Long arInvoiceId,
            String arInvoiceUid,
            BigDecimal allocatedAmount
    ) {}
}
