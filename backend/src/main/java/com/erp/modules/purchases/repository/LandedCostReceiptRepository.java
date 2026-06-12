package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.LandedCostReceipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LandedCostReceiptRepository extends JpaRepository<LandedCostReceipt, Long> {

    List<LandedCostReceipt> findByLandedCostId(Long landedCostId);

    boolean existsByLandedCostIdAndGoodsReceiptId(Long landedCostId, Long goodsReceiptId);

    Optional<LandedCostReceipt> findByLandedCostIdAndGoodsReceiptId(Long landedCostId,
                                                                      Long goodsReceiptId);
}
