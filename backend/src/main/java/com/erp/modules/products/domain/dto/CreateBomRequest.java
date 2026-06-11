package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for POST /api/v1/boms — create a DRAFT BOM header (ADR-0026 API surface).
 *
 * @param parentProductUid  uid of the product this BOM produces (required)
 * @param outputQty         how many parent base-units one execution produces; default 1
 * @param yieldPercent      header yield %; default 100 (no loss)
 * @param notes             optional engineering notes
 * @param cloneFromBomUid   if supplied, clone components from this BOM version (OQ-BOM-06)
 */
public record CreateBomRequest(
        @NotBlank String parentProductUid,
        @NotNull @DecimalMin("0.000001") BigDecimal outputQty,
        @DecimalMin("0.0001") @DecimalMax("100") BigDecimal yieldPercent,
        String notes,
        String cloneFromBomUid
) {
}
