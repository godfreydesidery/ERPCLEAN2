package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Response DTO for a petty-cash fund movement (ADR-0050 D-7 PR-B). RECORD-ONLY: no GL posting. */
public record PettyCashTransactionDto(
        Long id,
        String uid,
        String fundUid,
        String txnNumber,
        PettyCashTxnType txnType,
        LocalDate txnDate,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String glAccountUid,
        String reference,
        String description,
        Instant createdAt
) {}
