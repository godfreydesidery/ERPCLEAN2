package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.ApPaymentKind;
import com.erp.modules.ap.domain.enums.ApPaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for an AP payment.
 * D-7: exposes whtAmount and whtTransactionUid stamped at payment time.
 * D-9: exposes unallocatedAmount, status, chequeUid for on-account tracking.
 */
public record ApPaymentDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long supplierId,
        String paymentNumber,
        ApPaymentKind kind,
        LocalDate paymentDate,
        BigDecimal amount,
        String currency,
        String tenderType,
        String bankReference,
        String glEntryUid,
        // D-7: WHT withheld on this payment
        BigDecimal whtAmount,
        String whtTransactionUid,
        // D-9: on-account tracking
        BigDecimal unallocatedAmount,
        ApPaymentStatus status,
        String chequeUid,
        // ADR-0041 D3: grouping run id (null for single-bill payments)
        Long paymentRunId,
        List<PaymentAllocationDto> allocations
) {
    public record PaymentAllocationDto(
            Long id,
            Long supplierBillId,
            String supplierBillUid,
            BigDecimal allocatedAmount
    ) {}
}
