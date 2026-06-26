package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.LeaveType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {

    Optional<LeaveType> findByUid(String uid);

    @Query("SELECT l.companyId FROM LeaveType l WHERE l.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<LeaveType> findByCompanyIdAndActiveTrue(Long companyId);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Company-scoped id lookup — used by LeaveServiceImpl to prevent cross-tenant leave-type binding. */
    Optional<LeaveType> findByIdAndCompanyId(Long id, Long companyId);
}
