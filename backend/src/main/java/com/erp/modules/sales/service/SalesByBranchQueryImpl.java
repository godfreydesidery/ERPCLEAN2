package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.BranchSalesAggregateDto;
import com.erp.modules.sales.repository.PosSaleReturnRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link SalesByBranchQuery}.
 *
 * <p>Scope-checks via {@link ScopeGuard#assertCanActIn} before every repository call —
 * consistent with the defense-in-depth pattern used by the other query services (D-5).
 *
 * <p>ADR-0072: the branch total is net of POS item returns paid in the window. The original invoice
 * stays FINALISED, so the returned amount is subtracted here, in base currency, per branch (a branch
 * whose only activity was a return shows a negative total and a count of 0).
 */
@Service
@Transactional(readOnly = true)
public class SalesByBranchQueryImpl implements SalesByBranchQuery {

    private final SalesInvoiceRepository invoices;
    private final PosSaleReturnRepository posReturns;
    private final ScopeGuard             scopeGuard;

    public SalesByBranchQueryImpl(SalesInvoiceRepository invoices,
                                  PosSaleReturnRepository posReturns, ScopeGuard scopeGuard) {
        this.invoices    = invoices;
        this.posReturns  = posReturns;
        this.scopeGuard  = scopeGuard;
    }

    @Override
    public List<BranchSalesAggregateDto> sumByBranch(Long companyId,
                                                     Instant fromInstant,
                                                     Instant toInstant,
                                                     Long branchId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<BranchSalesAggregateDto> sold =
                invoices.sumFinalisedByBranch(companyId, fromInstant, toInstant, branchId);
        List<BranchSalesAggregateDto> refunded =
                posReturns.sumBaseGrossByCompanyIdBranch(companyId, fromInstant, toInstant, branchId);
        if (refunded.isEmpty()) {
            return sold;
        }
        Map<Long, BranchSalesAggregateDto> byBranch = new LinkedHashMap<>();
        for (BranchSalesAggregateDto s : sold) {
            byBranch.put(s.branchId(), s);
        }
        for (BranchSalesAggregateDto r : refunded) {
            BranchSalesAggregateDto s = byBranch.get(r.branchId());
            byBranch.put(r.branchId(), s == null
                    ? new BranchSalesAggregateDto(r.branchId(), r.total().negate(), 0L)
                    : new BranchSalesAggregateDto(r.branchId(),
                            nz(s.total()).subtract(nz(r.total())), s.count()));
        }
        return new ArrayList<>(byBranch.values());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
