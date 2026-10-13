package com.erp.modules.cashbank.domain.dto;

import com.erp.modules.cashbank.domain.enums.CashBankAccountType;

/**
 * Narrow picker row for "where did the money go / come from": an ACTIVE cash, bank or mobile-money
 * account of the company (ARC-05).
 *
 * <p>Like {@link CashTillOptionDto} it deliberately carries no balances, bank account numbers or GL
 * link, so the Record Receipt screen can offer the account picker to a cashier who holds
 * {@code AR.RECEIPT.RECORD} without granting {@code CASH.VIEW}. Mobile-money wallets are BANK-type
 * accounts (there is no separate type); the name tells them apart.
 */
public record CashAccountOptionDto(
        Long id,
        String uid,
        String code,
        String name,
        CashBankAccountType accountType,
        Long branchId,
        String currency,
        boolean isDefault,
        /** True when the account belongs to the branch the caller is working in right now. */
        boolean inCurrentBranch
) {}
