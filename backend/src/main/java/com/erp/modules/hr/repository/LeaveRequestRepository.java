package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.LeaveRequest;
import com.erp.modules.hr.domain.enums.LeaveRequestStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    Optional<LeaveRequest> findByUid(String uid);

    @Query("SELECT r.companyId FROM LeaveRequest r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Page<LeaveRequest> findByCompanyId(Long companyId, Pageable pageable);

    List<LeaveRequest> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    List<LeaveRequest> findByCompanyIdAndStatus(Long companyId, LeaveRequestStatus status);
}
