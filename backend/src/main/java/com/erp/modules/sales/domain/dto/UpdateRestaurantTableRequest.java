package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Edits a floor table; {@code status} INACTIVE hides it from the floor (never deleted). */
public record UpdateRestaurantTableRequest(
        @NotBlank @Size(max = 40) String name,
        @Min(1) @Max(999) Short seats,
        @Size(max = 40) String area,
        Integer sortOrder,
        MasterStatus status
) {}
