package com.erp.modules.fixedassets.repository;

import com.erp.modules.fixedassets.domain.entity.DepreciationRun;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepreciationRunRepository extends JpaRepository<DepreciationRun, Long> {

    Optional<DepreciationRun> findByUid(String uid);

    Page<DepreciationRun> findByCompanyId(Long companyId, Pageable pageable);

    /** Idempotency check — at most one run per (company, fiscal period). */
    Optional<DepreciationRun> findByCompanyIdAndFiscalPeriodId(Long companyId, Long fiscalPeriodId);

    @Query("SELECT dr.companyId FROM DepreciationRun dr WHERE dr.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
