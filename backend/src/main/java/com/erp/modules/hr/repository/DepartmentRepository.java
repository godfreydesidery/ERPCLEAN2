package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.Department;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    Optional<Department> findByUid(String uid);

    @Query("SELECT d.companyId FROM Department d WHERE d.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<Department> findByCompanyIdAndActiveTrue(Long companyId);

    List<Department> findByCompanyId(Long companyId);

    boolean existsByCompanyIdAndCode(Long companyId, String code);
}
