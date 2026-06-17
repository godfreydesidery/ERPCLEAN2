package com.erp.modules.fx.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to enable (add) a currency for a company (ADR-0039 D-7/D-8).
 * Reusing existing {@code CURRENCY.MANAGE} permission.
 */
public record EnableCurrencyRequest(
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        boolean setAsDefault) {
}
