package com.erp.modules.costing.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a custom dimension type (ADR-0025 D-1).
 * Only the spare slots DIMENSION_3 and DIMENSION_4 may be claimed this way;
 * COST_CENTRE and DEPARTMENT are seeder-owned built-ins (FR-CC-01).
 * At most two custom dimensions per company (one per spare slot).
 */
public record CreateDimensionRequest(
        @NotNull Long companyId,
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 255) String description
) {}
