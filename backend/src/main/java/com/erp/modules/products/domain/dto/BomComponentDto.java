package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.BomComponent;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import java.math.BigDecimal;

/**
 * BOM component line response DTO (ADR-0026 D-3, API surface).
 */
public record BomComponentDto(
        Long id,
        String uid,
        Long bomId,
        Long companyId,
        short lineNo,
        Long componentProductId,
        String componentProductCode,
        String componentProductName,
        BigDecimal qtyPer,
        ComponentSourcing sourcing,
        BigDecimal scrapPercent,
        String reference,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static BomComponentDto from(BomComponent c) {
        return new BomComponentDto(
                c.getId(),
                c.getUid(),
                c.getBomId(),
                c.getCompanyId(),
                c.getLineNo(),
                c.getComponentProductId(),
                c.getComponentProductCode(),
                c.getComponentProductName(),
                c.getQtyPer(),
                c.getSourcing(),
                c.getScrapPercent(),
                c.getReference(),
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getCreatedBy(),
                c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null,
                c.getUpdatedBy()
        );
    }
}
