package com.erp.modules.fx.domain.dto;

import com.erp.modules.fx.domain.entity.BranchCurrency;

/**
 * Response DTO for a {@code branch_currency} row (ADR-0039 D-7/D-8).
 */
public record BranchCurrencyDto(
        Long    id,
        String  uid,
        Long    companyId,
        Long    branchId,
        String  currencyCode,
        boolean isDefault,
        boolean active) {

    public static BranchCurrencyDto from(BranchCurrency bc) {
        return new BranchCurrencyDto(
                bc.getId(),
                bc.getUid(),
                bc.getCompanyId(),
                bc.getBranchId(),
                bc.getCurrencyCode().value(),
                bc.isDefault(),
                bc.isActive());
    }
}
