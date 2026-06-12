package com.erp.modules.stock.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO: create a stock count (FR-INVD-12, ADR-0028 D-6).
 */
public record CreateStockCountRequest(
        @NotBlank String locationUid,
        @NotNull LocalDate countDate,
        /** FULL or CYCLE */
        @NotBlank String countType,
        /** For CYCLE counts: optional product uid subset. Empty/null = FULL location scope. */
        @Size(max = 500) List<String> productUids,
        @Size(max = 500) String notes
) {}
