package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP balance enquiry — net outstanding for a supplier sub-ledger (ADR-0015 D-7).
 *
 * <p>D-9 (ADR-0040): balance now nets on-account (unallocated) payments:
 * {@code balance = Σ outstanding_bills − Σ unallocated_payments}.
 * A negative balance means the supplier has a net prepayment credit, which reconciles
 * against GL 2100 because the on-account payment already reduced the control account
 * via its DR AP / CR Cash posting.
 */
@Service
@Transactional(readOnly = true)
public class ApBalanceServiceImpl implements ApBalanceService {

    private final SupplierBillRepository bills;
    private final ApPaymentRepository    payments;
    private final CompanyRepository      companies;
    private final ScopeGuard             scopeGuard;

    public ApBalanceServiceImpl(SupplierBillRepository bills,
                                 ApPaymentRepository payments,
                                 CompanyRepository companies,
                                 ScopeGuard scopeGuard) {
        this.bills      = bills;
        this.payments   = payments;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    @Override
    public ApBalanceDto currentBalance(Long companyId, Long supplierId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        BigDecimal outstanding  = bills.sumOutstandingBySupplier(companyId, supplierId);
        // D-9: net out on-account remainder (mirror of ArBalanceServiceImpl)
        BigDecimal unallocated  = payments.sumUnallocatedByCompanyAndSupplier(companyId, supplierId);
        BigDecimal balance      = outstanding.subtract(unallocated);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");

        return new ApBalanceDto(companyId, supplierId, balance, currency);
    }
}
