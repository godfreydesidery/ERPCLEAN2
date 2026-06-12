package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.PayrollRun;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, Long> {

    Optional<PayrollRun> findByUid(String uid);

    @Query("SELECT r.companyId FROM PayrollRun r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Page<PayrollRun> findByCompanyId(Long companyId, Pageable pageable);

    List<PayrollRun> findByCompanyIdAndStatus(Long companyId, PayrollRunStatus status);

    Optional<PayrollRun> findByRunNumber(String runNumber);

    /** Check for an active (non-reversed) run for the same period (by year+month). */
    @Query("SELECT r FROM PayrollRun r WHERE r.companyId = :companyId AND r.periodYear = :periodYear " +
           "AND r.periodMonth = :periodMonth AND r.status <> com.erp.modules.hr.domain.enums.PayrollRunStatus.REVERSED")
    List<PayrollRun> findActiveForPeriod(Long companyId, short periodYear, short periodMonth);
}
