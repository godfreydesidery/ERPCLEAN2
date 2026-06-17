package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.ApPaymentKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for an AP payment.
 * D-7: exposes whtAmount and whtTransactionUid stamped at payment time.
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
        List<PaymentAllocationDto> allocations
) {
    public record PaymentAllocationDto(
            Long id,
            Long supplierBillId,
            String supplierBillUid,
            BigDecimal allocatedAmount
    ) {}
}
