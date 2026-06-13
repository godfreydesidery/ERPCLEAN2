package com.erp.modules.manufacturing.repository;

import com.erp.modules.manufacturing.domain.entity.WorkOrder;
import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long> {

    Optional<WorkOrder> findByUid(String uid);

    /** ScopeGuard target-type resolution (D-13). */
    @Query("SELECT wo.companyId FROM WorkOrder wo WHERE wo.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<WorkOrder> findByCompanyIdAndWoNumber(Long companyId, String woNumber);

    Page<WorkOrder> findByCompanyId(Long companyId, Pageable pageable);

    Page<WorkOrder> findByCompanyIdAndStatus(Long companyId, WorkOrderStatus status, Pageable pageable);

    Page<WorkOrder> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    Page<WorkOrder> findByCompanyIdAndFinishedProductId(Long companyId, Long productId, Pageable pageable);

    /** Σ open-order WIP for the WIP reconciliation (FR-MFG-14, D-6). Uses the ix_work_orders_open index. */
    @Query(value = """
            SELECT COALESCE(SUM(wo.wip_debit_total - wo.wip_credit_total), 0)
            FROM work_orders wo
            WHERE wo.company_id = :companyId
              AND wo.status IN ('RELEASED','IN_PROGRESS','COMPLETED')
            """, nativeQuery = true)
    java.math.BigDecimal sumOpenWip(@Param("companyId") Long companyId);

    /** All open orders for a company — used by WIP recon + cancel-at-close guard. */
    List<WorkOrder> findByCompanyIdAndStatusIn(Long companyId, List<WorkOrderStatus> statuses);
}
