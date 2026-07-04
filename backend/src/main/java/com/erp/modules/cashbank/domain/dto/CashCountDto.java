package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashCountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response DTO for an end-of-day cash count (ADR-0050 D-7 PR-A). */
public record CashCountDto(
        Long id,
        String uid,
        Long companyId,
        String cashBankAccountUid,
        String cashBankAccountName,
        String countNumber,
        LocalDate businessDate,
        BigDecimal expectedAmount,
        BigDecimal countedAmount,
        BigDecimal varianceAmount,
        String currency,
        CashCountStatus status,
        String journalEntryRef,
        List<CashCountDenominationDto> denominations,
        Instant countedAt,
        Instant reconciledAt
) {}
