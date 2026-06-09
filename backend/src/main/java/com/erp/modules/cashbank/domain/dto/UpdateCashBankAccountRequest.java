package com.erp.modules.cashbank.domain.dto;

/** Request to update a cash/bank account (FR-CASH-02). */
public record UpdateCashBankAccountRequest(
        String name,
        String bankName,
        String bankAccountNo,
        String bankBranch,
        Boolean active
) {}
