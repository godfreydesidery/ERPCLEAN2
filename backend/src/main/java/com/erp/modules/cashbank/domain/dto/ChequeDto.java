package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.ChequeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Response DTO for a cheque register entry (ADR-0016 D-2d). */
public record ChequeDto(
        Long id,
        String uid,
        Long companyId,
        Long cashBankAccountId,
        String chequeNumber,
        String payee,
        BigDecimal amount,
        String currency,
        LocalDate issueDate,
        LocalDate valueDate,
        ChequeStatus status,
        String apPaymentUid,
        String cashTransactionUid,
        Instant clearedAt,
        Instant cancelledAt
) {}
