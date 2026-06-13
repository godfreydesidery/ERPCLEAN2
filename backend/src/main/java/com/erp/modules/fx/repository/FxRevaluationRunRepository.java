package com.erp.modules.fx.repository;

import com.erp.modules.fx.domain.entity.FxRevaluationRun;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FxRevaluationRunRepository extends JpaRepository<FxRevaluationRun, Long> {

    Optional<FxRevaluationRun> findByUid(String uid);

    Page<FxRevaluationRun> findByCompanyId(Long companyId, Pageable pageable);

    /** Idempotency check (ADR-0036 D-6) — at most one run per (company, fiscal period). */
    Optional<FxRevaluationRun> findByCompanyIdAndFiscalPeriodId(Long companyId, Long fiscalPeriodId);

    @Query("SELECT r.companyId FROM FxRevaluationRun r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
