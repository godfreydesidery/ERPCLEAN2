package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalPeriodRepository extends JpaRepository<FiscalPeriod, Long> {

    Optional<FiscalPeriod> findByUid(String uid);

    Optional<FiscalPeriod> findByCompanyIdAndUid(Long companyId, String uid);

    List<FiscalPeriod> findByFiscalYearIdOrderByPeriodNo(Long fiscalYearId);

    List<FiscalPeriod> findByCompanyIdOrderByStartDateAsc(Long companyId);

    /**
     * Resolves the OPEN fiscal period that covers the given posting date (ADR-0013 D-4/D-3).
     * Hits ix_fiscal_periods_company_dates. Returns at most one (periods are non-overlapping).
     */
    @Query("""
            SELECT p FROM FiscalPeriod p
            WHERE p.companyId = :companyId
              AND p.status = :status
              AND p.startDate <= :date
              AND p.endDate   >= :date
            """)
    Optional<FiscalPeriod> findOpenPeriodForDate(@Param("companyId") Long companyId,
                                                  @Param("date") LocalDate date,
                                                  @Param("status") PeriodStatus status);

    /** Single-column projection for ScopeGuard case "fiscalperiod" (ADR-0013 D-10). */
    @Query("SELECT p.companyId FROM FiscalPeriod p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
