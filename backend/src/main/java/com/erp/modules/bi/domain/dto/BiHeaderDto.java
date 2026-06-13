package com.erp.modules.bi.domain.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Dashboard header — mirrors {@code StatementHeaderDto} but for the BI composite (ADR-0037 D-6).
 * currency resolved via CompanyRepository.getBaseCurrency() — NEVER hardcoded.
 */
public record BiHeaderDto(
        Long      companyId,
        String    companyName,
        String    currency,
        String    periodLabel,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOf,
        Instant   generatedAt
) {}
