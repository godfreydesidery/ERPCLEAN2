package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, Long> {

    /** All lines for a given GR, ordered for display. */
    List<GoodsReceiptLine> findByGoodsReceiptIdOrderByLineNo(Long goodsReceiptId);

    Optional<GoodsReceiptLine> findByUid(String uid);

    /** GR line scoped under its parent GR (F16 child-by-parent, ADR-0011 D-12). */
    @Query("""
            SELECT l FROM GoodsReceiptLine l
            WHERE l.uid = :uid AND l.goodsReceipt.id = :goodsReceiptId
            """)
    Optional<GoodsReceiptLine> findByUidAndGoodsReceiptId(@Param("uid") String uid,
                                                           @Param("goodsReceiptId") Long goodsReceiptId);

    /** Max line_no for a given GR. */
    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM GoodsReceiptLine l WHERE l.goodsReceipt.id = :goodsReceiptId")
    int findMaxLineNo(@Param("goodsReceiptId") Long goodsReceiptId);

    /**
     * All non-void GR lines for a specific PO line — used for outstanding-reconciliation assertions
     * (ADR-0011 D-3, NFR-PURCH-07: received_qty_in_base == Σ non-void GR lines' qty_in_base).
     */
    @Query("""
            SELECT l FROM GoodsReceiptLine l
            JOIN l.goodsReceipt gr
            WHERE l.purchaseOrderLineId = :poLineId
              AND gr.status <> 'VOID'
            """)
    List<GoodsReceiptLine> findNonVoidByPurchaseOrderLineId(@Param("poLineId") Long poLineId);
}
