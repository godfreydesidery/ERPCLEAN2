package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.EmployeeRecurringItem;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRecurringItemRepository extends JpaRepository<EmployeeRecurringItem, Long> {

    Optional<EmployeeRecurringItem> findByUid(String uid);

    @Query("SELECT i.companyId FROM EmployeeRecurringItem i WHERE i.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<EmployeeRecurringItem> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);

    @Query("SELECT i FROM EmployeeRecurringItem i WHERE i.companyId = :companyId AND i.employeeId = :employeeId " +
           "AND i.effectiveFrom <= :payDate AND (i.effectiveTo IS NULL OR i.effectiveTo >= :payDate)")
    List<EmployeeRecurringItem> findActiveForEmployee(Long companyId, Long employeeId, LocalDate payDate);
}
