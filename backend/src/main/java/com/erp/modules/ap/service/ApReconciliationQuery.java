package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApReconciliationDto;
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
 * <p>Unapplied debit notes are netted too (credit-note parity with {@code ArReconciliationQuery},
 * ADR-0040 D-6 / ADR-0041 D3): a debit note posts its FULL DR AP-control once at raise and APPLY
 * posts nothing (bar a realized-FX plug), so at every committed state
 * {@code GL 2100 = Σ bills − Σ payments − Σ DN raised}. On the sub-ledger side the applied part of
 * each DN has already reduced its bills' outstanding, so only the UNAPPLIED remainder is netted:
 * {@code subLedger = Σ outstanding_bills − Σ unallocated_payments − Σ unapplied_debit_notes}.
 * A reversed (bounced) payment restores its bills' outstanding and is left out of the unallocated
 * sum, matching its GL reversal.
 *
 * <p>Invariant: netSubLedger == GL 2100 balance (BR-AP-02).
 * A non-zero difference is a finance-grade defect (NFR-AP-01).
 *
 * <p>Owner ruling 2026-10-02: compared in BASE currency using stored base amounts for reliable
 * rows (see {@link ApFxSplitQuery}); V62-filled foreign rows are excluded and listed per currency
 * in {@code unconverted}.
 */
@Component
@Transactional(readOnly = true)
public class ApReconciliationQuery {

    /** CoA code for the AP control account seeded in V12 (acct 2100). */
    private static final String AP_CONTROL_CODE = "2100";

    private final ApFxSplitQuery         fxSplit;
    private final CompanyRepository      companies;
    private final TrialBalanceQuery      trialBalance;
    private final ScopeGuard             scopeGuard;

    public ApReconciliationQuery(ApFxSplitQuery fxSplit,
                                  CompanyRepository companies,
                                  TrialBalanceQuery trialBalance,
                                  ScopeGuard scopeGuard) {
        this.fxSplit      = fxSplit;
        this.companies    = companies;
        this.trialBalance = trialBalance;
        this.scopeGuard   = scopeGuard;
    }

    public ApReconciliationDto reconcile(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        // D-9: net sub-ledger = Σ outstanding − Σ unallocated_payments − Σ unapplied_debit_notes
        // (raise posts DR AP for the full DN; apply posts nothing → net the unapplied remainder)
        // — in BASE currency over reliable rows only. Foreign rows still carrying the V62 fill
        // (fx_rate = 1) are excluded and reported per currency (owner ruling 2026-10-02).
        ApFxSplitQuery.Split split = fxSplit.split(companyId, null);
        BigDecimal subLedger    = split.baseTotal();

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

        return new ApReconciliationDto(companyId, subLedger, glControl, difference, currency,
                split.unconverted());
    }
}
