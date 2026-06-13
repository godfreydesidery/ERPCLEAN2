package com.erp.modules.budgeting.repository;

import com.erp.modules.budgeting.domain.entity.BudgetLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetLineRepository extends JpaRepository<BudgetLine, Long> {

    Optional<BudgetLine> findByUid(String uid);

    List<BudgetLine> findByBudgetVersionIdOrderByFiscalPeriodId(Long budgetVersionId);

    Optional<BudgetLine> findByBudgetVersionIdAndAccountIdAndFiscalPeriodId(
            Long budgetVersionId, Long accountId, Long fiscalPeriodId);

    /** Delete all lines for a version — used in seed-and-replace flows. */
    @Modifying
    @Query("DELETE FROM BudgetLine l WHERE l.budgetVersionId = :versionId")
    void deleteByBudgetVersionId(@Param("versionId") Long versionId);

    /** Sum of amounts for a version and account (variance budget side). */
    @Query("""
            SELECT COALESCE(SUM(l.amount), 0)
            FROM BudgetLine l
            WHERE l.budgetVersionId = :versionId
              AND l.accountId = :accountId
              AND l.fiscalPeriodId IN :periodIds
            """)
    java.math.BigDecimal sumAmountByVersionAccountPeriods(
            @Param("versionId") Long versionId,
            @Param("accountId") Long accountId,
            @Param("periodIds") List<Long> periodIds);

    /** All lines for a version in a given period set (variance budget side — account-aggregate). */
    @Query("""
            SELECT l FROM BudgetLine l
            WHERE l.budgetVersionId = :versionId
              AND l.fiscalPeriodId IN :periodIds
            """)
    List<BudgetLine> findByVersionAndPeriods(
            @Param("versionId") Long versionId,
            @Param("periodIds") List<Long> periodIds);

    /** Check if a version has at least one line (submit guard, BR-BUD-11). */
    @Query("SELECT COUNT(l) > 0 FROM BudgetLine l WHERE l.budgetVersionId = :versionId")
    boolean existsByBudgetVersionId(@Param("versionId") Long versionId);
}
