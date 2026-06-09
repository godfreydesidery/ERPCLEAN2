package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.dto.TrialBalanceRowDto;
import com.erp.modules.gl.service.TrialBalanceQuery;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sub-ledger total vs GL 2100 (Accounts Payable control) reconciliation (ADR-0015 D-7/D-8).
 * Invariant: Σ(outstanding_amount) == GL 2100 balance (BR-AP-02).
 * A non-zero difference is a finance-grade defect.
 */
@Component
@Transactional(readOnly = true)
public class ApReconciliationQuery {

    /** CoA code for the AP control account seeded in V12 (acct 2100). */
    private static final String AP_CONTROL_CODE = "2100";

    private final SupplierBillRepository bills;
    private final CompanyRepository      companies;
    private final TrialBalanceQuery      trialBalance;
    private final ScopeGuard             scopeGuard;

    public ApReconciliationQuery(SupplierBillRepository bills,
                                  CompanyRepository companies,
                                  TrialBalanceQuery trialBalance,
                                  ScopeGuard scopeGuard) {
        this.bills        = bills;
        this.companies    = companies;
        this.trialBalance = trialBalance;
        this.scopeGuard   = scopeGuard;
    }

    public ApReconciliationDto reconcile(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        BigDecimal subLedger = bills.sumOutstandingByCompany(companyId);

        // GL 2100 — liability account, normal balance CREDIT.
        // TrialBalance.net = debit − credit; for a pure-credit account this is negative.
        // Negate to get the positive "payable balance" that matches the sub-ledger.
        TrialBalanceDto tb = trialBalance.compute(companyId);
        BigDecimal glControl = tb.rows().stream()
                .filter(r -> AP_CONTROL_CODE.equals(r.accountCode()))
                .map(TrialBalanceRowDto::net)
                .findFirst()
                .orElse(BigDecimal.ZERO)
                .negate();   // credit-normal: flip sign so positive = payable balance

        BigDecimal difference = subLedger.subtract(glControl);

        return new ApReconciliationDto(companyId, subLedger, glControl, difference, currency);
    }
}
