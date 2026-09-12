package com.erp.modules.purchases.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record UpdatePurchaseSettingsRequest(
        @NotBlank String companyUid,
        boolean poApprovalEnabled,
        @PositiveOrZero BigDecimal poApprovalThresholdAmount,
        String currency,
        // P2 D7 — procurement policy defaults (all optional; null = leave unchanged)
        Long       defaultPaymentTermsId,
        Long       defaultLocationId,
        @PositiveOrZero BigDecimal matchTolerancePct,
        @PositiveOrZero BigDecimal matchToleranceAbs,
        Boolean    autoCloseEnabled,
        Boolean    requisitionApprovalEnabled,
        @PositiveOrZero BigDecimal requisitionApprovalThresholdAmount,
        // Goods-receipt over-receipt tolerance percent (null clears / strict). Range-checked in the
        // service with a friendly message (a bean-validation annotation would leak the raw field name).
        BigDecimal receiptTolerancePct,
        // V105: how goods-receipt costs are read by the PRINTED note — EXCLUSIVE (add VAT on top),
        // INCLUSIVE (extract it), NONE (no band). Null = leave unchanged, like its neighbours above.
        // Validated in the service rather than by annotation, so an unknown value gets a friendly
        // message naming the valid options instead of leaking the raw field name.
        String purchaseVatTreatment
) {}
