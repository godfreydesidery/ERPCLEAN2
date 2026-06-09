package com.erp.modules.cashbank.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/** Per-account running statement with balance (FR-CASH-12). */
public record CashAccountStatementDto(
        Long cashBankAccountId,
        String cashBankAccountUid,
        String accountName,
        BigDecimal currentBalance,
        String currency,
        List<CashTransactionDto> transactions
) {}
