package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.repository.ArCreditNoteRepository;
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
    private final ArCreditNoteRepository creditNoteRepo;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;

    public ArBalanceServiceImpl(ArInvoiceRepository invoices,
                                 ArReceiptRepository receipts,
                                 ArCreditNoteRepository creditNoteRepo,
                                 CompanyRepository companies,
                                 ScopeGuard scopeGuard) {
        this.invoices        = invoices;
        this.receipts        = receipts;
        this.creditNoteRepo  = creditNoteRepo;
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

        BigDecimal outstanding  = invoices.sumOutstandingByCompanyAndCustomer(companyId, customerId);
        BigDecimal unallocated  = receipts.sumUnallocatedByCompanyAndCustomer(companyId, customerId);
        // ADR-0040 D-6: net unapplied CNs — raise posts CR AR full; apply posts nothing,
        // so the sub-ledger must subtract CN unapplied to equal GL 1200.
        BigDecimal cnUnapplied  = creditNoteRepo.sumUnappliedByCompanyAndCustomer(companyId, customerId);
        BigDecimal balance      = outstanding.subtract(unallocated).subtract(cnUnapplied);

        // Derive the base currency — company record holds it (V10 ADD COLUMN).
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> new IllegalStateException("Company not found."));

        return new ArBalanceDto(companyId, customerId, balance, currency);
    }
}
