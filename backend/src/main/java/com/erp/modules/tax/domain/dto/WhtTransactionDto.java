package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.WhtKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * WHT transaction (register / certificate) response DTO (ADR-0017 D-1 / D-2e).
 * D-7: exposes tin + ratePct snapshots and remittance tracking fields.
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
        String journalEntryRef,
        // D-7: TIN + rate snapshot
        String tin,
        BigDecimal ratePct,
        // D-7: remittance tracking
        boolean remitted,
        String remittancePeriod,
        String remittanceRef,
        Instant remittedAt,
        Long remittedBy
) {}
