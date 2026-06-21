package com.erp.modules.budgeting.repository;

import com.erp.modules.budgeting.domain.entity.Budget;
import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    Optional<Budget> findByUid(String uid);

    /** ScopeGuard case "budget" (ADR-0034 D-13). */
    @Query("SELECT b.companyId FROM Budget b WHERE b.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<Budget> findByCompanyId(Long companyId, Pageable pageable);

    Page<Budget> findByCompanyIdAndFiscalYearId(Long companyId, Long fiscalYearId, Pageable pageable);

    /**
     * List budgets for a company where at least one version carries the given status (Defect #4).
     * Distinct to avoid duplicates when multiple versions share the status.
     */
    @Query("""
            SELECT DISTINCT b FROM Budget b
            WHERE b.companyId = :companyId
              AND EXISTS (
                  SELECT 1 FROM BudgetVersion v
                  WHERE v.budgetId = b.id AND v.status = :status)
            """)
    Page<Budget> findByCompanyIdAndVersionStatus(
            @Param("companyId") Long companyId,
            @Param("status") BudgetVersionStatus status,
            Pageable pageable);

    /** Check uniqueness: at most one budget per (company, FY, cost centre) — null-safe guard. */
    @Query("""
            SELECT CASE WHEN COUNT(b) > 0 THEN true ELSE false END
            FROM Budget b
            WHERE b.companyId = :companyId
              AND b.fiscalYearId = :fiscalYearId
              AND ((:ccId IS NULL AND b.costCentreValueId IS NULL)
                   OR b.costCentreValueId = :ccId)
            """)
    boolean existsByCompanyIdAndFiscalYearIdAndCostCentreValueId(
            @Param("companyId") Long companyId,
            @Param("fiscalYearId") Long fiscalYearId,
            @Param("ccId") Long ccId);
}
