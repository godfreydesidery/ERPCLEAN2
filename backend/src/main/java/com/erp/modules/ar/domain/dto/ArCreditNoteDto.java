package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import java.math.BigDecimal;
import java.time.LocalDate;

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
        String glEntryUid
) {}
