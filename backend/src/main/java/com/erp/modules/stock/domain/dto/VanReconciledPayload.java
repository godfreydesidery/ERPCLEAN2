package com.erp.modules.stock.domain.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Informational outbox payload for a van reconciliation reconcile action (ADR-0051 D-8.2).
 * RECORD-ONLY: no stock/GL post is triggered by this event. Published for downstream consumers
 * (e.g. reporting/notifications) only.
 */
public record VanReconciledPayload(
        String reconUid,
        Long companyId,
        Long branchId,
        Long vanLocationId,
        Long agentId,
        LocalDate businessDate,
        Instant reconciledAt
) {}
