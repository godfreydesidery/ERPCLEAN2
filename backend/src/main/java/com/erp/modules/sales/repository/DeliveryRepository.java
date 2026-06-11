package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.Delivery;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByUid(String uid);

    @Query("SELECT d.companyId FROM Delivery d WHERE d.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<Delivery> findBySalesOrderId(Long salesOrderId);

    Page<Delivery> findByCompanyId(Long companyId, Pageable pageable);
}
