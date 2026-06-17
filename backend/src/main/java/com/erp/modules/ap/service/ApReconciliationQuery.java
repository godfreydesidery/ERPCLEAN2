package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.repository.ApPaymentRepository;
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
 *
 * <p>D-9 (ADR-0040): sub-ledger total now nets on-account payments:
 * {@code subLedger = Σ outstanding_bills − Σ unallocated_payments}.
 * This mirrors the AR reconciliation pattern and keeps the invariant vs GL 2100 intact —
 * each on-account payment posted DR AP / CR Cash, so GL 2100 credit balance already
 * reflects the prepayment reduction. The sub-ledger must match.
 *
 * <p>Invariant: netSubLedger == GL 2100 balance (BR-AP-02).
 * A non-zero difference is a finance-grade defect (NFR-AP-01).
 */
@Component
@Transactional(readOnly = true)
public class ApReconciliationQuery {

    /** CoA code for the AP control account seeded in V12 (acct 2100). */
    private static final String AP_CONTROL_CODE = "2100";

    private final SupplierBillRepository bills;
    private final ApPaymentRepository    payments;
    private final CompanyRepository      companies;
    private final TrialBalanceQuery      trialBalance;
    private final ScopeGuard             scopeGuard;

    public ApReconciliationQuery(SupplierBillRepository bills,
                                  ApPaymentRepository payments,
                                  CompanyRepository companies,
                                  TrialBalanceQuery trialBalance,
                                  ScopeGuard scopeGuard) {
        this.bills        = bills;
        this.payments     = payments;
        this.companies    = companies;
        this.trialBalance = trialBalance;
        this.scopeGuard   = scopeGuard;
    }

    public ApReconciliationDto reconcile(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        // D-9: net sub-ledger = Σ outstanding − Σ unallocated_payments
        BigDecimal outstanding  = bills.sumOutstandingByCompany(companyId);
        BigDecimal unallocated  = payments.sumUnallocatedByCompany(companyId);
        BigDecimal subLedger    = outstanding.subtract(unallocated);

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
