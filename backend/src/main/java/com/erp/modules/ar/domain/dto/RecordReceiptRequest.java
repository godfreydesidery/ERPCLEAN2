package com.erp.modules.ar.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request to record a customer receipt (FR-AR-06).
 * allocations is optional — if empty the receipt is fully on-account (BR-AR-05).
 * cashBankAccountUid is optional — null resolves to the company default cash/bank account
 * (ADR-0016 D-10).
 * whtTypeUid + whtAmount are optional WHT fields (ADR-0017 D-9): when present the WHT leg is
 * captured and the cash DR is reduced by whtAmount.
 */
public record RecordReceiptRequest(
        String companyUid,
        String customerUid,
        /** Receipt amount must be strictly positive (issue #16). */
        @Positive BigDecimal amount,
        String currency,
        LocalDate receiptDate,
        String tenderType,
        String bankReference,
        /** Manual override allocations (oldest-first used when this is empty). */
        @Valid List<AllocationLineRequest> allocations,
        /** Optional: uid of the cash/bank account to post to; null = company default (ADR-0016 D-10). */
        String cashBankAccountUid,
        /** Optional: uid of the WhtType to use for WHT_ON_RECEIPT capture (ADR-0017 D-9). */
        String whtTypeUid,
        /** Optional: WHT amount withheld by the customer (ADR-0017 D-9). */
        BigDecimal whtAmount,
        /**
         * Optional: uid of the INBOUND cheque funding this receipt (ADR-0041 D3). When set, the
         * receipt records cheque_uid so a later cheque bounce can locate + reverse this receipt.
         */
        String chequeUid
) {
    /** Back-compat overload: omit cashBankAccountUid → null; no WHT; no cheque. */
    public RecordReceiptRequest(String companyUid, String customerUid, BigDecimal amount,
                                String currency, LocalDate receiptDate, String tenderType,
                                String bankReference, List<AllocationLineRequest> allocations) {
        this(companyUid, customerUid, amount, currency, receiptDate, tenderType, bankReference,
                allocations, null, null, null, null);
    }

    /** Back-compat overload: include cashBankAccountUid but no WHT; no cheque. */
    public RecordReceiptRequest(String companyUid, String customerUid, BigDecimal amount,
                                String currency, LocalDate receiptDate, String tenderType,
                                String bankReference, List<AllocationLineRequest> allocations,
                                String cashBankAccountUid) {
        this(companyUid, customerUid, amount, currency, receiptDate, tenderType, bankReference,
                allocations, cashBankAccountUid, null, null, null);
    }

    /** Back-compat overload: cashBankAccountUid + WHT, but no cheque (ADR-0017 D-9 call shape). */
    public RecordReceiptRequest(String companyUid, String customerUid, BigDecimal amount,
                                String currency, LocalDate receiptDate, String tenderType,
                                String bankReference, List<AllocationLineRequest> allocations,
                                String cashBankAccountUid, String whtTypeUid, BigDecimal whtAmount) {
        this(companyUid, customerUid, amount, currency, receiptDate, tenderType, bankReference,
                allocations, cashBankAccountUid, whtTypeUid, whtAmount, null);
    }

    /** One allocation line in the create request. */
    public record AllocationLineRequest(
            String arInvoiceUid,
            /** Each allocation slice must be strictly positive (issue #16). */
            @Positive BigDecimal allocatedAmount,
            /** Optional settlement discount taken on this allocation (ADR-0041 D1, data-only). */
            BigDecimal discountAmount,
            /** Optional residual write-off recorded on this allocation (ADR-0041 D1, data-only). */
            BigDecimal writeOffAmount
    ) {
        /** Back-compat overload: omit discount / write-off → null. */
        public AllocationLineRequest(String arInvoiceUid, BigDecimal allocatedAmount) {
            this(arInvoiceUid, allocatedAmount, null, null);
        }
    }
}
