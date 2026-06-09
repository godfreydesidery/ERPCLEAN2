package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ArWriteOffDto(
        Long id,
        String uid,
        Long companyId,
        Long customerId,
        Long arInvoiceId,
        LocalDate writeOffDate,
        BigDecimal amount,
        String currency,
        String reason,
        String glEntryUid
) {}
