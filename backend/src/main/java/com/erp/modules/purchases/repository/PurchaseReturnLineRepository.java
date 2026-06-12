package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseReturnLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseReturnLineRepository extends JpaRepository<PurchaseReturnLine, Long> {

    List<PurchaseReturnLine> findByPurchaseReturnIdOrderByLineNo(Long purchaseReturnId);

    Optional<PurchaseReturnLine> findByUidAndPurchaseReturnId(String uid, Long purchaseReturnId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM PurchaseReturnLine l WHERE l.purchaseReturnId = :returnId")
    int findMaxLineNo(@Param("returnId") Long returnId);

    /** Total returned qty in base for a specific GR line across all confirmed returns. */
    @Query("""
            SELECT COALESCE(SUM(l.returnedQtyInBase), 0)
            FROM PurchaseReturnLine l
            JOIN PurchaseReturn r ON l.purchaseReturnId = r.id
            WHERE l.goodsReceiptLineId = :grLineId
              AND r.status = 'CONFIRMED'
            """)
    BigDecimal sumReturnedQtyInBaseForGrLine(@Param("grLineId") Long grLineId);
}
