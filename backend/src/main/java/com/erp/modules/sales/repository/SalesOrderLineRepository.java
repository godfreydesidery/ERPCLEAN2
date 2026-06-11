package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesOrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, Long> {

    Optional<SalesOrderLine> findByUid(String uid);

    List<SalesOrderLine> findBySalesOrderIdOrderByLineNo(Long salesOrderId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM SalesOrderLine l WHERE l.salesOrderId = :id")
    int findMaxLineNo(@Param("id") Long salesOrderId);

    Optional<SalesOrderLine> findByUidAndSalesOrderId(String uid, Long salesOrderId);
}
