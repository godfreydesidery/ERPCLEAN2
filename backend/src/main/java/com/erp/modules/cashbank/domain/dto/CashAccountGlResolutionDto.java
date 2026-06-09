package com.erp.modules.cashbank.domain.dto;

/**
 * DTO returned by CashBankAccountResolver to AR/AP services (ADR-0016 D-8).
 * Contains the resolved cash/bank account and its linked GL account — the only data
 * AR/AP need to route the cash leg and record the settlement. Pure DTO, no entity.
 */
public record CashAccountGlResolutionDto(
        Long cashBankAccountId,
        String cashBankAccountUid,
        Long glAccountId,
        String glAccountCode
) {}
