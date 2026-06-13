package com.erp.modules.bi.service;

import com.erp.modules.bi.domain.dto.CrmSnapshotDto;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.bi.domain.dto.FinanceSummaryDto;
import com.erp.modules.bi.domain.dto.InventorySummaryDto;
import com.erp.modules.bi.domain.dto.TrendDto;
import com.erp.modules.bi.domain.dto.WorkingCapitalDto;
import java.time.LocalDate;

/**
 * BI dashboard read service (ADR-0037 D-1).
 * Pure composition: orchestrates already-shipped query beans, owns no SQL.
 */
public interface DashboardService {

    /** Composite dashboard: all panels (each nullable if the caller lacks the fine perm). */
    DashboardDto dashboard(Long companyId, LocalDate from, LocalDate to, Long branchId);

    /** Finance + cash panel only (F-1..F-4). */
    FinanceSummaryDto financeSummary(Long companyId, LocalDate from, LocalDate to);

    /** Working capital panel (W-AR, W-AP). */
    WorkingCapitalDto workingCapital(Long companyId);

    /** Inventory panel (O-1). */
    InventorySummaryDto inventorySummary(Long companyId);

    /** CRM panel (C-1, C-2, C-3). */
    CrmSnapshotDto crmSnapshot(Long companyId, Long branchId, LocalDate from, LocalDate to);

    /** 12-period revenue trend (iterates FiscalPeriodRepository x AccountMovementQuery). */
    TrendDto revenueTrend(Long companyId);

    /** 12-period net-profit trend (iterates FiscalPeriodRepository x AccountMovementQuery). */
    TrendDto netProfitTrend(Long companyId);
}
