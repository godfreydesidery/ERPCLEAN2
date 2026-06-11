package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesReturn;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesReturnRepository extends JpaRepository<SalesReturn, Long> {

    Optional<SalesReturn> findByUid(String uid);

    @Query("SELECT r.companyId FROM SalesReturn r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<SalesReturn> findByCompanyId(Long companyId, Pageable pageable);

    Page<SalesReturn> findByCompanyIdAndDeliveryId(Long companyId, Long deliveryId, Pageable pageable);
}
