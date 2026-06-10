package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.VatAdjustmentReason;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request to add a manual VAT adjustment line on a DRAFT return (ADR-0017 D-3, FR-VAT-05).
 */
public record AddVatAdjustmentRequest(
        @NotNull VatAdjustmentReason reason,
        @NotNull VatAdjustmentSign sign,
        @NotNull @Positive BigDecimal amount,
        String narrative
) {}
