package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.PriceList;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Response DTO for a PriceList (ADR-0007 D-7/D-12).
 * Carries both {@code id} (JSON string) and {@code uid}.
 */
public record PriceListDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static PriceListDto from(PriceList pl) {
        return new PriceListDto(
                pl.getId(),
                pl.getUid(),
                pl.getCompanyId(),
                pl.getCode(),
                pl.getName(),
                pl.getStatus(),
                pl.getVersion(),
                pl.getCreatedAt() != null ? pl.getCreatedAt().toString() : null,
                pl.getCreatedBy(),
                pl.getUpdatedAt() != null ? pl.getUpdatedAt().toString() : null,
                pl.getUpdatedBy()
        );
    }
}
