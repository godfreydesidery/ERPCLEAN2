package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request to create a cash/bank account (FR-CASH-01). */
public record CreateCashBankAccountRequest(
        @NotBlank String companyUid,
        String branchUid,
        @NotBlank String name,
        @NotNull CashBankAccountType accountType,
        /** Required when accountType == BANK. */
        String bankName,
        String bankAccountNo,
        String bankBranch,
        /** uid of the GL 1xxx asset account to link (mandatory). */
        @NotBlank String glAccountUid,
        boolean setAsDefault
) {}
