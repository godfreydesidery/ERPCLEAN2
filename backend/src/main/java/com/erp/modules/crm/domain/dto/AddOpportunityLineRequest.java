package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record AddOpportunityLineRequest(
        @NotBlank String productUid,
        @NotBlank String unitUid,
        @NotNull @DecimalMin("0.000001") BigDecimal estimatedQty,
        /** If null, estimated price defaults to 0 (CRM does not compute money). */
        BigDecimal estimatedUnitPriceAmount,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent
) {}
