package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.DimensionType;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Response DTO for a UnitOfMeasure (brief §DTOs, ADR-0007).
 * Carries both {@code id} (JSON string via global Long-as-string config) and {@code uid}.
 */
public record UnitOfMeasureDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        DimensionType dimensionType,
        short decimalPlaces,
        boolean fractional,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static UnitOfMeasureDto from(UnitOfMeasure u) {
        return new UnitOfMeasureDto(
                u.getId(),
                u.getUid(),
                u.getCompanyId(),
                u.getCode(),
                u.getName(),
                u.getDimensionType(),
                u.getDecimalPlaces(),
                u.isFractional(),
                u.getStatus(),
                u.getVersion(),
                u.getCreatedAt() != null ? u.getCreatedAt().toString() : null,
                u.getCreatedBy(),
                u.getUpdatedAt() != null ? u.getUpdatedAt().toString() : null,
                u.getUpdatedBy()
        );
    }
}
