package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.ChequeDirection;
import com.erp.modules.cashbank.domain.enums.ChequeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO for a cheque register entry (ADR-0016 D-2d).
 * D-9: exposes direction, arReceiptUid, depositedAt, bouncedAt, bounceReason, representCount.
 */
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
        Instant cancelledAt,
        // D-9: bidirectional cheque fields
        ChequeDirection direction,
        String arReceiptUid,
        Instant depositedAt,
        Instant bouncedAt,
        String bounceReason,
        short representCount
) {}
