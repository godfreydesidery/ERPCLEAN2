package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.PayrollLine;
import com.erp.modules.hr.domain.enums.PayrollLineStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayrollLineRepository extends JpaRepository<PayrollLine, Long> {

    Optional<PayrollLine> findByUid(String uid);

    @Query("SELECT l.companyId FROM PayrollLine l WHERE l.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<PayrollLine> findByPayrollRunIdOrderByEmployeeIdAsc(Long payrollRunId);

    List<PayrollLine> findByPayrollRunIdAndStatus(Long payrollRunId, PayrollLineStatus status);

    Optional<PayrollLine> findByPayrollRunIdAndEmployeeId(Long payrollRunId, Long employeeId);
}
