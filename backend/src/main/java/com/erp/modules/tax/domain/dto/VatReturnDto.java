package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.VatReturnStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * VAT return response DTO (ADR-0017 D-1).
 */
public record VatReturnDto(
        Long id,
        String uid,
        Long companyId,
        String returnNumber,
        short periodYear,
        short periodMonth,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        VatReturnStatus status,
        BigDecimal outputVat,
        BigDecimal inputVat,
        BigDecimal adjustmentsTotal,
        BigDecimal openingCredit,
        BigDecimal netVat,
        BigDecimal closingCredit,
        Long priorReturnId,
        String filingReference,
        LocalDate filingDate,
        String postedJournalUid,
        Instant filedAt,
        Long filedBy,
        List<VatReturnBandDto> bands
) {}
