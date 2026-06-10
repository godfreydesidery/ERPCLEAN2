package com.erp.modules.reporting.domain.dto;

/**
 * One detail line in a statement section — one GL account (ADR-0018 D-1).
 * Synthetic lines (e.g. the equity-fold lines) carry null accountId/accountUid/accountCode.
 */
public record StatementLineDto(
        Long          accountId,
        String        accountUid,
        String        accountCode,
        String        accountName,
        AmountPairDto amounts
) {}
