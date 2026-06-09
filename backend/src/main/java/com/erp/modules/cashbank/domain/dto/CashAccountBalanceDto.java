package com.erp.modules.cashbank.domain.dto;

import java.math.BigDecimal;

/** Current book balance of a cash/bank account (FR-CASH-12). */
public record CashAccountBalanceDto(
        Long cashBankAccountId,
        String cashBankAccountUid,
        String accountCode,
        String accountName,
        BigDecimal bookBalance,
        String currency
) {}
