package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArReconciliationDto;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
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
 * Sub-ledger total vs GL 1200 reconciliation (ADR-0014 D-8, FR-AR-18).
 * subLedgerTotal = Σ outstanding (OPEN/PARTIAL) − Σ unallocated receipts.
 * glControlBalance = net balance of account 1200 from the trial balance.
 * A non-zero difference is a finance-grade defect.
 */
@Component
@Transactional(readOnly = true)
public class ArReconciliationQuery {

    private final ArInvoiceRepository invoices;
    private final ArReceiptRepository receipts;
    private final CompanyRepository companies;
    private final TrialBalanceQuery trialBalance;
    private final ScopeGuard scopeGuard;

    public ArReconciliationQuery(ArInvoiceRepository invoices,
                                  ArReceiptRepository receipts,
                                  CompanyRepository companies,
                                  TrialBalanceQuery trialBalance,
                                  ScopeGuard scopeGuard) {
        this.invoices      = invoices;
        this.receipts      = receipts;
        this.companies     = companies;
        this.trialBalance  = trialBalance;
        this.scopeGuard    = scopeGuard;
    }

    public ArReconciliationDto reconcile(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        BigDecimal outstanding  = invoices.sumOutstandingByCompany(companyId);
        BigDecimal unallocated  = receipts.sumUnallocatedByCompany(companyId);
        BigDecimal subLedger    = outstanding.subtract(unallocated);

        // GL 1200 balance from the trial balance (net = debit - credit for an asset account)
        TrialBalanceDto tb = trialBalance.compute(companyId);
        BigDecimal glControl = tb.rows().stream()
                .filter(r -> "1200".equals(r.accountCode()))
                .map(TrialBalanceRowDto::net)
                .findFirst()
                .orElse(BigDecimal.ZERO);

        BigDecimal difference = subLedger.subtract(glControl);

        return new ArReconciliationDto(companyId, subLedger, glControl, difference, currency);
    }
}
