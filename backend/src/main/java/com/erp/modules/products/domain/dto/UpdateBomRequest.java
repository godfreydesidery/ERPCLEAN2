package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * Request body for PUT /api/v1/boms/uid/{uid} (ADR-0026 API surface).
 * On a DRAFT header: outputQty + yieldPercent + notes are editable.
 * On an ACTIVE header: only notes is editable (BR-BOM-03 — component set frozen).
 */
public record UpdateBomRequest(
        @DecimalMin("0.000001") BigDecimal outputQty,
        @DecimalMin("0.0001") @DecimalMax("100") BigDecimal yieldPercent,
        String notes
) {
}
