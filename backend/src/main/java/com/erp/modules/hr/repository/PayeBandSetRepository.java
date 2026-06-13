package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.PayeBandSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayeBandSetRepository extends JpaRepository<PayeBandSet, Long> {

    Optional<PayeBandSet> findByUid(String uid);

    @Query("SELECT p.companyId FROM PayeBandSet p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<PayeBandSet> findByCompanyIdOrderByEffectiveFromDesc(Long companyId);

    /** Resolve the in-force set: latest effective_from <= payDate. */
    @Query("SELECT p FROM PayeBandSet p WHERE p.companyId = :companyId AND p.effectiveFrom <= :payDate " +
           "ORDER BY p.effectiveFrom DESC")
    List<PayeBandSet> findInForce(Long companyId, LocalDate payDate);
}
