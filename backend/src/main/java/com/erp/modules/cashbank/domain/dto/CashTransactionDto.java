package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Response DTO for a cash transaction (ADR-0016 D-2b). */
public record CashTransactionDto(
        Long id,
        String uid,
        Long companyId,
        Long cashBankAccountId,
        String txnNumber,
        LocalDate txnDate,
        CashTxnDirection direction,
        BigDecimal amount,
        String currency,
        CashTxnType txnType,
        String sourceRef,
        Long counterGlAccountId,
        String journalEntryRef,
        boolean cleared,
        Long clearedInReconciliationId,
        String memo
) {}
