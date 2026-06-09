package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.JournalLine;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalLineRepository extends JpaRepository<JournalLine, Long> {

    List<JournalLine> findByEntryIdOrderByLineNo(Long entryId);

    /**
     * Trial-balance aggregate: GROUP BY account, SUM(debit) - SUM(credit) per company
     * (ADR-0013 D-8, FR-GL-16). Hits ix_journal_lines_company_account.
     * Returns Object[] rows: [accountId, totalDebit, totalCredit].
     */
    @Query("""
            SELECT l.accountId,
                   SUM(l.debitAmount)  AS totalDebit,
                   SUM(l.creditAmount) AS totalCredit
            FROM JournalLine l
            WHERE l.companyId = :companyId
            GROUP BY l.accountId
            """)
    List<Object[]> trialBalanceSums(@Param("companyId") Long companyId);

    /**
     * Trial-balance aggregate filtered to a single fiscal period.
     * Joins journal_entries to filter by fiscal_period_id.
     */
    @Query("""
            SELECT l.accountId,
                   SUM(l.debitAmount)  AS totalDebit,
                   SUM(l.creditAmount) AS totalCredit
            FROM JournalLine l
            JOIN JournalEntry e ON e.id = l.entryId
            WHERE l.companyId = :companyId
              AND e.fiscalPeriodId = :periodId
            GROUP BY l.accountId
            """)
    List<Object[]> trialBalanceSumsByPeriod(@Param("companyId") Long companyId,
                                             @Param("periodId") Long periodId);

    /** Grand total debit and credit for a company — used to assert TB nets to zero. */
    @Query("""
            SELECT SUM(l.debitAmount), SUM(l.creditAmount)
            FROM JournalLine l
            WHERE l.companyId = :companyId
            """)
    Object[] grandTotals(@Param("companyId") Long companyId);

    /**
     * Net balance of a single GL account: SUM(debit) - SUM(credit).
     * Returns null when no lines exist — callers treat null as zero.
     * Used by CashGlReconciliationQuery (ADR-0016 D-9).
     */
    @Query("""
            SELECT SUM(l.debitAmount) - SUM(l.creditAmount)
            FROM JournalLine l
            WHERE l.companyId = :companyId
              AND l.accountId = :accountId
            """)
    BigDecimal accountBalance(@Param("companyId") Long companyId, @Param("accountId") Long accountId);
}
