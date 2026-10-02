package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ArBalanceServiceImpl implements ArBalanceService {

    private final ArInvoiceRepository invoices;
    private final ArFxSplitQuery fxSplit;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;

    public ArBalanceServiceImpl(ArInvoiceRepository invoices,
                                 ArFxSplitQuery fxSplit,
                                 CompanyRepository companies,
                                 ScopeGuard scopeGuard) {
        this.invoices        = invoices;
        this.fxSplit         = fxSplit;
        this.companies       = companies;
        this.scopeGuard      = scopeGuard;
    }

    @Override
    public boolean hasAllocations(Long companyId, String sourceInvoiceUid) {
        return invoices.findBySalesInvoiceUid(companyId, sourceInvoiceUid)
                .map(inv -> inv.getOutstandingAmount().compareTo(inv.getOriginalAmount()) < 0)
                .orElse(false);
    }

    @Override
    public ArBalanceDto currentBalance(Long companyId, Long customerId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // balance = Σ outstanding − Σ unallocated receipts − Σ unapplied CNs (ADR-0040 D-6: raise
        // posts CR AR full; apply posts nothing, so CN unapplied is netted to equal GL 1200) —
        // in BASE currency, reliable rows only; V62-filled foreign rows are listed per currency
        // (owner ruling 2026-10-02).
        ArFxSplitQuery.Split split = fxSplit.split(companyId, customerId);

        // Derive the base currency — company record holds it (V10 ADD COLUMN).
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> new IllegalStateException("Company not found."));

        return new ArBalanceDto(companyId, customerId, split.baseTotal(), currency,
                split.unconverted());
    }
}
