package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {

    /** All lines for a given PO, ordered for display. */
    List<PurchaseOrderLine> findByPurchaseOrderIdOrderByLineNo(Long purchaseOrderId);

    /** Line scoped under its parent PO (F16 child-by-parent pattern, ADR-0011 D-12). */
    @Query("""
            SELECT l FROM PurchaseOrderLine l
            WHERE l.uid = :uid AND l.purchaseOrder.id = :purchaseOrderId
            """)
    Optional<PurchaseOrderLine> findByUidAndPurchaseOrderId(@Param("uid") String uid,
                                                             @Param("purchaseOrderId") Long purchaseOrderId);

    /** Max line_no for a given PO (to compute the next sequential ordinal). */
    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM PurchaseOrderLine l WHERE l.purchaseOrder.id = :purchaseOrderId")
    int findMaxLineNo(@Param("purchaseOrderId") Long purchaseOrderId);
}
