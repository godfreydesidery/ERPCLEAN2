package com.erp.modules.cashbank.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Response DTO for a cash transfer (ADR-0016 D-2c). */
public record CashTransferDto(
        Long id,
        String uid,
        Long companyId,
        String transferNumber,
        Long sourceAccountId,
        String sourceAccountUid,
        Long destinationAccountId,
        String destinationAccountUid,
        LocalDate transferDate,
        BigDecimal amount,
        String currency,
        String reference,
        Long outTxnId,
        Long inTxnId,
        String journalEntryRef
) {}
