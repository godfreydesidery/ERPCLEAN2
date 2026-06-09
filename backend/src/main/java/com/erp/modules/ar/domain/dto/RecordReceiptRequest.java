package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to record a customer receipt (FR-AR-06).
 * allocations is optional — if empty the receipt is fully on-account (BR-AR-05).
 * cashBankAccountUid is optional — null resolves to the company default cash/bank account
 * (ADR-0016 D-10).
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
        List<AllocationLineRequest> allocations,
        /** Optional: uid of the cash/bank account to post to; null = company default (ADR-0016 D-10). */
        String cashBankAccountUid
) {
    /** Back-compat overload: omit cashBankAccountUid → null = company default cash/bank account. */
    public RecordReceiptRequest(String companyUid, String customerUid, BigDecimal amount,
                                String currency, LocalDate receiptDate, String tenderType,
                                String bankReference, List<AllocationLineRequest> allocations) {
        this(companyUid, customerUid, amount, currency, receiptDate, tenderType, bankReference,
                allocations, null);
    }

    /** One allocation line in the create request. */
    public record AllocationLineRequest(
            String arInvoiceUid,
            BigDecimal allocatedAmount
    ) {}
}
