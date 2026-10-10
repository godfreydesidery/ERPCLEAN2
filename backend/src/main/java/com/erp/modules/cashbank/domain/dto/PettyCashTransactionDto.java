package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO for a petty-cash fund movement (ADR-0050 D-7 PR-B). {@code journalEntryRef} is the GL
 * entry the movement posted (ARC-10); null on movements recorded before petty cash posted.
 */
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
        Instant createdAt,
        String journalEntryRef
) {
    /** The shape before ARC-10, without the journal reference. */
    public PettyCashTransactionDto(Long id, String uid, String fundUid, String txnNumber,
                                   PettyCashTxnType txnType, LocalDate txnDate, BigDecimal amount,
                                   BigDecimal balanceAfter, String glAccountUid, String reference,
                                   String description, Instant createdAt) {
        this(id, uid, fundUid, txnNumber, txnType, txnDate, amount, balanceAfter, glAccountUid,
                reference, description, createdAt, null);
    }
}
