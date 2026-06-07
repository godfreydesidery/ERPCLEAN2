package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductComponent;
import java.math.BigDecimal;

/** Response DTO for a single-level BOM component entry (FR-PROD-14). */
public record ProductComponentDto(
        Long id,
        Long composedProductId,
        Long componentProductId,
        String componentProductUid,
        String componentProductCode,
        String componentProductName,
        BigDecimal quantity
) {

    public static ProductComponentDto from(ProductComponent pc) {
        return new ProductComponentDto(
                pc.getId(),
                pc.getComposedProduct().getId(),
                pc.getComponentProduct().getId(),
                pc.getComponentProduct().getUid(),
                pc.getComponentProduct().getCode(),
                pc.getComponentProduct().getName(),
                pc.getQuantity()
        );
    }
}
