package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.EmployeeLoanInstallment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeLoanInstallmentRepository extends JpaRepository<EmployeeLoanInstallment, Long> {

    Optional<EmployeeLoanInstallment> findByUid(String uid);

    @Query("SELECT i.companyId FROM EmployeeLoanInstallment i WHERE i.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<EmployeeLoanInstallment> findByEmployeeLoanIdOrderByInstallmentNoAsc(Long employeeLoanId);

    @Query("SELECT i FROM EmployeeLoanInstallment i WHERE i.employeeLoanId = :employeeLoanId " +
           "AND i.status = com.erp.modules.hr.domain.enums.LoanInstallmentStatus.PENDING " +
           "ORDER BY i.installmentNo ASC")
    List<EmployeeLoanInstallment> findUnpaidByLoanId(Long employeeLoanId);
}
