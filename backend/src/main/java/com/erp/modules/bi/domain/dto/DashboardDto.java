package com.erp.modules.bi.domain.dto;

import java.util.List;

/**
 * The composite BI dashboard payload (ADR-0037 D-6).
 *
 * <p>Each panel is NULLABLE — a null panel means the caller lacked the fine-grained panel permission,
 * or the upstream query raised an unexpected exception. A null panel renders as a calm "forbidden" /
 * "no data" state on the frontend, never a whole-page 403 (C's graceful per-panel degrade).
 *
 * <p>The {@code health} list aggregates all recon badges (AR/AP/Cash/Stock/TB) for the health-strip
 * at the top of the dashboard.
 */
public record DashboardDto(
        BiHeaderDto          header,
        FinanceSummaryDto    finance,        // nullable — gated BI.FINANCE.VIEW
        WorkingCapitalDto    workingCapital, // nullable — gated BI.FINANCE.VIEW
        InventorySummaryDto  inventory,      // nullable — gated BI.OPS.VIEW
        CrmSnapshotDto       crm,            // nullable — gated BI.CRM.VIEW
        TrendDto             revenueTrend,   // nullable — gated BI.FINANCE.VIEW
        TrendDto             netProfitTrend, // nullable — gated BI.FINANCE.VIEW
        SalesByBranchDto     salesByBranch,  // nullable — gated BI.FINANCE.VIEW
        List<HealthIndicatorDto> health      // always present, may be empty
) {}
