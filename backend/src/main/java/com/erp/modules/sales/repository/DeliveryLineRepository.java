package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.DeliveryLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryLineRepository extends JpaRepository<DeliveryLine, Long> {

    Optional<DeliveryLine> findByUid(String uid);

    List<DeliveryLine> findByDeliveryIdOrderByLineNo(Long deliveryId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM DeliveryLine l WHERE l.deliveryId = :id")
    int findMaxLineNo(@Param("id") Long deliveryId);

    Optional<DeliveryLine> findByUidAndDeliveryId(String uid, Long deliveryId);

    List<DeliveryLine> findBySalesOrderLineId(Long salesOrderLineId);
}
