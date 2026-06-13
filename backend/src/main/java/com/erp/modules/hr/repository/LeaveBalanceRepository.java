package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.LeaveBalance;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, Long> {

    Optional<LeaveBalance> findByUid(String uid);

    @Query("SELECT b.companyId FROM LeaveBalance b WHERE b.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<LeaveBalance> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    Optional<LeaveBalance> findByCompanyIdAndEmployeeIdAndLeaveTypeIdAndAsOfYear(
            Long companyId, Long employeeId, Long leaveTypeId, Short asOfYear);
}
