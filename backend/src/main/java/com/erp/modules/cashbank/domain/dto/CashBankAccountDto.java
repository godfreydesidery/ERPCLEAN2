package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashBankAccountType;

/** Response DTO for a cash/bank account (ADR-0016 D-1). */
public record CashBankAccountDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String code,
        String name,
        CashBankAccountType accountType,
        String bankName,
        String bankAccountNo,
        String bankBranch,
        String currency,
        Long glAccountId,
        boolean isDefault,
        boolean active
) {}
