package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request body for POST /api/v1/boms/uid/{uid}/activate (ADR-0026 D-2, API surface).
 *
 * @param effectiveFrom the date from which this version applies; must not be null
 */
public record ActivateBomRequest(
        @NotNull LocalDate effectiveFrom
) {
}
