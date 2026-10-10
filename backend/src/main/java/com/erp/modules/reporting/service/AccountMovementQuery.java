package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 *
 * <p>The {@link StatementScope} overloads narrow every read to one branch's lines
 * ({@code journal_lines.branch_id}) or to the lines with no branch. The original company-wide
 * signatures are kept, unchanged in behaviour, for their existing callers (BI dashboard, ledger).
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
        return periodMovementByAccount(companyId, StatementScope.companyWide(), fromDate, toDate, false);
    }

    /**
     * Period movement by account inside a {@link StatementScope}.
     *
     * @param excludeClosing true to leave out year-end close journals (and their reopen
     *                       reversals). A closing entry is a transfer of the year's result into
     *                       retained earnings, not income or expense, so a P&amp;L must not read it
     *                       as one — otherwise a closed year reports a profit of zero.
     */
    public Map<Long, BigDecimal[]> periodMovementByAccount(Long companyId, StatementScope scope,
                                                            LocalDate fromDate, LocalDate toDate,
                                                            boolean excludeClosing) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        TypedQuery<Object[]> q = em.createQuery("""
                SELECT l.accountId,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                WHERE l.companyId = :companyId
                  AND e.postingDate BETWEEN :fromDate AND :toDate
                """ + scopePredicate(scope) + closingPredicate(excludeClosing)
                + " GROUP BY l.accountId", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate);
        bindScope(q, scope);
        bindClosing(q, excludeClosing);
        return toAccountMap(q.getResultList());
    }

    // -------------------------------------------------------------------------
    // (b) Cumulative balance as-at a date by account — inception → asAtDate (D-3(b))
    // Returns Map<accountId, [sumDebit, sumCredit]>
    // -------------------------------------------------------------------------

    public Map<Long, BigDecimal[]> cumulativeByAccountAsAt(Long companyId, LocalDate asAtDate) {
        return cumulativeByAccountAsAt(companyId, StatementScope.companyWide(), asAtDate);
    }

    /** Cumulative balance by account as-at a date inside a {@link StatementScope}. */
    public Map<Long, BigDecimal[]> cumulativeByAccountAsAt(Long companyId, StatementScope scope,
                                                            LocalDate asAtDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        TypedQuery<Object[]> q = em.createQuery("""
                SELECT l.accountId,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                WHERE l.companyId = :companyId
                  AND e.postingDate <= :asAtDate
                """ + scopePredicate(scope)
                + " GROUP BY l.accountId", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("asAtDate", asAtDate);
        bindScope(q, scope);
        return toAccountMap(q.getResultList());
    }

    // -------------------------------------------------------------------------
    // (c) Movement by account_type over a window — the P&L net + equity-fold (D-3(c))
    // Returns Map<AccountType, [sumDebit, sumCredit]>
    // -------------------------------------------------------------------------

    public Map<AccountType, BigDecimal[]> periodMovementByAccountType(Long companyId,
                                                                       LocalDate fromDate,
                                                                       LocalDate toDate) {
        return periodMovementByAccountType(companyId, StatementScope.companyWide(), fromDate, toDate, false);
    }

    /**
     * Movement by account type inside a {@link StatementScope}; see
     * {@link #periodMovementByAccount(Long, StatementScope, LocalDate, LocalDate, boolean)} for
     * {@code excludeClosing}.
     */
    public Map<AccountType, BigDecimal[]> periodMovementByAccountType(Long companyId,
                                                                       StatementScope scope,
                                                                       LocalDate fromDate,
                                                                       LocalDate toDate,
                                                                       boolean excludeClosing) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        TypedQuery<Object[]> q = em.createQuery("""
                SELECT a.accountType,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                JOIN ChartOfAccount a ON a.id = l.accountId
                WHERE l.companyId = :companyId
                  AND e.postingDate BETWEEN :fromDate AND :toDate
                """ + scopePredicate(scope) + closingPredicate(excludeClosing)
                + " GROUP BY a.accountType", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate);
        bindScope(q, scope);
        bindClosing(q, excludeClosing);
        Map<AccountType, BigDecimal[]> result = new EnumMap<>(AccountType.class);
        for (Object[] row : q.getResultList()) {
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
        return netIncomeOf(periodMovementByAccountType(companyId, fromDate, toDate));
    }

    /**
     * INCOME net − EXPENSE net over [fromDate, toDate] inside a {@link StatementScope}.
     *
     * <p>The Balance Sheet fold passes {@code excludeClosing=false}: it must see the year-end close
     * (which moves the result into 3900) or the closed year would be counted twice. The Income
     * Statement and the "profit for the period" of the equity statement pass {@code true}.
     * An empty window ({@code fromDate} after {@code toDate}) is zero.
     */
    public BigDecimal netIncomeForPeriod(Long companyId, StatementScope scope,
                                          LocalDate fromDate, LocalDate toDate,
                                          boolean excludeClosing) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (fromDate.isAfter(toDate)) {
            return BigDecimal.ZERO;
        }
        return netIncomeOf(periodMovementByAccountType(companyId, scope, fromDate, toDate, excludeClosing));
    }

    /** INCOME net (credit − debit) − EXPENSE net (debit − credit) from a type aggregate. */
    static BigDecimal netIncomeOf(Map<AccountType, BigDecimal[]> byType) {
        BigDecimal[] income  = byType.getOrDefault(AccountType.INCOME,  new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal[] expense = byType.getOrDefault(AccountType.EXPENSE, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        // INCOME net (CREDIT-normal): credit − debit
        BigDecimal incomeNet  = income[1].subtract(income[0]);
        // EXPENSE net (DEBIT-normal): debit − credit
        BigDecimal expenseNet = expense[0].subtract(expense[1]);
        return incomeNet.subtract(expenseNet);
    }

    /**
     * Net debit (Σ debit − Σ credit) of EVERY line in the scope as-at a date, across all accounts.
     *
     * <p>Zero for the whole company by double entry. For a branch slice it is zero as long as each
     * journal entry's lines all carry the same branch — which is how the posting engine writes
     * them. Any other value is the "inter-branch balance": the amount by which entries that
     * straddle branches leave this slice's own lines unbalanced. Statements show it as an
     * explicit line instead of silently failing to balance.
     */
    public BigDecimal scopeNetDebitAsAt(Long companyId, StatementScope scope, LocalDate asAtDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (scope.isCompanyWide()) {
            return BigDecimal.ZERO; // double entry: the whole company always nets to zero
        }
        TypedQuery<Object[]> q = em.createQuery("""
                SELECT SUM(l.debitAmount), SUM(l.creditAmount)
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                WHERE l.companyId = :companyId
                  AND e.postingDate <= :asAtDate
                """ + scopePredicate(scope), Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("asAtDate", asAtDate);
        bindScope(q, scope);
        Object[] row = q.getSingleResult();
        return toBD(row[0]).subtract(toBD(row[1]));
    }

    /**
     * Movement on EQUITY accounts over [fromDate, toDate], company-wide, grouped by account and
     * the journal's source type — the raw material of the Statement of Changes in Equity.
     *
     * @return rows of {@code [accountId (Long), sourceType (JournalSourceType), sumDebit, sumCredit]}
     */
    public List<Object[]> equityMovementBySource(Long companyId, LocalDate fromDate, LocalDate toDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        List<Object[]> rows = em.createQuery("""
                SELECT l.accountId, e.sourceType,
                       SUM(l.debitAmount)  AS d,
                       SUM(l.creditAmount) AS c
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                JOIN ChartOfAccount a ON a.id = l.accountId
                WHERE l.companyId = :companyId
                  AND a.accountType = :equity
                  AND e.postingDate BETWEEN :fromDate AND :toDate
                GROUP BY l.accountId, e.sourceType
                """, Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("equity", AccountType.EQUITY)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new Object[]{r[0], r[1], toBD(r[2]), toBD(r[3])});
        }
        return out;
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
    // Scope / closing predicates — appended to the JPQL above; values are always bound
    // parameters, never inlined.
    // -------------------------------------------------------------------------

    private static String scopePredicate(StatementScope scope) {
        if (scope == null || scope.isCompanyWide()) return "";
        if (scope.unassignedOnly()) return " AND l.branchId IS NULL";
        if (scope.branchIds() != null) {
            // A branch-limited caller's own branches; none assigned reads nothing.
            return scope.branchIds().isEmpty() ? " AND 1 = 0" : " AND l.branchId IN :branchIds";
        }
        return " AND l.branchId = :branchId";
    }

    private static void bindScope(TypedQuery<?> q, StatementScope scope) {
        if (scope != null && scope.branchIds() != null && !scope.branchIds().isEmpty()) {
            q.setParameter("branchIds", scope.branchIds());
        }
        if (scope != null && scope.branchId() != null) {
            q.setParameter("branchId", scope.branchId());
        }
    }

    private static String closingPredicate(boolean excludeClosing) {
        return excludeClosing ? " AND e.sourceType <> :closingType" : "";
    }

    private static void bindClosing(TypedQuery<?> q, boolean excludeClosing) {
        if (excludeClosing) {
            q.setParameter("closingType", JournalSourceType.YEAR_END_CLOSE);
        }
    }

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
