package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO: edit a stock location's name/type (FR-INVD-02, ADR-0028 D-4).
 */
public record UpdateStockLocationRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull LocationType locationType
) {}
