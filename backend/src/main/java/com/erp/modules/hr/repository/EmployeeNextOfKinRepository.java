package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.EmployeeNextOfKin;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeNextOfKinRepository extends JpaRepository<EmployeeNextOfKin, Long> {

    Optional<EmployeeNextOfKin> findByUid(String uid);

    List<EmployeeNextOfKin> findByEmployeeId(Long employeeId);

    boolean existsByEmployeeIdAndIsPrimaryTrue(Long employeeId);
}
