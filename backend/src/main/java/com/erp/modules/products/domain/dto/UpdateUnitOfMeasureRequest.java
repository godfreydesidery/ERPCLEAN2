package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to update a UnitOfMeasure's display name.
 * Code and company are not updatable (immutable after creation).
 */
public record UpdateUnitOfMeasureRequest(
        @NotBlank String name
) {
}
