package com.erp.modules.tax.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request to mark a WHT transaction as remitted to the tax authority (ADR-0040 D-7).
 * {@code remittancePeriod} is the YYYY-MM period; {@code remittanceRef} is the authority acknowledgement.
 */
public record WhtRemitRequest(
        @NotBlank String remittancePeriod,
        @NotBlank String remittanceRef) {
}
