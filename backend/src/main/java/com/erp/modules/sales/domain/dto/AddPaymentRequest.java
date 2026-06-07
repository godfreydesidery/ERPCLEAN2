package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.TenderType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request DTO to add a tender payment to a draft invoice (ADR-0008 D-8, FR-SALES-17).
 * currency must match the invoice header currency (BR-CUR-07).
 */
public record AddPaymentRequest(
        @NotNull TenderType tenderType,
        @NotNull @Positive BigDecimal amount,
        @NotNull String currency,
        String reference
) {
}
