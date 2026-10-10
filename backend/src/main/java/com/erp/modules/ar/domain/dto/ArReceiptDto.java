package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArReceiptStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for an AR receipt plus its allocation lines.
 *
 * <p>{@code customerUid}, {@code customerCode} and {@code customerName} are read-time fills (the
 * receipt stores only the numeric customer id); they are null where a caller builds the DTO
 * without the fill.
 */
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
        List<AllocationDto> allocations,
        String customerUid,
        String customerCode,
        String customerName
) {
    /** The original shape, without the customer fill. */
    public ArReceiptDto(Long id, String uid, Long companyId, Long branchId, Long customerId,
                        String receiptNumber, LocalDate receiptDate, BigDecimal amount,
                        BigDecimal unallocatedAmount, String currency, String tenderType,
                        String bankReference, String glEntryUid, ArReceiptStatus status,
                        List<AllocationDto> allocations) {
        this(id, uid, companyId, branchId, customerId, receiptNumber, receiptDate, amount,
                unallocatedAmount, currency, tenderType, bankReference, glEntryUid, status,
                allocations, null, null, null);
    }

    /** A copy carrying the customer's uid, code and display name (read-time fill). */
    public ArReceiptDto withCustomer(String custUid, String custCode, String custName) {
        return new ArReceiptDto(id, uid, companyId, branchId, customerId, receiptNumber,
                receiptDate, amount, unallocatedAmount, currency, tenderType, bankReference,
                glEntryUid, status, allocations, custUid, custCode, custName);
    }

    /** One allocation slice within the receipt. */
    public record AllocationDto(
            Long id,
            Long arInvoiceId,
            String arInvoiceUid,
            BigDecimal allocatedAmount
    ) {}
}
