package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.entity.OpportunityLine;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;

public record OpportunityLineDto(
        Long id,
        String uid,
        Long opportunityId,
        Long companyId,
        Long branchId,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal estimatedQty,
        BigDecimal estimatedUnitPriceAmount,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent,
        String currency,
        Long version,
        Instant createdAt,
        Long createdBy
) {
    public static OpportunityLineDto from(OpportunityLine l) {
        return new OpportunityLineDto(
                l.getId(), l.getUid(), l.getOpportunityId(), l.getCompanyId(), l.getBranchId(),
                l.getLineNo(), l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(), l.getEstimatedQty(), l.getEstimatedUnitPriceAmount(),
                l.getLineDiscountAmount(), l.getLineDiscountPercent(), CurrencyCode.value(l.getCurrency()),
                l.getVersion(), l.getCreatedAt(), l.getCreatedBy());
    }
}
