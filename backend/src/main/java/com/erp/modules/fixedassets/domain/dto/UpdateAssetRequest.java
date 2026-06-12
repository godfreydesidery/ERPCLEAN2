package com.erp.modules.fixedassets.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Updates non-financial fields while DRAFT (name, location, tag). */
public record UpdateAssetRequest(
        @NotBlank String name,
        String location,
        String assetTag,
        Long costCentreId
) {}
