package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.enums.EmploymentStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByUid(String uid);

    @Query("SELECT e.companyId FROM Employee e WHERE e.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Page<Employee> findByCompanyId(Long companyId, Pageable pageable);

    List<Employee> findByCompanyIdAndStatus(Long companyId, EmploymentStatus status);

    Optional<Employee> findByCompanyIdAndUserId(Long companyId, Long userId);

    boolean existsByCompanyIdAndEmployeeNumber(Long companyId, String employeeNumber);

    long countByCompanyId(Long companyId);
}
