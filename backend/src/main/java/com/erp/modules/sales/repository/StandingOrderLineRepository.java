package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.StandingOrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StandingOrderLineRepository extends JpaRepository<StandingOrderLine, Long> {

    Optional<StandingOrderLine> findByUid(String uid);

    @Query("SELECT l.companyId FROM StandingOrderLine l WHERE l.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<StandingOrderLine> findByStandingOrderId(Long standingOrderId);
}
