package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for POST /api/v1/work-orders/uid/{uid}/apply-cost (ADR-0035 D-4b).
 * At least one of labourAmount / overheadAmount must be > 0 (validated in service).
 */
public record ApplyCostRequest(
        BigDecimal labourAmount,
        BigDecimal overheadAmount,
        /** Optional: the operation uid this applies to. Null = header-level apply. */
        String operationUid,
        @NotNull LocalDate postingDate
) {}
