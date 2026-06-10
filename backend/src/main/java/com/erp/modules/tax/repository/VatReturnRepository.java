package com.erp.modules.tax.repository;

import com.erp.modules.tax.domain.entity.VatReturn;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VatReturnRepository extends JpaRepository<VatReturn, Long> {

    Optional<VatReturn> findByUid(String uid);

    /** ScopeGuard projection (ADR-0017 D-11). */
    @Query("SELECT r.companyId FROM VatReturn r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<VatReturn> findByCompanyIdAndUid(Long companyId, String uid);

    Page<VatReturn> findByCompanyId(Long companyId, Pageable pageable);

    /** One-per-month guard check (BR-VAT-01). */
    boolean existsByCompanyIdAndPeriodYearAndPeriodMonth(Long companyId, short periodYear, short periodMonth);

    /** Resolve the immediately prior FILED return for carry-forward (D-4). */
    @Query("""
            SELECT r FROM VatReturn r
            WHERE r.companyId = :companyId
              AND r.status = 'FILED'
              AND (r.periodYear < :year
                   OR (r.periodYear = :year AND r.periodMonth < :month))
            ORDER BY r.periodYear DESC, r.periodMonth DESC
            LIMIT 1
            """)
    Optional<VatReturn> findLatestFiledBefore(@Param("companyId") Long companyId,
                                              @Param("year") short year,
                                              @Param("month") short month);

    /** Find the immediately prior calendar period's return (any status) for "prior must be FILED" check. */
    @Query("""
            SELECT r FROM VatReturn r
            WHERE r.companyId = :companyId
              AND (r.periodYear < :year
                   OR (r.periodYear = :year AND r.periodMonth < :month))
            ORDER BY r.periodYear DESC, r.periodMonth DESC
            LIMIT 1
            """)
    Optional<VatReturn> findLatestBefore(@Param("companyId") Long companyId,
                                         @Param("year") short year,
                                         @Param("month") short month);

    Page<VatReturn> findByCompanyIdAndStatus(Long companyId, VatReturnStatus status, Pageable pageable);
}
