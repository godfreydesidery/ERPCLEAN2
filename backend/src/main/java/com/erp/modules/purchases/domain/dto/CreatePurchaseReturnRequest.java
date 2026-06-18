package com.erp.modules.purchases.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public record CreatePurchaseReturnRequest(
        @NotBlank String companyUid,
        @NotBlank String goodsReceiptUid,
        @NotBlank String reason,
        @NotEmpty @Valid List<ReturnLineRequest> lines
) {
    /** Per-line entry for a purchase return. Distinct name avoids OpenAPI schema collision
     *  with RFQ and requisition line records (issue #11 / theme #11). */
    public record ReturnLineRequest(
            /** The GR line being returned. */
            @NotBlank String goodsReceiptLineUid,
            @NotNull @Positive BigDecimal returnedQty
    ) {}
}
