package com.erp.modules.reporting.service;

import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the set of GL account IDs that represent cash and cash-equivalents for a company
 * (ADR-0018 D-7). The basis is {@code cash_bank_accounts.gl_account_id} — the one-to-one set
 * from ADR-0016, the same surface CashGlReconciliationQuery reads.
 *
 * <p>This generalises "Cash 1000 + Bank 1100" to every linked cash/bank GL account, so a company
 * with multiple tills and bank accounts ties correctly.
 */
@Component
@Transactional(readOnly = true)
public class CashEquivalentAccountResolver {

    private final CashBankAccountRepository cashBankAccounts;
    private final ScopeGuard               scopeGuard;

    public CashEquivalentAccountResolver(CashBankAccountRepository cashBankAccounts,
                                          ScopeGuard scopeGuard) {
        this.cashBankAccounts = cashBankAccounts;
        this.scopeGuard       = scopeGuard;
    }

    /**
     * Returns the distinct GL account IDs linked from cash_bank_accounts for this company.
     * assertCanActIn is the caller's responsibility (ReportingServiceImpl calls it first).
     */
    public List<Long> cashAccountIds(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return cashBankAccounts.findByCompanyId(companyId)
                .stream()
                .map(a -> a.getGlAccountId())
                .distinct()
                .toList();
    }
}
