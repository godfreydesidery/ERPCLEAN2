package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.LandedCostCharge;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LandedCostChargeRepository extends JpaRepository<LandedCostCharge, Long> {

    List<LandedCostCharge> findByLandedCostIdOrderByLineNo(Long landedCostId);

    Optional<LandedCostCharge> findByUidAndLandedCostId(String uid, Long landedCostId);

    @Query("SELECT COALESCE(MAX(c.lineNo), 0) FROM LandedCostCharge c WHERE c.landedCostId = :lcId")
    int findMaxLineNo(@Param("lcId") Long lcId);
}
