package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.BranchSalesAggregateDto;
import java.time.Instant;
import java.util.List;

/**
 * Read-only query surface for per-branch FINALISED invoice aggregates.
 *
 * <p>Consumed by the BI module's {@code DashboardServiceImpl} to build the Sales-by-Branch panel
 * (ADR-0037). Cross-module boundary is honoured: BI depends on this interface (in
 * {@code ..sales.service..}) and the shared DTO ({@code ..sales.domain.dto..}); it never touches
 * the sales repository directly.
 */
public interface SalesByBranchQuery {

    /**
     * Returns per-branch FINALISED invoice totals and counts for {@code companyId} whose
     * {@code finalisedAt} falls in [{@code fromInstant}, {@code toInstant}).
     *
     * @param companyId   required; assertCanActIn is enforced inside the impl
     * @param fromInstant inclusive lower bound (UTC midnight of the from-date)
     * @param toInstant   exclusive upper bound (UTC midnight of the day after to-date)
     * @param branchId    optional; when non-null only that branch is returned
     */
    List<BranchSalesAggregateDto> sumByBranch(Long companyId,
                                              Instant fromInstant,
                                              Instant toInstant,
                                              Long branchId);
}
