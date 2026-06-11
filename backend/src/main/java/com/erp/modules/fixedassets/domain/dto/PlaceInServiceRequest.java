package com.erp.modules.fixedassets.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Trigger place-in-service for a DRAFT asset (ADR-0030 D-3). */
public record PlaceInServiceRequest(
        @NotNull LocalDate postingDate
) {}
