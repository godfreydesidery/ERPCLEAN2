package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ArBalanceServiceImpl implements ArBalanceService {

    private final ArInvoiceRepository invoices;
    private final ArReceiptRepository receipts;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;

    public ArBalanceServiceImpl(ArInvoiceRepository invoices,
                                 ArReceiptRepository receipts,
                                 CompanyRepository companies,
                                 ScopeGuard scopeGuard) {
        this.invoices   = invoices;
        this.receipts   = receipts;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    @Override
    public ArBalanceDto currentBalance(Long companyId, Long customerId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        BigDecimal outstanding  = invoices.sumOutstandingByCompanyAndCustomer(companyId, customerId);
        BigDecimal unallocated  = receipts.sumUnallocatedByCompanyAndCustomer(companyId, customerId);
        BigDecimal balance      = outstanding.subtract(unallocated);

        // Derive the base currency — company record holds it (V10 ADD COLUMN).
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");

        return new ArBalanceDto(companyId, customerId, balance, currency);
    }
}
