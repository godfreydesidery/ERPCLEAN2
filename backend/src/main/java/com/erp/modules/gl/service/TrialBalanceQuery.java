package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.dto.TrialBalanceRowDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the trial balance on demand (ADR-0013 D-8, FR-GL-16).
 * Not stored — computed from journal_lines GROUP BY account_id.
 * A correct set of books yields totalDebits == totalCredits (nets to zero).
 */
@Component
public class TrialBalanceQuery {

    private final JournalLineRepository lineRepo;
    private final ChartOfAccountRepository accountRepo;
    private final ScopeGuard scopeGuard;

    public TrialBalanceQuery(JournalLineRepository lineRepo,
                              ChartOfAccountRepository accountRepo,
                              ScopeGuard scopeGuard) {
        this.lineRepo    = lineRepo;
        this.accountRepo = accountRepo;
        this.scopeGuard  = scopeGuard;
    }

    /** Full trial balance for a company (all periods). */
    @Transactional(readOnly = true)
    public TrialBalanceDto compute(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> sums = lineRepo.trialBalanceSums(companyId);
        return buildDto(companyId, sums);
    }

    /** Trial balance filtered to a single fiscal period. */
    @Transactional(readOnly = true)
    public TrialBalanceDto computeForPeriod(Long companyId, Long periodId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> sums = lineRepo.trialBalanceSumsByPeriod(companyId, periodId);
        return buildDto(companyId, sums);
    }

    // -------------------------------------------------------------------------

    private TrialBalanceDto buildDto(Long companyId, List<Object[]> sums) {
        // Pre-fetch accounts for this company to enrich rows without N+1 queries
        Map<Long, ChartOfAccount> accountMap = new HashMap<>();
        accountRepo.findByCompanyId(companyId,
                org.springframework.data.domain.Pageable.unpaged()).forEach(
                a -> accountMap.put(a.getId(), a));

        List<TrialBalanceRowDto> rows = new ArrayList<>();
        BigDecimal totalDebit  = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (Object[] row : sums) {
            Long       accountId = (Long)       row[0];
            BigDecimal debit     = toBD(row[1]);
            BigDecimal credit    = toBD(row[2]);

            ChartOfAccount acct = accountMap.get(accountId);
            if (acct == null) {
                // account was deleted after posting — skip (historical orphan)
                continue;
            }
            BigDecimal net = debit.subtract(credit);
            rows.add(new TrialBalanceRowDto(
                    acct.getId(), acct.getUid(), acct.getAccountCode(), acct.getName(),
                    acct.getAccountType(), acct.getNormalBalance(),
                    debit, credit, net));
            totalDebit  = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }

        // Sort by account code
        rows.sort((a, b) -> a.accountCode().compareTo(b.accountCode()));

        return new TrialBalanceDto(companyId, rows, totalDebit, totalCredit);
    }

    private static BigDecimal toBD(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        return new BigDecimal(val.toString());
    }
}
