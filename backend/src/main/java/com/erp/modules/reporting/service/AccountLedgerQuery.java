package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.AccountLedgerRowDto;
import com.erp.modules.reporting.domain.dto.StatementHeaderDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paginated running-balance account ledger for one GL account over a period (ADR-0018 D-3(d)).
 * The one inherently row-by-row read — paginated to bound memory (NFR-REP-02).
 * Uses EntityManager directly for all JPQL so no unused-repo field exists.
 */
@Component
@Transactional(readOnly = true)
public class AccountLedgerQuery {

    private static final String P_COMPANY  = "companyId";
    private static final String P_ACCOUNT  = "accountId";
    private static final String P_FROM     = "fromDate";
    private static final String P_TO       = "toDate";

    private final EntityManager            em;
    private final ChartOfAccountRepository accountRepo;
    private final AccountMovementQuery     movementQuery;
    private final ScopeGuard              scopeGuard;

    public AccountLedgerQuery(EntityManager em,
                               ChartOfAccountRepository accountRepo,
                               AccountMovementQuery movementQuery,
                               ScopeGuard scopeGuard) {
        this.em            = em;
        this.accountRepo   = accountRepo;
        this.movementQuery = movementQuery;
        this.scopeGuard    = scopeGuard;
    }

    /**
     * Returns the paginated account ledger for {@code accountUid} over [{@code fromDate},
     * {@code toDate}]. Opening balance = cumulative net-debit as-at {@code fromDate − 1}.
     * Running balance is computed incrementally; prior-pages sum is fetched once per request.
     */
    public AccountLedgerDto query(Long companyId,
                                   String accountUid,
                                   LocalDate fromDate,
                                   LocalDate toDate,
                                   int page,
                                   int size) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        ChartOfAccount account = Lookups.orNotFound(
                accountRepo.findByUid(accountUid), "ChartOfAccount", accountUid);

        if (!account.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account " + accountUid + " not found in company " + companyId);
        }

        Long accountId = account.getId();

        // Opening balance: cumulative net-debit as-at fromDate − 1
        LocalDate priorDay = fromDate.minusDays(1);
        Map<Long, BigDecimal[]> priorCumul = movementQuery.cumulativeByAccountAsAt(companyId, priorDay);
        BigDecimal[] priorRow  = priorCumul.getOrDefault(accountId, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal openingBal  = priorRow[0].subtract(priorRow[1]);

        // Closing balance: cumulative net-debit as-at toDate
        Map<Long, BigDecimal[]> closingCumul = movementQuery.cumulativeByAccountAsAt(companyId, toDate);
        BigDecimal[] closingRow = closingCumul.getOrDefault(accountId, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal closingBal   = closingRow[0].subtract(closingRow[1]);

        // Total line count in period
        Long totalCount = em.createQuery("""
                SELECT COUNT(l)
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                WHERE l.companyId  = :companyId
                  AND l.accountId  = :accountId
                  AND e.postingDate BETWEEN :fromDate AND :toDate
                """, Long.class)
                .setParameter(P_COMPANY, companyId)
                .setParameter(P_ACCOUNT, accountId)
                .setParameter(P_FROM, fromDate)
                .setParameter(P_TO, toDate)
                .getSingleResult();

        // Running balance up to (but not including) this page's first row
        BigDecimal runningBalance = openingBal;
        if (page > 0) {
            Object[] priorTotals = (Object[]) em.createQuery("""
                    SELECT SUM(l.debitAmount), SUM(l.creditAmount)
                    FROM JournalLine l
                    JOIN JournalEntry e ON e.id = l.entryId
                    WHERE l.companyId  = :companyId
                      AND l.accountId  = :accountId
                      AND e.postingDate BETWEEN :fromDate AND :toDate
                    """)
                    .setParameter(P_COMPANY, companyId)
                    .setParameter(P_ACCOUNT, accountId)
                    .setParameter(P_FROM, fromDate)
                    .setParameter(P_TO, toDate)
                    .setMaxResults(page * size)
                    .getSingleResult();
            BigDecimal priorD = AccountMovementQuery.toBD(priorTotals[0]);
            BigDecimal priorC = AccountMovementQuery.toBD(priorTotals[1]);
            runningBalance = runningBalance.add(priorD).subtract(priorC);
        }

        // This page's rows — ordered by date, entry sequence, line number
        List<Object[]> lineRows = em.createQuery("""
                SELECT l.debitAmount, l.creditAmount, l.lineMemo,
                       e.postingDate, e.sourceType, e.sourceRef, e.uid
                FROM JournalLine l
                JOIN JournalEntry e ON e.id = l.entryId
                WHERE l.companyId  = :companyId
                  AND l.accountId  = :accountId
                  AND e.postingDate BETWEEN :fromDate AND :toDate
                ORDER BY e.postingDate ASC, e.entryNo ASC, l.lineNo ASC
                """, Object[].class)
                .setParameter(P_COMPANY, companyId)
                .setParameter(P_ACCOUNT, accountId)
                .setParameter(P_FROM, fromDate)
                .setParameter(P_TO, toDate)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();

        List<AccountLedgerRowDto> rows = new ArrayList<>(lineRows.size());
        for (Object[] r : lineRows) {
            BigDecimal debit  = AccountMovementQuery.toBD(r[0]);
            BigDecimal credit = AccountMovementQuery.toBD(r[1]);
            runningBalance = runningBalance.add(debit).subtract(credit);
            rows.add(new AccountLedgerRowDto(
                    (LocalDate) r[3],
                    r[4] != null ? r[4].toString() : null,
                    (String) r[5],
                    (String) r[6],
                    (String) r[2],
                    debit,
                    credit,
                    runningBalance));
        }

        StatementHeaderDto header = new StatementHeaderDto(
                companyId, null, null,
                fromDate + " – " + toDate, null,
                fromDate, toDate, null,
                Instant.now());

        return new AccountLedgerDto(
                header,
                accountId, account.getUid(), account.getAccountCode(), account.getName(),
                openingBal,
                rows,
                closingBal,
                page, size, totalCount);
    }
}
