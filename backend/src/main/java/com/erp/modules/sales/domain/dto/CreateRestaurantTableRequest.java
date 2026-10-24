package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds a table to a branch's floor (ADR-0065 D-3). {@code branchUid} null means the caller's
 * active branch.
 */
public record CreateRestaurantTableRequest(
        String branchUid,
        @NotBlank @Size(max = 40) String name,
        @Min(1) @Max(999) Short seats,
        @Size(max = 40) String area,
        Integer sortOrder
) {}
