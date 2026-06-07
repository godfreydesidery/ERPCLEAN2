package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to create a new UnitOfMeasure.
 * Carries {@code companyUid} (String) per the uid-in-URL convention (ADR-0007).
 */
public record CreateUnitOfMeasureRequest(
        @NotBlank String companyUid,
        @NotBlank String code,
        @NotBlank String name
) {
}
