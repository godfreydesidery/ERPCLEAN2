package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.ApDebitNoteStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ApDebitNoteDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long supplierId,
        String debitNoteNumber,
        Long supplierBillId,
        LocalDate noteDate,
        BigDecimal amount,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        String currency,
        String reason,
        String glEntryUid,
        String origin,
        // P2: source-document uid suffix (isolated from the combined origin tag)
        String originRef,
        // ADR-0041 D3: unapplied tracking (credit-note parity)
        BigDecimal unappliedAmount,
        BigDecimal baseAmount,
        BigDecimal baseUnappliedAmount,
        BigDecimal fxRate,
        ApDebitNoteStatus status,
        List<AllocationDto> allocations
) {
    /** One allocation slice within the debit note. */
    public record AllocationDto(
            Long id,
            Long supplierBillId,
            String supplierBillUid,
            BigDecimal allocatedAmount
    ) {}
}
