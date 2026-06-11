package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesOrder;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    Optional<SalesOrder> findByUid(String uid);

    @Query("SELECT o.companyId FROM SalesOrder o WHERE o.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<SalesOrder> findByCompanyId(Long companyId, Pageable pageable);
}
