package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import java.math.BigDecimal;
import java.time.LocalDate;

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
        // P2: bank-identifier detail
        String iban,
        String swift,
        String bic,
        String sortCode,
        // P2: treasury limits + cached reconciliation figures
        BigDecimal overdraftLimit,
        BigDecimal minimumBalance,
        LocalDate lastReconciledDate,
        BigDecimal lastReconciledBalance,
        String currency,
        Long glAccountId,
        boolean isDefault,
        boolean active
) {}
