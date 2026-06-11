package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ComponentSourcing;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * Request body for PUT /api/v1/boms/uid/{uid}/components/{componentUid} (ADR-0026 D-3).
 * All fields are optional; only supplied fields are updated (DRAFT only — BR-BOM-03).
 */
public record UpdateBomComponentRequest(
        @DecimalMin("0.000001") BigDecimal qtyPer,
        ComponentSourcing sourcing,
        @DecimalMin("0") @DecimalMax("99.9999") BigDecimal scrapPercent,
        String reference
) {
}
