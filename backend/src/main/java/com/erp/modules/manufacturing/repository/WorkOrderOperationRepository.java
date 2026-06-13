package com.erp.modules.manufacturing.repository;

import com.erp.modules.manufacturing.domain.entity.WorkOrderOperation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderOperationRepository extends JpaRepository<WorkOrderOperation, Long> {

    Optional<WorkOrderOperation> findByUid(String uid);

    /** ScopeGuard target-type resolution (D-13). */
    @Query("SELECT op.companyId FROM WorkOrderOperation op WHERE op.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<WorkOrderOperation> findByWorkOrderIdOrderBySeqNoAsc(Long workOrderId);

    Optional<WorkOrderOperation> findByWorkOrderIdAndUid(Long workOrderId, String uid);

    /** Find unapplied operations for a work order (for cancel reversal path). */
    @Query("SELECT op FROM WorkOrderOperation op WHERE op.workOrderId = :woId AND op.applied = true")
    List<WorkOrderOperation> findAppliedByWorkOrderId(@Param("woId") Long workOrderId);
}
