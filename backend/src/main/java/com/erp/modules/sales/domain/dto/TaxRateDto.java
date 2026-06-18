package com.erp.modules.sales.domain.dto;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.TaxRate;
import com.erp.modules.sales.domain.enums.TaxType;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

/**
 * Response DTO for a TaxRate (ADR-0008 D-5b).
 */
public record TaxRateDto(
        Long id,
        String uid,
        Long companyId,
        String name,
        TaxType taxType,
        VatStatus vatStatus,
        BigDecimal rate,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static TaxRateDto from(TaxRate t) {
        return new TaxRateDto(
                t.getId(),
                t.getUid(),
                t.getCompanyId(),
                t.getName(),
                t.getTaxType(),
                t.getVatStatus(),
                t.getRate(),
                t.getStatus(),
                t.getVersion(),
                t.getCreatedAt() != null ? t.getCreatedAt().toString() : null,
                t.getCreatedBy(),
                t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null,
                t.getUpdatedBy()
        );
    }
}
