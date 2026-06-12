package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.Payslip;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayslipRepository extends JpaRepository<Payslip, Long> {

    Optional<Payslip> findByUid(String uid);

    @Query("SELECT p.companyId FROM Payslip p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Page<Payslip> findByCompanyId(Long companyId, Pageable pageable);

    List<Payslip> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    Optional<Payslip> findByPayrollRunIdAndEmployeeId(Long payrollRunId, Long employeeId);

    List<Payslip> findByPayrollRunId(Long payrollRunId);
}
