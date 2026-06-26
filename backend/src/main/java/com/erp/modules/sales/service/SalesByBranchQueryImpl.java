package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.BranchSalesAggregateDto;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link SalesByBranchQuery}.
 *
 * <p>Scope-checks via {@link ScopeGuard#assertCanActIn} before every repository call —
 * consistent with the defense-in-depth pattern used by the other query services (D-5).
 */
@Service
@Transactional(readOnly = true)
public class SalesByBranchQueryImpl implements SalesByBranchQuery {

    private final SalesInvoiceRepository invoices;
    private final ScopeGuard             scopeGuard;

    public SalesByBranchQueryImpl(SalesInvoiceRepository invoices, ScopeGuard scopeGuard) {
        this.invoices    = invoices;
        this.scopeGuard  = scopeGuard;
    }

    @Override
    public List<BranchSalesAggregateDto> sumByBranch(Long companyId,
                                                     Instant fromInstant,
                                                     Instant toInstant,
                                                     Long branchId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return invoices.sumFinalisedByBranch(companyId, fromInstant, toInstant, branchId);
    }
}
