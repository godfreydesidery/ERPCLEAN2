package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.LandedCostAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LandedCostAllocationRepository extends JpaRepository<LandedCostAllocation, Long> {

    List<LandedCostAllocation> findByLandedCostId(Long landedCostId);

    boolean existsByLandedCostIdAndGoodsReceiptLineId(Long landedCostId, Long goodsReceiptLineId);
}
