package com.erp.modules.fx.domain.dto;

import com.erp.modules.fx.domain.entity.CompanyCurrency;

/**
 * Response DTO for a {@code company_currency} row (ADR-0039 D-7/D-8).
 * {@code id} and {@code uid} both exposed per PROJECT-CONVENTIONS §3.
 */
public record CompanyCurrencyDto(
        Long    id,
        String  uid,
        Long    companyId,
        String  currencyCode,
        boolean isDefault,
        boolean active) {

    public static CompanyCurrencyDto from(CompanyCurrency cc) {
        return new CompanyCurrencyDto(
                cc.getId(),
                cc.getUid(),
                cc.getCompanyId(),
                cc.getCurrencyCode().value(),
                cc.isDefault(),
                cc.isActive());
    }
}
