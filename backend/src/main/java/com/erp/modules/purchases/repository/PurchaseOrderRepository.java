package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByUid(String uid);

    Optional<PurchaseOrder> findByCompanyIdAndUid(Long companyId, String uid);

    Page<PurchaseOrder> findByCompanyId(Long companyId, Pageable pageable);

    Page<PurchaseOrder> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    List<PurchaseOrder> findByCompanyIdAndStatusIn(Long companyId, List<PurchaseOrderStatus> statuses);

    @Query("""
            SELECT p FROM PurchaseOrder p
            WHERE p.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(p.supplierName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR p.orderNumber = :q
                   OR p.supplierCode = :q)
            """)
    Page<PurchaseOrder> search(@Param("companyId") Long companyId,
                               @Param("q") String q,
                               Pageable pageable);

    /** ScopeGuard target-type projection (ADR-0011 D-10). */
    @Query("SELECT p.companyId FROM PurchaseOrder p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
