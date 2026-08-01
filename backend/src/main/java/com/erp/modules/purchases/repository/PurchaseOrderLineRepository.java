package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    /**
     * Most recent line this company ACTUALLY ordered from a supplier for a product/unit — the
     * last-purchased-price source behind a PO-line cost suggestion (SAM client feedback 2026-08).
     *
     * <p>Only placed orders count: DRAFT is a price nobody committed to and VOID is a price that was
     * taken back, so both are excluded — which also guarantees {@code orderedAt} is set (it is
     * stamped in {@code placeOrder}) and the ordering is well defined. Matches on unit_id so a cost
     * per carton is never suggested for a line ordered per piece.
     *
     * <p>Caller passes a one-row Pageable; the id tiebreaker keeps the ordering total.
     */
    @Query("""
            SELECT l FROM PurchaseOrderLine l
            WHERE l.companyId = :companyId
              AND l.purchaseOrder.supplierId = :supplierId
              AND l.productId = :productId
              AND l.unitId    = :unitId
              AND l.purchaseOrder.status NOT IN ('DRAFT','VOID')
            ORDER BY l.purchaseOrder.orderedAt DESC, l.id DESC
            """)
    List<PurchaseOrderLine> findLastPurchasedLine(@Param("companyId") Long companyId,
                                                   @Param("supplierId") Long supplierId,
                                                   @Param("productId") Long productId,
                                                   @Param("unitId") Long unitId,
                                                   Pageable pageable);
}
