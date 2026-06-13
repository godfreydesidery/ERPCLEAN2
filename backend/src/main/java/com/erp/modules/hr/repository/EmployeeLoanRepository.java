package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.EmployeeLoan;
import com.erp.modules.hr.domain.enums.LoanStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeLoanRepository extends JpaRepository<EmployeeLoan, Long> {

    Optional<EmployeeLoan> findByUid(String uid);

    @Query("SELECT l.companyId FROM EmployeeLoan l WHERE l.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Page<EmployeeLoan> findByCompanyId(Long companyId, Pageable pageable);

    List<EmployeeLoan> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    List<EmployeeLoan> findByCompanyIdAndStatus(Long companyId, LoanStatus status);

    /** Active loans that still have an outstanding balance for deduction in payroll. */
    @Query("SELECT l FROM EmployeeLoan l WHERE l.companyId = :companyId AND l.employeeId = :employeeId " +
           "AND l.status = com.erp.modules.hr.domain.enums.LoanStatus.ACTIVE AND l.outstandingAmount > 0")
    List<EmployeeLoan> findActiveWithBalance(Long companyId, Long employeeId);
}
