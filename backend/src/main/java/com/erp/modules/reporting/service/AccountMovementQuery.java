package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Period-windowed and as-at SQL-aggregated reads for the reporting module (ADR-0018 D-3).
 *
 * <p>All queries aggregate in SQL (GROUP BY), never summing raw lines in Java (NFR-REP-02).
 * Scoped per company_id; {@code assertCanActIn} called by the orchestrating service before any
 * query (D-11). Direct access to GL repositories is the documented cross-module read allowance
 * (D-12, same stance as CashGlReconciliationQuery).
 */
@Component
@Transactional(readOnly = true)
public class AccountMovementQuery {

    private final EntityManager            em;
    private final ChartOfAccountRepository accountRepo;
    private final ScopeGuard              scopeGuard;

    public AccountMovementQuery(EntityManager em,
                                 ChartOfAccountRepository accountRepo,
                                 ScopeGuard scopeGuard) {
        this.em          = em;
        this.accountRepo = accountRepo;
        this.scopeGuard  = scopeGuard;
    }

    // -------------------------------------------------------------------------
    // (a) Period movement by account — [fromDate, toDate] (D-3(a))
    // Returns Map<accountId, [sumDebit, sumCredit]>
    // -------------------------------------------------------------------------

    public Map<Long, BigDecimal[]> periodMovementByAccount(Long companyId,
                                                            LocalDate fromDate,
                                                            LocalDate toDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> rows = em.createQuery("""
                SELECT l.accountId,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                WHERE l.companyId = :companyId
                  AND e.postingDate BETWEEN :fromDate AND :toDate
                GROUP BY l.accountId
                """, Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();
        return toAccountMap(rows);
    }

    // -------------------------------------------------------------------------
    // (b) Cumulative balance as-at a date by account — inception → asAtDate (D-3(b))
    // Returns Map<accountId, [sumDebit, sumCredit]>
    // -------------------------------------------------------------------------

    public Map<Long, BigDecimal[]> cumulativeByAccountAsAt(Long companyId, LocalDate asAtDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> rows = em.createQuery("""
                SELECT l.accountId,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                WHERE l.companyId = :companyId
                  AND e.postingDate <= :asAtDate
                GROUP BY l.accountId
                """, Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("asAtDate", asAtDate)
                .getResultList();
        return toAccountMap(rows);
    }

    // -------------------------------------------------------------------------
    // (c) Movement by account_type over a window — the P&L net + equity-fold (D-3(c))
    // Returns Map<AccountType, [sumDebit, sumCredit]>
    // -------------------------------------------------------------------------

    public Map<AccountType, BigDecimal[]> periodMovementByAccountType(Long companyId,
                                                                       LocalDate fromDate,
                                                                       LocalDate toDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> rows = em.createQuery("""
                SELECT a.accountType,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                JOIN ChartOfAccount a ON a.id = l.accountId
                WHERE l.companyId = :companyId
                  AND e.postingDate BETWEEN :fromDate AND :toDate
                GROUP BY a.accountType
                """, Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();
        Map<AccountType, BigDecimal[]> result = new EnumMap<>(AccountType.class);
        for (Object[] row : rows) {
            AccountType type   = (AccountType) row[0];
            BigDecimal  debit  = toBD(row[1]);
            BigDecimal  credit = toBD(row[2]);
            result.put(type, new BigDecimal[]{debit, credit});
        }
        return result;
    }

    /**
     * Cumulative movement by account_type as-at a date (inception → asAtDate).
     * Used for the equity-fold inception-to-date computation (D-6).
     */
    public Map<AccountType, BigDecimal[]> cumulativeByAccountTypeAsAt(Long companyId,
                                                                        LocalDate asAtDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> rows = em.createQuery("""
                SELECT a.accountType,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                JOIN ChartOfAccount a ON a.id = l.accountId
                WHERE l.companyId = :companyId
                  AND e.postingDate <= :asAtDate
                GROUP BY a.accountType
                """, Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("asAtDate", asAtDate)
                .getResultList();
        Map<AccountType, BigDecimal[]> result = new EnumMap<>(AccountType.class);
        for (Object[] row : rows) {
            AccountType type   = (AccountType) row[0];
            BigDecimal  debit  = toBD(row[1]);
            BigDecimal  credit = toBD(row[2]);
            result.put(type, new BigDecimal[]{debit, credit});
        }
        return result;
    }

    /**
     * Equity-fold: INCOME net − EXPENSE net cumulative for posting_date within [start, end].
     * Used to split into "prior-years retained" (before fyStart) and "current-year earnings"
     * ([fyStart, asAt]) for the BS presentation (D-6, step 3).
     */
    public BigDecimal netIncomeForPeriod(Long companyId, LocalDate fromDate, LocalDate toDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Map<AccountType, BigDecimal[]> byType = periodMovementByAccountType(companyId, fromDate, toDate);
        BigDecimal[] income  = byType.getOrDefault(AccountType.INCOME,  new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal[] expense = byType.getOrDefault(AccountType.EXPENSE, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        // INCOME net (CREDIT-normal): credit − debit
        BigDecimal incomeNet  = income[1].subtract(income[0]);
        // EXPENSE net (DEBIT-normal): debit − credit
        BigDecimal expenseNet = expense[0].subtract(expense[1]);
        return incomeNet.subtract(expenseNet);
    }

    /**
     * Full account map for a company, used by builders to enrich account metadata.
     * Pre-fetched once per statement build to avoid N+1 (mirrors TrialBalanceQuery.buildDto).
     */
    public Map<Long, ChartOfAccount> accountMapForCompany(Long companyId) {
        Map<Long, ChartOfAccount> map = new LinkedHashMap<>();
        accountRepo.findByCompanyId(companyId, org.springframework.data.domain.Pageable.unpaged())
                .forEach(a -> map.put(a.getId(), a));
        return map;
    }

    // -------------------------------------------------------------------------

    private Map<Long, BigDecimal[]> toAccountMap(List<Object[]> rows) {
        Map<Long, BigDecimal[]> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long       accountId = (Long)       row[0];
            BigDecimal debit     = toBD(row[1]);
            BigDecimal credit    = toBD(row[2]);
            result.put(accountId, new BigDecimal[]{debit, credit});
        }
        return result;
    }

    static BigDecimal toBD(Object val) {
        if (val == null) return BigDecimal.ZERO;
        if (val instanceof BigDecimal bd) return bd;
        return new BigDecimal(val.toString());
    }
}
