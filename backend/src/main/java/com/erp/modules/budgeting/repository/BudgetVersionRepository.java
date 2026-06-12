package com.erp.modules.budgeting.repository;

import com.erp.modules.budgeting.domain.entity.BudgetVersion;
import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface BudgetVersionRepository extends JpaRepository<BudgetVersion, Long> {

    Optional<BudgetVersion> findByUid(String uid);

    /** ScopeGuard case "budgetversion" (ADR-0034 D-13). */
    @Query("SELECT v.companyId FROM BudgetVersion v WHERE v.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<BudgetVersion> findByBudgetIdOrderByVersionNoDesc(Long budgetId);

    Page<BudgetVersion> findByBudgetId(Long budgetId, Pageable pageable);

    /** Next version ordinal within a budget. */
    @Query("SELECT COALESCE(MAX(v.versionNo), 0) FROM BudgetVersion v WHERE v.budgetId = :budgetId")
    int findMaxVersionNo(@Param("budgetId") Long budgetId);

    /**
     * Find the current APPROVED version for a (company, FY, cost centre), FOR UPDATE
     * (serialised supersede — ADR-0034 D-5). Uses PESSIMISTIC_WRITE so the supersede TX
     * exclusively locks the prior approved version before setting it SUPERSEDED.
     * Null-safe: a null costCentreValueId (company-wide) is handled by a separate partial unique.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT v FROM BudgetVersion v
            WHERE v.companyId = :companyId
              AND v.fiscalYearId = :fiscalYearId
              AND v.status = :status
              AND ((:ccId IS NULL AND v.costCentreValueId IS NULL)
                   OR v.costCentreValueId = :ccId)
            """)
    Optional<BudgetVersion> findApprovedForUpdate(
            @Param("companyId") Long companyId,
            @Param("fiscalYearId") Long fiscalYearId,
            @Param("ccId") Long ccId,
            @Param("status") BudgetVersionStatus status);

    /**
     * Find the active APPROVED version for variance read (no lock — read-only path).
     */
    @Query("""
            SELECT v FROM BudgetVersion v
            WHERE v.companyId = :companyId
              AND v.fiscalYearId = :fiscalYearId
              AND v.status = 'APPROVED'
              AND ((:ccId IS NULL AND v.costCentreValueId IS NULL)
                   OR v.costCentreValueId = :ccId)
            """)
    Optional<BudgetVersion> findApproved(
            @Param("companyId") Long companyId,
            @Param("fiscalYearId") Long fiscalYearId,
            @Param("ccId") Long ccId);
}
