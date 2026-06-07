package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to override the unit price on a draft line (FR-SALES-08, BR-SALES-09).
 * Requires SALES.INVOICE.OVERRIDE permission. The override is always recorded and audited.
 */
public record OverrideLinePriceRequest(
        @NotNull BigDecimal unitPriceAmount
) {
}
