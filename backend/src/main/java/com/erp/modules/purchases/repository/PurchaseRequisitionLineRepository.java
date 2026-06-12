package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseRequisitionLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseRequisitionLineRepository extends JpaRepository<PurchaseRequisitionLine, Long> {

    List<PurchaseRequisitionLine> findByPurchaseRequisitionIdOrderByLineNo(Long requisitionId);

    Optional<PurchaseRequisitionLine> findByUidAndPurchaseRequisitionId(String uid,
                                                                         Long requisitionId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM PurchaseRequisitionLine l WHERE l.purchaseRequisitionId = :reqId")
    int findMaxLineNo(@Param("reqId") Long reqId);
}
