package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import java.math.BigDecimal;

/** One row in the trial balance: account + total debit/credit + net (ADR-0013 D-8). */
public record TrialBalanceRowDto(
        Long accountId,
        String accountUid,
        String accountCode,
        String accountName,
        AccountType accountType,
        NormalBalance normalBalance,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal net
) {}
