package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
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
 *
 * <p>Unapplied debit notes are netted as well — the per-supplier mirror of
 * {@code ApReconciliationQuery} (and of {@code ArBalanceServiceImpl} netting unapplied credit
 * notes): {@code balance = Σ outstanding − Σ unallocated_payments − Σ unapplied_debit_notes}, so
 * the supplier balances add up to the reconciled sub-ledger total.
 *
 * <p>Owner ruling 2026-10-02: the total is in BASE currency over reliable rows only (see
 * {@link ApFxSplitQuery}); a foreign-currency row still carrying the V62 back-fill
 * ({@code fx_rate = 1}) is listed per currency in {@code unconverted}, never summed into base.
 */
@Service
@Transactional(readOnly = true)
public class ApBalanceServiceImpl implements ApBalanceService {

    private final ApFxSplitQuery    fxSplit;
    private final CompanyRepository companies;
    private final ScopeGuard        scopeGuard;

    public ApBalanceServiceImpl(ApFxSplitQuery fxSplit,
                                 CompanyRepository companies,
                                 ScopeGuard scopeGuard) {
        this.fxSplit    = fxSplit;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    @Override
    public ApBalanceDto currentBalance(Long companyId, Long supplierId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // Σ outstanding − Σ unallocated payments (D-9) − Σ unapplied debit notes (raise already
        // relieved GL 2100 in full) — in BASE currency, reliable rows only; V62-filled foreign
        // rows are listed per currency (owner ruling 2026-10-02).
        ApFxSplitQuery.Split split = fxSplit.split(companyId, supplierId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");

        return new ApBalanceDto(companyId, supplierId, split.baseTotal(), currency,
                split.unconverted());
    }
}
