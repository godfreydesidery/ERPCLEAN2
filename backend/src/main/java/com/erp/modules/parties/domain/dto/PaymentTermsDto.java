package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.domain.enums.PaymentTermsBasis;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

/** Response DTO for a PaymentTerms master (D-2, ADR-0040). Carries both id and uid. */
public record PaymentTermsDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        PaymentTermsBasis basis,
        int netDays,
        Integer discountDays,
        BigDecimal discountPercent,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {
    public static PaymentTermsDto from(PaymentTerms pt) {
        return new PaymentTermsDto(
                pt.getId(),
                pt.getUid(),
                pt.getCompanyId(),
                pt.getCode(),
                pt.getName(),
                pt.getBasis(),
                pt.getNetDays(),
                pt.getDiscountDays(),
                pt.getDiscountPercent(),
                pt.getStatus(),
                pt.getVersion(),
                pt.getCreatedAt() != null ? pt.getCreatedAt().toString() : null,
                pt.getCreatedBy(),
                pt.getUpdatedAt() != null ? pt.getUpdatedAt().toString() : null,
                pt.getUpdatedBy()
        );
    }
}
