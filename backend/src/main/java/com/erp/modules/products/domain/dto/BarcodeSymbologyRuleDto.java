package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.BarcodeSymbologyRule;
import com.erp.modules.products.domain.enums.SymbologyItemMatch;
import com.erp.modules.products.domain.enums.SymbologyValueKind;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Response DTO for a {@link BarcodeSymbologyRule} (ADR-0044 D-1a / BR-9).
 * Carries both {@code id} (serialised as JSON string by the global Jackson config) and {@code uid}.
 * No {@code version} field — the V73 table has no optimistic-lock column (admin-only, low-contention).
 */
public record BarcodeSymbologyRuleDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        String prefix,
        SymbologyValueKind valueKind,
        short itemCodeStart,
        short itemCodeLength,
        short valueStart,
        short valueLength,
        short valueDecimals,
        SymbologyItemMatch itemMatch,
        String checkDigitMode,
        int priority,
        MasterStatus status,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static BarcodeSymbologyRuleDto from(BarcodeSymbologyRule r) {
        return new BarcodeSymbologyRuleDto(
                r.getId(),
                r.getUid(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getPrefix(),
                r.getValueKind(),
                r.getItemCodeStart(),
                r.getItemCodeLength(),
                r.getValueStart(),
                r.getValueLength(),
                r.getValueDecimals(),
                r.getItemMatch(),
                r.getCheckDigitMode(),
                r.getPriority(),
                r.getStatus(),
                r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                r.getCreatedBy(),
                r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null,
                r.getUpdatedBy()
        );
    }
}
