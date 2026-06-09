package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP balance enquiry — sum of outstanding for a supplier sub-ledger (ADR-0015 D-7).
 */
@Service
@Transactional(readOnly = true)
public class ApBalanceServiceImpl implements ApBalanceService {

    private final SupplierBillRepository bills;
    private final ScopeGuard             scopeGuard;

    public ApBalanceServiceImpl(SupplierBillRepository bills, ScopeGuard scopeGuard) {
        this.bills      = bills;
        this.scopeGuard = scopeGuard;
    }

    @Override
    public ApBalanceDto currentBalance(Long companyId, Long supplierId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        BigDecimal outstanding = bills.sumOutstandingBySupplier(companyId, supplierId);
        return new ApBalanceDto(companyId, supplierId, outstanding, "TZS");
    }
}
