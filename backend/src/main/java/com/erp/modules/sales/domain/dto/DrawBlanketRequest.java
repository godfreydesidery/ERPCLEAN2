package com.erp.modules.sales.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request to draw a sales order against a blanket order (ADR-0029 D-7).
 * Generates a SalesOrder with source_blanket_uid set.
 */
public record DrawBlanketRequest(
        @NotBlank String blanketUid,
        @NotNull  Long branchId,
        @NotNull  Long agentId,
        @NotEmpty @Valid List<DrawLine> lines
) {
    public record DrawLine(
            @NotBlank String blanketLineUid,
            @NotNull @DecimalMin("0.0001") BigDecimal qtyBase
    ) {}
}
