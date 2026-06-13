package com.erp.modules.manufacturing.repository;

import com.erp.modules.manufacturing.domain.entity.WorkOrderComponent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderComponentRepository extends JpaRepository<WorkOrderComponent, Long> {

    Optional<WorkOrderComponent> findByUid(String uid);

    /** ScopeGuard target-type resolution (D-13). */
    @Query("SELECT c.companyId FROM WorkOrderComponent c WHERE c.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<WorkOrderComponent> findByWorkOrderIdOrderByLineNoAsc(Long workOrderId);

    Optional<WorkOrderComponent> findByWorkOrderIdAndUid(Long workOrderId, String uid);
}
