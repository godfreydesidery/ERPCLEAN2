package com.erp.modules.fixedassets.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Outbox payload for the {@code DEPRECIATION.RUN.EXECUTED} event (ADR-0030 D-8).
 * Consumers (future notifications / management-reporting) subscribe to this event.
 */
public record DepreciationRunExecutedPayload(
        String runUid,
        Long companyId,
        Long fiscalPeriodId,
        LocalDate postingDate,
        BigDecimal totalChargeAmount,
        int assetCount,
        String glEntryUid,
        Instant executedAt
) {}
