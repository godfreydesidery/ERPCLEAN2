package com.erp.modules.projects.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Project P&L read model DTO (ADR-0033 D-6, FR-PROJ-09/10/11).
 * Revenue = Σ tagged INCOME-account credits; cost = Σ tagged EXPENSE-account debits.
 * WIP = max(0, cost − revenue); unbooked in v1 (BR-PROJ-07).
 * recon.computed == recon.expected by construction (BR-PROJ-09) — a mismatch is a bug.
 */
public record ProjectPnlDto(
        String projectUid,
        String projectNumber,
        String name,
        Long customerId,
        BigDecimal revenue,
        BigDecimal totalCost,
        List<ProjectCostingRowDto> costByType,
        BigDecimal margin,
        BigDecimal marginPct,
        BigDecimal budget,
        BigDecimal budgetVariance,
        BigDecimal wip,
        String currency,
        /** Structural self-check: computed == GL Σ by account-type (BR-PROJ-09). */
        ReconDto recon
) {
    /** Lightweight recon bar: the read-model total vs the GL-computed total (should always match). */
    public record ReconDto(
            BigDecimal computedRevenue,
            BigDecimal computedCost,
            BigDecimal glRevenue,
            BigDecimal glCost,
            boolean balanced
    ) {}
}
