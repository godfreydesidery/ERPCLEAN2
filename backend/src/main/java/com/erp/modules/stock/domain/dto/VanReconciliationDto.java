package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.VanReconciliationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a van reconciliation worksheet (ADR-0051 D-8).
 * id + uid duality per PROJECT-CONVENTIONS; long ids serialise as strings globally.
 */
public record VanReconciliationDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String vanLocationUid,
        String vanLocationName,
        String agentUid,
        String agentName,
        String reconNumber,
        LocalDate businessDate,
        VanReconciliationStatus status,
        String notes,
        List<VanReconciliationLineDto> lines,
        Instant reconciledAt
) {}
