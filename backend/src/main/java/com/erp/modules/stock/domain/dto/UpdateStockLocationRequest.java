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
        @NotNull LocationType locationType,
        /**
         * Optional route-agent uid (ADR-0051 D-8.4). Only valid when {@code locationType} is
         * {@code VAN}; the agent must belong to the same company and have no other ACTIVE van.
         * Pass {@code null} to leave the current assignment unchanged, or an empty string to clear it.
         */
        String agentUid
) {}
