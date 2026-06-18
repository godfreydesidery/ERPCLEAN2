package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.StatutoryRateSet;
import com.erp.modules.hr.domain.enums.StatutoryRateType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StatutoryRateSetRepository extends JpaRepository<StatutoryRateSet, Long> {

    Optional<StatutoryRateSet> findByUid(String uid);

    @Query("SELECT s.companyId FROM StatutoryRateSet s WHERE s.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<StatutoryRateSet> findByCompanyIdAndRateTypeOrderByEffectiveFromDesc(Long companyId, StatutoryRateType rateType);

    List<StatutoryRateSet> findByCompanyIdOrderByRateTypeAscEffectiveFromDesc(Long companyId);

    /** Duplicate-guard: one effective-dated set per company+rateType+effectiveFrom (issue #26). */
    boolean existsByCompanyIdAndRateTypeAndEffectiveFrom(Long companyId, StatutoryRateType rateType, LocalDate effectiveFrom);

    /** Resolve the in-force set for a given type: latest effective_from <= payDate. */
    @Query("SELECT s FROM StatutoryRateSet s WHERE s.companyId = :companyId AND s.rateType = :rateType " +
           "AND s.effectiveFrom <= :payDate ORDER BY s.effectiveFrom DESC")
    List<StatutoryRateSet> findInForce(Long companyId, StatutoryRateType rateType, LocalDate payDate);
}
