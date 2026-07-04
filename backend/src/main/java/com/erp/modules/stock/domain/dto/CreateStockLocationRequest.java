package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO: create a new stock location (FR-INVD-01, ADR-0028 D-4).
 */
public record CreateStockLocationRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull LocationType locationType,
        /** The owning branch uid. */
        @NotBlank String branchUid,
        /** If true, this location becomes the branch default (clears any existing default). */
        boolean makeDefault,
        /**
         * Optional route-agent uid (ADR-0051 D-8.4). Only valid when {@code locationType} is
         * {@code VAN}; the agent must belong to the same company and have no other ACTIVE van.
         */
        String agentUid
) {}
