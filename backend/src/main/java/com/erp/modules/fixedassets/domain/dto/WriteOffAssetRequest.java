package com.erp.modules.fixedassets.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Write off an asset — proceeds = 0, loss = full NBV (ADR-0030 D-6d). */
public record WriteOffAssetRequest(
        @NotNull LocalDate disposalDate,
        String reason
) {}
