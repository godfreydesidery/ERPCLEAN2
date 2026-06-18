package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.domain.enums.ArCreditNoteStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ArCreditNoteDto(
        Long id,
        String uid,
        Long companyId,
        Long customerId,
        String creditNoteNumber,
        Long arInvoiceId,
        LocalDate noteDate,
        BigDecimal amount,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        String currency,
        String reason,
        ArCreditNoteOrigin origin,
        String glEntryUid,
        // D-6: unapplied tracking (ADR-0040 D-6)
        BigDecimal unappliedAmount,
        BigDecimal baseAmount,
        BigDecimal baseUnappliedAmount,
        BigDecimal fxRate,
        ArCreditNoteStatus status,
        List<AllocationDto> allocations
) {
    /** One allocation slice within the credit note. */
    public record AllocationDto(
            Long id,
            Long arInvoiceId,
            String arInvoiceUid,
            BigDecimal allocatedAmount
    ) {}
}
