package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.ApPaymentKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        List<PaymentAllocationDto> allocations
) {
    public record PaymentAllocationDto(
            Long id,
            Long supplierBillId,
            String supplierBillUid,
            BigDecimal allocatedAmount
    ) {}
}
