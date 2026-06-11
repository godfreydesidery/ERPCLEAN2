package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ComponentSourcing;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request body for POST /api/v1/boms/uid/{uid}/components (ADR-0026 D-3, API surface).
 *
 * @param componentProductUid uid of the child product to add (required)
 * @param qtyPer              child base-units per one output_qty execution (must be &gt; 0)
 * @param sourcing            MAKE or BUY; if null, defaulted from child's BOM state (BR-BOM-07)
 * @param scrapPercent        line scrap %; 0 = no inflation (default)
 * @param reference           reference designator / note (optional)
 */
public record AddBomComponentRequest(
        @NotBlank String componentProductUid,
        @NotNull @DecimalMin("0.000001") BigDecimal qtyPer,
        ComponentSourcing sourcing,
        @DecimalMin("0") @DecimalMax("99.9999") BigDecimal scrapPercent,
        String reference
) {
}
