package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

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
        String originRef
) {}
