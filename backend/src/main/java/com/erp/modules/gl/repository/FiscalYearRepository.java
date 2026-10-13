package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.FiscalYear;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FiscalYearRepository extends JpaRepository<FiscalYear, Long> {

    Optional<FiscalYear> findByUid(String uid);

    Optional<FiscalYear> findByCompanyIdAndYearCode(Long companyId, String yearCode);

    /** Company-scoped by-id lookup (the tenant-scoping rule forbids a bare findById in services). */
    Optional<FiscalYear> findByCompanyIdAndId(Long companyId, Long id);

    List<FiscalYear> findByCompanyIdOrderByStartDateDesc(Long companyId);

    /**
     * The company's fiscal years whose [startDate, endDate] intersects [start, end] (ACC-09).
     * Fiscal years must not overlap: two OPEN periods covering one date make every posting on that
     * date ambiguous.
     */
    @Query("""
            SELECT y FROM FiscalYear y
            WHERE y.companyId = :companyId
              AND y.startDate <= :end
              AND y.endDate   >= :start
            ORDER BY y.startDate ASC
            """)
    List<FiscalYear> findOverlapping(@Param("companyId") Long companyId,
                                     @Param("start") LocalDate start,
                                     @Param("end") LocalDate end);

    @Query("SELECT y.companyId FROM FiscalYear y WHERE y.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
