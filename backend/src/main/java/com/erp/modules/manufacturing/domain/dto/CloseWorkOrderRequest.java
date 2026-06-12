package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Request body for POST /api/v1/work-orders/uid/{uid}/close (ADR-0035 D-4d). */
public record CloseWorkOrderRequest(
        @NotNull LocalDate postingDate
) {}
