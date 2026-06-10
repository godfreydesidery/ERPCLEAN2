package com.erp.modules.reporting.domain.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Common header attached to every statement DTO (ADR-0018 D-1).
 */
public record StatementHeaderDto(
        Long        companyId,
        String      companyName,
        String      currency,
        String      periodLabel,
        String      comparativeLabel,
        LocalDate   fromDate,
        LocalDate   toDate,
        LocalDate   asAtDate,
        Instant     generatedAt
) {}
