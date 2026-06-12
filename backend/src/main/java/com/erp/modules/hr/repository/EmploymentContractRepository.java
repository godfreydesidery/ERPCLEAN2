package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.EmploymentContract;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmploymentContractRepository extends JpaRepository<EmploymentContract, Long> {

    Optional<EmploymentContract> findByUid(String uid);

    @Query("SELECT c.companyId FROM EmploymentContract c WHERE c.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    Optional<EmploymentContract> findByCompanyIdAndEmployeeIdAndActiveTrue(Long companyId, Long employeeId);

    List<EmploymentContract> findByCompanyIdAndEmployeeId(Long companyId, Long employeeId);
}
