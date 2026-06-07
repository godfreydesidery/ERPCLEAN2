package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.GoodsReceipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {

    Optional<GoodsReceipt> findByUid(String uid);

    Optional<GoodsReceipt> findByCompanyIdAndUid(Long companyId, String uid);

    Page<GoodsReceipt> findByCompanyId(Long companyId, Pageable pageable);

    /** All GRs against a specific PO (used for void-check: "PO has non-void GRs"). */
    List<GoodsReceipt> findByPurchaseOrderId(Long purchaseOrderId);

    @Query("""
            SELECT g FROM GoodsReceipt g
            WHERE g.companyId = :companyId
              AND (:q IS NULL OR g.receiptNumber = :q)
            """)
    Page<GoodsReceipt> search(@Param("companyId") Long companyId,
                               @Param("q") String q,
                               Pageable pageable);

    /** ScopeGuard target-type projection (ADR-0011 D-10). */
    @Query("SELECT g.companyId FROM GoodsReceipt g WHERE g.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
