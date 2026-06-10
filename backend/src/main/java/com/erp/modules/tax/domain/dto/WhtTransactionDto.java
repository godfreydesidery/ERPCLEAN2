package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.WhtKind;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * WHT transaction (register / certificate) response DTO (ADR-0017 D-1 / D-2e).
 */
public record WhtTransactionDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String whtNumber,
        Long whtTypeId,
        WhtKind kind,
        String partyKind,
        Long partyId,
        String partyName,
        String sourceRef,
        BigDecimal taxableBase,
        BigDecimal whtAmount,
        String currency,
        LocalDate certificateDate,
        String journalEntryRef
) {}
