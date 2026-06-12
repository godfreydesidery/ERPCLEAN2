package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.PayrollStatutorySnapshot;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayrollStatutorySnapshotRepository extends JpaRepository<PayrollStatutorySnapshot, Long> {

    Optional<PayrollStatutorySnapshot> findByUid(String uid);

    @Query("SELECT s.companyId FROM PayrollStatutorySnapshot s WHERE s.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Optional<PayrollStatutorySnapshot> findByPayrollLineId(Long payrollLineId);
}
